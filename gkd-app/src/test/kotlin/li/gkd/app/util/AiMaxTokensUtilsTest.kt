package li.gkd.app.util

import li.gkd.app.data.settings.AiConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiMaxTokensUtilsTest {

    private fun config(maxTokens: Int, ceiling: Int = 0) =
        AiConfig(maxTokens = maxTokens, maxTokensCeiling = ceiling)

    @Test
    fun smallBudgetIsRaisedToTheFloor() {
        // 旧配置的 8192 会在生成时被抬到下限，用户不必先白跑一次被截断的生成
        assertEquals(AiMaxTokensUtils.MIN_MAX_TOKENS, AiMaxTokensUtils.raisedBudget(config(8192)))
        assertEquals(AiMaxTokensUtils.MIN_MAX_TOKENS, AiMaxTokensUtils.raisedBudget(config(4096)))
    }

    @Test
    fun budgetAtOrAboveTheFloorIsLeftAlone() {
        assertNull(AiMaxTokensUtils.raisedBudget(config(AiMaxTokensUtils.MIN_MAX_TOKENS)))
        assertNull(AiMaxTokensUtils.raisedBudget(config(32768)))
    }

    @Test
    fun raiseIsSkippedWhenTheServerRejectedTheFloor() {
        // 服务端上限比下限还低时，抬上去只会每次生成都先撞一次 400
        assertNull(
            AiMaxTokensUtils.raisedBudget(config(maxTokens = 8192, ceiling = AiMaxTokensUtils.MIN_MAX_TOKENS)),
        )
        assertNull(AiMaxTokensUtils.raisedBudget(config(maxTokens = 8192, ceiling = 8192)))
        // 被拒的值高于下限，说明下限本身没问题，仍然可以抬
        assertEquals(
            AiMaxTokensUtils.MIN_MAX_TOKENS,
            AiMaxTokensUtils.raisedBudget(config(maxTokens = 8192, ceiling = 65536)),
        )
    }

    @Test
    fun nextBudgetDoublesUpToTheCeiling() {
        assertEquals(32768, AiMaxTokensUtils.nextBudget(config(16384)))
        assertEquals(65536, AiMaxTokensUtils.nextBudget(config(32768)))
        assertEquals(AiMaxTokensUtils.MAX_TOKENS_CEILING, AiMaxTokensUtils.nextBudget(config(65536)))
    }

    @Test
    fun nextBudgetStopsAtTheHardCeiling() {
        assertNull(AiMaxTokensUtils.nextBudget(config(AiMaxTokensUtils.MAX_TOKENS_CEILING)))
        assertNull(AiMaxTokensUtils.nextBudget(config(200000)))
    }

    @Test
    fun nextBudgetStopsOnceAValueWasRejected() {
        // 已经探到被拒的值，再往上加只会重复失败
        assertNull(AiMaxTokensUtils.nextBudget(config(maxTokens = 8192, ceiling = 65536)))
    }
}
