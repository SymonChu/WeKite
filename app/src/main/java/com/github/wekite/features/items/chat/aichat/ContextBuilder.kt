package com.github.wekite.features.items.chat.aichat

import com.github.wekite.features.api.core.WeDatabaseApi
import com.github.wekite.features.api.core.models.WeMessage
import com.github.wekite.features.items.chat.aichat.protocol.ChoiceProtocol
import com.github.wekite.utils.WeLogger
import kotlin.math.abs

/**
 * 上下文组装：取当前聊天最近 N 条**可读**消息（文本 + 已转写语音）作为模型依据。
 *
 * 三个关键点（都是实测踩出来的）：
 * 1. **顺序**：`getMessages` 是 `ORDER BY createTime DESC`（新→旧），而模型需要**从旧到新**，
 *    收集后必须 reversed —— 否则模型看到倒序对话、时间间隔算成负数。
 * 2. **时间单位不固定**：message.createTime 在不同微信版本/库里可能是毫秒也可能是秒，
 *    沿用 WeKite 既有的运行时探测（见 AiGroupNewMsgSummary.createTimeDivisor），不硬编码 ×1000。
 * 3. **目标消息不能重复**：被分析的那条按 msgId 排除，避免既当「目标」又当「前文」。
 *
 * 群聊：content 形如 `wxid_xxx:\n正文`，发言人解析成群昵称；语音只读微信已存的转写
 * （VoiceTransText），未转写的跳过并计数，卡片中注明「部分语音未转写」。
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

    @Volatile
    private var divisorCache: Long = 0L

    /** 库里时间单位换算因子：1=已是毫秒，1000=是秒（探测法与 AiGroupNewMsgSummary 一致）。 */
    private fun divisor(): Long {
        divisorCache.takeIf { it != 0L }?.let { return it }
        val d = runCatching {
            val newest = WeDatabaseApi.rawQuery("SELECT MAX(createTime) FROM message").use { c ->
                if (c.moveToFirst()) c.getLong(0) else 0L
            }
            val now = System.currentTimeMillis()
            val window = 30L * 24 * 3600 * 1000
            when {
                newest <= 0L -> 1L
                abs(newest - now) < window -> 1L            // 库里就是毫秒
                abs(newest * 1000 - now) < window -> 1000L  // 库里是秒
                else -> if (newest > 100_000_000_000L) 1L else 1000L
            }
        }.getOrDefault(1L)
        divisorCache = d
        WeLogger.i(TAG, "createTime divisor = $d")
        return d
    }

    private fun toMs(value: Long): Long = value * divisor()

    /**
     * @param excludeMsgId 目标消息自身的 msgId（排除，避免重复出现在前文）
     * @param beforeTimeMs 只取该时刻之前的消息；<=0 表示不限
     */
    fun build(talker: String, excludeMsgId: Long = 0L, beforeTimeMs: Long = 0L, isGroup: Boolean): Built {
        val limit = AiChatConfig.contextLimit.coerceIn(5, 100)
        val budget = AiChatConfig.contextBudget.coerceIn(1000, 48000)
        val raw: List<WeMessage> = try {
            WeDatabaseApi.getMessages(talker, pageIndex = 1, pageSize = limit * 2)
        } catch (e: Exception) {
            WeLogger.e(TAG, "read history failed", e)
            return Built(emptyList(), 0, false)
        }
        val collected = mutableListOf<ChoiceProtocol.HistoryMessage>()
        var skippedVoice = 0
        var used = 0
        var truncated = false
        // raw 是新→旧：从最新往回取，取够后整体反转成 旧→新
        for (m in raw) {
            if (collected.size >= limit) { truncated = true; break }
            if (excludeMsgId > 0 && m.msgId == excludeMsgId) continue
            val tMs = toMs(m.createTime)
            if (beforeTimeMs > 0 && tMs >= beforeTimeMs) continue
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
            collected += ChoiceProtocol.HistoryMessage(speaker, body, tMs, isSelf)
        }
        val chronological = collected.asReversed()
        WeLogger.i(
            TAG,
            "context built talker=$talker count=${chronological.size} skippedVoice=$skippedVoice truncated=$truncated"
        )
        return Built(chronological, skippedVoice, truncated)
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

    fun targetText(m: WeMessage, isGroup: Boolean): String? = readableBody(m, isGroup)

    fun isGroupTalker(talker: String): Boolean = talker.endsWith("@chatroom")
}
