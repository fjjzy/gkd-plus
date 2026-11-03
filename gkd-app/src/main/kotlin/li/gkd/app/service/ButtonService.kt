package li.gkd.app.service

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import li.gkd.app.MainActivity
import li.gkd.app.a11y.A11yRuntime
import li.gkd.app.app
import li.gkd.app.feature.ai.AiPendingNav
import li.gkd.app.text.UiStrings
import li.gkd.app.notif.NotificationCatalog
import li.gkd.app.permission.PermissionStates
import li.gkd.app.snapshot.SnapshotCapture
import li.gkd.app.store.AppStore
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.ui.share.launchUi
import li.gkd.app.util.AiRuleGenerator
import li.gkd.app.util.IntentUtils
import li.gkd.app.util.LogUtils
import li.gkd.app.util.ToastUtils.toast
import li.gkd.app.util.systemUiAppId
import li.gkd.app.ui.component.GkIcon
import li.gkd.app.ui.component.GkIcons

class ButtonService : OverlayWindowService(
    positionKey = "button"
) {
    private var lastClickTime = 0L
    private val menuVisible = MutableStateFlow(false)

    override fun onClickView() {
        if (isOverlayContentHidden) return
        // 单击：展开/收起菜单
        menuVisible.value = !menuVisible.value
    }

    override fun onLongClickView() = stopSelf()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 控制栏磁贴点击：启动后直接展开菜单，由用户自己选「仅快照 / 快速识别 / 我来选择」
        if (intent?.getBooleanExtra(EXTRA_EXPAND_MENU, false) == true) {
            menuVisible.value = true
        }
        // 开启「快照磁贴直达选组件」后：跳过菜单，直接进入「我来选择」
        val pickComponents = intent?.getBooleanExtra(EXTRA_PICK_COMPONENTS, false) == true
        if (pickComponents) {
            lifecycleScope.launchUi { pickAndCapture(fromTile = true) }
        } else {
            // 首次展开菜单时提示长按可关闭；直达模式按钮随即收起，提示没有意义
            showCloseHintIfNeeded()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun showCloseHintIfNeeded() {
        if (storeFlow.value.snapshotButtonHintShown) return
        AppStore.updateSettings { it.copy(snapshotButtonHintShown = true) }
        toast("长按快照按钮可以关闭")
    }

    /** 仅快照：只保存快照，不登记 AI 会话、不发起识别 */
    private suspend fun onlyCapture() {
        menuVisible.value = false
        try {
            withAllOverlaysHidden {
                SnapshotCapture.capture()
            }
        } finally {
            // 触发任一操作后都收起按钮，避免按钮一直挂在屏幕上
            stopSelf()
        }
    }

    /** 快速识别：登记 AI 会话并发起识别；用户主动发起，不受「AI 自动识别规则」开关影响 */
    private suspend fun quickCapture() {
        menuVisible.value = false
        try {
            withAllOverlaysHidden {
                SnapshotCapture.capture(triggerAiRule = true, recordAiSession = true)
            }
        } finally {
            stopSelf()
        }
    }

    private suspend fun pickAndCapture(fromTile: Boolean = false) {
        menuVisible.value = false
        val startedAt = System.currentTimeMillis()
        try {
            withAllOverlaysHidden {
                // 从磁贴直达时控制栏可能还没收起，根节点可能仍是 SystemUI 或短暂为 null
                awaitAppRootReady()
                // 等控制栏收起动画走完再截图/拉起界面，避免截到过渡帧或拉起被系统拦截
                if (fromTile) delay(150)
                val startAt = System.currentTimeMillis()
                // keepStatusBar：选择器需坐标对齐；triggerAiRule=false：选完组件再决定生成
                // recordAiSession：即使最后点了「算了」，这张快照也要能在工作台里找到
                val snapshot = SnapshotCapture.capture(
                    keepStatusBar = true,
                    triggerAiRule = false,
                    recordAiSession = true,
                )
                val capturedAt = System.currentTimeMillis()
                // 记录待跳转快照（悬浮窗内输入兼容性差，改跳主界面选择页）
                AiPendingNav.pendingPickerSnapshotId.value = snapshot.id
                val intent = Intent(app, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                }
                app.startActivity(intent)
                // 排查「点我来选择后要等一下/再点一下才跳转」：分别看捕获耗时与启动耗时
                LogUtils.d(
                    "我来选择(${if (fromTile) "磁贴直达" else "悬浮菜单"}): " +
                        "等待根节点=${capturedAt - startedAt}ms, " +
                        "捕获耗时=${capturedAt - startAt}ms, " +
                        "startActivity耗时=${System.currentTimeMillis() - capturedAt}ms",
                    loc = "ButtonService",
                )
            }
        } finally {
            stopSelf()
        }
    }

    /**
     * 等到控制栏收起、下层应用的根节点就绪再截图。
     * 从磁贴直达时控制栏可能还没收起，甚至返回瞬间根节点会短暂为 null；
     * 这两类情况都不能直接截图，否则会截到控制栏或直接抛「根节点缺失」。
     * 正常从悬浮菜单进入时控制栏早已收起，这里只会多一次判断。
     */
    private suspend fun awaitAppRootReady(): Boolean {
        val deadline = System.currentTimeMillis() + QUICK_SETTINGS_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val appId = A11yRuntime.getRoot()?.packageName?.toString()
            if (appId != null && appId != systemUiAppId) return true
            delay(50)
        }
        LogUtils.d("等待应用根节点超时，按当前状态继续截图", loc = "ButtonService")
        return false
    }

    @Composable
    override fun ComposeContent() {
        val showMenu by menuVisible.collectAsStateWithLifecycle()
        val bgColor = MaterialTheme.colorScheme.surfaceContainerHigh
        val alpha = 0.75f
        // 基类把本方法放进 Box 中：必须只输出一个根节点。
        // 3 个选项横排在按钮右侧，按钮图标保持不变。
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            GkIcon(
                imageVector = GkIcons.CenterFocusWeak,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha))
                    .size(40.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
            )
            if (showMenu) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(bgColor)
                        .padding(horizontal = 2.dp, vertical = 2.dp),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                ) {
                    MenuItem(text = "算了") {
                        lifecycleScope.launchUi { menuVisible.value = false }
                    }
                    MenuItem(text = "仅快照") {
                        lifecycleScope.launchUi { onlyCapture() }
                    }
                    MenuItem(text = "快速识别") {
                        lifecycleScope.launchUi { quickCapture() }
                    }
                    MenuItem(text = "我来选择") {
                        lifecycleScope.launchUi { pickAndCapture() }
                    }
                }
            }
        }
    }

    @Composable
    private fun MenuItem(text: String, onClick: () -> Unit) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        )
    }

    init {
        useServicePresence(
            stateFlow = isRunning,
            name = UiStrings.snapshot_button_service,
            // 快照按钮会频繁出现/收起，启停 toast 只会造成打扰
            showToast = false,
        )
        onCreated {
            NotificationCatalog.button().startForeground()
        }
    }

    companion object {
        private const val EXTRA_EXPAND_MENU = "expand_menu"
        private const val EXTRA_PICK_COMPONENTS = "pick_components"

        /** 等控制栏收起的上限；迟迟没收到就按当前状态截图，避免卡住流程 */
        private const val QUICK_SETTINGS_WAIT_MS = 1500L

        val isRunning: StateFlow<Boolean>
            field = MutableStateFlow(false)

        fun start(expandMenu: Boolean = false, pickComponents: Boolean = false) {
            if (!PermissionStates.drawOverlays.checkOrToast()) return
            IntentUtils.startForegroundServiceByClass(ButtonService::class) {
                putExtra(EXTRA_EXPAND_MENU, expandMenu)
                putExtra(EXTRA_PICK_COMPONENTS, pickComponents)
            }
        }

        fun stop() = IntentUtils.stopServiceByClass(ButtonService::class)

    }
}
