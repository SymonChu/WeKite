package com.github.wekite.features.items.chat.aichat

import com.github.wekite.preferences.WePrefs

/**
 * 每聊天/群聊开关（用户核心诉求：粒度到每个聊天）。
 * 存 MMKV（WePrefs），key 前缀区分能力：
 *   ai_chat_analyze_on_<talker>  分析开关
 *   ai_chat_reply_on_<talker>    建议回复开关（二期）
 *   ai_chat_auto_on_<talker>     自动回复开关（三期，双闸之一）
 * 开关值进程内缓存 + 落盘，微信重启后保留。
 */
object AiChatStore {

    private fun analyzeKey(talker: String) = "ai_chat_analyze_on_$talker"
    private fun replyKey(talker: String) = "ai_chat_reply_on_$talker"
    private fun autoKey(talker: String) = "ai_chat_auto_on_$talker"

    // ---------- 一期：意图分析 ----------
    fun isAnalyzeOn(talker: String): Boolean =
        talker.isNotBlank() && WePrefs.getBoolOrDef(analyzeKey(talker), false)

    fun setAnalyzeOn(talker: String, on: Boolean) {
        WePrefs.putBool(analyzeKey(talker), on)
        if (!on) AnalysisEngine.cancelConversation(talker)
    }

    // ---------- 二期：建议回复（确认后发送） ----------
    fun isReplyOn(talker: String): Boolean =
        talker.isNotBlank() && WePrefs.getBoolOrDef(replyKey(talker), false)

    fun setReplyOn(talker: String, on: Boolean) {
        WePrefs.putBool(replyKey(talker), on)
    }

    // ---------- 三期：自动回复 ----------
    fun isAutoReplyOn(talker: String): Boolean =
        talker.isNotBlank() && AiChatConfig.autoReplyConsent && WePrefs.getBoolOrDef(autoKey(talker), false)

    fun setAutoReplyOn(talker: String, on: Boolean) {
        WePrefs.putBool(autoKey(talker), on)
        if (!on) AutoReplyEngine.cancelPending(talker)
    }

    /** 清空某聊天的全部开关（用户可能想整体重置）。 */
    fun resetAll(talker: String) {
        setAnalyzeOn(talker, false)
        setReplyOn(talker, false)
        setAutoReplyOn(talker, false)
    }
}
