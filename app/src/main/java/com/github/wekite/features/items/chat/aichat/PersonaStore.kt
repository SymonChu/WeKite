package com.github.wekite.features.items.chat.aichat

import com.github.wekite.features.api.core.WeDatabaseApi
import com.github.wekite.features.items.chat.aichat.net.AiChatHttp
import com.github.wekite.preferences.WePrefs
import com.github.wekite.utils.WeLogger
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * 会话预设：手写人设 + 自动「风格画像」。
 *
 * 目的（用户 2026-09-29）：更省 token、更像机主本人。
 * - 手写预设：用户给每个联系人/群写一段人设说明（如「我妈，随意但有分寸」）。
 * - 风格画像：从该会话**机主自己发出的消息**（isSend==1）里总结出的口吻卡片
 *   （句长/标点/口癖/语气），一次生成、缓存复用，替代「靠原始历史口吻对齐」的那部分 token。
 *
 * 存储（MMKV，按会话隔离）：
 *   ai_chat_persona_<talker>          手写预设文本
 *   ai_chat_profile_<talker>          自动风格画像文本
 *   ai_chat_profile_at_<talker>       画像生成时间（ms）
 *   ai_chat_profile_auto_<talker>     过期自动重生成开关（默认关）
 */
object PersonaStore {
    private const val TAG = "AiPersona"
    private const val AGE_LIMIT_MS = 7L * 24 * 3600 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 画像生成中（按会话防并发重复合成） */
    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, Deferred<String?>>()
    /** 上次尝试合成时间（失败退避：≥10 分钟才允许再试，避免开着自动重生成时每条消息都白烧一次调用） */
    private val lastAttempt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ---------- 手写预设 ----------

    fun preset(talker: String): String = WePrefs.getStringOrDef(presetKey(talker), "")

    fun setPreset(talker: String, text: String) {
        WePrefs.putString(presetKey(talker), text.trim())
    }

    // ---------- 风格画像 ----------

    fun profile(talker: String): String = WePrefs.getStringOrDef(profileKey(talker), "")

    fun profileAt(talker: String): Long = WePrefs.getLongOrDef(profileAtKey(talker), 0L)

    fun profileAutoRegen(talker: String): Boolean =
        WePrefs.getBoolOrDef(profileAutoKey(talker), false)

    fun setProfileAutoRegen(talker: String, on: Boolean) {
        WePrefs.putBool(profileAutoKey(talker), on)
    }

    fun profileAgeDays(talker: String): Int =
        ((System.currentTimeMillis() - profileAt(talker)) / (24 * 3600_000L)).toInt()

    fun isExpired(talker: String): Boolean =
        profile(talker).isBlank() || System.currentTimeMillis() - profileAt(talker) > AGE_LIMIT_MS

    /** 画像可用性：非空 + （未过期 或 开了自动重生成） */
    fun usable(talker: String): String? {
        val text = profile(talker)
        if (text.isBlank()) return null
        return if (!isExpired(talker) || profileAutoRegen(talker)) text else null
    }

    /**
     * 取画像：未过期直接用；过期且开了自动重生成则后台合成（首次返回旧值/空，好了之后下条消息生效）。
     */
    suspend fun ensureProfile(talker: String): String? {
        val text = profile(talker)
        if (text.isNotBlank() && !isExpired(talker)) return text
        if (profileAutoRegen(talker)) {
            // 不阻塞当前消息：后台生成，成功后写库，下一条消息起生效
            regenAsync(talker)
            return text.takeIf { it.isNotBlank() }
        }
        return null
    }

    /** 后台合成画像（幂等：同会话并发只跑一次，完成后唤醒所有等待者）。 */
    fun regenAsync(talker: String) {
        if (inFlight.containsKey(talker)) return
        val last = lastAttempt[talker] ?: 0L
        if (System.currentTimeMillis() - last < 10 * 60_000L) return
        lastAttempt[talker] = System.currentTimeMillis()
        val d = scope.async { generate(talker) }
        inFlight[talker] = d
        d.invokeOnCompletion { inFlight.remove(talker) }
    }

