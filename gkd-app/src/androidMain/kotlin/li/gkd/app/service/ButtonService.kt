package li.gkd.app.service

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
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
import li.gkd.app.app
import li.gkd.app.feature.ai.AiPendingNav
import li.gkd.app.logging.LogUtils
import li.gkd.app.matching.AndroidAutomationSessions
import li.gkd.app.notif.NotificationCatalog
import li.gkd.app.permission.PermissionStates
import li.gkd.app.resources.*
import li.gkd.app.settings.SettingsRepository
import li.gkd.app.snapshot.SnapshotCaptureHost
import li.gkd.app.snapshot.SnapshotCaptureOutcome
import li.gkd.app.ui.component.GkIcon
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.share.launchUi
import li.gkd.app.util.Constants
import li.gkd.app.util.IntentUtils
import li.gkd.app.util.toast

class ButtonService :
    OverlayWindowService(
        positionKey = "button",
    ) {
    private val menuVisible = MutableStateFlow(false)

    /** 最近一次启动的 startId：停止时只停这一次，避免连带取消后来的启动 */
    private var lastStartId = 0

    /** 由磁贴拉起时，完成任一操作后自动收起；从设置里打开则保持常驻 */
    private var autoHideAfterAction = false

    /** 磁贴连点会重复下发 onStartCommand，用 Job 挡住重复的直达流程 */
    private var pickJob: Job? = null

    override fun onClickView() {
        if (isOverlayContentHidden) return
        menuVisible.value = !menuVisible.value
    }

    override fun onLongClickView() = stopSelf()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        autoHideAfterAction = intent?.getBooleanExtra(EXTRA_FROM_TILE, false) == true
        if (intent?.getBooleanExtra(EXTRA_EXPAND_MENU, false) == true) {
            menuVisible.value = true
        }
        if (intent?.getBooleanExtra(EXTRA_PICK_COMPONENTS, false) == true) {
            if (pickJob?.isActive != true) {
                pickJob = lifecycleScope.launchUi { pickAndCapture() }
            }
        } else {
            showCloseHintIfNeeded()
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun showCloseHintIfNeeded() {
        if (!SettingsRepository.markSnapshotHintShown()) return
        toast("长按快照按钮可以关闭")
    }

    private fun hideIfOneShot(startId: Int = lastStartId) {
        if (autoHideAfterAction) stopSelf(startId)
    }

    /** 仅快照：只保存快照，不登记 AI 会话、不发起识别 */
    private suspend fun onlyCapture() {
        menuVisible.value = false
        try {
            withAllOverlaysHidden {
                SnapshotCaptureHost.capture()
            }
        } finally {
            hideIfOneShot()
        }
    }

    /** 快速识别：登记 AI 会话并发起识别；用户主动发起，不受「AI 自动识别规则」开关影响 */
    private suspend fun quickCapture() {
        menuVisible.value = false
        try {
            withAllOverlaysHidden {
                SnapshotCaptureHost.capture(triggerAiRule = true, recordAiSession = true)
            }
        } finally {
            hideIfOneShot()
        }
    }

    private suspend fun pickAndCapture() {
        menuVisible.value = false
        val fromTile = autoHideAfterAction
        val startedAt = System.currentTimeMillis()
        try {
            withAllOverlaysHidden {
                if (fromTile) {
                    awaitAppRootReady()
                    delay(QUICK_SETTINGS_SETTLE_MS)
                }
                val startAt = System.currentTimeMillis()
                // keepStatusBar：选择器需坐标对齐；triggerAiRule=false：选完组件再决定生成
                // recordAiSession：即使最后点了「算了」，这张快照也要能在工作台里找到
                val outcome =
                    SnapshotCaptureHost.capture(
                        keepStatusBar = true,
                        triggerAiRule = false,
                        recordAiSession = true,
                    )
                val snapshot =
                    (outcome as? SnapshotCaptureOutcome.Captured)?.value
                        ?: return@withAllOverlaysHidden
                val capturedAt = System.currentTimeMillis()
                AiPendingNav.pendingPickerSnapshotId.value = snapshot.id
                val intent =
                    Intent(app, MainActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                        )
                    }
                app.startActivity(intent)
                LogUtils.d(
                    "我来选择(${if (fromTile) "磁贴直达" else "悬浮菜单"}): " +
                        "等待根节点=${capturedAt - startedAt}ms, " +
                        "捕获耗时=${capturedAt - startAt}ms, " +
                        "startActivity耗时=${System.currentTimeMillis() - capturedAt}ms",
                    loc = "ButtonService",
                )
            }
        } finally {
            hideIfOneShot()
        }
    }

    /** 等到控制栏收起、下层应用的根节点就绪再截图。 */
    private suspend fun awaitAppRootReady() {
        val deadline = System.currentTimeMillis() + QUICK_SETTINGS_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            val appId = AndroidAutomationSessions.getRoot()?.packageName?.toString()
            if (appId != null && appId != Constants.systemUiAppId) return
            delay(50)
        }
        LogUtils.d("等待应用根节点超时，按当前状态继续截图", loc = "ButtonService")
    }

    @Composable
    override fun ComposeContent() {
        val showMenu by menuVisible.collectAsStateWithLifecycle()
        val bgColor = MaterialTheme.colorScheme.surfaceContainerHigh
        val alpha = 0.75f
        Row(verticalAlignment = Alignment.CenterVertically) {
            GkIcon(
                imageVector = GkIcons.CenterFocusWeak,
                modifier =
                    Modifier.clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha))
                        .size(40.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
            )
            if (showMenu) {
                Row(
                    modifier =
                        Modifier.padding(start = 8.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(bgColor)
                            .padding(horizontal = 2.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
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
            modifier =
                Modifier.clip(MaterialTheme.shapes.small)
                    .clickable(onClick = onClick)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
        )
    }

    init {
        useServicePresence(
            stateFlow = isRunning,
            name = Res.string.snapshot_button_service,
            showToast = false,
        )
        onCreated {
            NotificationCatalog.button().startForeground()
        }
    }

    companion object {
        private const val EXTRA_EXPAND_MENU = "expand_menu"
        private const val EXTRA_PICK_COMPONENTS = "pick_components"
        private const val EXTRA_FROM_TILE = "from_tile"
        private const val QUICK_SETTINGS_WAIT_MS = 1500L
        private const val QUICK_SETTINGS_SETTLE_MS = 150L

        val isRunning: StateFlow<Boolean>
            field = MutableStateFlow(false)

        fun start(
            mode: ButtonStartMode = ButtonStartMode.ButtonOnly,
            fromTile: Boolean = false,
        ) {
            if (!PermissionStates.drawOverlays.checkOrToast()) return
            IntentUtils.startForegroundService(ButtonService::class) {
                putExtra(EXTRA_EXPAND_MENU, mode == ButtonStartMode.Menu)
                putExtra(EXTRA_PICK_COMPONENTS, mode == ButtonStartMode.PickComponents)
                putExtra(EXTRA_FROM_TILE, fromTile)
            }
        }

        fun stop() = IntentUtils.stopService(ButtonService::class)
    }
}

/** 拉起快照按钮的意图：只显示按钮、展开菜单，或直接进入「我来选择」 */
enum class ButtonStartMode { ButtonOnly, Menu, PickComponents }
