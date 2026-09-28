package com.github.wekite.features.items.chat.aichat

import android.content.ContentValues
import com.github.wekite.features.api.core.WeDatabaseApi
import com.github.wekite.features.api.core.WeDatabaseListenerApi
import com.github.wekite.features.api.core.WeMessageApi
import com.github.wekite.features.items.chat.aichat.net.AiChatHttp
import com.github.wekite.features.items.chat.aichat.protocol.ChoiceProtocol
import com.github.wekite.features.items.chat.aichat.protocol.IntentQuestions
import com.github.wekite.features.items.chat.aichat.protocol.ReplyProtocol
import com.github.wekite.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * AI 聊天引擎（v3.44 重构）：
 * **消息到达即分析** → 结果推到输入框上方面板；聊天开了全自动回复时再自动发送。
 *
 * 触发：message 表插入监听（`isSend=0`、文本或已转写语音）
 *   两闸任一开着就干活：
 *   - 分析+建议：[AiChatStore.isAnalyzeOn]（聊天右上角「AI」开关）
 *   - 全自动回复：[AiChatStore.isAutoReplyOn]（双闸：全局 consent + 每聊天开关）
 *
 * 分析链路：JEV 情绪概率（可选）→ LLM 解读 + 建议回复（一次请求）
 * 自动发送：规则过滤 → 延迟窗口可撤回 → sendText → 打 [AutoReplyMarker]（仅本机可见徽标）
 */
object ChatAiEngine {
    private const val TAG = "AiChatEngine"
    private const val TABLE_MESSAGE = "message"
    private const val TYPE_TEXT = 1
    private const val TYPE_VOICE = 34

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(2)

    /** 每聊天最近一次结果（面板读它渲染） */
    data class Result(
        val msgId: Long,
        val emotionLine: String,
        val reading: String,
        val replies: List<String>,
        val autoSent: Boolean,
        val note: String = "",
    )

    sealed interface State {
        data object Working : State
        data class Done(val result: Result) : State
        data class Failed(val message: String) : State
    }

    private val states = ConcurrentHashMap<String, State>()
    private val running = ConcurrentHashMap<String, Job>()
    private val lastReplyAt = ConcurrentHashMap<String, Long>()
    private val dailyCount = ConcurrentHashMap<String, MutableMap<String, Int>>()

    private val insertListener = WeDatabaseListenerApi.IInsertListener { table, values ->
        if (table != TABLE_MESSAGE) return@IInsertListener
        try { onIncoming(values) } catch (e: Throwable) { WeLogger.e(TAG, "onIncoming failed", e) }
    }

    fun start() {
        WeDatabaseListenerApi.addListener(insertListener)
        WeLogger.i(TAG, "engine started")
    }

    fun stop() {
        WeDatabaseListenerApi.removeListener(insertListener)
        running.values.forEach { it.cancel() }
        running.clear()
        WeLogger.i(TAG, "engine stopped")
    }

    fun stateOf(talker: String): State? = states[talker]

    /** 当前是否值得展示面板（有结果或正在跑）。 */
    fun hasPanelContent(talker: String): Boolean = states[talker] != null

    fun clear(talker: String) {
        states.remove(talker)
        running.remove(talker)?.cancel()
    }

    /** 用户手动重试（面板上的「重试」）。 */
    fun retry(talker: String) {
        running.put(talker, scope.launch { runAnalyze(talker, 0L, AiChatStore.isAutoReplyOn(talker)) })
    }

    // ==================== 触发 ====================

