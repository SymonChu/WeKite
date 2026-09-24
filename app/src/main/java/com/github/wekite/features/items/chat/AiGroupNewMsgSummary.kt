package com.github.wekite.features.items.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.wekite.features.api.core.WeDatabaseApi
import com.github.wekite.features.api.ui.WeChatNewMsgTipApi
import com.github.wekite.features.core.ClickableFeature
import com.github.wekite.features.core.Feature
import com.github.wekite.preferences.WePrefs.Companion.prefOption
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.Button
import com.github.wekite.ui.content.DefaultColumn
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.content.dialogListItemColors
import com.github.wekite.ui.content.dialogSwitchColors
import com.github.wekite.ui.utils.dpToPx
import com.github.wekite.ui.utils.showComposeDialog
import com.github.wekite.utils.WeLogger
import com.github.wekite.utils.android.showToast
import com.github.wekite.utils.strings.isGroupChatWxId
import com.github.wekite.utils.strings.stripWxId
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

/**
 * 群聊新消息 AI 分析：在聊天页右下「N条新消息」提示条的**下面**挂一个同尺寸「AI分析」胶囊，
 * 点击即把该群**新消息**（未读范围）发给用户自填的 OpenAI 兼容接口，生成一份中文报告。
 *
 * 设计要点：
 * - 挂件尺寸/圆角/内边距/字体**全部复制提示条本体**（背景用 `constantState.newDrawable()`），
 *   所以「和 N条新消息 一样大」不靠写死常量，换微信版本/字体自动一致。
 * - 位置 = 提示条的原始 `marginBottom` 处，提示条自己上移 `自身高度 + 8dp` ⇒ 视觉上紧贴其下方。
 *   提示条被微信隐藏时挂件同步隐藏（没有新消息就没什么可分析的）。
 * - 接口/Key/模型/请求头全部用户自填，支持各类反代与公益站（自定义 URL、Key 头名与前缀、
 *   User-Agent、任意额外请求头、超时/重试、忽略 TLS 校验、错误原文回显）。
 */
@SuppressLint("DiscouragedApi")
@Feature(
    name = "群聊新消息 AI 分析",
    categories = ["聊天"],
    description = "聊天页「N条新消息」下方挂同尺寸「AI分析」胶囊，点击总结该群新消息（接口/模型自填，支持各类反代）"
)
object AiGroupNewMsgSummary : ClickableFeature(), WeChatNewMsgTipApi.ITipListener {

    private const val TAG = "AiGroupNewMsgSummary"

    private const val PILL_TEXT = "AI分析"

    /** 挂件与提示条的间距（视觉上「接着挂」） */
    private const val GAP_DP = 8

    /** 每批喂给模型的条数 */
    private const val CHUNK_SIZE = 60

    /** 拿不到未读数时的兜底条数 */
    private const val FALLBACK_MSGS = 50

    private const val DEFAULT_MAX_MSGS = 200

    private const val DEFAULT_SYSTEM_PROMPT =
        "你是微信群聊分析助手。请把下面这批群聊消息总结成一份中文报告：主要话题、讨论要点、" +
                "值得注意的信息（含待办、决定、链接类信息）。条理清晰，不要编造。"

    // ==================== BYOK 配置 ====================

    /** 完整接口地址（优先；留空则用 base + path 拼） */
    private var apiUrl by prefOption("ai_sum_api_url", "")
    private var apiBase by prefOption("ai_sum_api_base", "")
    private var apiPath by prefOption("ai_sum_api_path", "/v1/chat/completions")
    private var apiKey by prefOption("ai_sum_api_key", "")
    private var model by prefOption("ai_sum_model", "")
    private var keyHeader by prefOption("ai_sum_key_header", "Authorization")
    private var keyPrefix by prefOption("ai_sum_key_prefix", "Bearer ")
    private var userAgent by prefOption("ai_sum_user_agent", "")
    private var extraHeaders by prefOption("ai_sum_extra_headers", "")
    private var timeoutSec by prefOption("ai_sum_timeout_sec", 120)
    private var retries by prefOption("ai_sum_retries", 1)
    private var skipTls by prefOption("ai_sum_skip_tls", false)
    private var maxMsgs by prefOption("ai_sum_max_msgs", DEFAULT_MAX_MSGS)
    private var systemPrompt by prefOption("ai_sum_system_prompt", DEFAULT_SYSTEM_PROMPT)

