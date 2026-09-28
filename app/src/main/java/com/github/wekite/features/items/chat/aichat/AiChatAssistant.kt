package com.github.wekite.features.items.chat.aichat

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.wekite.features.api.ui.WeChatMessageViewApi
import com.github.wekite.features.api.ui.WeCurrentConversationApi
import com.github.wekite.features.core.ClickableFeature
import com.github.wekite.features.core.Feature
import com.github.wekite.features.items.chat.aichat.net.AiChatHttp
import com.github.wekite.features.items.chat.aichat.protocol.ChoiceProtocol
import com.github.wekite.features.items.chat.aichat.protocol.IntentQuestions
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.Button
import com.github.wekite.ui.content.DefaultColumn
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.utils.showComposeDialog
import com.github.wekite.utils.WeLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AI 聊天助手。
 *
 * 工作方式：
 * - 聊天右上角「AI」开关打开 ⇒ 该聊天的**新消息自动分析**，结果与建议回复显示在**输入框上方**面板
 * - 全自动回复：设置页全局总闸 + 每聊天开关（双闸）；发出的消息带「AI」小徽标（仅本机可见）
 *
 * 配置：WeKite 设置 → 聊天 → AI 聊天助手（点条目进配置弹窗）。
 */
@Feature(name = "AI 聊天助手", categories = ["聊天"], description = "新消息自动分析并给出建议回复，可全自动回复")
object AiChatAssistant : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener {

