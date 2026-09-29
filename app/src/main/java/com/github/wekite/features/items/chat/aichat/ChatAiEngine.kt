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
        data class Working(val msgId: Long) : State
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

    /** 「聊天#消息」→ 状态：气泡下的分析卡按消息取用（上游 append-only 同思路）。 */
    private val byMsg = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, State>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, State>?) = size > 200
        }
    )

    private fun msgKey(talker: String, msgId: Long) = "$talker#$msgId"

    private fun putMsgState(talker: String, msgId: Long, state: State) {
        if (msgId > 0) byMsg[msgKey(talker, msgId)] = state
    }

    /** 该消息的分析状态（气泡卡渲染用）；null = 这条没分析过。 */
    fun stateFor(talker: String, msgId: Long): State? = byMsg[msgKey(talker, msgId)]

    /** 某聊天下所有「分析过」的消息 → 状态（气泡卡据此把卡片挂到已有行上）。 */
    fun statesForTalker(talker: String): Map<Long, State> {
        val prefix = "$talker#"
        return synchronized(byMsg) {
            byMsg.entries.filter { it.key.startsWith(prefix) }
                .mapNotNull { e ->
                    val id = e.key.removePrefix(prefix).toLongOrNull() ?: return@mapNotNull null
                    id to e.value
                }
                .toMap()
        }
    }

    /** 当前是否值得展示面板（有结果或正在跑）。 */
    fun hasPanelContent(talker: String): Boolean = states[talker] != null

    fun clear(talker: String) {
        states.remove(talker)
        BubbleCard.clear(talker)
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
        // 「所有消息也处理」按群单独开（AiChatStore.isGroupAllMessages）。
        // 例外（用户 2026-09-29 要求的 OR 语义）：配置了关键词白名单且这条消息命中 ⇒
        // 即使没 @ 我也放行（后续发送仍过 canAutoSend 全套规则）。
        if (ContextBuilder.isGroupTalker(talker) && !AiChatStore.isGroupAllMessages(talker)) {
            val body = stripGroupPrefix(content)
            if (!GroupMention.isAddressedToMe(talker, msgId, body)) {
                if (keywordWhitelistHit(content)) {
                    WeLogger.i(TAG, "group message not @me but keyword hit, process talker=$talker msgId=$msgId")
                } else {
                    WeLogger.i(TAG, "skip group message (not addressed to me) talker=$talker msgId=$msgId")
                    return
                }
            }
        }

        // 只开了自动回复、而当前规则不允许发送（冷却/日限额/免打扰）⇒ 不必跑模型，省一次调用
        // （09-28 日志实测：一条群消息触发整套分析后才发现被冷却拦下）
        if (!analyze && !canAutoSend(talker)) {
            WeLogger.i(TAG, "skip: auto not allowed now (cooldown/limit/quiet) talker=$talker")
            return
        }

        if (running[talker]?.isActive == true) {
            // 同一聊天同时只跑一个：自动回复必须处理，纯分析可跳过
            if (!auto) { WeLogger.i(TAG, "skip: previous still running talker=$talker"); return }
        }
        running.put(talker, scope.launch { runAnalyze(talker, msgId, auto) })
    }

    private suspend fun runAnalyze(talker: String, msgId: Long, auto: Boolean) {
        states[talker] = State.Working(msgId)
        putMsgState(talker, msgId, State.Working(msgId))
        BubbleCard.refresh(talker)
        try {
            slots.withPermit { runPipeline(talker, msgId, auto) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            states.remove(talker)
        } catch (e: Throwable) {
            WeLogger.e(TAG, "pipeline failed talker=$talker", e)
            states[talker] = State.Failed(e.message ?: "分析失败")
            putMsgState(talker, msgId, State.Failed(e.message ?: "分析失败"))
        } finally {
            running.remove(talker)
        }
    }

    private suspend fun runPipeline(talker: String, triggerMsgId: Long, auto: Boolean) {
        val isGroup = ContextBuilder.isGroupTalker(talker)
        val t0 = System.currentTimeMillis()
        delay(250)  // 等 DB 行稳定（原 800ms，实测偏保守；语音转写多数在插入时已就绪）
        val latest = latestIncoming(talker, isGroup)
        if (latest == null) {
            WeLogger.i(TAG, "no readable incoming message talker=$talker")
            states[talker] = State.Failed("没有可分析的文本消息")
            BubbleCard.refresh(talker)
            return
        }
        // 目标消息按 msgId 从上下文里排除（否则同一条既当「目标」又当「前文」）
        val built = ContextBuilder.build(talker, excludeMsgId = latest.first, isGroup = isGroup)
        val tCtx = System.currentTimeMillis()
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
        val tJev = System.currentTimeMillis()

        // 2) LLM 解读 + 建议回复
        if (!AiChatConfig.llmConfigured) {
            states[talker] = State.Failed("请先在设置里配置 LLM 接口（分析：$emotionLine）")
            putMsgState(talker, latest.first, states[talker]!!)
            BubbleCard.refresh(talker)
            return
        }
        val knowledge = ReplyKnowledge.load(isGroup)
        val messages = ReplyProtocol.analyzeMessages(
            state = state,
            emotionSummary = emotionLine,
            relationship = if (isGroup) "群聊成员" else "朋友",
            count = AiChatConfig.suggestionCount,
            knowledge = knowledge,
            persona = PersonaStore.injectBlock(talker),
        )
        val parsed = ReplyProtocol.parse(AiChatHttp.llmExchange(messages, temperature = 0.9))
        val tLlm = System.currentTimeMillis()
        val note = buildString {
            if (built.skippedVoice > 0) append("前文有 ${built.skippedVoice} 条语音未转写。")
            if (built.truncated) append("上下文取最近 ${built.messages.size} 条。")
        }

        // 3) 自动回复（双闸已在触发处判定；发送前再查一次）
        var autoSent = false
        if (auto && parsed.replies.isNotEmpty() && canAutoSend(talker)) {
            val base = AiChatConfig.autoReplyDelaySec.coerceIn(3, 60) * 1000L
            delay(base + (0L..2000L).random())
            if (AiChatStore.isAutoReplyOn(talker)) {
                var sent = 0
                // 候选只挑前 N 条发（默认 1）——见 AiChatConfig.autoReplySends 的说明
                val toSend = parsed.replies.take(AiChatConfig.autoReplySends.coerceIn(1, 3))
                for (r in toSend) {
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
                    // 发送成功即在该消息下方插一条系统提示（防撤回同款机制，仅本机可见）
                    AutoReplyMarker.markSent(talker, r)
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
        putMsgState(talker, latest.first, states[talker]!!)
        BubbleCard.refresh(talker)
        WeLogger.i(
            TAG,
            "done talker=$talker emotion=$emotionLine replies=${parsed.replies.size} autoSent=$autoSent"
        )
        WeLogger.i(
            TAG,
            "timing talker=$talker ctx=${tCtx - t0}ms jev=${tJev - tCtx}ms llm=${tLlm - tJev}ms total=${tLlm - t0}ms"
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
            // OR 语义（用户 2026-09-29）：群聊里被 @ 我 ⇒ 不看关键词直接放行；
            // 其余（单聊全部 / 群未@）按白名单过滤。空名单 = 不过滤。
            val isGroup = ContextBuilder.isGroupTalker(talker)
            val latest = latestIncoming(talker, isGroup)
            val body = latest?.second.orEmpty()
            val atMe = isGroup && latest != null &&
                GroupMention.isAddressedToMe(talker, latest.first, stripGroupPrefix(body))
            if (!atMe && keywords.none { body.contains(it) }) {
                WeLogger.i(TAG, "auto blocked: keywords talker=$talker"); return false
            }
        }
        return true
    }

    /** 关键词白名单是否命中（空名单恒 false；命中判定与 [canAutoSend] 里的发送过滤同一套词表）。 */
    private fun keywordWhitelistHit(content: String): Boolean {
        val keywords = AiChatConfig.autoReplyKeywords.split(',', '，').map { x: String -> x.trim() }.filter { it.isNotEmpty() }
        if (keywords.isEmpty()) return false
        return keywords.any { content.contains(it) }
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
