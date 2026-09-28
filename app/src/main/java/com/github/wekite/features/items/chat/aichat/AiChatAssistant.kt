package com.github.wekite.features.items.chat.aichat

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.Button
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
 * 工作方式（v3.44 起）：
 * - 聊天右上角「AI」开关打开 ⇒ 该聊天的**新消息自动分析**，结果与建议回复显示在**输入框上方**面板
 * - 全自动回复：右上角长按「AI」→ 自动回复（双闸：设置页总闸 + 每聊天开关）
 * - 不再需要长按消息取菜单（v3.43 的「翻译意图 / 帮我回」已移除）
 *
 * 配置在 WeKite 设置 → 聊天 → AI 聊天助手（点条目进配置弹窗）。
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

    /** 借消息 View 创建回调拿到宿主 Activity 与聊天页，用于安装页面监听 + 维护面板。 */
    override fun onCreateView(param: com.github.wekite.utils.HookParam, view: View) {
        if (!isEnabled) return
        (view.context as? android.app.Activity)?.let { activity ->
            if (lifecycleInstalled.compareAndSet(false, true)) ChatPageLifecycle.install(activity)
        }
        val talker = WeCurrentConversationApi.value
        if (talker.isBlank()) return
        // 自动回复发出的消息 → 气泡旁「AI」小徽标（仅本机可见）
        val msgInfo = try {
            WeChatMessageViewApi.getMsgInfoFromParam(param)
        } catch (_: Exception) {
            return
        }
        if (msgInfo.isSend == 1 && AutoReplyMarker.isMarked(msgInfo.serverId)) {
            AiBadge.attach(view)
        }
    }

    // ==================== 设置弹窗 ====================

    private fun showSettingsDialog(activity: ComponentActivity) {
        showComposeDialog(activity, directlyDismissable = false) {
            AlertDialogContent(
                title = { Text("AI 聊天助手") },
                text = { SettingsContent() },
                confirmButton = { Button(onDismiss) { Text("关闭") } },
            )
        }
    }

    /** 供设置弹窗内的「测试连接」使用。 */
    suspend fun testConnection(kind: String): String = withContext(Dispatchers.IO) {
        try {
            when (kind) {
                "jev" -> {
                    check(AiChatConfig.jevConfigured) { "请先填 JEV 地址与 Key" }
                    val state = com.github.wekite.features.items.chat.aichat.protocol.ChoiceProtocol.contextBlock(
                        "你好", "对方", System.currentTimeMillis(), emptyList()
                    )
                    val body = AiChatHttp.jevExchange(
                        com.github.wekite.features.items.chat.aichat.protocol.IntentQuestions
                            .payload(state, AiChatConfig.jevModel)
                    )
                    val r = com.github.wekite.features.items.chat.aichat.protocol.IntentQuestions.parse(body)
                    "✅ JEV 可用：${com.github.wekite.features.items.chat.aichat.protocol.IntentQuestions.formatOutcome(r)}"
                }
                else -> {
                    check(AiChatConfig.llmConfigured) { "请先填 LLM 地址、Key 与模型" }
                    val body = AiChatHttp.llmExchange(
                        listOf("system" to "你是连通性测试助手。", "user" to "只回复两个字：可用"),
                        temperature = 0.0,
                    )
                    val text = org.json.JSONObject(body).optJSONArray("choices")
                        ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                    "✅ LLM 可用：${text.take(40)}"
                }
            }
        } catch (e: Exception) {
            WeLogger.e(TAG, "test connection failed ($kind)", e)
            "❌ 失败：${e.message?.take(200)}"
        }
    }

    private fun toast(activity: android.app.Activity, text: String) =
        android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_SHORT).show()

    @Composable
    private fun SettingsContent() {
        Column {
            val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
            var jevKey by remember { mutableStateOf(AiChatConfig.jevApiKey) }
            var llmEndpoint by remember { mutableStateOf(AiChatConfig.llmEndpoint) }
            var llmKey by remember { mutableStateOf(AiChatConfig.llmApiKey) }
            var llmModel by remember { mutableStateOf(AiChatConfig.llmModel) }
            var testResult by remember { mutableStateOf("") }
            val talker = WeCurrentConversationApi.value
            // 粘贴的 Key 常带换行（Authorization 报 0x0a）⇒ 一律清洗空白
            fun cleanKey(v: String) = v.filterNot { it.isWhitespace() }
            fun test(kind: String) {
                testResult = "测试中…（用地址：${if (kind == "jev") AiChatConfig.jevUrl else AiChatConfig.llmUrl}）"
                scope.launch { testResult = testConnection(kind) }
            }

            OutlinedTextField(
                value = llmEndpoint,
                onValueChange = { llmEndpoint = it; AiChatConfig.llmEndpoint = it.trim() },
                label = { Text("LLM 接口地址（可只填 base，如 https://openrouter.ai/api/v1）") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
            )
            OutlinedTextField(
                value = llmKey,
                onValueChange = { llmKey = it; AiChatConfig.llmApiKey = cleanKey(it) },
                label = { Text("LLM API Key") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
            )
            OutlinedTextField(
                value = llmModel,
                onValueChange = { llmModel = it; AiChatConfig.llmModel = it.trim() },
                label = { Text("LLM 模型 ID（如 deepseek-v4-flash）") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
            )
            Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ test("llm") }) { Text("测试 LLM") }
                androidx.compose.material3.Text(
                    "  实际请求：${AiChatConfig.llmUrl}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            OutlinedTextField(
                value = jevKey,
                onValueChange = { jevKey = it; AiChatConfig.jevApiKey = cleanKey(it) },
                label = { Text("JEV / OpenRouter Key（情绪概率线路，可留空）") },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                singleLine = true,
            )
            Row(modifier = Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton({ test("jev") }) { Text("测试 JEV") }
                androidx.compose.material3.Text(
                    "  实际请求：${AiChatConfig.jevUrl}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (testResult.isNotBlank()) {
                androidx.compose.material3.Text(
                    testResult,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            androidx.compose.material3.Text(
                "自动回复总开关（还需在聊天里单独开启）",
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                var consent by remember { mutableStateOf(AiChatConfig.autoReplyConsent) }
                Switch(
                    checked = consent,
                    onCheckedChange = { checked ->
                        if (checked && !AiChatConfig.autoReplyConsent) {
                            AiChatConfig.autoReplyConsent = true
                            consent = true
                            if (AiChatConfig.autoReplyConsent) { /* engine 常驻监听，无需重启 */ }
                        } else if (!checked) {
                            AiChatConfig.autoReplyConsent = false
                            consent = false
                        }
                    },
                )
                androidx.compose.material3.Text(
                    "允许全自动回复（全局）",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                var useJev by remember { mutableStateOf(AiChatConfig.useJev) }
                Switch(
                    checked = useJev,
                    onCheckedChange = { AiChatConfig.useJev = it; useJev = it },
                )
                androidx.compose.material3.Text("启用 JEV 情绪概率", modifier = Modifier.padding(start = 8.dp))
            }

            if (talker.isNotBlank()) {
                androidx.compose.material3.Text("当前聊天：$talker", modifier = Modifier.padding(top = 12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var on by remember { mutableStateOf(AiChatStore.isAnalyzeOn(talker)) }
                    Switch(
                        checked = on,
                        onCheckedChange = { AiChatStore.setAnalyzeOn(talker, it); on = it },
                    )
                    androidx.compose.material3.Text("自动分析并在输入框上方给建议", modifier = Modifier.padding(start = 8.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    var on by remember { mutableStateOf(AiChatStore.isAutoReplyOn(talker)) }
                    Switch(
                        checked = on,
                        onCheckedChange = { checked ->
                            if (checked && !AiChatConfig.autoReplyConsent) {
                                AiChatConfig.autoReplyConsent = true
                            }
                            AiChatStore.setAutoReplyOn(talker, checked)
                            on = AiChatStore.isAutoReplyOn(talker)
                        },
                    )
                    androidx.compose.material3.Text(
                        "全自动回复本聊天（${AiChatConfig.autoReplyDelaySec}s 内可撤回）",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            } else {
                androidx.compose.material3.Text(
                    "打开一个聊天后，这里会出现该聊天的开关",
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
