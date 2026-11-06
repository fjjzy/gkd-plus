package li.gkd.app.ui.home

import li.gkd.app.MainViewModel
import li.gkd.app.ui.component.GkPageBottomSpace

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import li.gkd.app.permission.PermissionStates
import li.gkd.app.priv.privilegeContextFlow
import li.gkd.app.text.UiStrings
import li.gkd.app.ui.PrivilegeServiceRoute
import li.gkd.app.ui.component.GkIcon
import li.gkd.app.ui.component.GkIconButton
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.ui.share.launchUi
import li.gkd.app.ui.style.itemHorizontalPadding
import li.gkd.app.ui.style.itemVerticalPadding
import li.gkd.app.ui.style.surfaceCardColors
import li.gkd.app.util.IntentUtils
import li.gkd.app.util.TimeUtils.throttle
import li.gkd.app.util.ToastUtils.toast

@Serializable
data object KeepAliveGuideRoute : NavKey

@Composable
fun KeepAliveGuidePage() {
    val mainVm = MainViewModel.requireCurrent()
    val privilegeContext by privilegeContextFlow.collectAsStateWithLifecycle()
    val ignoreBatteryOptimizations by PermissionStates.ignoreBatteryOptimizations.stateFlow.collectAsStateWithLifecycle()
    val writeSecureSettings by PermissionStates.writeSecureSettings.stateFlow.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GkTopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    GkIconButton(
                        imageVector = GkIcons.ArrowBack,
                        onClick = mainVm::popPage,
                    )
                },
                title = {
                    Text(text = UiStrings.keep_alive_guide_title)
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = itemHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                modifier = Modifier.padding(top = 16.dp),
                text = UiStrings.keep_alive_guide_intro,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            KeepAliveGuideCard(
                imageVector = GkIcons.PowerSettingsNew,
                title = UiStrings.keep_alive_guide_autostart,
                description = UiStrings.keep_alive_guide_autostart_description,
                onClickLabel = UiStrings.keep_alive_guide_autostart_open,
                onClick = {
                    mainVm.scope.launchUi {
                        val currentContext = privilegeContextFlow.value
                        if (currentContext == null ||
                            !IntentUtils.openAutoStartSettingsByRoot(currentContext)
                        ) {
                            IntentUtils.openAutoStartSettings()
                        }
                    }
                },
            )
            KeepAliveGuideCard(
                imageVector = GkIcons.BatteryFull,
                title = UiStrings.keep_alive_guide_battery,
                description = UiStrings.keep_alive_guide_battery_description,
                onClickLabel = UiStrings.keep_alive_guide_battery_open,
                onClick = {
                    mainVm.scope.launchUi {
                        mainVm.permissionRequests.ensurePermissions(
                            PermissionStates.ignoreBatteryOptimizations,
                        )
                    }
                },
                satisfied = ignoreBatteryOptimizations,
            )
            KeepAliveGuideCard(
                imageVector = GkIcons.Memory,
                title = UiStrings.keep_alive_guide_background,
                description = UiStrings.keep_alive_guide_background_description,
                onClickLabel = UiStrings.keep_alive_guide_background_open,
                onClick = { IntentUtils.openAppDetailsSettings() },
            )
            KeepAliveGuideCard(
                imageVector = GkIcons.Lock,
                title = UiStrings.keep_alive_guide_recents,
                description = UiStrings.keep_alive_guide_recents_description,
                onClickLabel = if (privilegeContext != null) {
                    UiStrings.keep_alive_guide_recents_open
                } else {
                    UiStrings.recents_lock_manual_hint
                },
                onClick = {
                    val currentContext = privilegeContextFlow.value
                    if (currentContext == null) {
                        toast(UiStrings.recents_lock_manual_hint)
                    } else {
                        currentContext.keyevent(KeyEvent.KEYCODE_APP_SWITCH)
                    }
                },
            )
            KeepAliveGuideCard(
                imageVector = GkIcons.VerifiedUser,
                title = UiStrings.keep_alive_guide_a11y_boot,
                description = UiStrings.keep_alive_guide_a11y_boot_description,
                onClickLabel = UiStrings.keep_alive_guide_a11y_boot_open,
                onClick = { mainVm.navigatePage(PrivilegeServiceRoute) },
                satisfied = writeSecureSettings,
            )
            GkPageBottomSpace()
        }
    }
}

@Composable
private fun KeepAliveGuideCard(
    imageVector: ImageVector,
    title: String,
    description: String,
    onClickLabel: String,
    onClick: () -> Unit,
    satisfied: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = surfaceCardColors,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    if (satisfied) {
                        stateDescription = UiStrings.keep_alive_guide_enabled
                    }
                }
                .clickable(
                    onClickLabel = onClickLabel,
                    onClick = throttle(onClick),
                )
                .padding(itemVerticalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GkIcon(
                imageVector = imageVector,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(8.dp)
                    .size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
                contentDescription = null,
            )
            Spacer(modifier = Modifier.width(itemHorizontalPadding))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            GkIcon(
                imageVector = if (satisfied) GkIcons.Check else GkIcons.KeyboardArrowRight,
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                contentDescription = null,
            )
        }
    }
}
