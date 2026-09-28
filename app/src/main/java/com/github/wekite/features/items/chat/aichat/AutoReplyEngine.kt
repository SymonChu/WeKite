package com.github.wekite.features.items.chat.aichat

import android.content.ContentValues
import com.github.wekite.features.api.core.WeDatabaseListenerApi
import com.github.wekite.features.api.core.WeDatabaseApi
import com.github.wekite.features.items.chat.aichat.net.AiChatHttp
import com.github.wekite.features.items.chat.aichat.protocol.ChoiceProtocol
import com.github.wekite.features.items.chat.aichat.protocol.ReplyProtocol
import com.github.wekite.features.api.core.WeMessageApi
import com.github.wekite.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 三期：全自动回复引擎。
 *
 * 链路：message 表插入监听 → [AiChatStore] 每聊天开关（双闸之一）→ 规则过滤
 * （关键词、免打扰时段、冷却、每日限额）→ LLM 生成分条回复 → 延迟窗口（可取消）
 * → [WeMessageApi.sendText] 发送 → 查回 msgSvrId 打上 [AutoReplyMarker]（仅自己可见的「AI」徽标）。
 *
 * 安全设计：
 * - 全局总开关 [AiChatConfig.autoReplyConsent] + 每聊天开关双闸
 * - 发送前延迟窗口内用户关开关即取消
 * - 每次动作写审计日志
 * - 成功发送的消息通过消息表回查 msgSvrId → 标记表 → 气泡徽标（仅本机可见）
 */
object AutoReplyEngine {
    private const val TAG = "AiAutoReply"
    private const val TABLE_MESSAGE = "message"
    private const val TYPE_TEXT = 1

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** talker -> 上次自动回复时间(ms) */
    private val lastReplyAt = ConcurrentHashMap<String, Long>()
    /** talker -> 日期 -> 计数 */
    private val dailyCount = ConcurrentHashMap<String, MutableMap<String, Int>>()
    /** talker -> 进行中的发送 job（可取消） */
    private val pendingJobs = ConcurrentHashMap<String, Job>()

    /**
     * 等待回查标记的发送：talker -> 发送内容快照。
     * sendText 走微信原声队列后消息表会出现 isSend=1 的新行；用内容+时间窗回查 msgSvrId。
     */
    private val awaitingMark = ConcurrentHashMap<String, SentTrace>()

    private class SentTrace(val talker: String, val contents: List<String>, val sentAtMs: Long)

    private val insertListener = WeDatabaseListenerApi.IInsertListener { table, values ->
        if (table != TABLE_MESSAGE) return@IInsertListener
        try { onIncomingRow(values) } catch (e: Throwable) {
            WeLogger.e(TAG, "onIncomingRow failed", e)
        }
    }

    fun start() {
        WeDatabaseListenerApi.addListener(insertListener)
        WeLogger.i(TAG, "auto reply engine started")
    }

    fun stop() {
        WeDatabaseListenerApi.removeListener(insertListener)
        pendingJobs.values.forEach { it.cancel() }
        pendingJobs.clear()
        WeLogger.i(TAG, "auto reply engine stopped")
    }

    /** 用户关闭某聊天开关时，撤掉在途任务。 */
    fun cancelPending(talker: String) {
        pendingJobs.remove(talker)?.cancel()
    }

    // ==================== 触发 ====================

    private fun onIncomingRow(values: ContentValues) {
        val talker = values.getAsString("talker") ?: return
        val isSend = values.getAsInteger("isSend") ?: return

        // 出消息行：若在等待回查标记 → 查 svrId 打标
        if (isSend == 1) {
            maybeMarkOutgoing(talker, values)
            return
        }
        // 入消息行：评估是否触发自动回复
        if (!AiChatStore.isAutoReplyOn(talker)) return

        val type = values.getAsInteger("type") ?: return
        if (type != TYPE_TEXT) return
        val content = values.getAsString("content").orEmpty()
        if (content.isBlank()) return

        val now = System.currentTimeMillis()
        val cooldownMs = AiChatConfig.autoReplyCooldownSec.coerceAtLeast(10) * 1000L
        lastReplyAt[talker]?.let { if (now - it < cooldownMs) return }
        if (dailyCount(talker) >= AiChatConfig.autoReplyDailyLimit) return
        if (inQuietHours(now)) return
        val keywords = AiChatConfig.autoReplyKeywords.split(',', '，').map { x: String -> x.trim() }.filter { it.isNotEmpty() }
        if (keywords.isNotEmpty() && keywords.none { content.contains(it) }) return

        val msgId = values.getAsLong("msgId") ?: 0L
        val jobId = scope.launch { generateAndSend(talker, msgId, now) }
        pendingJobs.put(talker, jobId)?.cancel()
    }

    // ==================== 生成 + 发送 ====================

