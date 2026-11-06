package li.gkd.app.network

import kotlinx.serialization.Serializable

@Serializable
data class NewVersion(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String = "",
    val fileSize: Long = 0L,
    val versionLogs: List<VersionLog> = emptyList(),
    /** 兜底源（Gitee / npm）提供的夸克网盘下载地址；GitHub 不可达时用它 */
    val quarkUrl: String? = null,
) {
    val hasApkDownload: Boolean
        get() = downloadUrl.isNotEmpty()

    fun absoluteDownloadUrl(baseUrl: String): String =
        downloadUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: java.net.URI(baseUrl).resolve(downloadUrl).toString()
}

@Serializable data class VersionLog(val name: String, val code: Int, val desc: String)
