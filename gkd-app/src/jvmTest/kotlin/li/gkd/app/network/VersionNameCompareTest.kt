package li.gkd.app.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GitHub 源的 versionCode 由 tag 合成（`v1.13.0` → 11300），与安装包的真实
 * Android versionCode（101）不同量级，所以更新判断必须按版本名比较。
 */
class VersionNameCompareTest {
    @Test
    fun olderReleaseIsNotAnUpdate() {
        // 装着 1.13.0-plus.1 时，GitHub 最新 release 仍是 1.12.2-plus.3
        assertTrue(compareVersionNames("1.12.2-plus.3", "1.13.0-plus.1") < 0)
    }

    @Test
    fun sameVersionIsNotAnUpdate() {
        assertEquals(0, compareVersionNames("1.13.0-plus.1", "v1.13.0-plus.1"))
    }

    @Test
    fun plusSuffixIsOrdered() {
        assertTrue(compareVersionNames("1.13.0-plus.2", "1.13.0-plus.1") > 0)
        assertTrue(compareVersionNames("1.13.1-plus.1", "1.13.0-plus.9") > 0)
    }

    @Test
    fun plainVersionIsOlderThanPlusBuild() {
        assertTrue(compareVersionNames("1.13.0", "1.13.0-plus.1") < 0)
    }

    @Test
    fun betaSuffixIsOrdered() {
        assertEquals(0, compareVersionNames("1.14.0-beta.1", "v1.14.0-beta.1"))
        assertTrue(compareVersionNames("1.14.0-beta.2", "1.14.0-beta.1") > 0)
    }

    @Test
    fun betaIsOlderThanSameBaseRelease() {
        // 稳定版用户收到 beta 时不应提示升级
        assertTrue(compareVersionNames("1.14.0-beta.2", "1.14.0") < 0)
        // beta 用户能看到同基线的正式版
        assertTrue(compareVersionNames("1.14.0", "1.14.0-beta.2") > 0)
        assertTrue(compareVersionNames("1.14.0-plus.1", "1.14.0-beta.2") > 0)
    }

    @Test
    fun betaLineIsNewerThanPreviousBase() {
        assertTrue(compareVersionNames("1.14.0-beta.1", "1.13.0-plus.9") > 0)
        assertTrue(compareVersionNames("1.14.0-beta.1", "1.13.9") > 0)
    }
}
