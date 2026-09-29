package com.github.wekite.features.items.chat.aichat

import com.github.wekite.features.api.core.WeDatabaseApi
import com.github.wekite.features.api.core.WeMessageApi
import com.github.wekite.features.api.core.models.MessageType
import com.github.wekite.preferences.WePrefs
import com.github.wekite.preferences.WePrefs.Companion.prefOption
import com.github.wekite.utils.WeLogger

/**
 * 自动回复消息的「仅自己可见」标识。
 *
 * ⚠️ 机制选择（2026-09-28 用户实测后的结论，**改用防撤回同款做法**）：
 * - 视图层挂小徽标失败过两次：挂 RecyclerView 上不参与布局；改用浅蓝配色在浅色聊天背景上
 *   几乎看不见（日志显示挂载成功但用户看不到）。
 * - 现改为 **在消息表里插一条 SYSTEM 类型的系统提示行** —— 与 WeKite「防撤回」提示完全同款机制
 *   （`WeMessageApi.createSimpleMsgInfoAndInsert`），微信自身会把它渲染成灰色居中的系统文案，
 *   在任何聊天背景下都清晰可见；只写本地库、不发送 ⇒ 对方看不到。
 *
 * 时间戳单位：插入时沿用刚发出的那条消息的 createTime（同库同单位）再 +1，
 * 保证排在它后面；查不到时按库单位取当前时间的等效值。
 */
object AutoReplyMarker {
    private const val TAG = "AiAutoMarker"
    private val recentlyMarked = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val DEDUP_MS = 120_000L

    /** 系统提示文案（可配，占位：无）。 */
    private var noticeText by prefOption("ai_chat_mark_text", "本条消息由 AI 自动回复")

    /**
     * 自动发送成功后调用：在该消息下方插一条系统提示行。
     * @return 是否插入成功
     */
    fun markSent(talker: String, content: String): Boolean {
        if (talker.isBlank() || content.isBlank()) return false
        val key = "$talker\u0000${content.trim()}"
        val now = System.currentTimeMillis()
        recentlyMarked.entries.removeAll { now - it.value > DEDUP_MS }
        if (recentlyMarked.containsKey(key)) return false   // 同一条不重复标记（重试路径）
        recentlyMarked[key] = now

        val createTime = findCreateTime(talker, content)
        val text = noticeText.ifBlank { "本条消息由 AI 自动回复" }
        return try {
            WeMessageApi.createSimpleMsgInfoAndInsert(
                MessageType.SYSTEM.code,
                talker,
                text,
                (createTime ?: currentInDbUnit()) + 1,
            )
            WeLogger.i(TAG, "marker inserted talker=$talker text=${content.take(20)}")
            true
        } catch (e: Exception) {
            WeLogger.e(TAG, "marker insert failed talker=$talker", e)
            false
        }
    }

    /** 刚发出的那条（自己发的、正文匹配）的 createTime；查不到返回 null。 */
    private fun findCreateTime(talker: String, content: String): Long? = try {
        WeDatabaseApi.rawQuery(
            "SELECT createTime FROM message WHERE talker = ? AND isSend = 1 AND content = ? " +
                "ORDER BY msgId DESC LIMIT 1",
            arrayOf<Any>(talker, content)
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
    } catch (e: Exception) {
        WeLogger.w(TAG, "findCreateTime failed: ${e.message}")
        null
    }

    /** 当前时间的「库单位」等效值（毫秒库直接返回，秒库返回秒）。 */
    private fun currentInDbUnit(): Long {
        val divisor = ContextBuilder.dbUnitDivisor()
        return System.currentTimeMillis() / divisor
    }
}