    private const val TAG = "AiChatAssistant"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycleInstalled = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
        ChatAiEngine.start()
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        ChatAiEngine.stop()
        SuggestionPanel.detach()
        ChatHeaderToggle.remove()
    }

    override fun onClick(context: ComponentActivity) = showSettingsDialog(context)

    fun openSettingsDialog(activity: android.app.Activity) {
        (activity as? ComponentActivity)?.let { showSettingsDialog(it) }
    }

    override fun onCreateView(param: com.github.wekite.utils.HookParam, view: View) {
        if (!isEnabled) return
        val activity = view.context as? android.app.Activity
        if (activity != null && lifecycleInstalled.compareAndSet(false, true)) {
            ChatPageLifecycle.install(activity)
        }
        if (WeCurrentConversationApi.value.isBlank()) return
        val msgInfo = try {
            WeChatMessageViewApi.getMsgInfoFromParam(param)
        } catch (_: Exception) {
            return
        }
        // 全自动回复发出的消息 → 气泡旁「AI」小徽标（按「聊天 + 正文」哈希比对，不依赖 msgSvrId）
        if (msgInfo.isSend == 1) {
            val body = msgInfo.actualContent.trim()
            if (body.isNotEmpty() && AutoReplyMarker.isMarked(msgInfo.talker, body)) {
                AiBadge.attach(view)
            } else {
                AiBadge.detach(view)
            }
        } else if (activity != null) {
            // 借「有消息在渲染」这个时机尽力挂面板（此时 ChatFooter 通常已就绪）
            SuggestionPanel.attach(activity, WeCurrentConversationApi.value)
        }
    }

    // ==================== 设置弹窗 ====================

    private fun showSettingsDialog(activity: ComponentActivity) {
        showComposeDialog(activity, directlyDismissable = false) {
            // 弹窗限高 + 底部留白：条目多时下方不再被屏幕裁掉（用户反馈过）
            val dm = activity.resources.displayMetrics
            val h = (dm.heightPixels * 0.66f).toInt()
            window.setLayout((dm.widthPixels * 0.92f).toInt(), h)
            AlertDialogContent(
                title = { Text("AI 聊天助手") },
                text = { SettingsContent() },
                confirmButton = { Button(onDismiss) { Text("关闭") } },
            )
        }
    }

    /** 供「测试连接」按钮使用。 */
    suspend fun testConnection(kind: String): String = withContext(Dispatchers.IO) {
        try {
            if (kind == "jev") {
                check(AiChatConfig.jevConfigured) { "请先填 JEV 地址与 Key" }
                val state = ChoiceProtocol.contextBlock("你好", "对方", System.currentTimeMillis(), emptyList())
                val body = AiChatHttp.jevExchange(IntentQuestions.payload(state, AiChatConfig.jevModel))
                "✅ JEV 可用：${IntentQuestions.formatOutcome(IntentQuestions.parse(body))}"
            } else {
                check(AiChatConfig.llmConfigured) { "请先填 LLM 地址、Key 与模型" }
                val body = AiChatHttp.llmExchange(
                    listOf("system" to "你是连通性测试助手。", "user" to "只回复两个字：可用"),
                    temperature = 0.0,
                )
                val content = org.json.JSONObject(body).optJSONArray("choices")
                    ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                "✅ LLM 可用：${content.take(40)}"
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "test connection failed ($kind)", e)
            "❌ 失败：${e.message?.take(160)}"
        }
    }

    @Composable
    private fun SettingsContent() {
        val talker = WeCurrentConversationApi.value
        // 粘贴的 Key 常带换行（Authorization 报 0x0a）⇒ 一律清洗空白
        fun cleanKey(v: String) = v.filterNot { it.isWhitespace() }

        var llmEndpoint by remember { mutableStateOf(AiChatConfig.llmEndpoint) }
        var llmKey by remember { mutableStateOf(AiChatConfig.llmApiKey) }
        var llmModel by remember { mutableStateOf(AiChatConfig.llmModel) }
        var jevKey by remember { mutableStateOf(AiChatConfig.jevApiKey) }
        var testResult by remember { mutableStateOf("") }

        fun test(kind: String) {
            testResult = "测试中…（${if (kind == "jev") AiChatConfig.jevUrl else AiChatConfig.llmUrl}）"
            scope.launch { testResult = testConnection(kind) }
        }

        DefaultColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            scrollable = true,
        ) {
            // ---- 主线路：LLM（分析 + 建议回复都用它）----
            Text("对话模型（必填）", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = llmEndpoint,
                onValueChange = { llmEndpoint = it; AiChatConfig.llmEndpoint = it.trim() },
                label = { Text("接口地址（可只填 base 网址）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = llmKey,
                onValueChange = { llmKey = it; AiChatConfig.llmApiKey = cleanKey(it) },
                label = { Text("API Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = llmModel,
                onValueChange = { llmModel = it; AiChatConfig.llmModel = it.trim() },
                label = { Text("模型 ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ test("llm") }) { Text("测试") }
                Text("实际请求：${AiChatConfig.llmUrl}", style = MaterialTheme.typography.bodySmall)
            }

            // ---- 情绪线路：JEV（可选）----
            Text("情绪概率（可选，OpenRouter Key 即可）", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = jevKey,
                onValueChange = { jevKey = it; AiChatConfig.jevApiKey = cleanKey(it) },
                label = { Text("OpenRouter / JEV Key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ test("jev") }) { Text("测试") }
                Text("实际请求：${AiChatConfig.jevUrl}", style = MaterialTheme.typography.bodySmall)
            }

            if (testResult.isNotBlank()) {
                Text(testResult, style = MaterialTheme.typography.bodySmall)
            }

            // ---- 开关 ----
            Text("开关", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                var consent by remember { mutableStateOf(AiChatConfig.autoReplyConsent) }
                Switch(
                    checked = consent,
                    onCheckedChange = { checked ->
                        AiChatConfig.autoReplyConsent = checked
                        consent = checked
                    },
                )
                Text("允许全自动回复（全局总闸）", modifier = Modifier.padding(start = 8.dp))
            }
            if (talker.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var on by remember { mutableStateOf(AiChatStore.isAnalyzeOn(talker)) }
                    Switch(
                        checked = on,
                        onCheckedChange = { AiChatStore.setAnalyzeOn(talker, it); on = it },
                    )
                    Text("本聊天：自动分析 + 输入框上方建议", modifier = Modifier.padding(start = 8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var on by remember { mutableStateOf(AiChatStore.isAutoReplyOn(talker)) }
                    Switch(
                        checked = on,
                        onCheckedChange = { checked ->
                            if (checked && !AiChatConfig.autoReplyConsent) AiChatConfig.autoReplyConsent = true
                            AiChatStore.setAutoReplyOn(talker, checked)
                            on = AiChatStore.isAutoReplyOn(talker)
                        },
                    )
                    Text("本聊天：全自动回复", modifier = Modifier.padding(start = 8.dp))
                }
            } else {
                Text("打开一个聊天后，这里会出现该聊天的开关", style = MaterialTheme.typography.bodySmall)
            }
            // 底部余量：卡片底边与最后一个控件之间留距离
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
        }
    }
}
