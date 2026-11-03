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
import kotlinx.coroutines.Job
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

    /** 最近一次启动的 startId：停止时只停这一次，避免连带取消后来的启动 */
    private var lastStartId = 0

    /** 由磁贴拉起时，完成任一操作后自动收起；从设置里打开则保持常驻 */
    private var autoHideAfterAction = false

    /** 磁贴连点会重复下发 onStartCommand，用 Job 挡住重复的直达流程 */
    private var pickJob: Job? = null

    override fun onClickView() {
        if (isOverlayContentHidden) return
        // 单击：展开/收起菜单
        menuVisible.value = !menuVisible.value
    }

    override fun onLongClickView() = stopSelf()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        autoHideAfterAction = intent?.getBooleanExtra(EXTRA_FROM_TILE, false) == true
        // 控制栏磁贴点击：启动后直接展开菜单，由用户自己选「仅快照 / 快速识别 / 我来选择」
        if (intent?.getBooleanExtra(EXTRA_EXPAND_MENU, false) == true) {
            menuVisible.value = true
        }
        // 开启「快照磁贴直达选组件」后：跳过菜单，直接进入「我来选择」
        if (intent?.getBooleanExtra(EXTRA_PICK_COMPONENTS, false) == true) {
            if (pickJob?.isActive != true) {
                pickJob = lifecycleScope.launchUi { pickAndCapture() }
            }
        } else {
            // 首次显示按钮时提示长按可关闭；直达模式按钮随即收起，提示没有意义
            showCloseHintIfNeeded()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun showCloseHintIfNeeded() {
        if (!AppStore.markSnapshotHintShown()) return
        toast("长按快照按钮可以关闭")
    }

    /** 磁贴拉起的按钮用完即收；从设置打开的常驻按钮不动 */
    private fun hideIfOneShot(startId: Int = lastStartId) {
        if (autoHideAfterAction) stopSelf(startId)
    }

    /** 仅快照：只保存快照，不登记 AI 会话、不发起识别 */
    private suspend fun onlyCapture() {
        menuVisible.value = false
        try {
            withAllOverlaysHidden {
                SnapshotCapture.capture()
            }
        } finally {
            // 磁贴拉起的按钮用完即收；设置里打开的常驻按钮保持显示
            hideIfOneShot()
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
            hideIfOneShot()
        }
    }

    private suspend fun pickAndCapture() {
        menuVisible.value = false
        // 服务是否由磁贴拉起：磁贴路径才需要等控制栏收起、并在一动作后自动收起按钮
        val fromTile = autoHideAfterAction
        val startedAt = System.currentTimeMillis()
        try {
            withAllOverlaysHidden {
                // 从磁贴直达时控制栏可能还没收起，根节点可能仍是 SystemUI 或短暂为 null
                if (fromTile) {
                    awaitAppRootReady()
                    // 等收起动画走完再截图/拉起界面，避免截到过渡帧或拉起被系统拦截
                    delay(QUICK_SETTINGS_SETTLE_MS)
                }
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
            // 停止最近一次启动：连点磁贴时后续的 startId 也一起收掉，避免按钮残留
            hideIfOneShot()
        }
    }

    /**
     * 等到控制栏收起、下层应用的根节点就绪再截图。
     * 从磁贴直达时控制栏可能还没收起，甚至返回瞬间根节点会短暂为 null；
     * 这两类情况都不能直接截图，否则会截到控制栏或直接抛「根节点缺失」。
     */
    private suspend fun awaitAppRootReady() {
        val deadline = System.currentTimeMillis() + QUICK_SETTINGS_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val appId = A11yRuntime.getRoot()?.packageName?.toString()
            if (appId != null && appId != systemUiAppId) return
            delay(50)
        }
        LogUtils.d("等待应用根节点超时，按当前状态继续截图", loc = "ButtonService")
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

        /** 是否由控制栏磁贴拉起：只有磁贴拉起的按钮会在操作后自动收起 */
        private const val EXTRA_FROM_TILE = "from_tile"

        /** 等控制栏收起的上限；迟迟没收到就按当前状态截图，避免卡住流程 */
        private const val QUICK_SETTINGS_WAIT_MS = 1500L

        /** 根节点就绪后，再等收起动画结束的时长 */
        private const val QUICK_SETTINGS_SETTLE_MS = 150L

        val isRunning: StateFlow<Boolean>
            field = MutableStateFlow(false)

        fun start(
            mode: ButtonStartMode = ButtonStartMode.ButtonOnly,
            fromTile: Boolean = false,
        ) {
            if (!PermissionStates.drawOverlays.checkOrToast()) return
            IntentUtils.startForegroundServiceByClass(ButtonService::class) {
                putExtra(EXTRA_EXPAND_MENU, mode == ButtonStartMode.Menu)
                putExtra(EXTRA_PICK_COMPONENTS, mode == ButtonStartMode.PickComponents)
                putExtra(EXTRA_FROM_TILE, fromTile)
            }
        }

        fun stop() = IntentUtils.stopServiceByClass(ButtonService::class)

    }
}

/** 拉起快照按钮的意图：只显示按钮、展开菜单，或直接进入「我来选择」 */
enum class ButtonStartMode { ButtonOnly, Menu, PickComponents }
