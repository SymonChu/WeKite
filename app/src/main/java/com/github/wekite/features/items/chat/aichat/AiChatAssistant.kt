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
        ChatUi.installListener()     // 会话变化事件（替代轮询，见 ChatUi 注释）
        AiChatPrefsEntry.register()  // 群详情/聊天详情页的开关条目（v3.56 加回）
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        ChatAiEngine.stop()
        ChatHeaderStatus.remove()
        AiChatPrefsEntry.unregister()
        ChatUi.uninstallListener()
        ChatUi.unbind()
    }

    override fun onClick(context: ComponentActivity) = showSettingsDialog(context)

    fun openSettingsDialog(activity: android.app.Activity, talker: String? = null) {
        // 微信聊天页的 Activity 不是 ComponentActivity；强转失败会静默不弹（2026-09-28 用户实测点不动）
        showSettingsDialog(activity, talker)
    }

    /** 「本聊天预设（人设/风格）」弹窗：手写人设 + 自动风格画像。 */
    fun openPersonaDialog(activity: android.app.Activity, talker: String) {
        showComposeDialog(activity, directlyDismissable = false) {
            val dm = activity.resources.displayMetrics
            window.setLayout((dm.widthPixels * 0.92f).toInt(), (dm.heightPixels * 0.7f).toInt())
            AlertDialogContent(
                title = { Text("本聊天预设") },
                text = { PersonaContent(talker) },
                confirmButton = { Button(onDismiss) { Text("关闭") } },
            )
        }
    }

    override fun onCreateView(param: com.github.wekite.utils.HookParam, view: View) {
        if (!isEnabled) return
        val activity = view.context as? android.app.Activity
        if (activity != null && lifecycleInstalled.compareAndSet(false, true)) {
            ChatPageLifecycle.install(activity)
        }
        // 标识改由「消息表里的系统提示行」承担（防撤回同款机制），此处只负责挂面板
        // 对方消息：登记行 View + 若已有分析状态就直接把卡片挂上（上游式「气泡下分析卡」）
        val talker = ChatUi.talker ?: WeCurrentConversationApi.value.takeIf { it.isNotBlank() } ?: return
        val msgInfo2 = try {
            WeChatMessageViewApi.getMsgInfoFromParam(param)
        } catch (_: Exception) {
            return
        }
        if (msgInfo2.isSend != 0) return
        BubbleCard.onRowBound(view, talker, msgInfo2.id)
        BubbleCard.show(view, talker, msgInfo2.id)
    }

    // ==================== 设置弹窗 ====================

    private fun showSettingsDialog(activity: android.app.Activity, explicitTalker: String? = null) {
        // 从聊天页进入 ⇒ 用该聊天；从模块设置页进入 ⇒ 用最近跟踪到的会话
        val targetTalker = explicitTalker?.takeIf { it.isNotBlank() }
            ?: ChatUi.talker
            ?: WeCurrentConversationApi.value
        showComposeDialog(activity, directlyDismissable = false) {
            // 弹窗限高 + 底部留白：条目多时下方不再被屏幕裁掉（用户反馈过）
            val dm = activity.resources.displayMetrics
            val h = (dm.heightPixels * 0.66f).toInt()
            window.setLayout((dm.widthPixels * 0.92f).toInt(), h)
            AlertDialogContent(
                title = { Text("AI 聊天助手") },
                text = { SettingsContent(targetTalker) },
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

    /** 「标签  −  值单位  ＋」一行式步进器。 */
    @Composable
    private fun StepperRow(
        label: String,
        value: Int,
        min: Int,
        max: Int,
        unit: String,
        step: Int = 1,
        onCommit: (Int) -> Unit,
    ) {
        var v by remember { mutableStateOf(value) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            TextButton({
                if (v - step >= min) { v = v - step; onCommit(v) }
            }) { Text("−") }
            Text(
                "$v $unit",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            TextButton({
                if (v + step <= max) { v = v + step; onCommit(v) }
            }) { Text("＋") }
        }
    }

    @Composable
    private fun PersonaContent(talker: String) {
        var preset by remember { mutableStateOf(PersonaStore.preset(talker)) }
        var profile by remember { mutableStateOf(PersonaStore.profile(talker)) }
        var autoRegen by remember { mutableStateOf(PersonaStore.profileAutoRegen(talker)) }
        var status by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }

        DefaultColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            scrollable = true,
        ) {
            Text("手写人设（对 AI 优先级最高）", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = preset,
                onValueChange = { preset = it; PersonaStore.setPreset(talker, it) },
                label = { Text("如：我妈，说话随意但要有分寸 / 工作群，简洁正式") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )

            Text("风格画像（自动总结，AI 模仿用）", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            if (profile.isBlank()) {
                Text("还没有画像。点「生成」从你在该会话的发言里总结。", style = MaterialTheme.typography.bodySmall)
            } else {
                val age = PersonaStore.profileAgeDays(talker)
                Text(
                    (if (age > 7) "已过期（${age} 天前生成）：\n" else "${age} 天前生成：\n") + profile,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // 画像常不够准（自动总结）：可直接编辑修正，存的是同一份（injectBlock 注入的就是它）
            var editingProfile by remember { mutableStateOf(false) }
            if (profile.isNotBlank()) {
                TextButton({ editingProfile = !editingProfile }) {
                    Text(if (editingProfile) "收起编辑" else "编辑")
                }
            }
            if (editingProfile) {
                OutlinedTextField(
                    value = profile,
                    onValueChange = { profile = it; PersonaStore.setProfile(talker, it) },
                    label = { Text("风格画像（可手动修正，AI 按这里说的说话）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({
                    if (!busy) {
                        scope.launch {
                            busy = true
                            status = "生成中…（读取你在该会话的最近发言）"
                            val err = PersonaStore.regenerateBlocking(talker)
                            busy = false
                            status = err ?: "已更新"
                            if (err == null) profile = PersonaStore.profile(talker)
                        }
                    }
                }) { Text(if (busy) "生成中…" else "生成 / 重新生成") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = autoRegen,
                    onCheckedChange = { PersonaStore.setProfileAutoRegen(talker, it); autoRegen = it },
                )
                Text(
                    "过期后自动重新生成（默认关）",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
        }
    }

    @Composable
    private fun SettingsContent(talker: String) {
        // 粘贴的 Key 常带换行（Authorization 报 0x0a）⇒ 一律清洗空白
        fun cleanKey(v: String) = v.filterNot { it.isWhitespace() }

        var llmEndpoint by remember { mutableStateOf(AiChatConfig.llmEndpoint) }
        var llmKey by remember { mutableStateOf(AiChatConfig.llmApiKey) }
        var llmModel by remember { mutableStateOf(AiChatConfig.llmModel) }
        var jevKey by remember { mutableStateOf(AiChatConfig.jevApiKey) }
        var testResult by remember { mutableStateOf("") }
        var advanced by remember { mutableStateOf(false) }
        var quietStart by remember { mutableStateOf(AiChatConfig.quietHoursStart) }
        var quietEnd by remember { mutableStateOf(AiChatConfig.quietHoursEnd) }
        var keywords by remember { mutableStateOf(AiChatConfig.autoReplyKeywords) }

        fun test(kind: String) {
            testResult = "测试中…（${if (kind == "jev") AiChatConfig.jevUrl else AiChatConfig.llmUrl}）"
            scope.launch { testResult = testConnection(kind) }
        }

        @Composable
        fun SectionTitle(text: String) {
            Text(
                text,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        DefaultColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
            scrollable = true,
        ) {
            // ---- 本聊天状态（只读）：开关本体在长按「…」菜单 / 群详情页 / 聊天详情页（2026-09-28 去重）----
            if (talker.isNotBlank()) {
                val analyzeOn = AiChatStore.isAnalyzeOn(talker)
                val autoOn = AiChatStore.isAutoReplyOn(talker)
                val groupAll = ContextBuilder.isGroupTalker(talker) && AiChatStore.isGroupAllMessages(talker)
                val llmOk = AiChatConfig.llmConfigured
                Text(
                    buildString {
                        append("当前聊天：显示分析卡")
                        append(if (analyzeOn) " 开" else " 关")
                        append(" · 自动回复")
                        append(if (autoOn) " 开" else " 关")
                        if (ContextBuilder.isGroupTalker(talker)) {
                            append(if (groupAll) " · 所有消息" else " · 只回@我")
                        }
                        if (autoOn && !llmOk) append("（⚠ 对话模型未配置，开了也不会回复）")
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "本聊天开关：长按聊天页右上角「…」，或在群/联系人详情页",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // ---- 模型连接 ----
            SectionTitle("对话模型（必填）")
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
            SectionTitle("情绪概率（可选，OpenRouter Key 即可）")
            Row(verticalAlignment = Alignment.CenterVertically) {
                var useJev by remember { mutableStateOf(AiChatConfig.useJev) }
                Switch(
                    checked = useJev,
                    onCheckedChange = { AiChatConfig.useJev = it; useJev = it },
                )
                Text("启用情绪判断", modifier = Modifier.padding(start = 8.dp))
            }
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

            // ---- 自动回复（全局参数；总闸在这里，会话开关不重复出现）----
            SectionTitle("自动回复")
            Row(verticalAlignment = Alignment.CenterVertically) {
                var consent by remember { mutableStateOf(AiChatConfig.autoReplyConsent) }
                Switch(
                    checked = consent,
                    onCheckedChange = { checked ->
                        AiChatConfig.autoReplyConsent = checked
                        consent = checked
                    },
                )
                Text(
                    "允许全自动回复（全局总闸，控制所有聊天）",
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            StepperRow("一次发送条数", AiChatConfig.autoReplySends, 1, 3, "条") {
                AiChatConfig.autoReplySends = it
            }
            StepperRow("发送前等待", AiChatConfig.autoReplyDelaySec, 3, 60, "秒（可撤回窗口）") {
                AiChatConfig.autoReplyDelaySec = it
            }
            StepperRow("同一聊天冷却", AiChatConfig.autoReplyCooldownSec, 10, 600, "秒") {
                AiChatConfig.autoReplyCooldownSec = it
            }
            StepperRow("每日上限", AiChatConfig.autoReplyDailyLimit, 1, 200, "条/聊天") {
                AiChatConfig.autoReplyDailyLimit = it
            }
            // 免打扰时段：引擎一直在用（quiet hours 拦截），此前无 UI（隐性设置，2026-09-28 显式暴露）
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("免打扰时段", modifier = Modifier.weight(1f))
                OutlinedTextField(
                    value = quietStart,
                    onValueChange = { quietStart = it; AiChatConfig.quietHoursStart = it.trim() },
                    label = { Text("开始 如 23:00") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = quietEnd,
                    onValueChange = { quietEnd = it; AiChatConfig.quietHoursEnd = it.trim() },
                    label = { Text("结束 如 07:30") },
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    singleLine = true,
                )
            }
            // 关键词白名单：空 = 不过滤（隐性设置，同上）
            OutlinedTextField(
                value = keywords,
                onValueChange = { keywords = it; AiChatConfig.autoReplyKeywords = it },
                label = { Text("群聊触发关键词（仅「只回@我」模式生效，@我 或 含关键词 即回）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            // ---- 面板 ----
            SectionTitle("面板")
            StepperRow("建议条数上限", AiChatConfig.suggestionCount, 1, 6, "条") {
                AiChatConfig.suggestionCount = it
            }

            // ---- 高级（折叠）----
            TextButton({ advanced = !advanced }) {
                Text(if (advanced) "收起高级设置" else "高级设置")
            }
            if (advanced) {
                StepperRow("上下文条数", AiChatConfig.contextLimit, 5, 100, "条") {
                    AiChatConfig.contextLimit = it
                }
                StepperRow("上下文预算", AiChatConfig.contextBudget, 1000, 48000, "字", step = 1000) {
                    AiChatConfig.contextBudget = it
                }
            }

            // 底部余量：卡片底边与最后一个控件之间留距离
            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
        }
    }
}
