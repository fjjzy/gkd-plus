package li.gkd.app.util

import li.gkd.app.data.settings.AiConfig

/**
 * Max Tokens 的自适应策略。三条规则互相牵制，集中在这里：
 *
 * - **抬底**：生成前把明显偏小的预算抬到 [MIN_MAX_TOKENS]，省得先白跑一次被截断的生成。
 * - **上调**：输出被截断时翻倍，最高 [MAX_TOKENS_CEILING]。
 * - **回退**：服务端拒绝当前值时降到 [FALLBACK_MAX_TOKENS]，并把被拒的值记进
 *   [AiConfig.maxTokensCeiling]，避免抬底和上调再把同一个值送上去撞 400。
 */
object AiMaxTokensUtils {

    /** 生成前的最低预算；低于它的配置会被抬上来 */
    const val MIN_MAX_TOKENS = 16384

    /** 自动上调的硬上限 */
    const val MAX_TOKENS_CEILING = 131072

    /** 服务端拒绝当前值时的保守回退值 */
    const val FALLBACK_MAX_TOKENS = 8192

    /**
     * 生成前要抬到的预算；null 表示不用改。
     *
     * 已知被拒的值不高于下限时直接放弃：抬上去只会每次生成都先失败一次。
     */
    fun raisedBudget(config: AiConfig): Int? {
        if (config.maxTokens >= MIN_MAX_TOKENS) return null
        if (config.maxTokensCeiling in 1..MIN_MAX_TOKENS) return null
        return MIN_MAX_TOKENS
    }

    /**
     * 截断后下一次尝试的预算；null 表示不再上调。
     *
     * 已经探到过被服务端拒绝的值时也不再上调——再往上加只会重复失败。
     */
    fun nextBudget(config: AiConfig): Int? {
        if (config.maxTokensCeiling > 0) return null
        if (config.maxTokens >= MAX_TOKENS_CEILING) return null
        return (config.maxTokens * 2)
            .coerceAtLeast(MIN_MAX_TOKENS)
            .coerceAtMost(MAX_TOKENS_CEILING)
    }
}
