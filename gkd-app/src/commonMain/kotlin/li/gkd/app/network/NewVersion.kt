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

/**
 * 比较版本名：`1.13.0-plus.2` / `1.14.0-beta.1` 这类格式，返回 >0 表示 [left] 比 [right] 新。
 *
 * 不能直接比 `versionCode`：GitHub 源用 tag 合成版本号（`v1.13.0` → 11300），
 * 而安装包用的是真实 Android versionCode（101），两者不同量级，会导致装着 1.13
 * 却提示升级到更旧的 1.12 release。解析失败时退回字符串比较。
 *
 * 同一基线下顺序为：`x.y.z-beta.N` < `x.y.z` < `x.y.z-plus.N`
 * （beta 是预发布；plus 是正式版之后的修订构建）。
 */
fun compareVersionNames(left: String, right: String): Int {
    val a = parseVersionName(left)
    val b = parseVersionName(right)
    if (a == null || b == null) return left.compareTo(right)
    for (index in a.indices) {
        val result = a[index].compareTo(b[index])
        if (result != 0) return result
    }
    return 0
}

private val versionNamePattern =
    Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-plus\.(\d+))?(?:-beta\.(\d+))?$""")

private fun parseVersionName(value: String): List<Int>? =
    versionNamePattern.find(value.trim())?.let { match ->
        val beta = match.groupValues[5].takeIf { it.isNotEmpty() }?.toInt()
        listOf(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            // 同基线下正式版高于 beta
            if (beta == null) 1 else 0,
            match.groupValues[4].takeIf { it.isNotEmpty() }?.toInt() ?: 0,
            beta ?: 0,
        )
    }
