package li.gkd.app.service

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
                // 双击 → 加强模式
                menuVisible.value = false
                lastClickTime = 0L
                withAllOverlaysHidden {
                    AiRuleGenerator.enhancedGenerate()
                }
            } else {
                // 等待判断是否双击
                delay(400)
                if (lastClickTime == now) {
                    // 单击 → 展开快照触发菜单（快速识别 / 我来选择）
                    menuVisible.value = !menuVisible.value
                }
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
            val snapshot = SnapshotCapture.capture()
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
        if (showMenu) {
            Column(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.medium)
                    .background(bgColor)
                    .padding(4.dp),
            ) {
                MenuItem(text = "快速识别") {
                    lifecycleScope.launchUi { quickCapture() }
                }
                MenuItem(text = "我来选择") {
                    lifecycleScope.launchUi { pickAndCapture() }
                }
            }
        }
        val alpha = 0.75f
        GkIcon(
            imageVector = GkIcons.CenterFocusWeak,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = alpha))
                .size(40.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
        )
    }

    @Composable
    private fun MenuItem(text: String, onClick: () -> Unit) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
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
