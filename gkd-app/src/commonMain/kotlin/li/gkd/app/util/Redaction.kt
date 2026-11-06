package li.gkd.app.util

private const val MASKED_SECRET = "********"

private val jsonApiKeyRegex = Regex("""(?i)("api[-_]?key"\s*:\s*")[^"]*(")""")
private val plainApiKeyRegex = Regex("""(?i)\b(api[-_ ]?key\s*[=:]\s*)[^\s,}"']+""")
private val bearerTokenRegex = Regex("""(?i)\b(authorization\s*[:=]\s*bearer\s+)[^\s,}"']+""")
private val xApiKeyRegex = Regex("""(?i)\b(x-api-key\s*[:=]\s*)[^\s,}"']+""")

/** 把 API Key / Bearer Token 替换成固定掩码，用于导出日志、分享对话等外发内容。 */
fun redactSensitiveText(text: String): String =
    text
        .replace(jsonApiKeyRegex) { it.groupValues[1] + MASKED_SECRET + it.groupValues[2] }
        .replace(plainApiKeyRegex) { it.groupValues[1] + MASKED_SECRET }
        .replace(bearerTokenRegex) { it.groupValues[1] + MASKED_SECRET }
        .replace(xApiKeyRegex) { it.groupValues[1] + MASKED_SECRET }
