package com.github.wekite.features.items.chat.aichat.protocol

import org.json.JSONObject

/**
 * 回复优选协议（第二次 JEV 请求）：LLM 生成候选之后，让 JEV 从候选里挑最贴合当前语境的一条。
 *
 * 与 [IntentQuestions] 同一套 choice 协议（复用 [ChoiceProtocol]），差异只有两点：
 * ① **时序**上必须在候选生成之后跑（情绪那轮在 LLM 之前），所以是独立的一次 JEV 请求；
 * ② 选项是候选原文（r1..rN）+ 一个 none（都不合适）。
 *
 * 宽容化（对齐 [ChoiceProtocol] 既有哲学）：任何失败都不影响主分析管线 —— 调用方拿到
 * null / rejected 就按原顺序走，不抛错、不打断发送。
 */
object ReplyValuation {

    /** 「都不合适」的选项键（不作为候选下标返回）。 */
    const val NONE = "r_none"

    /**
     * 单个选项的描述文本上限。
     * JEV 每个问题有选项 token 预算（Laya 侧 `head_max_len` 为 192 token），候选过长会被裁，
     * 裁到几条读起来一样 ⇒ 「选哪条」退化成「选哪条被截断的」。主动截断比被下游裁掉可预期。
     * 提示词已要求候选「多数 20 字以内」，正常情况下够用。
     */
    private const val MAX_OPTION_CHARS = 40

    private const val INSTRUCTION =
        "下面是同一条消息的多个候选回复（r1..rN）。选出最贴合当前语境、最像机主本人此刻会发的一条。" +
            "只看候选是否接住了对方的话、语气是否与上下文一致，不评价措辞好坏、不挑最礼貌的。" +
            "如果没有任何一条适合现在发送（话题已收尾、候选都不像人话、或上下文不足以判断），选「都不合适」。"
    private const val NONE_LABEL = "都不合适，现在不该回复"

    /** 选项表：r1..rN + none。描述用候选原文。 */
    fun options(candidates: List<String>): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        candidates.forEachIndexed { i, c -> map["r${i + 1}"] = c.take(MAX_OPTION_CHARS) }
        map[NONE] = NONE_LABEL
        return map
    }

    /**
     * 仅用于 [parse] 的键集校验：描述留空 —— [ChoiceProtocol.read] 只比对键集与概率分布。
     * 不复用 [options] 是为了避免「解析时拿截断后的描述去校验」这种耦合。
     */
    private fun optionKeys(candidateCount: Int): LinkedHashMap<String, String> {
        val map = LinkedHashMap<String, String>()
        for (i in 1..candidateCount) map["r$i"] = ""
        map[NONE] = ""
        return map
    }

    /** JEV 请求体：state 复用情绪那轮的上下文块，多问一道 reply。 */
    fun payload(state: JSONObject, candidates: List<String>, model: String): JSONObject = JSONObject()
        .put("model", model)
        .put("state", state)
        .put(
            "questions",
            JSONObject().put("reply", ChoiceProtocol.question(INSTRUCTION, options(candidates))),
        )

    /** 优选结果。[index] 为 null = none 胜出（rejected，不该发）。 */
    data class Pick(
        val index: Int?,
        val probabilities: Map<String, Double>,
        val confidence: Double,
    ) {
        /** 选中项的校准概率（卡片上显示的那个百分比）。 */
        val chosenProbability: Double get() = index?.let { probabilities["r${it + 1}"] } ?: 0.0
        val rejected: Boolean get() = index == null
    }

    /**
     * 解析并映射回候选下标（0-based）。
     * - `choice == none` ⇒ [Pick.index] = null
     * - 其余校验交给 [ChoiceProtocol.read]（选项越界 / 概率不合法会抛，由调用方兜住）
     */
    fun parse(body: String, candidateCount: Int): Pick {
        val answers = JSONObject(body).getJSONObject("answers")
        val decision = ChoiceProtocol.read(answers, "reply", optionKeys(candidateCount))
        if (decision.choice == NONE) return Pick(null, decision.probabilities, decision.confidence)
        val ordinal = decision.choice.removePrefix("r").toIntOrNull()
            ?: throw IllegalArgumentException("unknown reply choice: ${decision.choice}")
        if (ordinal !in 1..candidateCount) throw IllegalArgumentException("reply choice out of range: ${decision.choice}")
        return Pick(ordinal - 1, decision.probabilities, decision.confidence)
    }
}
