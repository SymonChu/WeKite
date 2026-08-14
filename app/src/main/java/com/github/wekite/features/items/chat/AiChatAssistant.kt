package com.github.wekite.features.items.chat

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Auto_awesome
import com.composables.icons.materialsymbols.outlined.Smart_toy
import com.composables.icons.materialsymbols.outlined.Summarize
import com.github.wekite.features.api.core.WeDatabaseApi
import com.github.wekite.features.api.core.models.MessageInfo
import com.github.wekite.features.api.ui.WeChatMessageContextMenuApi
import com.github.wekite.features.api.ui.WeChatMessageContextMenuApi.MenuItem
import com.github.wekite.features.api.ui.WeCurrentConversationApi
import com.github.wekite.features.core.ClickableFeature
import com.github.wekite.features.core.Feature
import com.github.wekite.preferences.WePrefs.Companion.prefOption
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.Button
import com.github.wekite.ui.content.DefaultColumn
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.utils.showComposeDialog
import com.github.wekite.ui.utils.ShowComposeDialogScope
import com.github.wekite.utils.WeLogger
import com.github.wekite.utils.android.showToast
import com.github.wekite.utils.strings.stripWxId
import dev.ujhhgtg.reflekt.reflekt
import kotlin.concurrent.thread
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AI 聊天助手：长按消息 AI 分析 / 智能回复 / 群聊批量分析。
 *
 * - 用户自填 OpenAI 兼容 API（DeepSeek/GLM 等），key 只存本地 MMKV
 * - 单条分析：长按文本消息 → 「AI 分析」，对话框展示结果
 * - 智能回复：长按文本消息 → 「AI 智能回复」，生成后填入输入框让用户确认再发
 * - 群聊分析：长按群聊消息 → 「AI 分析群聊」，读取最近 N 条文本消息分块汇总
 */
