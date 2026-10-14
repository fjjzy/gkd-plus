package li.gkd.app.util

import li.gkd.app.text.UiStrings
import li.gkd.app.util.ToastUtils.toast
import li.gkd.app.util.TimeUtils.throttle

import android.content.Intent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.ktor.client.call.body
import io.ktor.client.plugins.onDownload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.copyAndClose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import li.gkd.app.META
import li.gkd.app.MainViewModel
import li.gkd.app.app
import li.gkd.app.store.FileStateStore
import li.gkd.app.store.AppStore.storeFlow
import li.gkd.app.ui.component.GkAlertDialog
import li.songe.codeorigin.CallSite
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.time.Duration.Companion.days


private val UPDATE_URL: String
    get() = UpdateChannelOption.objects.findOption(storeFlow.value.updateChannel).url

/**
 * 更新检查这几个请求的超时。
 *
 * 不设的话会落到 OkHttp 默认的 connect 10s + read 10s：GitHub 被墙时要干等约 20s 才失败，
 * 而兜底源（Gitee / npmmirror）国内通常 1s 内就回来了，白等没有意义。
 * 只作用于版本查询，APK 下载不受影响。
 */
private const val UPDATE_CHECK_TIMEOUT_MS = 5_000L

@Serializable
data class GitHubReleaseAsset(
    val name: String,
    val size: Long,
    @SerialName("browser_download_url")
    val browserDownloadUrl: String,
)

@Serializable
data class GitHubRelease(
    @SerialName("tag_name")
    val tagName: String,
    val name: String? = null,
    val body: String? = null,
    @SerialName("published_at")
    val publishedAt: String? = null,
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

@Serializable
data class NewVersion(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String = "",
    val fileSize: Long = 0L,
    val versionLogs: List<VersionLog> = emptyList(),
    val quarkUrl: String? = null,
) {
    val hasApkDownload: Boolean
        get() = downloadUrl.isNotEmpty()

    val absoluteDownloadUrl: String
        get() = downloadUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: URI(UPDATE_URL).resolve(downloadUrl).toString()
}

@Serializable
data class VersionLog(
    val name: String,
    val code: Int,
    val desc: String,
)

private fun String.parseVersionCode(): Int {
    val match = Regex("""v?(\d+)\.(\d+)\.(\d+)""").find(this) ?: return 0
    val (major, minor, patch) = match.destructured
    return major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
}

private fun String.parseGitHubTime(): Long? {
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(this)?.time
    }.getOrNull()
}

private fun GitHubRelease.isNewerThanCurrent(): Boolean {
    if (tagName == META.tagName) return false
    return (publishedAt?.parseGitHubTime() ?: 0L) > META.commitTime
}

private fun GitHubRelease.toNewVersion(): NewVersion {
    val apkAsset = assets.firstOrNull { asset ->
        asset.name.endsWith(".apk", ignoreCase = true)
    } ?: error("未找到 APK 下载文件")
    return NewVersion(
        versionCode = tagName.parseVersionCode(),
        versionName = tagName.removePrefix("v"),
        downloadUrl = apkAsset.browserDownloadUrl,
        fileSize = apkAsset.size,
        versionLogs = listOf(
            VersionLog(
                name = tagName.removePrefix("v"),
                code = tagName.parseVersionCode(),
                desc = body.orEmpty(),
            )
        ),
    )
}

private var lastCheckTime = 0L

@Serializable
private data class FallbackVersion(
    val versionCode: Int,
    val versionName: String,
    val quarkUrl: String? = null,
    val desc: String? = null,
)

/** npm 包清单; 版本信息存在 CI 写入的自定义字段 gkdPlus 里 */
@Serializable
private data class NpmPackageManifest(
    @SerialName("gkdPlus")
    val gkdPlus: FallbackVersion? = null,
)

/**
 * 并发读取所有兜底数据源; 单个源网络或解析失败只丢弃该源。
 * 两个源的响应结构不同, 所以分别解析: Gitee 直接返回版本信息, npm 包读的是包清单。
 */
private suspend fun fetchFallbackVersions(): List<FallbackVersion> = coroutineScope {
    listOf(
        async { fetchGiteeFallbackVersion() },
        async { fetchNpmFallbackVersion() },
    ).awaitAll().filterNotNull()
}

/** 空字符串表示跳过该源 */
private suspend fun fetchGiteeFallbackVersion(): FallbackVersion? {
    if (GITEE_FALLBACK_VERSION_URL.isEmpty()) return null
    return fetchFallbackOrNull {
        client.get(GITEE_FALLBACK_VERSION_URL) {
            timeout { requestTimeoutMillis = UPDATE_CHECK_TIMEOUT_MS }
        }.body<FallbackVersion>()
    }
}

