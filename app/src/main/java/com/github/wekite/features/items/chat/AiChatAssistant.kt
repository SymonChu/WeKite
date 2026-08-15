package com.github.wekite.features.items.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.github.wekite.features.api.core.WeDatabaseApi
import com.github.wekite.features.api.ui.WeConversationContextMenuApi
import com.github.wekite.features.api.ui.WeConversationContextMenuApi.ConversationContext
import com.github.wekite.features.api.ui.WeConversationContextMenuApi.MenuItem
import com.github.wekite.features.core.ClickableFeature
import com.github.wekite.features.core.Feature
import com.github.wekite.preferences.WePrefs.Companion.prefOption
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.Button
import com.github.wekite.ui.content.DefaultColumn
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.utils.showComposeDialog
import com.github.wekite.utils.android.showToast
import com.github.wekite.utils.strings.stripWxId
import kotlin.concurrent.thread
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * AI 聊天助手：群聊批量分析。
 *
 * - 用户自填 OpenAI 兼容 API（DeepSeek/GLM 等），key 只存本地 MMKV
 * - 入口：微信主页长按群聊名称 → 菜单末尾「群聊分析」
 * - 读取最近 N 条文本消息分块汇总成报告
 */
@SuppressLint("DiscouragedApi")
@Feature(
    name = "AI 聊天助手",
    categories = ["聊天"],
    description = "长按群聊名称 AI 分析群聊消息（需自配 API）"
)
object AiChatAssistant : ClickableFeature(), WeConversationContextMenuApi.IMenuItemsProvider {

    private const val TAG = "AiChatAssistant"

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
        WeConversationContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeConversationContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<MenuItem> = listOf(
        MenuItem(
            id = MENU_GROUP,
            text = "群聊分析",
            drawable = com.github.wekite.ui.utils.ChatInfoIcon,
            shouldShow = { context, _ -> context.talker.endsWith("@chatroom") },
            onClick = { context -> analyzeGroup(context) }
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

    // ==================== 群聊分析 ====================

    private fun analyzeGroup(context: ConversationContext) {
        val activity = context.activity
        if (!ensureConfigured(activity)) return
        val talker = context.talker
        val count = groupMsgCount

        runWithProgressDialog(activity, "读取群消息并分析中…") { onResult, onStageUpdate ->
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

    /** 弹「处理中」对话框，后台线程执行 [task]，完成后在主线程切换到结果视图 */
    private fun runWithProgressDialog(
        activity: Activity,
        title: String,
        task: (onResult: (Result<String>) -> Unit, onStageUpdate: (String) -> Unit) -> Unit
    ) {
        showComposeDialog(activity, directlyDismissable = false) {
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
                            copyToClipboard(activity, resultText)
                            showToast("已复制")
                        }) { Text("复制") }
                    }
                )
            }

            LaunchedEffect(Unit) {
                thread {
                    task(
                        { result ->
                            activity.runOnUiThread {
                                finished = true
                                result.onSuccess { resultText = it }
                                    .onFailure { errorText = it.message ?: "未知错误" }
                            }
                        },
                        { newStage ->
                            activity.runOnUiThread { stage = newStage }
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
