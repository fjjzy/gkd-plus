package li.gkd.app.ui.settings

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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import li.gkd.app.permission.AppPermission
import li.gkd.app.resources.*
import li.gkd.app.settings.SettingsRepository
import li.gkd.app.ui.MainViewModel
import li.gkd.app.ui.component.GkIcon
import li.gkd.app.ui.component.GkIconButton
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkPageBottomSpace
import li.gkd.app.ui.component.GkScaffold
import li.gkd.app.ui.component.GkTextSwitch
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.ui.component.gkTopAppBarScroll
import li.gkd.app.ui.home.dashboardPlatformState
import li.gkd.app.ui.navigation.PrivilegeServiceRoute
import li.gkd.app.ui.platform.ignoresBatteryOptimizations
import li.gkd.app.ui.platform.openAppDetails
import li.gkd.app.ui.platform.openAutoStartSettings
import li.gkd.app.ui.platform.openRecents
import li.gkd.app.ui.share.launchUi
import li.gkd.app.ui.style.itemHorizontalPadding
import li.gkd.app.ui.style.itemVerticalPadding
import li.gkd.app.ui.style.surfaceCardColors
import li.gkd.app.util.throttle
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * 保活引导页：按合并前的实现保留自启动、电池优化、后台运行、锁定任务、无障碍开机自启五项。
 */
@Composable
fun KeepAlivePage() {
    val mainVm = MainViewModel.requireCurrent()
    val store by SettingsRepository.settings.collectAsStateWithLifecycle()
    val batteryUnrestricted = ignoresBatteryOptimizations()
    val platform = dashboardPlatformState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    GkScaffold(
        modifier = Modifier.gkTopAppBarScroll(scrollBehavior),
        topBar = {
            GkTopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    GkIconButton(
                        imageVector = GkIcons.ArrowBack,
                        onClick = mainVm.navigator::pop,
                    )
                },
                title = {
                    Text(text = stringResource(Res.string.keep_alive_guide_title))
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
                text = stringResource(Res.string.keep_alive_guide_intro),
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 悬浮窗保活：StatusService 读 enableStatusOverlay 决定是否持有悬浮窗，这个开关是它唯一的写入方
            GkTextSwitch(
                title = stringResource(Res.string.status_overlay_keep_alive),
                checked = store.enableStatusOverlay,
                onCheckedChange = { enabled ->
                    mainVm.scope.launchUi {
                        val confirmed =
                            mainVm.dialogRequests.confirm(
                                title =
                                    getString(
                                        if (enabled) {
                                            Res.string.status_overlay_enable_title
                                        } else {
                                            Res.string.status_overlay_disable_title
                                        },
                                    ),
                                text =
                                    getString(
                                        if (enabled) {
                                            Res.string.status_overlay_enable_description
                                        } else {
                                            Res.string.status_overlay_disable_description
                                        },
                                    ),
                                confirmText =
                                    getString(
                                        if (enabled) {
                                            Res.string.action_turn_on
                                        } else {
                                            Res.string.action_close
                                        },
                                    ),
                                dismissOnRequest = true,
                            )
                        if (confirmed) {
                            SettingsRepository.updateSettings {
                                it.copy(enableStatusOverlay = enabled)
                            }
                        }
                    }
                },
            )
            KeepAliveGuideCard(
                imageVector = GkIcons.PowerSettingsNew,
                title = stringResource(Res.string.keep_alive_guide_autostart),
                description = stringResource(Res.string.keep_alive_guide_autostart_description),
                onClickLabel = stringResource(Res.string.keep_alive_guide_autostart_open),
                onClick = {
                    mainVm.scope.launchUi {
                        openAutoStartSettings()
                    }
                },
            )
            KeepAliveGuideCard(
                imageVector = GkIcons.BatteryFull,
                title = stringResource(Res.string.keep_alive_guide_battery),
                description = stringResource(Res.string.keep_alive_guide_battery_description),
                onClickLabel = stringResource(Res.string.keep_alive_guide_battery_open),
                onClick = {
                    mainVm.scope.launchUi {
                        mainVm.permissions.ensurePermissions(
                            AppPermission.IgnoreBatteryOptimizations,
                        )
                    }
                },
                satisfied = batteryUnrestricted,
            )
            KeepAliveGuideCard(
                imageVector = GkIcons.Memory,
                title = stringResource(Res.string.keep_alive_guide_background),
                description = stringResource(Res.string.keep_alive_guide_background_description),
                onClickLabel = stringResource(Res.string.keep_alive_guide_background_open),
                onClick = { openAppDetails() },
            )
            KeepAliveGuideCard(
                imageVector = GkIcons.Lock,
                title = stringResource(Res.string.keep_alive_guide_recents),
                description = stringResource(Res.string.keep_alive_guide_recents_description),
                onClickLabel = stringResource(Res.string.keep_alive_guide_recents_open),
                onClick = {
                    mainVm.scope.launchUi {
                        openRecents()
                    }
                },
            )
            KeepAliveGuideCard(
                imageVector = GkIcons.VerifiedUser,
                title = stringResource(Res.string.keep_alive_guide_a11y_boot),
                description = stringResource(Res.string.keep_alive_guide_a11y_boot_description),
                onClickLabel = stringResource(Res.string.keep_alive_guide_a11y_boot_open),
                onClick = { mainVm.navigator.navigate(PrivilegeServiceRoute) },
                satisfied = platform.writeSecureSettings,
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
    val enabledText = stringResource(Res.string.keep_alive_guide_enabled)
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
                        stateDescription = enabledText
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