    private fun onIncoming(values: ContentValues) {
        val talker = values.getAsString("talker") ?: return
        // 自己发出的消息不触发分析（自动回复的打标在发送时就已登记，见 AutoReplyMarker）
        if (values.getAsInteger("isSend") == 1) return
        val type = values.getAsInteger("type") ?: return
        if (type != TYPE_TEXT && type != TYPE_VOICE) return

        val analyze = AiChatStore.isAnalyzeOn(talker)
        val auto = AiChatStore.isAutoReplyOn(talker)
        if (!analyze && !auto) return

        val msgId = values.getAsLong("msgId") ?: 0L
        val content = values.getAsString("content").orEmpty()
        // 诊断日志（I 级）：每聊天开关状态 + 收到的消息类型，排查「没反应」时看这行
        WeLogger.i(TAG, "incoming talker=$talker type=$type msgId=$msgId analyze=$analyze auto=$auto len=${content.length}")

        // 群聊默认只在被 @（或 @所有人）时处理，避免在活跃群里见谁都插话；
        // 「所有消息也处理」按群单独开（AiChatStore.isGroupAllMessages）
        if (ContextBuilder.isGroupTalker(talker) && !AiChatStore.isGroupAllMessages(talker)) {
            val body = stripGroupPrefix(content)
            if (!GroupMention.isAddressedToMe(talker, msgId, body)) {
                WeLogger.i(TAG, "skip group message (not addressed to me) talker=$talker msgId=$msgId")
                return
            }
        }

        if (running[talker]?.isActive == true) {
            // 同一聊天同时只跑一个：自动回复必须处理，纯分析可跳过
            if (!auto) { WeLogger.i(TAG, "skip: previous still running talker=$talker"); return }
        }
        running.put(talker, scope.launch { runAnalyze(talker, msgId, auto) })
    }

    private suspend fun runAnalyze(talker: String, msgId: Long, auto: Boolean) {
        states[talker] = State.Working
        SuggestionPanel.refresh(talker)
        try {
            slots.withPermit { runPipeline(talker, msgId, auto) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            states.remove(talker)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "pipeline failed talker=$talker", e)
            states[talker] = State.Failed(e.message ?: "分析失败")
            SuggestionPanel.refresh(talker)
        } finally {
            running.remove(talker)
        }
    }

    private suspend fun runPipeline(talker: String, triggerMsgId: Long, auto: Boolean) {
        val isGroup = ContextBuilder.isGroupTalker(talker)
        delay(800)  // 等 DB 行与语音转写稳定
        val latest = latestIncoming(talker, isGroup)
        if (latest == null) {
            WeLogger.i(TAG, "no readable incoming message talker=$talker")
            states[talker] = State.Failed("没有可分析的文本消息")
            SuggestionPanel.refresh(talker)
            return
        }
        // 目标消息按 msgId 从上下文里排除（否则同一条既当「目标」又当「前文」）
        val built = ContextBuilder.build(talker, excludeMsgId = latest.first, isGroup = isGroup)
        val state = ChoiceProtocol.contextBlock(
            targetText = latest.second,
            speaker = latest.third,
            targetTimeMs = System.currentTimeMillis(),
            history = built.messages,
        )

        // 1) JEV 情绪（可选）
        var emotionLine = "未启用情绪判断"
        if (AiChatConfig.useJev && AiChatConfig.jevConfigured) {
            val body = AiChatHttp.jevExchange(IntentQuestions.payload(state, AiChatConfig.jevModel))
            emotionLine = IntentQuestions.formatOutcome(IntentQuestions.parse(body))
        }

        // 2) LLM 解读 + 建议回复
        if (!AiChatConfig.llmConfigured) {
            states[talker] = State.Failed("请先在设置里配置 LLM 接口（分析：$emotionLine）")
            SuggestionPanel.refresh(talker)
            return
        }
        val knowledge = ReplyKnowledge.load(isGroup)
        val messages = ReplyProtocol.analyzeMessages(
            state = state,
            emotionSummary = emotionLine,
            relationship = if (isGroup) "群聊成员" else "朋友",
            count = AiChatConfig.suggestionCount,
            knowledge = knowledge,
        )
        val parsed = ReplyProtocol.parse(AiChatHttp.llmExchange(messages, temperature = 0.7))
        val note = buildString {
            if (built.skippedVoice > 0) append("前文有 ${built.skippedVoice} 条语音未转写。")
            if (built.truncated) append("上下文过长已截断。")
        }

        // 3) 自动回复（双闸已在触发处判定；发送前再查一次）
        var autoSent = false
        if (auto && parsed.replies.isNotEmpty() && canAutoSend(talker)) {
            val base = AiChatConfig.autoReplyDelaySec.coerceIn(3, 60) * 1000L
            delay(base + (0L..2000L).random())
            if (AiChatStore.isAutoReplyOn(talker)) {
                var sent = 0
                for (r in parsed.replies) {
                    if (sent > 0) delay(1200L + (0L..1300L).random())
                    var ok = WeMessageApi.sendText(talker, r)
                    if (!ok) {
                        // 偶发失败（09-28 日志实测 3 条里 1 条 ok=false）：等一拍重试一次再放弃
                        delay(1000)
                        ok = WeMessageApi.sendText(talker, r)
                        WeLogger.i(TAG, "AUDIT auto send retried talker=$talker ok=$ok text=${r.take(40)}")
                    } else {
                        WeLogger.i(TAG, "AUDIT auto send talker=$talker ok=true text=${r.take(40)}")
                    }
                    if (!ok) break
                    // 发送成功即登记「这条是我自动发的」：徽标按「聊天+正文」哈希比对
                    AutoReplyMarker.mark(talker, r)
                    sent++
                }
                if (sent > 0) {
                    lastReplyAt[talker] = System.currentTimeMillis()
                    bumpDaily(talker)
                    autoSent = true
                }
            }
        }

        states[talker] = State.Done(Result(latest.first, emotionLine, parsed.reading, parsed.replies, autoSent, note))
        SuggestionPanel.refresh(talker)
        WeLogger.i(
            TAG,
            "done talker=$talker emotion=$emotionLine replies=${parsed.replies.size} autoSent=$autoSent"
        )
    }

