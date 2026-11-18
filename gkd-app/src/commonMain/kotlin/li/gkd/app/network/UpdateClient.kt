package li.gkd.app.network

import io.ktor.client.call.body
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.onDownload
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.util.cio.writeChannel
import io.ktor.utils.io.copyAndClose
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import li.gkd.app.META

/** GitHub 被墙时不要干等 OkHttp 默认的 20s；兜底源通常 1s 内就返回。 */
private const val UPDATE_CHECK_TIMEOUT_MS = 5_000L

private val updateJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

@Serializable
private data class GitHubReleaseAsset(
    val name: String,
    val size: Long = 0L,
    @SerialName("browser_download_url") val browserDownloadUrl: String,
)

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name") val tagName: String,
    val body: String? = null,
    @SerialName("published_at") val publishedAt: String? = null,
    val draft: Boolean = false,
    val assets: List<GitHubReleaseAsset> = emptyList(),
)

@Serializable
private data class FallbackVersion(
    val versionCode: Int,
    val versionName: String,
    val quarkUrl: String? = null,
    val desc: String? = null,
)

@Serializable
private data class NpmPackageManifest(
    @SerialName("gkdPlus") val gkdPlus: FallbackVersion? = null,
)

private fun String.parseVersionCode(): Int {
    val match = Regex("""v?(\d+)\.(\d+)\.(\d+)""").find(this) ?: return 0
    val (major, minor, patch) = match.destructured
    return major.toInt() * 10000 + minor.toInt() * 100 + patch.toInt()
}

object UpdateClient {
    /** 兜底源的顺序即并发发起顺序；[fetch] 的调用方可覆盖，测试传空即可关掉兜底。 */
    val FallbackUrls = listOf(AppLinks.GiteeFallbackVersion, AppLinks.NpmFallbackVersion)
    /**
     * 主渠道与兜底源并发请求：GitHub 不可达时取版本号最大的兜底源；
     * 主渠道可用时也带上兜底源的夸克网盘链接。
     */
    /**
     * 主渠道与兜底源并发请求：GitHub 不可达时取版本号最大的兜底源；
     * 主渠道可用时也带上兜底源的夸克网盘链接。
     *
     * [fallbackUrls] 传空表示不查兜底源，此时主渠道的失败原样抛出。
     */
    suspend fun fetch(
        url: String,
        fallbackUrls: List<String> = FallbackUrls,
    ): NewVersion = coroutineScope {
        val primary = async { runCatchingCancellable { fetchPrimary(url) } }
        val fallbacks = async { fetchFallbacks(fallbackUrls) }
        val primaryResult = primary.await()
        val fallbackVersions = fallbacks.await()
        val quarkUrl = fallbackVersions.firstNotNullOfOrNull { it.quarkUrl }
        primaryResult.fold(
            onSuccess = { it.copy(quarkUrl = quarkUrl) },
            onFailure = { cause ->
                val newest = fallbackVersions.maxByOrNull { it.versionCode }
                if (newest != null) {
                    newest.toNewVersion()
                } else {
                    throw cause
                }
            },
        )
    }

    suspend fun download(url: String, file: File, size: Long, progress: (Float) -> Unit) {
        NetworkClients.client
            .prepareGet(url) {
                expectSuccess = true
                onDownload { received, _ -> if (size > 0) progress(received.toFloat() / size) }
            }
            .execute { response ->
                response.bodyAsChannel().copyAndClose(file.writeChannel())
            }
    }

    private suspend fun fetchPrimary(url: String): NewVersion =
        if (url.contains("api.github.com/repos/fjjzy/gkd-plus/releases")) {
            fetchGitHubRelease(url)
        } else {
            NetworkClients.client
                .get(url) {
                    expectSuccess = true
                    timeout { requestTimeoutMillis = UPDATE_CHECK_TIMEOUT_MS }
                }
                .body()
        }

    private suspend fun fetchGitHubRelease(url: String): NewVersion {
        val text =
            NetworkClients.client
                .get(url) {
                    expectSuccess = true
                    timeout { requestTimeoutMillis = UPDATE_CHECK_TIMEOUT_MS }
                }
                .bodyAsText()
        val element = updateJson.parseToJsonElement(text)
        val releases =
            when (element) {
                is JsonObject -> {
                    if (element["tag_name"]?.jsonPrimitive?.content.isNullOrBlank()) {
                        val apiMessage = element["message"]?.jsonPrimitive?.content
                        error(
                            if (apiMessage == null) {
                                "更新接口没有返回版本信息（可能被网络代理拦截）"
                            } else {
                                "更新接口返回异常：$apiMessage"
                            }
                        )
                    }
                    listOf(element)
                }
                // 测试版渠道读 releases 列表，需要自己挑出最新的一条（含 prerelease）
                is JsonArray -> element.filterIsInstance<JsonObject>()
                else -> emptyList()
            }
        val latest =
            releases
                .map { updateJson.decodeFromJsonElement(GitHubRelease.serializer(), it) }
                .filter { !it.draft }
                .sortedWith { left, right -> compareVersionNames(left.tagName, right.tagName) }
                .lastOrNull()
                ?: error("更新接口没有返回可用版本（可能被网络代理拦截）")
        val apkAsset =
            latest.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: error("该 Release 里没有 APK 文件")
        // 当前安装包就是该 tag 构建的：返回自身版本，让调用方判为「暂无更新」
        val code =
            if (latest.tagName == META.tagName) {
                META.versionCode
            } else {
                latest.tagName.parseVersionCode()
            }
        return NewVersion(
            versionCode = code,
            versionName = latest.tagName.removePrefix("v"),
            downloadUrl = apkAsset.browserDownloadUrl,
            fileSize = apkAsset.size,
            versionLogs =
                listOf(
                    VersionLog(
                        name = latest.tagName.removePrefix("v"),
                        code = latest.tagName.parseVersionCode(),
                        desc = latest.body.orEmpty(),
                    )
                ),
        )
    }

    private suspend fun fetchFallbacks(urls: List<String>): List<FallbackVersion> = coroutineScope {
        urls
            .map { url ->
                async {
                    fetchFallbackOrNull {
                        if (url == AppLinks.NpmFallbackVersion) fetchNpmFallback()
                        else fetchGiteeFallback()
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
    }

    private suspend fun fetchGiteeFallback(): FallbackVersion? {
        val url = AppLinks.GiteeFallbackVersion.takeIf { it.isNotEmpty() } ?: return null
        return NetworkClients.client
            .get(url) {
                expectSuccess = true
                timeout { requestTimeoutMillis = UPDATE_CHECK_TIMEOUT_MS }
            }
            .body()
    }

    private suspend fun fetchNpmFallback(): FallbackVersion? {
        val url = AppLinks.NpmFallbackVersion.takeIf { it.isNotEmpty() } ?: return null
        return NetworkClients.client
            .get(url) {
                expectSuccess = true
                timeout { requestTimeoutMillis = UPDATE_CHECK_TIMEOUT_MS }
            }
            .body<NpmPackageManifest>()
            .gkdPlus
    }

    private fun FallbackVersion.toNewVersion() =
        NewVersion(
            versionCode = versionCode,
            versionName = versionName,
            quarkUrl = quarkUrl,
            versionLogs =
                listOf(
                    VersionLog(
                        name = versionName,
                        code = versionCode,
                        desc = desc.orEmpty(),
                    )
                ),
        )
}

private suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

private suspend fun <T> fetchFallbackOrNull(block: suspend () -> T?): T? =
    runCatchingCancellable(block).getOrNull()
