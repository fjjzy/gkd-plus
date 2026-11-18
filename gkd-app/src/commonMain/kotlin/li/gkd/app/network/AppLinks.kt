package li.gkd.app.network

object AppLinks {
    const val Home = "https://gkd.li"
    const val Repository = "https://github.com/fjjzy/gkd-plus"
    const val Issues = "https://github.com/fjjzy/gkd-plus/issues"
    private const val Inspect = "https://i.gkd.li"
    private const val FileShort = "https://f.gkd.li"
    const val Github = "https://github.com"
    const val GithubUploadReferer = "$Github/gkd-kit/inspect/issues/46"
    const val GithubGraphql = "$Github/_graphql"
    const val GithubUploadPolicies = "$Github/upload/policies/assets"
    const val PrivilegeRepository = "$Github/priv-kit/priv-kit"
    const val WebView2Download = "https://developer.microsoft.com/microsoft-edge/webview2/"
    /** GitHub 的 latest 接口会跳过 prerelease，稳定版因此天然看不到 beta */
    const val StableUpdate = "https://api.github.com/repos/fjjzy/gkd-plus/releases/latest"

    /** 测试版要能拿到标记为 prerelease 的 Release，只能读列表再自行挑最新 */
    const val BetaUpdate = "https://api.github.com/repos/fjjzy/gkd-plus/releases"
    const val GiteeFallbackVersion = "https://gitee.com/fjjzy/gkd-plus/raw/master/version.json"
    const val NpmFallbackVersion = "https://registry.npmmirror.com/gkd-plus-version/latest"
    const val DocConfig = "https://registry.npmmirror.com/@gkd-kit/docs/latest/files/_config.json"
    const val ErudaScript = "https://registry.npmmirror.com/eruda/latest/files"
    const val ServerScript =
        "https://registry.npmmirror.com/@gkd-kit/config/latest/files/dist/server.js"
    const val PlayStore = "https://play.google.com/store/apps/details?id=li.songe.gkd"
    const val CookieHelp = "https://gkd.li?r=1"
    const val WorkModeHelp = "https://gkd.li?r=2"
    const val SubscriptionHelp = "https://gkd.li?r=5"
    const val BackgroundRunningHelp = "https://gkd.li?r=6"
    const val Donate = "https://gkd.li?r=10"
    const val PrivacyPolicy = "https://gkd.li?r=11"
    const val TermsOfService = "https://gkd.li?r=12"
    const val GoogleServicesHelp = "https://gkd.li?r=13"
    const val SnapshotHelp = "https://gkd.li?r=15"

    fun log(id: Int) = "$Inspect/log/$id"

    fun snapshot(id: Int) = "$Inspect/i/$id"

    fun file(id: Int) = "$FileShort/$id"

    fun githubAssetUpload(path: String) = Github + path

    fun revision(commitId: String, tagName: String?) =
        "$Repository/" + if (tagName != null) "tree/$tagName" else "commit/$commitId"

    fun localServer(host: String, port: Int) = "http://$host:$port"
}
