package com.github.wekite.features.items.chat.aichat

import com.github.wekite.preferences.WePrefs

/**
 * 每聊天/群聊开关（粒度到每个聊天，MMKV 持久化）。
 *   ai_chat_analyze_on_<talker>  自动分析 + 输入框上方建议回复
 *   ai_chat_auto_on_<talker>     全自动回复（双闸之一，另一闸是设置页的全局总开关）
 */
object AiChatStore {

    private fun analyzeKey(talker: String) = "ai_chat_analyze_on_$talker"
    private fun autoKey(talker: String) = "ai_chat_auto_on_$talker"

    fun isAnalyzeOn(talker: String): Boolean =
        talker.isNotBlank() && WePrefs.getBoolOrDef(analyzeKey(talker), false)

    fun setAnalyzeOn(talker: String, on: Boolean) {
        WePrefs.putBool(analyzeKey(talker), on)
        if (!on) { ChatAiEngine.clear(talker); SuggestionPanel.refresh(talker) }
    }

    /** 全自动回复：需要设置页全局总开关 [AiChatConfig.autoReplyConsent] 同时打开。 */
    fun isAutoReplyOn(talker: String): Boolean =
        talker.isNotBlank() && AiChatConfig.autoReplyConsent && WePrefs.getBoolOrDef(autoKey(talker), false)

    fun setAutoReplyOn(talker: String, on: Boolean) {
        WePrefs.putBool(autoKey(talker), on)
    }

    fun resetAll(talker: String) {
        setAnalyzeOn(talker, false)
        setAutoReplyOn(talker, false)
    }
}
