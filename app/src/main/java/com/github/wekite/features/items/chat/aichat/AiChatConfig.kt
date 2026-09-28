package com.github.wekite.features.items.chat.aichat

import com.github.wekite.preferences.WePrefs
import com.github.wekite.preferences.WePrefs.Companion.prefOption

/**
 * AI 聊天助手配置。全部走 WePrefs；每聊天开关单独存 [AiChatStore]。
 *
 * 双线路（吸收自 yanwai 的思路，独立实现）：
 * - JEV 情绪线路：OpenRouter systemone 端点，结构化 choice 协议，出概率分布
 * - LLM 意图线路：任意 OpenAI 兼容 chat/completions，出自由文本解读
 * 情绪概率永远来自 JEV；LLM 只做补充解读（与 yanwai 2.1.x 行为一致）。
 */
object AiChatConfig {
    // ---------- JEV 线路 ----------
    /** OpenRouter JEV 完整地址；留空 = 未配置情绪线路 */
    var jevEndpoint by WePrefs.prefOption("ai_chat_jev_endpoint", "https://openrouter.ai/api/v1/systemone")
    var jevModel by WePrefs.prefOption("ai_chat_jev_model", "typesafe/jev-1.13")
    var jevApiKey by WePrefs.prefOption("ai_chat_jev_key", "")

    // ---------- LLM 线路（意图解读 + 回复生成共用） ----------
    /** OpenAI 兼容 chat/completions 完整地址；留空 = 未配置 */
    var llmEndpoint by WePrefs.prefOption("ai_chat_llm_endpoint", "")
    var llmApiKey by WePrefs.prefOption("ai_chat_llm_key", "")
    var llmModel by WePrefs.prefOption("ai_chat_llm_model", "")

    // ---------- 分析 ----------
    /** 自动分析可见消息（默认关，只保留长按单条） */
    var autoAnalyze by WePrefs.prefOption("ai_chat_auto_analyze", false)
    /** 组装上下文的最大条数 */
    var contextLimit by WePrefs.prefOption("ai_chat_context_limit", 30)
    /** 组装上下文的最大字符预算 */
    var contextBudget by WePrefs.prefOption("ai_chat_context_budget", 8000)

    // ---------- 二期：回复建议 ----------
    var replyConsent by WePrefs.prefOption("ai_chat_reply_consent", false)
    /** 默认参考条数 */
    var replyContextLimit by WePrefs.prefOption("ai_chat_reply_context_limit", 50)

    // ---------- 三期：自动回复 ----------
    var autoReplyConsent by WePrefs.prefOption("ai_chat_auto_reply_consent", false)
    /** 发送前延迟窗口（秒），窗口内可取消 */
    var autoReplyDelaySec by WePrefs.prefOption("ai_chat_auto_reply_delay_sec", 5)
    /** 同一聊天两次自动回复的最小间隔（秒） */
    var autoReplyCooldownSec by WePrefs.prefOption("ai_chat_auto_reply_cooldown_sec", 60)
    /** 每聊天每日自动回复上限 */
    var autoReplyDailyLimit by WePrefs.prefOption("ai_chat_auto_reply_daily_limit", 20)
    /** 触发关键词；逗号分隔，空 = 所有消息触发（仍受白名单/开关约束） */
    var autoReplyKeywords by WePrefs.prefOption("ai_chat_auto_reply_keywords", "")
    /** 免打扰时段起止（24h 制 "23:00"）；空 = 不限 */
    var quietHoursStart by WePrefs.prefOption("ai_chat_quiet_start", "")
    var quietHoursEnd by WePrefs.prefOption("ai_chat_quiet_end", "")

    val jevConfigured: Boolean get() = jevEndpoint.isNotBlank() && jevApiKey.isNotBlank()
    val llmConfigured: Boolean get() = llmEndpoint.isNotBlank() && llmApiKey.isNotBlank() && llmModel.isNotBlank()
}
