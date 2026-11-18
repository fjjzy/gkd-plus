package li.gkd.app.ui.update

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import li.gkd.app.META
import li.gkd.app.backup.BackupManager
import li.gkd.app.error.displayMessage
import li.gkd.app.model.findOption
import li.gkd.app.network.NewVersion
import li.gkd.app.network.UpdateClient
import li.gkd.app.network.compareVersionNames
import li.gkd.app.network.isNetworkRequestAllowed
import li.gkd.app.network.logNetworkFailure
import li.gkd.app.platform.PackageInstallCapability
import li.gkd.app.platform.PlatformResult
import li.gkd.app.platform.installPackagePrivileged
import li.gkd.app.platform.privilegedPackageInstallCapability
import li.gkd.app.platform.requestPackageInstall
import li.gkd.app.resources.*
import li.gkd.app.settings.FileSettingsStorage
import li.gkd.app.settings.SettingsRepository
import li.gkd.app.settings.UpdateChannel
import li.gkd.app.storage.FileExports
import li.gkd.app.storage.StorageMaintenance
import li.gkd.app.storage.appStorage
import li.gkd.app.storage.canSaveToDownloadsWithoutPermissionRequest
import li.gkd.app.storage.saveToDownloads
import li.gkd.app.ui.component.GkUpgradeDialogs
import li.gkd.app.ui.component.UpdateDownloadState
import li.gkd.app.ui.component.UpdateInstallState
import li.gkd.app.ui.home.importAppBackup
import li.gkd.app.ui.home.saveAppBackup
import li.gkd.app.ui.home.shareAppBackup
import li.gkd.app.ui.page.BackupDialog
import li.gkd.app.ui.page.GkBackupDialogs
import li.gkd.app.ui.platform.LocalUiHost
import li.gkd.app.ui.platform.SystemActionFeedback
import li.gkd.app.ui.platform.UiHost
import li.gkd.app.util.ToastUtils
import org.jetbrains.compose.resources.getString

/** GitHub 直链连续这么久没有下载进度，就降级到网盘 */
private const val DOWNLOAD_STALL_TIMEOUT_MS = 15_000L

