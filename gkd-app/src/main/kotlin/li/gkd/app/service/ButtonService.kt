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
import li.gkd.app.app
import li.gkd.app.feature.ai.AiPendingNav
import li.gkd.app.text.UiStrings
import li.gkd.app.notif.NotificationCatalog
import li.gkd.app.permission.PermissionStates
import li.gkd.app.snapshot.SnapshotCapture
import li.gkd.app.ui.share.launchUi
import li.gkd.app.util.AiRuleGenerator
import li.gkd.app.util.IntentUtils
import li.gkd.app.ui.component.GkIcon
import li.gkd.app.ui.component.GkIcons

class ButtonService : OverlayWindowService(
    positionKey = "button"
) {
    private var lastClickTime = 0L
    private val menuVisible = MutableStateFlow(false)

    override fun onClickView() {
        if (isOverlayContentHidden) return
        lifecycleScope.launchUi {
            val now = System.currentTimeMillis()
            val gap = now - lastClickTime
            lastClickTime = now
            if (gap < 400) {
                // 双击 → 加强模式（并关掉弹出的菜单）
                menuVisible.value = false
                lastClickTime = 0L
                withAllOverlaysHidden {
                    AiRuleGenerator.enhancedGenerate()
                }
            } else {
                // 单击 → 立即展开菜单（不等待双击判定，避免迟滞感）
                menuVisible.value = !menuVisible.value
            }
        }
    }

    override fun onLongClickView() = stopSelf()

    private suspend fun quickCapture() {
        menuVisible.value = false
        withAllOverlaysHidden {
            SnapshotCapture.capture()
        }
    }

    private suspend fun pickAndCapture() {
        menuVisible.value = false
        withAllOverlaysHidden {
            // keepStatusBar：选择器需坐标对齐；triggerAiRule=false：选完组件再决定生成
            val snapshot = SnapshotCapture.capture(
                keepStatusBar = true,
                triggerAiRule = false,
            )
            // 记录待导航快 App并跳转悬浮窗内输入兼容性差）
            AiPendingNav.pendingPickerSnapshotId.value = snapshot.id
            val intent = Intent(app, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            app.startActivity(intent)
        }
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
        )
        onCreated {
            NotificationCatalog.button().startForeground()
        }
    }

    companion object {
        val isRunning: StateFlow<Boolean>
            field = MutableStateFlow(false)

        fun start() {
            if (!PermissionStates.drawOverlays.checkOrToast()) return
            IntentUtils.startForegroundServiceByClass(ButtonService::class)
        }

        fun stop() = IntentUtils.stopServiceByClass(ButtonService::class)

    }
}