/** 空字符串表示跳过该源 */
private suspend fun fetchNpmFallbackVersion(): FallbackVersion? {
    if (NPM_FALLBACK_VERSION_URL.isEmpty()) return null
    return fetchFallbackOrNull {
        client.get(NPM_FALLBACK_VERSION_URL) {
            timeout { requestTimeoutMillis = UPDATE_CHECK_TIMEOUT_MS }
        }.body<NpmPackageManifest>().gkdPlus
    }
}

/** 取消安全的 runCatching, 不把协程取消当成普通失败吞掉 */
private suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

/** 取兜底源: 除协程取消外的任何失败都视为该源不可用 */
private suspend fun <T> fetchFallbackOrNull(block: suspend () -> T?): T? =
    runCatchingCancellable(block).getOrNull()

private fun FallbackVersion.toNewVersion(): NewVersion {
    return NewVersion(
        versionCode = versionCode,
        versionName = versionName,
        quarkUrl = quarkUrl,
        versionLogs = listOf(
            VersionLog(
                name = versionName,
                code = versionCode,
                desc = desc.orEmpty(),
            )
        ),
    )
}

class UpdateStatus(val scope: CoroutineScope) {
    private val checkUpdatingMutex = MutexState()
    val checkUpdatingFlow
        get() = checkUpdatingMutex.state
    private val newVersionFlow = MutableStateFlow<NewVersion?>(null)
    private val downloadStatusFlow = MutableStateFlow<LoadStatus<File>?>(null)
    private var downloadJob: Job? = null

    private val ignoreVersionListFlow by lazy {
        FileStateStore.createJsonFlow(
            key = "ignore_version_list",
            default = { emptySet<Int>() },
            scope = scope,
        )
    }
    private var lastManual = false

    val canRecheck get() = System.currentTimeMillis() - lastCheckTime > 1.days.inWholeMilliseconds