/** App-owned update workflow; only package installation belongs to the platform. */
class UpdateStatus(
    private val scope: CoroutineScope,
) {
    private val storage by lazy { FileSettingsStorage(appStorage().store) }
    private val directory
        get() = appStorage().sharedCache

    val checkUpdatingFlow: StateFlow<Boolean>
        field = MutableStateFlow(false)
    private val newVersion = MutableStateFlow<NewVersion?>(null)
    private val downloadStatus = MutableStateFlow<UpdateDownloadState?>(null)
    private var downloadJob: Job? = null
    private var downloaded: File? = null
    private var downloadedVersion: NewVersion? = null
    private var manualBackupSaved = false
    private val installState = MutableStateFlow<UpdateInstallState>(UpdateInstallState.Idle)
    private val installCapability = privilegedPackageInstallCapability()

    private var versionUrl = ""
    private var lastManual = false
    private var lastCheckTime = 0L
    private val ignoreMutex = Mutex()

    /**
     * 按 versionName 记录忽略项：GitHub 源的 versionCode 由 tag 合成，
     * 同一基线的 `1.14.0-beta.1` / `1.14.0-beta.2` / `1.14.0` 会得到同一个值，
     * 用 code 忽略会把整条版本线一起忽略掉。
     *
     * 1.13.0 之前存的是合成 versionCode（数字），读出来是 `"11202"` 这类字符串，
     * 匹配不上任何 versionName，等于一次性失效——用户被重新提示一次、重新忽略即可。
     * 不做数字到版本名的反推：合成 code 丢掉了 prerelease / plus 后缀，反推不可逆。
     */
    private fun readIgnored(): Set<String> =
        storage
            .read("ignore_version_list.json")
            ?.let { text ->
                runCatching {
                    Json.parseToJsonElement(text).jsonArray.map { it.jsonPrimitive.content }.toSet()
                }.getOrNull()
            }
            .orEmpty()

    val canRecheck
        get() = System.currentTimeMillis() - lastCheckTime > 86_400_000L

    fun checkUpdate(manual: Boolean = false) {
        if (checkUpdatingFlow.value) return
        checkUpdatingFlow.value = true
        scope.launch {
            try {
                lastCheckTime = System.currentTimeMillis()
                val ignored =
                    if (manual) emptySet()
                    else ignoreMutex.withLock { withContext(Dispatchers.IO) { readIgnored() } }
                if (!isNetworkRequestAllowed()) {
                    if (manual) ToastUtils.show(getString(Res.string.network_unavailable))
                    return@launch
                }
                val url =
                    UpdateChannel.entries
                        .findOption(SettingsRepository.settings.value.updateChannel)
                        .url
                val result = withContext(Dispatchers.IO) { UpdateClient.fetch(url) }
                // 按版本名比较：GitHub 源的 versionCode 是 tag 合成的，和真实 versionCode 不同量级
                if (compareVersionNames(result.versionName, META.versionName) <= 0) {
                    if (manual) ToastUtils.show(getString(Res.string.updates_none))
                } else if (manual || result.versionName !in ignored) {
                    lastManual = manual
                    versionUrl = url
                    manualBackupSaved = false
                    newVersion.value = result
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logNetworkFailure("Update check failed", e)
                if (manual) ToastUtils.show(e.displayMessage())
            } finally {
                checkUpdatingFlow.value = false
            }
        }
    }

    /**
     * 长按「检查更新」：不判断是否比当前版本新，直接弹出主渠道的最新版本。
     * 本地构建的 commitTime 通常比线上 release 新，正常检查会一直判为「暂无更新」，
     * 这个入口专门用来预览更新弹窗。
     */
    fun previewUpdate() {
        if (checkUpdatingFlow.value) return
        checkUpdatingFlow.value = true
        scope.launch {
            try {
                lastCheckTime = System.currentTimeMillis()
                if (!isNetworkRequestAllowed()) {
                    ToastUtils.show(getString(Res.string.network_unavailable))
                    return@launch
                }
                val url =
                    UpdateChannel.entries
                        .findOption(SettingsRepository.settings.value.updateChannel)
                        .url
                val result = withContext(Dispatchers.IO) { UpdateClient.fetch(url) }
                lastManual = true
                versionUrl = url
                newVersion.value = result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logNetworkFailure("Update preview failed", e)
                ToastUtils.show(e.displayMessage())
            } finally {
                checkUpdatingFlow.value = false
            }
        }
    }

    private fun startDownload(version: NewVersion) {
        if (installState.value is UpdateInstallState.Active) return
        if (downloadJob?.isActive == true) return
        newVersion.value = null
        downloadStatus.value = UpdateDownloadState.Loading(0f)
        downloaded = null
        downloadedVersion = null
        installState.value = UpdateInstallState.Idle
        val url = version.absoluteDownloadUrl(versionUrl)
        // 只有 GitHub 直链才降级到网盘；兜底源本身不经过 GitHub
        val quarkUrl =
            version.quarkUrl
                ?.takeIf { it.isNotBlank() && url.contains("github", ignoreCase = true) }
        val file = directory.resolve("gkd-v${version.versionCode}.apk")
        downloadJob = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    directory.mkdirs()
                    val stalled = AtomicBoolean(false)
                    val lastProgress = AtomicLong(System.currentTimeMillis())
                    coroutineScope {
                        val transfer =
                            launch {
                                UpdateClient.download(url, file, version.fileSize) { progress ->
                                    lastProgress.set(System.currentTimeMillis())
                                    downloadStatus.value =
                                        UpdateDownloadState.Loading(progress.coerceIn(0f, 1f))
                                }
                            }
                        val watchdog =
                            launch {
                                while (isActive) {
                                    delay(1_000)
                                    if (
                                        System.currentTimeMillis() - lastProgress.get() >
                                            DOWNLOAD_STALL_TIMEOUT_MS
                                    ) {
                                        stalled.set(true)
                                        transfer.cancel()
                                        break
                                    }
                                }
                            }
                        transfer.join()
                        watchdog.cancel()
                    }
                    if (stalled.get()) error("GitHub download stalled")
                }
                ensureActive()
                downloaded = file
                downloadedVersion = version
                downloadStatus.value = UpdateDownloadState.Success
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (quarkUrl != null) {
                    // GitHub 下载失败/长时间无进度：直接改走网盘，避免用户卡在错误弹窗里
                    downloadStatus.value = null
                    ToastUtils.show(getString(Res.string.update_github_download_fallback))
                    SystemActionFeedback.openExternal(quarkUrl)
                } else {
                    downloadStatus.value = UpdateDownloadState.Failure(e)
                }
            } finally {
                if (downloaded == null)
                    withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            }
        }
    }

    private fun ignoreVersion() {
        val version = newVersion.value ?: return
        scope.launch {
            try {
                ignoreMutex.withLock {
                    withContext(Dispatchers.IO) {
                        val next = readIgnored() + version.versionName
                        storage.writeAtomically(
                            "ignore_version_list.json",
                            Json.encodeToString(next),
                        )
                    }
                    if (newVersion.value == version) newVersion.value = null
                    ToastUtils.show(getString(Res.string.update_version_ignored))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ToastUtils.show(e.displayMessage())
            }
        }
    }

    private fun install(host: UiHost, privileged: Boolean) {
        if (installState.value is UpdateInstallState.Active) return
        val file = downloaded ?: return
        val version = downloadedVersion ?: return
        installState.value = UpdateInstallState.Active
        scope.launch {
            try {
                if (!manualBackupSaved && canSaveToDownloadsWithoutPermissionRequest()) {
                    try {
                        FileExports.withTemporaryFile(
                            create = BackupManager::exportData,
                            delete = { StorageMaintenance.deleteSharedFile(appStorage(), it) },
                            consume = { backup ->
                                when (val saved = saveToDownloads(backup)) {
                                    is PlatformResult.Success -> {
                                        ToastUtils.show(
                                            getString(
                                                Res.string.file_saved_to_downloads,
                                                saved.value,
                                            ),
                                        )
                                    }
                                    PlatformResult.Unsupported -> Unit
                                }
                            },
                        )
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Backup failure must not prevent installing the update.
                    }
                }
                val result =
                    if (privileged) {
                        // 按 versionName 校验：GitHub 源的 versionCode 是 tag 合成的，和 APK 实际 code 不同量级
                        installPackagePrivileged(file, version.versionName, host)
                    } else {
                        requestPackageInstall(file)
                    }
                when (result) {
                    is PlatformResult.Success ->
                        installState.value =
                            if (privileged) UpdateInstallState.Success else UpdateInstallState.Idle
                    PlatformResult.Unsupported -> {
                        installState.value = UpdateInstallState.Idle
                        ToastUtils.show(getString(Res.string.platform_action_unsupported))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                installState.value = UpdateInstallState.Failure(e)
            }
        }
    }

    @Composable
    fun UpgradeDialog() {
        val host = LocalUiHost.current
        val version by newVersion.collectAsStateWithLifecycle()
        var backupDialog by remember(version) { mutableStateOf<BackupDialog?>(null) }
        val status by downloadStatus.collectAsStateWithLifecycle()
        val installation by installState.collectAsStateWithLifecycle()
        val capability by
            installCapability.collectAsStateWithLifecycle(PackageInstallCapability.Checking)
        val text = version?.let { value ->
            val logs = value.versionLogs.takeWhile { it.code > META.versionCode }
            ("v${META.versionName} -> v${value.versionName}\n\n" +
                    when {
                        logs.size > 1 -> logs.joinToString("\n\n") { "v${it.name}\n${it.desc}" }
                        logs.isNotEmpty() -> logs.first().desc
                        else -> ""
                    })
                .trimEnd()
        }
        GkUpgradeDialogs(
            text,
            !lastManual,
            status,
            onDownload = { version?.let(::startDownload) },
            onDismissVersion = { newVersion.value = null },
            onQuark = version?.quarkUrl?.let { url -> { SystemActionFeedback.openExternal(url) } },
            showDownloadButton = version?.hasApkDownload != false,
            onIgnore = ::ignoreVersion,
            onBackup = { backupDialog = BackupDialog.Export },
            onCancelDownload = {
                downloadJob?.cancel()
                downloadStatus.value =
                    UpdateDownloadState.Failure(Exception(Res.string.download_abort.getSync()))
            },
            onDismissDownload = { downloadStatus.value = null },
            installCapability = capability,
            installState = installation,
            downloadedVersion = downloadedVersion?.versionName,
            onInternalInstall = { install(host, privileged = true) },
            onInstall = { install(host, privileged = false) },
        )
        GkBackupDialogs(
            dialog = backupDialog,
            onDismiss = { backupDialog = null },
            onExport = { backupDialog = BackupDialog.Export },
            onImport = host::importAppBackup,
            onShare = host::shareAppBackup,
            onSaveDownloads = {
                host.saveAppBackup {
                    manualBackupSaved = true
                }
            },
        )
    }
}
