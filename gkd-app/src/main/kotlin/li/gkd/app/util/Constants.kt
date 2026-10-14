package li.gkd.app.util

const val FILE_SHORT_URL = "https://f.gkd.li/"
const val IMPORT_SHORT_URL = "https://i.gkd.li/i/"

const val SERVER_SCRIPT_URL =
    "https://registry.npmmirror.com/@gkd-kit/config/latest/files/dist/server.js"

const val REPOSITORY_URL = "https://github.com/fjjzy/gkd-plus"
const val ISSUES_URL = "${REPOSITORY_URL}/issues"

const val HOME_PAGE_URL = "https://gkd.li"

// 更新兜底数据源, 主渠道 (GitHub) 不可达时提供版本号与夸克网盘下载链接,
// 多个源并发请求并取版本号最大者; 留空则跳过该源
// Gitee 仓库 raw 文件, 响应直接就是版本信息 JSON:
// https://gitee.com/{owner}/{repo}/raw/{branch}/{path}
// npm 包清单 (npmmirror 是国内只读镜像, CI 发布 npm 包后自动同步), 版本信息放在清单的
// gkdPlus 自定义字段里; 不能读 `latest/files/*`, 那个接口只对白名单包开放:
// https://registry.npmmirror.com/{package}/latest
const val GITEE_FALLBACK_VERSION_URL =
    "https://gitee.com/fjjzy/gkd-plus/raw/master/version.json"

const val NPM_FALLBACK_VERSION_URL =
    "https://registry.npmmirror.com/gkd-plus-version/latest"


object ShortUrlSet {
    const val URL1 = "https://gkd.li?r=1"
    const val URL2 = "https://gkd.li?r=2"
    const val URL5 = "https://gkd.li?r=5"
    const val URL6 = "https://gkd.li?r=6"
    const val URL10 = "https://gkd.li?r=10"
    const val URL11 = "https://gkd.li?r=11"
    const val URL12 = "https://gkd.li?r=12"
    const val URL13 = "https://gkd.li?r=13"
    const val URL15 = "https://gkd.li?r=15"
}

const val PLAY_STORE_URL = "https://play.google.com/store/apps/details?id=li.songe.gkd"

const val systemUiAppId = "com.android.systemui"