@SuppressLint("DiscouragedApi")
@Feature(
    name = "AI 聊天助手",
    categories = ["聊天"],
    description = "长按消息 AI 分析/智能回复，支持群聊批量分析（需自配 API）"
)
object AiChatAssistant : ClickableFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "AiChatAssistant"

    private const val MENU_ANALYZE = 790001
    private const val MENU_REPLY = 790002
    private const val MENU_GROUP = 790003

    // 群聊分析分块大小（条/块）
    private const val GROUP_CHUNK_SIZE = 50

    private var apiBase by prefOption("ai_api_base", "https://api.deepseek.com/v1")
    private var apiKey by prefOption("ai_api_key", "")
    private var model by prefOption("ai_model", "deepseek-v4-flash")
    private var groupMsgCount by prefOption("ai_group_msg_count", 100)

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<MenuItem> = listOf(
        MenuItem(
            id = MENU_ANALYZE,
            text = "AI 分析",
            drawable = com.github.wekite.ui.utils.EditIcon,
            imageVector = MaterialSymbols.Outlined.Auto_awesome,
            isSupported = { it.type?.isText == true },
            onClick = { view, _, msgInfo -> analyzeMessage(view, msgInfo) }
        ),
        MenuItem(
            id = MENU_REPLY,
            text = "AI 智能回复",
            drawable = com.github.wekite.ui.utils.EditIcon,
            imageVector = MaterialSymbols.Outlined.Smart_toy,
            isSupported = { it.type?.isText == true },
            onClick = { view, _, msgInfo -> replyMessage(view, msgInfo) }
        ),
        MenuItem(
            id = MENU_GROUP,
            text = "AI 分析群聊",
            drawable = com.github.wekite.ui.utils.ChatInfoIcon,
            imageVector = MaterialSymbols.Outlined.Summarize,
            isSupported = { it.isInGroupChat },
            onClick = { view, _, msgInfo -> analyzeGroup(view, msgInfo) }
        )
    )

    // ==================== 设置 ====================

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var baseInput by remember { mutableStateOf(apiBase) }
            var keyInput by remember { mutableStateOf(apiKey) }
            var modelInput by remember { mutableStateOf(model) }
            var countInput by remember { mutableStateOf(groupMsgCount.toString()) }
            AlertDialogContent(
                title = { Text("AI 聊天助手") },
                text = {
                    DefaultColumn {
                        OutlinedTextField(
                            value = baseInput,
                            onValueChange = { baseInput = it },
                            label = { Text("API 地址") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = { keyInput = it },
                            label = { Text("API Key") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = modelInput,
                            onValueChange = { modelInput = it },
                            label = { Text("模型名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = countInput,
                            onValueChange = { countInput = it },
                            label = { Text("群聊分析条数（最多 500）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button({
                        apiBase = baseInput.trim().trimEnd('/')
                        apiKey = keyInput.trim()
                        model = modelInput.trim()
                        groupMsgCount = countInput.trim().toIntOrNull()?.coerceIn(10, 500) ?: 100
                        showToast("AI 配置已保存")
                        onDismiss()
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }

    // ==================== 单条分析 ====================

    private fun analyzeMessage(view: View, msgInfo: MessageInfo) {
        if (!ensureConfigured(view.context)) return
        val text = msgInfo.humanReadableRepr.ifBlank { return }
        runWithProgressDialog(view, "AI 分析中…") { onResult, _ ->
            val result = runCatching {
                chatCompletion(
                    system = "你是微信消息分析助手。请分析用户提供的这条微信消息，从含义、语气、意图三方面简述，最后给出一句建议回复方向。用中文，简洁，不超过 200 字。",
                    user = "消息内容：\n$text"
                )
            }
            onResult(result)
        }
    }

    // ==================== 智能回复 ====================

    private fun replyMessage(view: View, msgInfo: MessageInfo) {
        if (!ensureConfigured(view.context)) return
        val text = msgInfo.humanReadableRepr.ifBlank { return }

        runWithProgressDialog(
            view,
            "AI 生成回复中…",
            doneContent = { reply ->
                AlertDialogContent(
                    title = { Text("AI 智能回复") },
                    text = {
                        Text(
                            text = reply,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    },
                    confirmButton = {
                        Button({
                            fillInputBox(reply)
                            showToast("已填入输入框，确认后发送")
                            onDismiss()
                        }) { Text("填入输入框") }
                    },
                    dismissButton = {
                        TextButton({
                            copyToClipboard(view.context, reply)
                            showToast("已复制")
                            onDismiss()
                        }) { Text("复制") }
                    }
                )
            }
        ) { onResult, _ ->
            val result = runCatching {
                chatCompletion(
                    system = "你是微信聊天助手。请以用户本人的身份，对下面这条收到的消息生成一条自然、口语化的中文回复。只输出回复内容本身，不要任何解释、前缀或引号。",
                    user = "收到的消息：\n$text"
                )
            }
            onResult(result)
        }
    }

    /** 将文本填入当前聊天输入框（ChatFooter 的 EditText），由用户确认后发送 */
    private fun fillInputBox(text: String) {
        val chatFooter = WeCurrentConversationApi.chatFooter ?: run {
            WeLogger.w(TAG, "no active ChatFooter, cannot fill input box")
            return
        }
        try {
            // 输入框字段：非空且暴露 addTextChangedListener 的字段（同 WeChatInputBarApi 定位方式）
            val editField = chatFooter.reflekt().firstFieldOrNull {
                type { clazz ->
                    clazz.isInterface && clazz.declaredMethods.any { it.name == "addTextChangedListener" }
                }
            }?.get() ?: run {
                WeLogger.w(TAG, "failed to locate chat input field")
                return
            }
            val editText = editField as? android.widget.EditText ?: return
            editText.setText(text)
            editText.setSelection(text.length)
        } catch (ex: Throwable) {
            WeLogger.e(TAG, "failed to fill input box", ex)
        }
    }

    // ==================== 群聊分析 ====================

    private fun analyzeGroup(view: View, msgInfo: MessageInfo) {
        if (!ensureConfigured(view.context)) return
        val talker = msgInfo.talker
        val count = groupMsgCount

        runWithProgressDialog(view, "读取群消息并分析中…") { onResult, onStageUpdate ->
            val result = runCatching {
                analyzeGroupMessages(talker, count) { stage -> onStageUpdate(stage) }
            }
            onResult(result)
        }
    }

    /** 拉取群聊最近 N 条文本消息，分块调用 LLM 生成小结，最后汇总成报告 */
    private fun analyzeGroupMessages(talker: String, count: Int, onStageUpdate: (String) -> Unit): String {
        // 拉取消息（SQL 按 createTime DESC，最新在前，翻转到时间正序）
        val pageSize = 500
        val messages = WeDatabaseApi.getMessages(talker, pageIndex = 1, pageSize = pageSize)
            .filter { it.type?.isText == true }
            .reversed() // 时间正序
            .takeLast(count)

        if (messages.isEmpty()) return "该群聊没有可分析的文本消息。"

        val chunks = messages.chunked(GROUP_CHUNK_SIZE)
        onStageUpdate("共 ${messages.size} 条消息，分 ${chunks.size} 批分析中…")

        val summaries = chunks.mapIndexed { index, chunk ->
            val prompt = chunk.joinToString("\n") { msg ->
                val sender = if (msg.isSend != 0) "我" else "群成员"
                val content = msg.content.stripWxId().replace('\n', ' ')
                "$sender: $content"
            }
            onStageUpdate("第 ${index + 1}/${chunks.size} 批分析中…")
            chatCompletion(
                system = "你是微信群聊分析助手。请总结下面这批群聊消息的主要内容、话题和关键信息，用中文，条理清晰，不超过 150 字。",
                user = prompt
            )
        }

        if (chunks.size == 1) return summaries.first()

        onStageUpdate("汇总各批次分析结果…")
        val digest = summaries.mapIndexed { index, summary ->
            "【第 ${index + 1} 批】\n$summary"
        }.joinToString("\n\n")
        return chatCompletion(
            system = "你是微信群聊分析助手。以下是对同一群聊多批消息的分析小结，请整合成一份完整清晰的群聊分析报告，包含：主要话题、讨论要点、值得注意的信息。用中文，条理清晰。",
            user = digest
        )
    }

    // ==================== 网络层 ====================

    private fun ensureConfigured(context: Context): Boolean {
        if (apiKey.isBlank() || apiBase.isBlank()) {
            showToast("请先在 WeKite 设置中配置 AI 聊天助手的 API Key")
            return false
        }
        return true
    }

    /** OpenAI 兼容 chat/completions 调用，返回模型回复文本；失败抛异常 */
    private fun chatCompletion(system: String, user: String): String {
        val url = "$apiBase/chat/completions"
        val payload = JSONObject()
            .put("model", model)
            .put("temperature", 0.7)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            )

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                throw IllegalStateException("API 请求失败 (${response.code}): ${body.take(200)}")
            }
            val json = JSONObject(body)
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    // ==================== UI 工具 ====================

    /**
     * 弹「处理中」对话框，后台线程执行 [task]，完成后在主线程切换到结果视图。
     * [doneContent] 非空时用于自定义成功结果视图（如智能回复的按钮），
     * 否则展示默认结果 + 复制按钮。
     */
    private fun runWithProgressDialog(
        view: View,
        title: String,
        doneContent: (@Composable ShowComposeDialogScope.(result: String) -> Unit)? = null,
        task: (onResult: (Result<String>) -> Unit, onStageUpdate: (String) -> Unit) -> Unit
    ) {
        showComposeDialog(view.context, directlyDismissable = false) {
            var stage by remember { mutableStateOf(title) }
            var finished by remember { mutableStateOf(false) }
            var resultText by remember { mutableStateOf("") }
            var errorText by remember { mutableStateOf("") }

            if (!finished) {
                AlertDialogContent(
                    title = { Text("AI 聊天助手") },
                    text = { Text(stage) },
                    confirmButton = null,
                    dismissButton = null
                )
            } else if (errorText.isNotEmpty()) {
                AlertDialogContent(
                    title = { Text("AI 出错") },
                    text = {
                        Text(
                            text = errorText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    },
                    confirmButton = { Button(onDismiss) { Text("关闭") } }
                )
            } else if (doneContent != null) {
                doneContent(resultText)
            } else {
                AlertDialogContent(
                    title = { Text("AI 结果") },
                    text = {
                        Text(
                            text = resultText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(280.dp)
                                .verticalScroll(rememberScrollState())
                        )
                    },
                    confirmButton = { Button(onDismiss) { Text("关闭") } },
                    dismissButton = {
                        TextButton({
                            copyToClipboard(view.context, resultText)
                            showToast("已复制")
                        }) { Text("复制") }
                    }
                )
            }

            LaunchedEffect(Unit) {
                thread {
                    task(
                        { result ->
                            view.post {
                                finished = true
                                result.onSuccess { resultText = it }
                                    .onFailure { errorText = it.message ?: "未知错误" }
                            }
                        },
                        { newStage ->
                            view.post { stage = newStage }
                        }
                    )
                }
            }
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("AI 结果", text))
    }
}
