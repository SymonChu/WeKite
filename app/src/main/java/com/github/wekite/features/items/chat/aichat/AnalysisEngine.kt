package com.github.wekite.features.items.chat.aichat

import com.github.wekite.features.items.chat.aichat.net.AiChatHttp
import com.github.wekite.features.items.chat.aichat.protocol.ChoiceProtocol
import com.github.wekite.features.items.chat.aichat.protocol.IntentQuestions
import com.github.wekite.features.items.chat.aichat.protocol.ReplyProtocol
import com.github.wekite.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 一期：消息意图分析引擎。
 *
 * 并发语义（借鉴 yanwai 的队列设计，独立实现）：
 * - Semaphore(2)：最多 2 个并发模型请求
 * - 每条消息一个 key（talker+msgId），结果缓存，重复请求复用
 * - 切聊天/关开关 → [cancelConversation] 取消该聊天全部在途任务
 * - 失败保留错误信息，点击卡片重试
 */
object AnalysisEngine {
    private const val TAG = "AiAnalysis"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(2)

    data class Result(
        val emotionLine: String,
        val reading: String?,
        val suggestions: List<String>,
        val note: String,
    )

    sealed interface State {
        data object Pending : State
        data class Done(val result: Result) : State
        data class Failed(val message: String) : State
    }

    private val states = ConcurrentHashMap<String, State>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val stateMutex = Mutex()

    fun stateOf(key: String): State? = states[key]

    fun submit(
        key: String,
        talker: String,
        isGroup: Boolean,
        targetText: String,
        targetSpeaker: String,
        targetTimeMs: Long,
    ) {
        if (states[key] is State.Done) return
        val old = jobs.put(key, scope.launch { run(key, talker, isGroup, targetText, targetSpeaker, targetTimeMs) })
        old?.cancel()
    }

    fun retry(key: String, talker: String, isGroup: Boolean, targetText: String, targetSpeaker: String, targetTimeMs: Long) {
        states.remove(key)
        submit(key, talker, isGroup, targetText, targetSpeaker, targetTimeMs)
    }

    private suspend fun run(
        key: String, talker: String, isGroup: Boolean,
        targetText: String, targetSpeaker: String, targetTimeMs: Long,
    ) {
        setState(key, State.Pending)
        try {
            slots.withPermit {
                val built = ContextBuilder.build(talker, beforeTimeMs = targetTimeMs, isGroup = isGroup)
                val noteBase = buildString {
                    if (built.skippedVoice > 0) append("有 ${built.skippedVoice} 条语音未转写，未纳入分析。")
                    if (built.truncated) append("上下文过长已截断。")
                }
                val state = ChoiceProtocol.contextBlock(targetText, targetSpeaker, targetTimeMs, built.messages)

                // JEV 线路：情绪概率
                check(AiChatConfig.jevConfigured) { "未配置 JEV 接口（设置 → AI 聊天助手）" }
                val jevBody = AiChatHttp.jevExchange(IntentQuestions.payload(state, AiChatConfig.jevModel))
                val intent = IntentQuestions.parse(jevBody)
                val emotionLine = IntentQuestions.formatOutcome(intent)

                // LLM 线路（可选）：补充解读
                var reading: String? = null
                var suggestions: List<String> = emptyList()
                if (AiChatConfig.llmConfigured) {
                    runCatching {
                        val msgs = ReplyProtocol.intentMessages(state, emotionLine)
                        val body = AiChatHttp.llmExchange(msgs, temperature = 0.5)
                        val (r, s) = ReplyProtocol.parseIntents(body)
                        reading = r
                        suggestions = s
                    }.onFailure { WeLogger.w(TAG, "llm intent failed: ${it.message}") }
                }
                setState(key, State.Done(Result(emotionLine, reading, suggestions, noteBase)))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            states.remove(key)
        } catch (e: IllegalStateException) {
            setState(key, State.Failed(e.message ?: "分析失败"))
        } catch (e: IllegalArgumentException) {
            setState(key, State.Failed("模型返回不完整，请点击重试"))
        } catch (e: Exception) {
            WeLogger.e(TAG, "analyze failed key=$key", e)
            setState(key, State.Failed("分析失败：${e.javaClass.simpleName}"))
        } finally {
            jobs.remove(key)
        }
    }

    private suspend fun setState(key: String, s: State) {
        stateMutex.withLock { states[key] = s }
    }

    fun cancelConversation(talker: String) {
        scope.coroutineContext[Job]?.cancelChildren()
        states.keys.removeAll { it.startsWith("$talker#") }
    }

    fun clearCache() {
        states.clear()
    }
}