    // ==================== 挂件状态 ====================

    /** ChattingContent -> 我们的胶囊 */
    private val pills = WeakHashMap<View, TextView>()

    /** 提示条 -> 原标题条底部外边距（只记一次，只加不覆盖） */
    private val tipBaseBottomMargins = WeakHashMap<View, Int>()

    @Volatile
    private var clientCache: Pair<AiParams, OkHttpClient>? = null

    override fun onEnable() {
        WeChatNewMsgTipApi.addListener(this)
    }

    override fun onDisable() {
        WeChatNewMsgTipApi.removeListener(this)
        pills.forEach { (host, pill) ->
            runCatching { (host as? ViewGroup)?.removeView(pill) }
        }
        pills.clear()
        tipBaseBottomMargins.clear()
    }

    // ==================== 挂件同步 ====================

    override fun onTipChanged(host: View, tip: View) {
        runCatching { syncPill(host, tip) }
            .onFailure { WeLogger.e(TAG, "syncPill failed", it) }
    }

    private fun syncPill(host: View, tip: View) {
        val conv = WeChatNewMsgTipApi.convIdOf(host)
        val isGroup = conv.isNotEmpty() && conv.isGroupChatWxId

        if (!isGroup) {
            pills[host]?.visibility = View.GONE
            restoreTipMargin(tip)
            return
        }

        val existing = pills[host]
        val pill = if (existing != null && existing.parent != null) existing
        else createPill(host, tip) ?: return

        val base = tipBaseBottomMargins.getOrPut(tip) {
            (tip.layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        }
        val tipVisible = tip.visibility == View.VISIBLE && tip.height > 0
        val tipHeight = if (tipVisible) tip.height else 0
        val gap = GAP_DP.dpToPx(tip.context)

        // 提示条让出原位：自己上移「高度 + 间距」，我们占它原来的底部边距 ⇒ 视觉上紧贴其下方
        setBottomMargin(tip, if (tipVisible) base + tipHeight + gap else base)
        setBottomMargin(pill, base)
        if (tipHeight > 0 && pill.minimumHeight != tipHeight) pill.minimumHeight = tipHeight

        pill.visibility = if (tipVisible) View.VISIBLE else View.GONE
        pill.setOnClickListener { onPillClick(host, conv) }
    }

    private fun restoreTipMargin(tip: View) {
        val base = tipBaseBottomMargins[tip] ?: return
        setBottomMargin(tip, base)
    }

    private fun setBottomMargin(view: View, margin: Int) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        if (lp.bottomMargin == margin) return
        lp.bottomMargin = margin
        view.layoutParams = lp
    }

    private fun createPill(host: View, tip: View): TextView? {
        val parent = tip.parent as? ViewGroup ?: return null
        val pill = TextView(tip.context).apply {
            text = PILL_TEXT
            isSingleLine = true
            gravity = Gravity.CENTER
        }
        styleFromTip(pill, tip)
        runCatching { parent.addView(pill, cloneParams(tip.layoutParams)) }
            .onFailure {
                WeLogger.e(TAG, "failed to add pill to ${parent.javaClass.name}", it)
                return null
            }
        pills[host] = pill
        WeLogger.i(TAG, "AI pill added: parent=${parent.javaClass.simpleName} host=${host.javaClass.simpleName}")
        return pill
    }

    /** 观感全部取自提示条本体 ⇒ 「挂件大小一样」不靠常量。 */
    private fun styleFromTip(pill: TextView, tip: View) {
        val src = (tip as? ViewGroup)?.let { group ->
            (0 until group.childCount).map { group.getChildAt(it) }.filterIsInstance<TextView>().firstOrNull()
        }
        if (src != null) {
            pill.setTextSize(TypedValue.COMPLEX_UNIT_PX, src.textSize)
            pill.typeface = src.typeface
            pill.setTextColor(src.currentTextColor)
            pill.includeFontPadding = src.includeFontPadding
            pill.gravity = src.gravity
        }
        val tipPadding = intArrayOf(tip.paddingLeft, tip.paddingTop, tip.paddingRight, tip.paddingBottom)
        val padSource = if (tipPadding.any { it > 0 }) tipPadding else null
        val p = padSource ?: src?.let {
            intArrayOf(it.paddingLeft, it.paddingTop, it.paddingRight, it.paddingBottom)
        }
        p?.let { pill.setPadding(it[0], it[1], it[2], it[3]) }

        val bg = tip.background
        if (bg != null) {
            val copy = runCatching { bg.constantState?.newDrawable()?.mutate() }.getOrNull()
            pill.background = copy ?: bg
        }
        if (tip.height > 0) pill.minimumHeight = tip.height
    }

