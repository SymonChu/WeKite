package com.github.wekite.features.items.chat.aichat.protocol

import com.github.wekite.utils.WeLogger
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * 结构化「选择题」判断协议（独立实现；协议形态受 yanwai 公开行为启发）：
 * 每个问题 = {type:"choice", instructions, criteria}；模型只回
 * {choice, confidence, probabilities}，不生成自由文本 → 低幻觉、可校验。
 *
 * 校验规则（不通过即抛 IllegalArgumentException → 调用方显示"返回不完整"）：
 * - choice 必须在 criteria 键集内
 * - probabilities 覆盖全部选项、每项 ∈ [0,1]、总和误差 ≤0.02
 * - chosen 概率应 ≥ 最大概率（模型自洽）——违反时**宽容化**：信任 choice、交换分布
 *   （2026-09-30：真机实测模型偶发自相矛盾，硬抛会让整条分析管线失败）
 */
object ChoiceProtocol {

    private const val TAG = "ChoiceProtocol"

    data class Decision(val choice: String, val probabilities: Map<String, Double>, val confidence: Double)

    fun question(instructions: String, options: Map<String, String>): JSONObject = JSONObject()
        .put("type", "choice")
        .put("instructions", instructions)
        .put("criteria", JSONObject(options))

    fun read(answers: JSONObject, key: String, options: Map<String, String>): Decision {
        if (!answers.has(key)) throw IllegalArgumentException("missing answer: $key")
        val answer = answers.getJSONObject(key)
        if (answer.optString("type") != "choice") throw IllegalArgumentException("answer type mismatch: $key")
        val confidence = probability(answer, "confidence")
        val chosen = answer.getString("choice")
        if (chosen !in options) throw IllegalArgumentException("unknown choice '$chosen' for $key")
        val distribution = answer.getJSONObject("probabilities")
        if (distribution.length() != options.size) throw IllegalArgumentException("probability size mismatch for $key")
        val values = options.keys.associateWith { probability(distribution, it) }
        if (abs(values.values.sum() - 1.0) > 0.02) throw IllegalArgumentException("probabilities do not sum to 1 for $key")
        // 模型偶发自相矛盾（choice 与 argmax 不一致，2026-09-30 真机实测 1/5 失败源于此）。
        // 宽容化：信任模型的 choice、把分布归一到它，打 W 不再让整条分析管线失败。
        if (values.getValue(chosen) + 1e-6 < values.values.max()) {
            val maxKey = values.entries.maxByOrNull { it.value }?.key ?: chosen
            WeLogger.w(
                TAG,
                "chosen not argmax for $key (chosen=$chosen, max=$maxKey); trusting model choice, swapped distribution",
            )
            val corrected = values.toMutableMap()
            corrected[maxKey] = values.getValue(chosen)
            corrected[chosen] = values.getValue(maxKey)
            return Decision(chosen, corrected, confidence)
        }
        return Decision(chosen, values, confidence)
    }

    private fun probability(obj: JSONObject, key: String): Double {
        val raw = obj.opt(key) ?: throw IllegalArgumentException("missing probability: $key")
        if (raw !is Number) throw IllegalArgumentException("probability not a number: $key")
        val v = raw.toDouble()
        require(v.isFinite() && v in 0.0..1.0) { "probability out of range: $key" }
        return v
    }

    /** 把上下文消息序列化成 state.message/state.context 块（时间量全部由程序计算，不让模型猜）。 */
    fun contextBlock(
        targetText: String,
        speaker: String,
        targetTimeMs: Long,
        history: List<HistoryMessage>,
    ): JSONObject {
        val messages = JSONArray()
        var previous: HistoryMessage? = null
        var block = 0
        var turn = 0
        for (m in history) {
            val gapMin = if (previous != null && m.timeMs >= previous.timeMs) (m.timeMs - previous.timeMs) / 60000 else null
            if (gapMin != null && gapMin >= 120) block++
            if (previous == null || previous.speaker != m.speaker || previous.timeMs <= 0 ||
                m.timeMs - previous.timeMs > 120_000) turn++
            messages.put(
                JSONObject()
                    .put("speaker", m.speaker)
                    .put("message", m.text)
                    .put("sent_at_ms", if (m.timeMs > 0) m.timeMs else JSONObject.NULL)
                    .put("minutes_before_target", gapMin?.let { gapMin.toDouble() } ?: JSONObject.NULL)
                    .put("time_block", block)
                    .put("speaker_turn", turn)
            )
            previous = m
        }
        val lastGapMin = previous?.let { if (targetTimeMs >= it.timeMs) (targetTimeMs - it.timeMs) / 60000 else null }
        return JSONObject()
            .put("message", targetText)
            .put("speaker", speaker)
            .put("sent_at_ms", if (targetTimeMs > 0) targetTimeMs else JSONObject.NULL)
            .put("context", messages)
            .put("time_note", TIME_NOTE)
    }

    data class HistoryMessage(val speaker: String, val text: String, val timeMs: Long, val isSelf: Boolean)

    private const val TIME_NOTE =
        "判断目标消息发送时的表达，不推测阅读时的心理。" +
            "time_block 仅按两小时间隔分组，不代表新话题或消气；speaker_turn 仅把同一发送者两分钟内的连续消息分组。" +
            "旧情绪不能自动延续，隔夜也不能自动清零。间隔与跨天由程序计算，不要凭回复间隔下结论。"
}
