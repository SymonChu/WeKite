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
    private fun groupAllKey(talker: String) = "ai_chat_group_all_$talker"

    fun isAnalyzeOn(talker: String): Boolean =
        talker.isNotBlank() && WePrefs.getBoolOrDef(analyzeKey(talker), false)

    fun setAnalyzeOn(talker: String, on: Boolean) {
        WePrefs.putBool(analyzeKey(talker), on)
        
    }

    /** 全自动回复：需要设置页全局总开关 [AiChatConfig.autoReplyConsent] 同时打开。 */
    fun isAutoReplyOn(talker: String): Boolean =
        talker.isNotBlank() && AiChatConfig.autoReplyConsent && WePrefs.getBoolOrDef(autoKey(talker), false)

    fun setAutoReplyOn(talker: String, on: Boolean) {
        WePrefs.putBool(autoKey(talker), on)
    }

    /**
     * 群聊：是否连「没点我名」的消息也处理（默认关 ⇒ 只在被 @ 时触发）。
     * 单聊无意义，恒 true。
     */
    fun isGroupAllMessages(talker: String): Boolean =
        !ContextBuilder.isGroupTalker(talker) || WePrefs.getBoolOrDef(groupAllKey(talker), false)

    fun setGroupAllMessages(talker: String, on: Boolean) {
        WePrefs.putBool(groupAllKey(talker), on)
    }

    fun resetAll(talker: String) {
        setAnalyzeOn(talker, false)
        setAutoReplyOn(talker, false)
    }
}
