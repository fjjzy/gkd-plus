package li.gkd.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import li.gkd.app.META
import li.gkd.app.error.displayMessage
import li.gkd.app.model.findOption
import li.gkd.app.network.AppLinks
import li.gkd.app.resources.*
import li.gkd.app.settings.SettingsRepository
import li.gkd.app.settings.SettingsRepository.settings
import li.gkd.app.settings.UpdateChannel
import li.gkd.app.time.format
import li.gkd.app.ui.MainViewModel
import li.gkd.app.ui.component.DialogRequests
import li.gkd.app.ui.component.GkIconButton
import li.gkd.app.ui.component.GkIcons
import li.gkd.app.ui.component.GkPageBottomSpace
import li.gkd.app.ui.component.GkRotatingLoadingIcon
import li.gkd.app.ui.component.GkScaffold
import li.gkd.app.ui.component.GkSettingItem
import li.gkd.app.ui.component.GkTextMenu
import li.gkd.app.ui.component.GkTopAppBar
import li.gkd.app.ui.component.gkTopAppBarScroll
import li.gkd.app.ui.icon.GkAnimatedLogoIcon
import li.gkd.app.ui.navigation.CrashReportRoute
import li.gkd.app.ui.page.GkVersionInfoDialog
import li.gkd.app.ui.page.feedbackNotice
import li.gkd.app.ui.platform.LocalUiHost
import li.gkd.app.ui.platform.UiHost
import li.gkd.app.ui.share.LocalDarkTheme
import li.gkd.app.ui.style.itemPadding
import li.gkd.app.ui.style.titleItemPadding
import li.gkd.app.util.ToastUtils
import li.gkd.app.util.throttle
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
fun AboutPage() {
    val host = LocalUiHost.current
    val mainVm = MainViewModel.requireCurrent()
    val vm = viewModel { AboutViewModel() }
    val showVersionInfoDialog by vm.showVersionInfoDialog.collectAsStateWithLifecycle()
    val showShareAppDialog by vm.showShareAppDialog.collectAsStateWithLifecycle()
    val store by settings.collectAsStateWithLifecycle()

    val checkingUpdate: Boolean =
        mainVm.updateStatus?.checkUpdatingFlow?.collectAsStateWithLifecycle()?.value ?: false

    val scope = rememberCoroutineScope()
    val primary = MaterialTheme.colorScheme.primary
    fun launchAction(action: suspend () -> Unit) {
        scope.launch {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mainVm.dialogRequests.showMessage(
                    getString(Res.string.notice_title),
                    e.displayMessage(),
                )
            }
        }
    }
    val updateChannel = UpdateChannel.entries.findOption(store.updateChannel)
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior()
    GkScaffold(
        modifier = Modifier.gkTopAppBarScroll(scrollBehavior),
        topBar = {
            GkTopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    GkIconButton(
                        imageVector = GkIcons.ArrowBack,
                        onClick = {
                            mainVm.navigator.pop()
                        },
                    )
                },
                title = { Text(text = stringResource(Res.string.about_title)) },
                actions = {
                    GkIconButton(
                        imageVector = GkIcons.Share,
                        onClick = { vm.setShowShareAppDialog(true) },
                    )
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier =
                Modifier.fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(contentPadding),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                GkAnimatedLogoIcon(
                    tint = Color(if (LocalDarkTheme.current) 0xFFFCFCFC else 0xFF111111),
                    modifier =
                        Modifier.clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = {
                                    ToastUtils.show(Res.string.about_easter_egg.getSync())
                                },
                            )
                            .fillMaxWidth(0.33f)
                            .aspectRatio(1f),
                )
                Column(
                    modifier =
                        Modifier.clip(MaterialTheme.shapes.extraSmall)
                            .clickable(onClick = { vm.setShowVersionInfoDialog(true) })
                            .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = META.appName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = META.versionName,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
            }

            Text(
                text = "版本声明",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "GKD PLUS 意为搞快点，并非针对广告，而是针对用户自己想要的全部搞快点的页面，减少人类的机械操作。",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "本软件是基于 GKD 的社区改版，并非 GKD 原版官方发布版本。",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "感谢原作者 lisonge 与 GKD 项目贡献者提供优秀的开源基础。本项目会尽量尊重原项目设计与 GPL-3.0 开源协议。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            GkSettingItem(
                imageVector = null,
                title = stringResource(Res.string.source_code),
                onClick = {
                    mainVm.textDialog.showUrl(AppLinks.Repository)
                },
            )
            if (META.isGkdChannel) {
                GkSettingItem(
                    imageVector = null,
                    title = stringResource(Res.string.donate),
                    onClick = {
                        mainVm.navigator.openWebPage(AppLinks.Donate)
                    },
                )
            }
            GkSettingItem(
                imageVector = null,
                title = stringResource(Res.string.terms_of_use),
                onClick = {
                    mainVm.navigator.openWebPage(AppLinks.TermsOfService)
                },
            )
            GkSettingItem(
                imageVector = null,
                title = stringResource(Res.string.privacy_policy),
                onClick = {
                    mainVm.navigator.openWebPage(AppLinks.PrivacyPolicy)
                },
            )

            FeedbackSection {
                launchAction {
                    if (
                        mainVm.dialogRequests.confirm(
                            title = getString(Res.string.feedback_notice),
                            text = feedbackNotice(primary),
                            confirmText = getString(Res.string.action_continue),
                            dismissOnRequest = true,
                        )
                    )
                        mainVm.textDialog.showUrl(AppLinks.Issues)
                }
            }
            GkSettingItem(
                title = stringResource(Res.string.crash_reports),
                onClick = { mainVm.navigator.navigate(CrashReportRoute) },
            )
            GkSettingItem(
                title = stringResource(Res.string.logs_export),
                imageVector = GkIcons.Share,
                onClick = {
                    mainVm.shareLog.show()
                },
            )
            if (mainVm.updateStatus != null) {
                Text(
                    text = stringResource(Res.string.action_update),
                    modifier = Modifier.titleItemPadding(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                GkTextMenu(
                    title = stringResource(Res.string.update_channel),
                    option = updateChannel,
                    onOptionChange = { option ->
                        launchAction {
                            if (
                                option != UpdateChannel.Beta ||
                                    mainVm.dialogRequests.confirm(
                                        title = getString(Res.string.version_channel),
                                        text = getString(Res.string.beta_channel_warning),
                                    )
                            )
                                SettingsRepository.updateSettings {
                                    it.copy(updateChannel = option.value)
                                }
                        }
                    },
                )

                Row(
                    modifier =
                        Modifier.combinedClickable(
                                onClick = { mainVm.updateStatus.checkUpdate(true) },
                                // 长按用于预览更新弹窗，不比较版本
                                onLongClick = throttle { mainVm.updateStatus.previewUpdate() },
                            )
                            .fillMaxWidth()
                            .itemPadding(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(Res.string.update_check),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    GkRotatingLoadingIcon(loading = checkingUpdate)
                }
            }
            GkPageBottomSpace()
        }
    }

    GkVersionInfoDialog(
        showVersionInfoDialog,
        META.channel,
        META.versionCode.toString(),
        META.versionName,
        META.tagName ?: META.commitId.take(16),
        META.commitTime.format("yyyy-MM-dd HH:mm:ss ZZ"),
        { mainVm.textDialog.showUrl(META.commitUrl) },
        { vm.setShowVersionInfoDialog(false) },
    )
    host.GkShareAppDialog(
        visible = showShareAppDialog,
        dialogs = mainVm.dialogRequests,
        scope = mainVm.scope,
        onOpenUrl = mainVm.textDialog::showUrl,
    ) {
        vm.setShowShareAppDialog(false)
    }
}

@Composable
expect fun UiHost.GkShareAppDialog(
    visible: Boolean,
    dialogs: DialogRequests,
    scope: CoroutineScope,
    onOpenUrl: (String) -> Unit,
    onDismissRequest: () -> Unit,
)

@Composable
private fun FeedbackSection(onClick: () -> Unit) {
    Text(
        stringResource(Res.string.feedback_title),
        modifier = Modifier.titleItemPadding(),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Column(Modifier.clickable(onClick = onClick).fillMaxWidth().itemPadding()) {
        Text(stringResource(Res.string.feedback_report), style = MaterialTheme.typography.bodyLarge)
    }
}