    private suspend fun generateAndSend(talker: String, triggerMsgId: Long, triggeredAt: Long) {
        try {
            val isGroup = ContextBuilder.isGroupTalker(talker)
            // 等一拍让 DB 行稳定
            delay(1500)
            val built = ContextBuilder.build(talker, beforeTimeMs = 0, isGroup = isGroup)
            val target = latestIncomingText(talker, isGroup) ?: return
            val state = ChoiceProtocol.contextBlock(
                targetText = target,
                speaker = groupSpeakerOfLatest(talker),
                targetTimeMs = triggeredAt,
                history = built.messages,
            )
            val knowledge = ReplyKnowledge.load(isGroup)
            val messages = ReplyProtocol.replyMessages(state, if (isGroup) "群聊成员" else "朋友", "", knowledge)
            val body = AiChatHttp.llmExchange(messages, temperature = 0.8)
            val replies = ReplyProtocol.parseReplies(body)
            if (replies.isEmpty()) return

            // 延迟窗口（可取消）：基础 + 随机抖动
            val base = AiChatConfig.autoReplyDelaySec.coerceIn(3, 60) * 1000L
            delay(base + (0..2000).random())

            // 发送前再查一次双闸（用户可能已关）
            if (!AiChatStore.isAutoReplyOn(talker)) return

            var sent = 0
            for (r in replies) {
                if (sent > 0) delay(1200L + (0L..1300L).random())
                val ok = WeMessageApi.sendText(talker, r)
                audit(talker, triggerMsgId, r, ok)
                if (!ok) break
                sent++
            }
            if (sent > 0) {
                lastReplyAt[talker] = System.currentTimeMillis()
                bumpDaily(talker)
                // 登记等待回查 msgSvrId（插入监听里完成打标）
                awaitingMark[talker] = SentTrace(talker, replies.take(sent), System.currentTimeMillis())
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            WeLogger.i(TAG, "auto reply cancelled talker=$talker")
        } catch (e: Exception) {
            WeLogger.e(TAG, "auto reply failed talker=$talker", e)
        } finally {
            pendingJobs.remove(talker)
        }
    }

    // ==================== 标记回查 ====================

    private fun maybeMarkOutgoing(talker: String, values: ContentValues) {
        val trace = awaitingMark[talker] ?: return
        val content = values.getAsString("content").orEmpty()
        val createTime = values.getAsLong("createTime") ?: 0L
        // 时间窗 60s + 内容精确匹配
        if (createTime > 0 && System.currentTimeMillis() / 1000 - createTime > 60) return
        val matched = trace.contents.any { it == content || content.endsWith(it) }
        if (!matched) return
        val svrId = values.getAsLong("msgSvrId") ?: 0L
        if (svrId > 0) {
            AutoReplyMarker.mark(svrId)
            awaitingMark.remove(talker)
            WeLogger.i(TAG, "AUDIT marked svrId=$svrId talker=$talker")
        }
    }

    // ==================== 规则辅助 ====================

    private fun latestIncomingText(talker: String, isGroup: Boolean): String? {
        val msgs = try {
            WeDatabaseApi.getMessages(talker, pageIndex = 1, pageSize = 5)
        } catch (e: Exception) { return null }
        for (m in msgs) {
            if (m.isSend == 1) continue
            if (m.typeCode != TYPE_TEXT) continue
            var text = m.content
            if (isGroup) {
                val idx = text.indexOf(":\n")
                if (idx in 1..64) text = text.substring(idx + 2)
            }
            return text.trim().takeIf { it.isNotEmpty() }
        }
        return null
    }

    private fun groupSpeakerOfLatest(talker: String): String {
        val msgs = try { WeDatabaseApi.getMessages(talker, pageIndex = 1, pageSize = 5) } catch (_: Exception) { return "对方" }
        for (m in msgs) {
            if (m.isSend == 1 || m.typeCode != TYPE_TEXT) continue
            val idx = m.content.indexOf(":\n")
            if (idx in 1..64) {
                val wxid = m.content.substring(0, idx)
                return WeDatabaseApi.getGroupMemberDisplayName(talker, wxid).ifBlank { wxid }
            }
        }
        return "对方"
    }

    private fun dailyCount(talker: String): Int = dailyCount[talker]?.get(todayKey()) ?: 0

    private fun bumpDaily(talker: String) {
        val key = todayKey()
        dailyCount.getOrPut(talker) { ConcurrentHashMap() }.merge(key, 1, Int::plus)
        dailyCount[talker]?.keys?.removeAll { it != key }
    }

    private fun todayKey(): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Calendar.getInstance().time)

    private fun inQuietHours(now: Long): Boolean {
        val start = AiChatConfig.quietHoursStart.trim()
        val end = AiChatConfig.quietHoursEnd.trim()
        if (start.isBlank() || end.isBlank()) return false
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val minutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val s = parseHm(start) ?: return false
        val e = parseHm(end) ?: return false
        return if (s <= e) minutes in s until e else minutes >= s || minutes < e
    }

    private fun parseHm(text: String): Int? =
        text.split(':').takeIf { it.size == 2 }?.let { (h, m) ->
            h.toIntOrNull()?.let { hh -> m.toIntOrNull()?.let { mm -> hh * 60 + mm } }
        }

    private fun audit(talker: String, triggerMsgId: Long, content: String, ok: Boolean) {
        WeLogger.i(TAG, "AUDIT talker=$talker triggerMsgId=$triggerMsgId ok=$ok content=${content.take(80)}")
    }
}