    private fun cloneParams(src: ViewGroup.LayoutParams?): ViewGroup.MarginLayoutParams = when (src) {
        is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(src)
        is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(src)
        is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(src)
        else -> ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // ==================== 点击 → 分析 ====================

    private fun onPillClick(host: View, conv: String) {
        val activity = host.context.activityOrNull() ?: return
        if (!conv.isGroupChatWxId) {
            showToast(activity, "仅支持群聊")
            return
        }
        val params = currentParams()
        if (!params.isUsable()) {
            showToast(activity, "请先配置接口地址 / API Key / 模型名")
            openSettings(activity)
            return
        }
        runWithProgressDialog(activity, "读取新消息…") { onResult, onStage ->
            onResult(runCatching { analyzeNewMessages(conv, params) { onStage(it) } })
        }
    }

    /** 读取该群「新消息」→ 分块总结 → 汇总成报告。 */
    private fun analyzeNewMessages(convId: String, params: AiParams, onStage: (String) -> Unit): String {
        val unread = queryUnread(convId)
        val limit = (if (unread > 0) unread else FALLBACK_MSGS).coerceAtMost(maxMsgs.coerceIn(1, 1000))
        onStage("读取该群最近 $limit 条新消息…")

        val messages = WeDatabaseApi.getMessages(convId, pageIndex = 1, pageSize = limit)
            .filter { it.type?.isText == true }
            .reversed()
        if (messages.isEmpty()) return "该群当前没有可分析的文本新消息。"

        val nameCache = HashMap<String, String>()
        val lines = messages.map { msg ->
            val raw = msg.content
            val who = if (msg.isSend != 0) "我" else resolveSender(convId, senderIdOf(raw), nameCache)
            val text = raw.stripWxId().replace('\n', ' ').trim()
                .let { if (it.length > 400) it.take(400) + "…" else it }
            "$who: $text"
        }

        val chunks = lines.chunked(CHUNK_SIZE)
        onStage("共 ${lines.size} 条新消息，分 ${chunks.size} 批分析…")
        val parts = chunks.mapIndexed { index, chunk ->
            onStage("第 ${index + 1}/${chunks.size} 批分析中…")
            chatCompletion(params, params.systemPrompt, chunk.joinToString("\n"))
        }
        if (parts.size == 1) return parts.first()

        onStage("汇总报告…")
        val digest = parts.mapIndexed { index, part -> "【第 ${index + 1} 批】\n$part" }.joinToString("\n\n")
        return chatCompletion(
            params,
            "你是微信群聊分析助手。以下是对同一群聊多批新消息的小结，请整合成一份完整的中文分析报告：" +
                    "主要话题、讨论要点、值得注意的信息与结论。条理清晰，不要编造。",
            digest
        )
    }

    /** 该会话的未读数（群聊取 `unReadCount` / `unReadMuteCount` 较大者）。 */
    private fun queryUnread(convId: String): Int = runCatching {
        WeDatabaseApi.rawQuery(
            "SELECT unReadCount, unReadMuteCount FROM rconversation WHERE username = ?",
            arrayOf<Any>(convId)
        ).use { cursor -> if (cursor.moveToFirst()) maxOf(cursor.getInt(0), cursor.getInt(1)) else 0 }
    }.getOrDefault(0)

    private val senderPrefixRegex = Regex("""^([^:\n]{1,64}):\n""")

    /** 群消息发送者 wxid 在内容前缀 `wxid:\n` 里（与 `stripWxId` 同一约定）。 */
    private fun senderIdOf(content: String): String =
        senderPrefixRegex.find(content)?.groupValues?.get(1).orEmpty()

    private fun resolveSender(convId: String, senderId: String, cache: HashMap<String, String>): String {
        if (senderId.isEmpty()) return "群成员"
        return cache.getOrPut(senderId) {
            WeDatabaseApi.getGroupMemberDisplayName(convId, senderId).takeIf { it.isNotBlank() } ?: senderId
        }
    }

    // ==================== 设置 ====================

    override fun onClick(context: ComponentActivity) = openSettings(context)

    private fun openSettings(context: Context) {
        showComposeDialog(context) {
            var urlInput by remember { mutableStateOf(apiUrl) }
            var baseInput by remember { mutableStateOf(apiBase) }
            var pathInput by remember { mutableStateOf(apiPath) }
            var keyInput by remember { mutableStateOf(apiKey) }
            var modelInput by remember { mutableStateOf(model) }
            var keyHeaderInput by remember { mutableStateOf(keyHeader) }
            var keyPrefixInput by remember { mutableStateOf(keyPrefix) }
            var uaInput by remember { mutableStateOf(userAgent) }
            var headersInput by remember { mutableStateOf(extraHeaders) }
            var promptInput by remember { mutableStateOf(systemPrompt) }
            var timeoutInput by remember { mutableStateOf(timeoutSec.toString()) }
            var retriesInput by remember { mutableStateOf(retries.toString()) }
            var maxInput by remember { mutableStateOf(maxMsgs.toString()) }
            var tlsInput by remember { mutableStateOf(skipTls) }
            var testStatus by remember { mutableStateOf("") }

            fun inputsAsParams(forTest: Boolean) = AiParams(
                endpoint = endpointOf(urlInput, baseInput, pathInput),
                apiKey = keyInput.filterNot { it.isWhitespace() },
                model = modelInput.trim(),
                keyHeader = keyHeaderInput.trim().ifEmpty { "Authorization" },
                keyPrefix = keyPrefixInput,
                userAgent = uaInput.trim(),
                extraHeaders = headersInput,
                timeoutSec = timeoutInput.trim().toIntOrNull()?.coerceIn(10, 600) ?: 120,
                retries = if (forTest) 0 else retriesInput.trim().toIntOrNull()?.coerceIn(0, 5) ?: 1,
                skipTls = tlsInput,
                systemPrompt = promptInput.trim().ifEmpty { DEFAULT_SYSTEM_PROMPT }
            )

            AlertDialogContent(
                title = { Text("群聊新消息 AI 分析") },
                text = {
                    DefaultColumn(modifier = Modifier.heightIn(max = 420.dp), scrollable = true) {
                        OutlinedTextField(
                            value = urlInput, onValueChange = { urlInput = it },
                            label = { Text("完整接口地址（优先，留空则用下面两项拼）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = baseInput, onValueChange = { baseInput = it },
                            label = { Text("API 基址，如 https://host/v1") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = pathInput, onValueChange = { pathInput = it },
                            label = { Text("接口路径，默认 /v1/chat/completions") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = keyInput, onValueChange = { keyInput = it },
                            label = { Text("API Key") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = modelInput, onValueChange = { modelInput = it },
                            label = { Text("模型名（原样透传，如 deepseek-chat / gpt-4o-mini）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = keyHeaderInput, onValueChange = { keyHeaderInput = it },
                            label = { Text("Key 请求头名，默认 Authorization（有的站要 x-api-key）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = keyPrefixInput, onValueChange = { keyPrefixInput = it },
                            label = { Text("Key 前缀，默认 “Bearer ”（x-api-key 类站点留空）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = uaInput, onValueChange = { uaInput = it },
                            label = { Text("User-Agent（部分公益站按键 UA 放行，可留空）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = headersInput, onValueChange = { headersInput = it },
                            label = { Text("额外请求头，每行一个 key: value") },
                            modifier = Modifier.fillMaxWidth().height(96.dp)
                        )
                        OutlinedTextField(
                            value = promptInput, onValueChange = { promptInput = it },
                            label = { Text("系统提示词（决定报告口径）") },
                            modifier = Modifier.fillMaxWidth().height(120.dp)
                        )
                        OutlinedTextField(
                            value = timeoutInput, onValueChange = { timeoutInput = it },
                            label = { Text("超时秒数（10–600）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = retriesInput, onValueChange = { retriesInput = it },
                            label = { Text("失败重试次数（0–5）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = maxInput, onValueChange = { maxInput = it },
                            label = { Text("单次最多分析条数（1–1000）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        ListItem(
                            colors = dialogListItemColors(),
                            leadingContent = {
                                Switch(
                                    checked = tlsInput,
                                    onCheckedChange = { tlsInput = it },
                                    colors = dialogSwitchColors()
                                )
                            },
                            headlineContent = { Text("忽略 TLS 证书校验（自签反代才需要）") }
                        )
                        Row {
                            TextButton(onClick = {
                                val params = inputsAsParams(forTest = true)
                                if (!params.isUsable()) {
                                    testStatus = "先填完整接口地址、API Key、模型名"
                                } else {
                                    testStatus = "测试中…"
                                    val activity = context.activityOrNull()
                                    thread {
                                        val result = runCatching {
                                            chatCompletion(params, "你是连通性测试助手。", "只回复两个字：可用")
                                        }
                                        val text = result.fold(
                                            onSuccess = { "✅ 连接成功：${it.take(60)}" },
                                            onFailure = { "❌ 失败：${it.message?.take(300)}" }
                                        )
                                        if (activity != null) activity.runOnUiThread { testStatus = text } else testStatus = text
                                    }
                                }
                            }) { Text("测试连接") }
                        }
                        if (testStatus.isNotEmpty()) Text(testStatus)
                    }
                },
                confirmButton = {
                    Button({
                        apiUrl = urlInput.trim()
                        apiBase = baseInput.trim().trimEnd('/')
                        apiPath = pathInput.trim().ifEmpty { "/v1/chat/completions" }
                        apiKey = keyInput.filterNot { it.isWhitespace() }
                        model = modelInput.trim()
                        keyHeader = keyHeaderInput.trim().ifEmpty { "Authorization" }
                        keyPrefix = keyPrefixInput
                        userAgent = uaInput.trim()
                        extraHeaders = headersInput.trim()
                        systemPrompt = promptInput.trim().ifEmpty { DEFAULT_SYSTEM_PROMPT }
                        timeoutSec = timeoutInput.trim().toIntOrNull()?.coerceIn(10, 600) ?: 120
                        retries = retriesInput.trim().toIntOrNull()?.coerceIn(0, 5) ?: 1
                        maxMsgs = maxInput.trim().toIntOrNull()?.coerceIn(1, 1000) ?: DEFAULT_MAX_MSGS
                        skipTls = tlsInput
                        clientCache = null
                        showToast(context, "配置已保存")
                        onDismiss()
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }

    // ==================== 网络层 ====================

    private data class AiParams(
        val endpoint: String,
        val apiKey: String,
        val model: String,
        val keyHeader: String,
        val keyPrefix: String,
        val userAgent: String,
        val extraHeaders: String,
        val timeoutSec: Int,
        val retries: Int,
        val skipTls: Boolean,
        val systemPrompt: String
    ) {
        fun isUsable() = endpoint.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
    }

    /** 完整地址优先；否则 base + path 拼接（path 缺省 `/v1/chat/completions`）。 */
    private fun endpointOf(url: String, base: String, path: String): String {
        val full = url.trim()
        if (full.isNotEmpty()) return full
        val trimmedBase = base.trim().trimEnd('/')
        if (trimmedBase.isEmpty()) return ""
        val p = path.trim().ifEmpty { "/v1/chat/completions" }
        return trimmedBase + (if (p.startsWith("/")) p else "/$p")
    }

    private fun currentParams() = AiParams(
        endpoint = endpointOf(apiUrl, apiBase, apiPath),
        apiKey = apiKey.filterNot { it.isWhitespace() },
        model = model.trim(),
        keyHeader = keyHeader.ifBlank { "Authorization" },
        keyPrefix = keyPrefix,
        userAgent = userAgent.trim(),
        extraHeaders = extraHeaders,
        timeoutSec = timeoutSec.coerceIn(10, 600),
        retries = retries.coerceIn(0, 5),
        skipTls = skipTls,
        systemPrompt = systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }
    )

    private fun clientFor(params: AiParams): OkHttpClient {
        clientCache?.let { if (it.first == params) return it.second }
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(params.timeoutSec.toLong(), TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .apply {
                if (params.skipTls) {
                    runCatching {
                        val trustAll = object : X509TrustManager {
                            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                        }
                        val sslContext = SSLContext.getInstance("TLS")
                        sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
                        sslSocketFactory(sslContext.socketFactory, trustAll)
                        hostnameVerifier { _, _ -> true }
                    }.onFailure { WeLogger.e(TAG, "skip-tls setup failed", it) }
                }
            }
            .build()
        clientCache = params to client
        return client
    }

    /** OpenAI 兼容 `chat/completions` 调用；失败抛出（含上游状态码与响应原文）。 */
    private fun chatCompletion(params: AiParams, system: String, user: String): String {
        val payload = JSONObject()
            .put("model", params.model)
            .put("stream", false)
            .put("temperature", 0.7)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val attempts = params.retries.coerceIn(0, 5) + 1
        var last: Throwable? = null

        for (attempt in 0 until attempts) {
            try {
                val builder = Request.Builder()
                    .url(params.endpoint)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .addHeader(params.keyHeader, params.keyPrefix + params.apiKey)
                    .post(body)
                if (params.userAgent.isNotEmpty()) builder.addHeader("User-Agent", params.userAgent)
                params.extraHeaders.lineSequence()
                    .map { it.trim() }
                    .filter { it.contains(':') && !it.startsWith("#") }
                    .forEach { line ->
                        val idx = line.indexOf(':')
                        builder.addHeader(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
                    }

                clientFor(params).newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        // 错误原文回显：公益站/反代的 4xx-5xx 往往只有 body 里说得清原因
                        throw IllegalStateException("HTTP ${response.code} ${response.message}\n${text.take(800)}")
                    }
                    val content = runCatching {
                        JSONObject(text).getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                    }.getOrElse {
                        throw IllegalStateException("响应不是 OpenAI 兼容格式（解析 choices[0].message.content 失败）\n${text.take(800)}")
                    }
                    return content.trim()
                }
            } catch (t: Throwable) {
                last = t
                if (attempt < attempts - 1) runCatching { Thread.sleep(800L * (attempt + 1)) }
            }
        }
        throw last ?: IllegalStateException("请求失败")
    }

    // ==================== 结果对话框 ====================

    private fun runWithProgressDialog(
        context: Context,
        title: String,
        task: (onResult: (Result<String>) -> Unit, onStageUpdate: (String) -> Unit) -> Unit
    ) {
        val activity = context.activityOrNull()
        showComposeDialog(context, directlyDismissable = false) {
            var stage by remember { mutableStateOf(title) }
            var finished by remember { mutableStateOf(false) }
            var resultText by remember { mutableStateOf("") }
            var errorText by remember { mutableStateOf("") }

            when {
                !finished -> AlertDialogContent(
                    title = { Text("群聊新消息 AI 分析") },
                    text = { Text(stage) }
                )

                errorText.isNotEmpty() -> AlertDialogContent(
                    title = { Text("分析失败") },
                    text = {
                        Text(
                            text = errorText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    },
                    confirmButton = { Button(onDismiss) { Text("关闭") } }
                )

                else -> AlertDialogContent(
                    title = { Text("群聊新消息分析") },
                    text = {
                        Text(
                            text = resultText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(300.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    },
                    confirmButton = { Button(onDismiss) { Text("关闭") } },
                    dismissButton = {
                        TextButton({
                            copyToClipboard(context, resultText)
                            showToast(context, "已复制")
                        }) { Text("复制") }
                    }
                )
            }

            LaunchedEffect(Unit) {
                thread {
                    task(
                        { result ->
                            activity?.runOnUiThread {
                                finished = true
                                result.onSuccess { resultText = it }
                                    .onFailure { errorText = it.message ?: "未知错误" }
                            }
                        },
                        { newStage -> activity?.runOnUiThread { stage = newStage } }
                    )
                }
            }
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("群聊分析", text))
    }

    /** 从 View / 弹窗 context 反查宿主 Activity（与本仓其他聊天页功能同一写法）。 */
    private tailrec fun Context.activityOrNull(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activityOrNull()
        else -> null
    }
}