    fun checkUpdate(
        manual: Boolean = false,
        @CallSite loc: String = "",
    ) {
        scope.launchLogged(Dispatchers.IO, loc = loc) {
            try {
                lastManual = manual
                checkUpdatingMutex.tryWithStateLock {
                    lastCheckTime = System.currentTimeMillis()
                    if (!NetworkUtils.isAvailable()) {
                        error(UiStrings.network_unavailable)
                    }
                    // 并发获取主渠道与所有兜底数据源, 主渠道不可用 (如 GitHub 超时) 时使用兜底源
                    val newVersion = fetchLatestVersion()
                    if (newVersion == null) {
                        if (manual) toast(UiStrings.updates_none, loc = loc)
                        return@tryWithStateLock
                    }
                    if (
                        !manual &&
                        ignoreVersionListFlow.value.contains(newVersion.versionCode)
                    ) return@tryWithStateLock
                    newVersionFlow.value = newVersion
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (manual) {
                    reportUpdateFailure(e)
                }
                throw e
            }
        }
    }

    /**
     * 长按「检查更新」使用：不判断是否比当前版本新，直接把主渠道的最新版本弹出来。
     * 本地构建的 commitTime 通常比线上 release 新，正常检查会一直判为「暂无更新」。
     */
    fun previewUpdate(@CallSite loc: String = "") {
        scope.launchLogged(Dispatchers.IO, loc = loc) {
            try {
                lastManual = true
                checkUpdatingMutex.tryWithStateLock {
                    if (!NetworkUtils.isAvailable()) {
                        error(UiStrings.network_unavailable)
                    }
                    // 预览不判断「是否比当前版本新」；主渠道挂了同样走兜底数据源，
                    // 否则 GitHub 一被限流就直接把序列化报错甩给用户
                    val version = fetchLatestVersion(requireNewer = false)
                    if (version == null) {
                        toast(UiStrings.updates_none, loc = loc)
                        return@tryWithStateLock
                    }
                    newVersionFlow.value = version
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportUpdateFailure(e)
                throw e
            }
        }
    }

/** 主渠道获取版本；[requireNewer] 为 true 时返回 null 表示已是最新 */
private suspend fun fetchPrimaryVersion(requireNewer: Boolean = true): NewVersion? {
    val version = if (isGitHubChannel()) {
        val release = fetchGitHubRelease()
        if (requireNewer && !release.isNewerThanCurrent()) null else release.toNewVersion()
    } else {
        client.get(UPDATE_URL) {
            timeout { requestTimeoutMillis = UPDATE_CHECK_TIMEOUT_MS }
        }.body<NewVersion>()
            .takeIf { !requireNewer || it.versionCode > META.versionCode }
    } ?: return null
    return version
}

private fun isGitHubChannel() =
    UPDATE_URL.contains("api.github.com/repos/fjjzy/gkd-plus/releases")

/**
 * 并发获取主渠道与所有兜底数据源; 主渠道不可达时取兜底源中版本号最大者,
 * 主渠道可用时也带上兜底源的夸克网盘链接, 全部不可用时抛出主渠道异常。
 * [requireNewer] 为 true 时返回 null 表示已是最新。
 */
private suspend fun fetchLatestVersion(requireNewer: Boolean = true): NewVersion? = coroutineScope {
    val primaryDeferred = async { runCatchingCancellable { fetchPrimaryVersion(requireNewer) } }
    val fallbackDeferred = async { fetchFallbackVersions() }
    val primary = primaryDeferred.await()
    val fallbacks = fallbackDeferred.await()
    val quarkUrl = fallbacks.firstNotNullOfOrNull { it.quarkUrl }
    primary.fold(
        onSuccess = { version -> version?.copy(quarkUrl = quarkUrl) },
        onFailure = { cause ->
            val newest = fallbacks
                .filter { !requireNewer || it.versionCode > META.versionCode }
                .maxByOrNull { it.versionCode }
            when {
                newest != null -> newest.toNewVersion()
                // 兜底源有响应但都不比当前新, 视为已是最新
                fallbacks.isNotEmpty() -> null
                else -> throw cause
            }
        },
    )
}

private fun JsonObject.stringField(name: String): String? =
    runCatching { this[name]?.jsonPrimitive?.content }.getOrNull()?.takeIf { it.isNotBlank() }

/**
 * 拉取 GitHub Release。
 *
 * 不能直接 `body<GitHubRelease>()`：限流（403）、仓库/Release 不存在（404）、
 * 或被网络代理拦截时，GitHub 返回的是 `{"message": ...}` 或一坨 HTML，
 * 缺 `tag_name` 会让 kotlinx 抛出「Field 'tag_name' is required」这种用户完全看不懂的报错。
 * 这里先按 JsonObject 取，拿到可读原因再说。
 */
private suspend fun fetchGitHubRelease(): GitHubRelease {
    // 用 bodyAsText + 手动解析，避免依赖 Ktor 对 JsonObject 的内容协商
    val text = client.get(UPDATE_URL) {
        timeout { requestTimeoutMillis = UPDATE_CHECK_TIMEOUT_MS }
    }.bodyAsText()
    val body = json.parseToJsonElement(text).jsonObject
    if (body.stringField("tag_name") == null) {
        val apiMessage = body.stringField("message")
        error(
            if (apiMessage == null) {
                "更新接口没有返回版本信息（可能被网络代理拦截）"
            } else {
                "更新接口返回异常：$apiMessage"
            }
        )
    }
    return json.decodeFromJsonElement(GitHubRelease.serializer(), body)
}

/** 检查更新失败的统一反馈：toast 说人话，完整报错进剪贴板 */
private fun reportUpdateFailure(e: Throwable) {
    val reason = updateErrorMessage(e)
    runCatching {
        ToastUtils.copyTextSilently("检查更新失败：\n${e.stackTraceToString()}")
    }
    toast("$reason\n（完整报错已复制）")
}

private fun updateErrorMessage(e: Throwable): String {
    val msg = (e.message ?: "").lowercase()
    return when {
        msg.contains("rate limit") || msg.contains("403") ->
            "GitHub 接口被限流了，稍后再试"
        msg.contains("not found") || msg.contains("404") ->
            "更新接口不存在（404），可能 Release 被删了"
        msg.contains("unable to resolve host") || msg.contains("unknownhost") ->
            "域名解析失败：当前网络可能访问不了 GitHub"
        msg.contains("timed out") || msg.contains("timeout") ->
            "连接超时：当前网络可能访问不了 GitHub"
        msg.contains("ssl") || msg.contains("certificate") ->
            "SSL 证书错误：可能被网络代理拦截了"
        msg.contains("json") || msg.contains("tag_name") ->
            "更新接口返回的不是有效数据（可能被网络代理拦截了）"
        msg.contains("apk") ->
            "该 Release 里没有 APK 文件"
        else -> "检查更新失败：${e.message ?: e.javaClass.simpleName}"
    }
}

    private fun startDownload(newVersion: NewVersion) {
        if (downloadStatusFlow.value is LoadStatus.Loading) return
        downloadStatusFlow.value = LoadStatus.Loading(0f)
        val apkFile = FolderUtils.sharedDir.resolve("gkd-v${newVersion.versionCode}.apk").apply {
            if (exists()) {
                delete()
            }
        }
        downloadJob = scope.launch(Dispatchers.IO) {
            try {
                val channel =
                    client.get(newVersion.absoluteDownloadUrl) {
                        onDownload { bytesSentTotal, _ ->
                            val downloadStatus = downloadStatusFlow.value
                            if (downloadStatus is LoadStatus.Loading) {
                                downloadStatusFlow.value = LoadStatus.Loading(
                                    bytesSentTotal.toFloat() / (newVersion.fileSize)
                                )
                            } else if (downloadStatus is LoadStatus.Failure) {
                                // 提前终止下载
                                downloadJob?.cancel()
                            }
                        }
                    }.bodyAsChannel()
                if (downloadStatusFlow.value is LoadStatus.Loading) {
                    channel.copyAndClose(apkFile.writeChannel())
                    downloadStatusFlow.value = LoadStatus.Success(apkFile)
                }
            } catch (e: Exception) {
                if (downloadStatusFlow.value is LoadStatus.Loading) {
                    downloadStatusFlow.value = LoadStatus.Failure(e)
                }
            } finally {
                downloadJob = null
            }
        }
    }

    @Composable
    fun UpgradeDialog() {
        newVersionFlow.collectAsStateWithLifecycle().value?.let { newVersionVal ->
            val text = remember {
                val logs = newVersionVal.versionLogs.takeWhile { v ->
                    v.code > META.versionCode
                }
                "v${META.versionName} -> v${newVersionVal.versionName}\n\n${
                    if (logs.size > 1) {
                        logs.joinToString("\n\n") { v -> "v${v.name}\n${v.desc}" }
                    } else if (logs.isNotEmpty()) {
                        logs.first().desc
                    } else {
                        ""
                    }
                }".trimEnd()
            }
            val scrollState = rememberScrollState()
            GkAlertDialog(
                title = {
                    Text(text = UiStrings.update_new_version)
                },
                text = {
                    Text(
                        text = text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 400.dp)
                            .verticalScroll(scrollState)
                    )
                },
                onDismissRequest = { },
                confirmButton = {
                    newVersionVal.quarkUrl?.let { quarkUrl ->
                        TextButton(onClick = {
                            newVersionFlow.value = null
                            MainViewModel.requireCurrent().openUrl(quarkUrl)
                        }) {
                            Text(text = UiStrings.update_quark)
                        }
                    }
                    if (newVersionVal.hasApkDownload) {
                        TextButton(onClick = {
                            newVersionFlow.value = null
                            startDownload(newVersionVal)
                        }) {
                            Text(text = UiStrings.update_download)
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { newVersionFlow.value = null }) {
                        Text(text = UiStrings.action_cancel)
                    }
                    if (!lastManual) {
                        TextButton(onClick = {
                            newVersionFlow.value = null
                            ignoreVersionListFlow.update {
                                it + newVersionVal.versionCode
                            }
                            toast(UiStrings.update_version_ignored)
                        }) {
                            Text(text = UiStrings.action_ignore)
                        }
                    }
                },
            )
        }

        downloadStatusFlow.collectAsStateWithLifecycle().value?.let { downloadStatusVal ->
            when (downloadStatusVal) {
                is LoadStatus.Loading -> {
                    GkAlertDialog(
                        title = { Text(text = UiStrings.image_downloading) },
                        text = {
                            LinearProgressIndicator(
                                progress = { downloadStatusVal.progress },
                            )
                        },
                        onDismissRequest = {},
                        confirmButton = {
                            TextButton(onClick = {
                                downloadStatusFlow.value = LoadStatus.Failure(
                                    Exception(UiStrings.download_abort)
                                )
                            }) {
                                Text(text = UiStrings.download_abort)
                            }
                        },
                    )
                }

                is LoadStatus.Failure -> {
                    GkAlertDialog(
                        title = { Text(text = UiStrings.download_failed) },
                        text = {
                            Text(text = downloadStatusVal.exception.let {
                                it.message ?: it.toString()
                            })
                        },
                        onDismissRequest = { downloadStatusFlow.value = null },
                        confirmButton = {
                            TextButton(onClick = {
                                downloadStatusFlow.value = null
                            }) {
                                Text(text = UiStrings.action_close)
                            }
                        },
                    )
                }

                is LoadStatus.Success -> {
                    GkAlertDialog(
                        title = { Text(text = UiStrings.download_complete) },
                        text = {
                            Text(text = UiStrings.update_ready_to_install)
                        },
                        onDismissRequest = {},
                        dismissButton = {
                            TextButton(onClick = {
                                downloadStatusFlow.value = null
                            }) {
                                Text(text = UiStrings.action_close)
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = throttle {
                                installApk(downloadStatusVal.result)
                            }) {
                                Text(text = UiStrings.action_install)
                            }
                        })
                }
            }
        }
    }
}


private fun installApk(file: File) {
    val uri = FileProvider.getUriForFile(
        app,
        "${app.packageName}.provider",
        file
    )
    val intent = Intent(Intent.ACTION_VIEW).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setDataAndType(uri, "application/vnd.android.package-archive")
    }
    app.tryStartActivity(intent)
}
