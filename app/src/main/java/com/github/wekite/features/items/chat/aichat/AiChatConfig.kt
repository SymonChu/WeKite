package com.github.wekite.features.items.chat.aichat

import com.github.wekite.preferences.WePrefs
import com.github.wekite.preferences.WePrefs.Companion.prefOption

/**
 * AI 聊天助手配置。
 *
 * 双线路：
 * - JEV 情绪线路：OpenRouter systemone 端点（结构化 choice 协议，出概率分布）
 * - LLM 线路：任意 OpenAI 兼容 chat/completions（意图解读 + 建议回复）
 *
 * ⚠️ 地址一律走 [resolveEndpoint] 归一化：用户填 base URL 也能用
 * （v3.43 用户实测踩坑：填 `https://openrouter.ai/api/v1` → HTTP 404，因为要求填完整路径）。
 */
object AiChatConfig {
    // ---------- JEV 线路 ----------
    var jevEndpoint by prefOption("ai_chat_jev_endpoint", "https://openrouter.ai/api/v1/systemone")
    var jevModel by prefOption("ai_chat_jev_model", "typesafe/jev-1.13")
    var jevApiKey by prefOption("ai_chat_jev_key", "")

    // ---------- LLM 线路 ----------
    var llmEndpoint by prefOption("ai_chat_llm_endpoint", "")
    var llmApiKey by prefOption("ai_chat_llm_key", "")
    var llmModel by prefOption("ai_chat_llm_model", "")

    // ---------- 分析 ----------
    /** JEV 情绪概率（默认开；关掉则只走 LLM 或都不走） */
    var useJev by prefOption("ai_chat_use_jev", true)

    /**
     * 回复优选（v3.64，默认开）：LLM 生成候选后，再用 JEV 挑一条 —— 自动回复发它选中的，
     * 手动模式在它选中的那条上加「⭐推荐」标记（位置不动，用户口径 2026-10-01）。
     *
     * 与 [useJev] **正交**：优选只借 JEV 线路（jevUrl/jevApiKey/jevModel），
     * 关掉情绪判断不影响优选。失败一律静默降级为「按原顺序」。
     */
    var replyValuation by prefOption("ai_chat_reply_valuation", true)
    var contextLimit by prefOption("ai_chat_context_limit", 30)
    var contextBudget by prefOption("ai_chat_context_budget", 8000)
    /** 建议条数上限（面板里显示几条） */
    var suggestionCount by prefOption("ai_chat_suggestion_count", 3)

    // ---------- 全自动回复 ----------
    var autoReplyConsent by prefOption("ai_chat_auto_reply_consent", false)
    var autoReplyDelaySec by prefOption("ai_chat_auto_reply_delay_sec", 5)
    var autoReplyCooldownSec by prefOption("ai_chat_auto_reply_cooldown_sec", 60)

    /**
     * 全自动回复一次发几条。
     * ⚠️ 建议回复里的多条是**候选**（用户挑一条发），不是要连着发 —— 默认 1 条（2026-09-28 用户实测纠正：
     * 自动回复把 3 条候选全发出去了）。上限 3，避免刷屏。
     */
    var autoReplySends by prefOption("ai_chat_auto_reply_sends", 1)
    var autoReplyDailyLimit by prefOption("ai_chat_auto_reply_daily_limit", 20)
    var autoReplyKeywords by prefOption("ai_chat_auto_reply_keywords", "")
    var quietHoursStart by prefOption("ai_chat_quiet_start", "")
    var quietHoursEnd by prefOption("ai_chat_quiet_end", "")

    /** 归一化后的 JEV 完整地址 */
    val jevUrl: String get() = resolveEndpoint(jevEndpoint, "/v1/systemone")
    /** 归一化后的 LLM 完整地址 */
    val llmUrl: String get() = resolveEndpoint(llmEndpoint, "/v1/chat/completions")

    val jevConfigured: Boolean get() = jevUrl.isNotBlank() && jevApiKey.isNotBlank() && jevModel.isNotBlank()
    val llmConfigured: Boolean get() = llmUrl.isNotBlank() && llmApiKey.isNotBlank() && llmModel.isNotBlank()
    /** 至少一条线路可用 */
    val anyConfigured: Boolean get() = (useJev && jevConfigured) || llmConfigured

    private val VERSION_TAIL = Regex("""/v\d+$""")

    /**
     * 用户填的地址 → 可直接请求的完整地址。
     * - 已含 `/chat/completions` / `/completions` / `/systemone` ⇒ 原样用
     * - 以 `/vN` 结尾 ⇒ 补 [defaultPath] 的末段（`/chat/completions` 或 `/systemone`）
     * - 否则 ⇒ 补 [defaultPath] 全路径（缺省 `/v1/chat/completions`）
     */
    fun resolveEndpoint(raw: String, defaultPath: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        if (s.endsWith("/chat/completions") || s.endsWith("/completions") ||
            s.endsWith("/systemone") || s.endsWith("/responses")
        ) return s
        val b = s.trimEnd('/')
        val tail = defaultPath.substringAfterLast('/')  // chat/completions | systemone
        return if (VERSION_TAIL.containsMatchIn(b)) "$b/$tail" else "$b$defaultPath"
    }
}
