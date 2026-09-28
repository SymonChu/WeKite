package com.github.wekite.features.items.chat.aichat

import com.github.wekite.features.api.core.WeDatabaseApi
import com.github.wekite.features.api.core.models.WeMessage
import com.github.wekite.utils.WeLogger
import com.github.wekite.features.items.chat.aichat.protocol.ChoiceProtocol

/**
 * 上下文组装：从 WeDatabaseApi 取当前聊天最近 N 条可读消息（文本 + 已转写语音），
 * 预算裁剪后转成协议层 [ChoiceProtocol.HistoryMessage]。
 *
 * 群聊：content 形如 "wxid_xxx:\n消息正文"，按 [WeDatabaseApi.getGroupMemberDisplayName] 解析发言者显示名。
 * 语音：仅当 VoiceTransText 已有转写时纳入（不主动触发转写——转写由 AutoSpeechToText 覆盖）；
 *       未转写的语音跳过并计数，卡片中注明"部分语音未转写"。
 */
object ContextBuilder {
    private const val TAG = "AiChatContext"
    private const val TYPE_VOICE = 34
    private const val TYPE_TEXT = 1

    data class Built(
        val messages: List<ChoiceProtocol.HistoryMessage>,
        val skippedVoice: Int,
        val truncated: Boolean,
    )

    /** 目标消息之外的前文（不含 target 本身）。 */
    fun build(talker: String, beforeTimeMs: Long, isGroup: Boolean): Built {
        val limit = AiChatConfig.contextLimit.coerceIn(5, 100)
        val budget = AiChatConfig.contextBudget.coerceIn(1000, 48000)
        val raw: List<WeMessage> = try {
            WeDatabaseApi.getMessages(talker, pageIndex = 1, pageSize = limit * 2)
        } catch (e: Exception) {
            WeLogger.e(TAG, "read history failed", e)
            return Built(emptyList(), 0, false)
        }
        val out = mutableListOf<ChoiceProtocol.HistoryMessage>()
        var skippedVoice = 0
        var used = 0
        var truncated = false
        for (m in raw) {
            if (out.size >= limit) { truncated = true; break }
            if (beforeTimeMs > 0 && m.createTime * 1000 >= beforeTimeMs) continue
            val isSelf = m.isSend == 1
            val speaker = when {
                isSelf -> "我"
                isGroup -> {
                    val senderWxId = groupSenderWxId(m)
                    WeDatabaseApi.getGroupMemberDisplayName(talker, senderWxId)
                        .takeIf { it.isNotBlank() } ?: senderWxId
                }
                else -> "对方"
            }
            val body = readableBody(m, isGroup)
            if (body == null) {
                if (m.typeCode == TYPE_VOICE) skippedVoice++
                continue
            }
            if (used + body.length > budget) { truncated = true; break }
            used += body.length
            out += ChoiceProtocol.HistoryMessage(speaker, body, m.createTime * 1000, isSelf)
        }
        return Built(out, skippedVoice, truncated)
    }

    /** 可读正文：文本直接返回；语音读已存转写；其他类型返回 null（不计入）。 */
    private fun readableBody(m: WeMessage, isGroup: Boolean): String? {
        var text = when (m.typeCode) {
            TYPE_TEXT -> m.content
            TYPE_VOICE -> voiceTranscript(m.msgId) ?: return null
            else -> return null
        }
        if (isGroup) {
            val idx = text.indexOf(":\n")
            if (idx in 1..64) {
                val prefix = text.substring(0, idx)
                if (prefix.startsWith("wxid_") || prefix.endsWith("@chatroom") || prefix.length in 20..40) {
                    text = text.substring(idx + 2)
                }
            }
        }
        text = text.trim()
        return text.takeIf { it.isNotEmpty() }?.take(1000)
    }

    /** 群消息发送者 wxid：content 前缀；解析不出回退空串（显示名函数会兜底）。 */
    private fun groupSenderWxId(m: WeMessage): String {
        val idx = m.content.indexOf(":\n")
        if (idx in 1..64) {
            val prefix = m.content.substring(0, idx)
            if (prefix.matches(Regex("[A-Za-z0-9_+-]+"))) return prefix
        }
        return m.talker
    }

    private fun voiceTranscript(msgId: Long): String? = try {
        val cursor = WeDatabaseApi.rawQuery(
            "SELECT content FROM VoiceTransText WHERE msgId = ? LIMIT 1",
            arrayOf<Any>(msgId)
        )
        cursor.use { c ->
            if (c.moveToFirst()) c.getString(0)?.trim()?.takeIf { it.isNotBlank() } else null
        }
    } catch (e: Exception) {
        WeLogger.w(TAG, "voice transcript read failed msgId=$msgId: ${e.message}")
        null
    }

    /** 目标消息本身（含群聊前缀剥离）。 */
    fun targetText(m: WeMessage, isGroup: Boolean): String? = readableBody(m, isGroup)

    fun isGroupTalker(talker: String): Boolean = talker.endsWith("@chatroom")
}