    /** 同步生成画像（弹窗「重新生成」按钮用，返回错误文案或 null=成功）。 */
    suspend fun regenerateBlocking(talker: String): String? = generate(talker)

    private suspend fun generate(talker: String): String? {
        try {
            val mine = myRecentMessages(talker)
            if (mine.size < 5) {
                WeLogger.i(TAG, "profile skip talker=$talker only ${mine.size} own messages")
                return "机主在该会话的发言少于 5 条，暂时无法总结风格"
            }
            val prompt = buildList {
                add(
                    "system" to "你是语言风格分析师。只输出风格总结本身，不要客套、不要标题、不要分点编号以外的内容。"
                )
                add(
                    "user" to buildString {
                        append("以下是机主在一个会话里自己发出的消息（按时间旧→新）：\n")
                        append(mine.joinToString("\n"))
                        append("\n\n请总结机主的聊天风格，恰好 4 行，每行一个方面，行首用标签：\n")
                        append("长度：典型字数与长短分布\n")
                        append("标点：句号/问号/省略号/空格的使用习惯\n")
                        append("用词：口癖、语气词、emoji、称呼习惯\n")
                        append("语气：整体气质（如随意/正式/调侃/温柔），给一句可直接指导写作的描述")
                    }
                )
            }
            val body = AiChatHttp.llmExchange(prompt, temperature = 0.3)
            val content = org.json.JSONObject(body).optJSONArray("choices")
                ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty().trim()
            if (content.isBlank()) return "模型返回为空，请稍后重试"
            WePrefs.putString(profileKey(talker), content)
            WePrefs.putLong(profileAtKey(talker), System.currentTimeMillis())
            WeLogger.i(TAG, "profile saved talker=$talker len=${content.length}")
            return null
        } catch (e: Exception) {
            WeLogger.e(TAG, "profile generate failed talker=$talker", e)
            return e.message?.take(160) ?: "生成失败"
        }
    }

    /** 机主最近发言（isSend==1，最多 100 条，旧→新）。 */
    private fun myRecentMessages(talker: String): List<String> {
        val raw = try {
            WeDatabaseApi.getMessages(talker, pageIndex = 1, pageSize = 100)
        } catch (e: Exception) {
            WeLogger.e(TAG, "read own messages failed talker=$talker", e)
            return emptyList()
        }
        val isGroup = ContextBuilder.isGroupTalker(talker)
        return raw.asReversed()
            .filter { it.isSend == 1 }
            .mapNotNull { m ->
                ContextBuilder.targetText(m, isGroup)
                    ?.takeIf { it.length in 2..200 }
                    ?.let { it.replace('\n', ' ') }
            }
            .takeLast(100)
    }

    // ---------- 拼注入块 ----------

    /** system 注入块：手写预设 + 画像。两者皆空返回 null。 */
    fun injectBlock(talker: String): String? {
        val preset = preset(talker)
        val current = profile(talker)
        val stale = current.isBlank() || isExpired(talker)
        // 开了自动重生成且过期 ⇒ 顺手触发后台合成（内部有并发去重 + 失败退避），本条消息先用旧值
        if (stale && profileAutoRegen(talker)) regenAsync(talker)
        val profile = usable(talker)
        if (preset.isBlank() && profile.isNullOrBlank()) return null
        return buildString {
            if (preset.isNotBlank()) {
                append("机主对这段关系的补充说明（优先级最高）：").append(preset).append('\n')
            }
            if (!profile.isNullOrBlank()) {
                append("机主本人的聊天风格（回复必须贴合，这是「怎么说话」的权威依据）：\n").append(profile)
                if (isExpired(talker)) append("\n（该画像已过期，仅供参考）")
            }
        }
    }

    private fun presetKey(talker: String) = "ai_chat_persona_$talker"
    private fun profileKey(talker: String) = "ai_chat_profile_$talker"
    private fun profileAtKey(talker: String) = "ai_chat_profile_at_$talker"
    private fun profileAutoKey(talker: String) = "ai_chat_profile_auto_$talker"
}