    // ==================== 自动回复规则 ====================

    private fun canAutoSend(talker: String): Boolean {
        val now = System.currentTimeMillis()
        val cooldown = AiChatConfig.autoReplyCooldownSec.coerceAtLeast(10) * 1000L
        lastReplyAt[talker]?.let { if (now - it < cooldown) { WeLogger.i(TAG, "auto blocked: cooldown talker=$talker"); return false } }
        if (dailyCount(talker) >= AiChatConfig.autoReplyDailyLimit) { WeLogger.i(TAG, "auto blocked: daily limit talker=$talker"); return false }
        if (inQuietHours(now)) { WeLogger.i(TAG, "auto blocked: quiet hours talker=$talker"); return false }
        val keywords = AiChatConfig.autoReplyKeywords.split(',', '，').map { x: String -> x.trim() }.filter { it.isNotEmpty() }
        if (keywords.isNotEmpty()) {
            val latest = latestIncoming(talker, ContextBuilder.isGroupTalker(talker))?.second.orEmpty()
            if (keywords.none { latest.contains(it) }) { WeLogger.i(TAG, "auto blocked: keywords talker=$talker"); return false }
        }
        return true
    }

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

    private fun parseHm(text: String): Int? = text.split(':').takeIf { it.size == 2 }?.let { (h, m) ->
        h.toIntOrNull()?.let { hh -> m.toIntOrNull()?.let { mm -> hh * 60 + mm } }
    }

    private fun dailyCount(talker: String): Int = dailyCount[talker]?.get(todayKey()) ?: 0

    private fun bumpDaily(talker: String) {
        val key = todayKey()
        dailyCount.getOrPut(talker) { ConcurrentHashMap() }.merge(key, 1, Int::plus)
        dailyCount[talker]?.keys?.removeAll { it != key }
    }

    private fun todayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Calendar.getInstance().time)


    /** 群消息正文去掉 `wxid_xxx:\n` 前缀。 */
    private fun stripGroupPrefix(content: String): String {
        val idx = content.indexOf(":\n")
        if (idx in 1..64) {
            val prefix = content.substring(0, idx)
            if (prefix.matches(Regex("[A-Za-z0-9_@+-]+"))) return content.substring(idx + 2)
        }
        return content
    }

    // ==================== 上下文辅助 ====================

    /** (msgId, 正文, 发送者显示名)；取最新一条对方文本消息。 */
    private fun latestIncoming(talker: String, isGroup: Boolean): Triple<Long, String, String>? {
        val msgs = try {
            WeDatabaseApi.getMessages(talker, pageIndex = 1, pageSize = 8)
        } catch (e: Exception) {
            WeLogger.e(TAG, "read messages failed", e); return null
        }
        for (m in msgs) {
            if (m.isSend == 1) continue
            if (m.typeCode != TYPE_TEXT) continue
            var text = m.content
            var speaker = "对方"
            if (isGroup) {
                val idx = text.indexOf(":\n")
                if (idx in 1..64) {
                    val wxid = text.substring(0, idx)
                    speaker = WeDatabaseApi.getGroupMemberDisplayName(talker, wxid).ifBlank { wxid }
                    text = text.substring(idx + 2)
                }
            }
            val body = text.trim()
            if (body.isEmpty()) continue
            return Triple(m.msgId, body.take(1000), speaker)
        }
        return null
    }
}
