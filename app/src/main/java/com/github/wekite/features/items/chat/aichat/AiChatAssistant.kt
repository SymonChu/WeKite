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
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Chat_bubble
import com.composables.icons.materialsymbols.outlined.Translate
import com.github.wekite.features.api.ui.WeCurrentConversationApi
import com.github.wekite.features.api.core.WeMessageApi
import com.github.wekite.features.api.ui.WeChatMessageContextMenuApi
import com.github.wekite.features.api.ui.WeChatMessageViewApi
import com.github.wekite.features.core.Feature
import com.github.wekite.features.core.ClickableFeature
import com.github.wekite.features.items.chat.aichat.net.AiChatHttp
import com.github.wekite.features.items.chat.aichat.protocol.IntentQuestions
import com.github.wekite.features.items.chat.aichat.protocol.ChoiceProtocol
import com.github.wekite.features.items.chat.aichat.protocol.ReplyProtocol
import com.github.wekite.preferences.WePrefs
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
 * AI 聊天助手（吸收 yanwai 思路的独立实现）：
 * 一期 意图分析：长按消息「翻译意图」/ 开聊天自动分析，JEV 情绪概率 + LLM 补充解读
 * 二期 建议回复：长按「帮我回」生成 1-6 条建议 → 确认卡逐条发送（WeMessageApi.sendText）
 * 三期 自动回复：每聊天开关 + 规则（关键词/时段/冷却/限额）+ 延迟窗口可取消 + 审计
 *
 * 开关粒度：每个聊天/群聊独立（[AiChatStore]），设置页里是全局配置与总闸。
 */
@Feature(name = "AI 聊天助手", categories = ["聊天"], description = "在聊天里分析消息意图、生成回复建议与自动回复")
object AiChatAssistant : ClickableFeature(), WeChatMessageViewApi.ICreateViewListener,
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "AiChatAssistant"

    /** 当前绑定的 view key（bind 时刷新卡片用） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 每聊天分析开关被打开时，此聊天进入"自动分析"模式：onCreateView 里对每条对方消息 submit。
    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)
        WeChatMessageContextMenuApi.addProvider(this)
        if (AiChatConfig.autoReplyConsent) AutoReplyEngine.start()
    }

    /** 首次拿到宿主 Activity 时安装聊天页生命周期监听（右上角开关需要）。 */
    private val lifecycleInstalled = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun ensureChatPageLifecycle(activity: android.app.Activity) {
        if (lifecycleInstalled.compareAndSet(false, true)) {
            ChatPageLifecycle.install(activity)
        }
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        WeChatMessageContextMenuApi.removeProvider(this)
        AutoReplyEngine.stop()
        AnalysisEngine.clearCache()
    }

    // ==================== 消息 View 创建（卡片挂载点） ====================

    /** 行 View -> 绑定的消息 key（不用 view.tag：微信自己往 tag 里存业务数据） */
    private val boundKeys = java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, String>())

    override fun onCreateView(param: com.github.wekite.utils.HookParam, view: View) {
        if (!isEnabled) return
        (view.context as? android.app.Activity)?.let { ensureChatPageLifecycle(it) }
        val msgInfo = try {
            WeChatMessageViewApi.getMsgInfoFromParam(param)
        } catch (_: Exception) {
            return
        }
        val talker = msgInfo.talker
        if (talker.isBlank()) return
        val isGroup = ContextBuilder.isGroupTalker(talker)

        // ---- 自动回复消息徽标（仅本机可见）：自己发出、且在标记表里 ----
        if (msgInfo.isSend == 1) {
            if (AutoReplyMarker.isMarked(msgInfo.serverId)) {
                AiBadge.attach(view)
            } else {
                AiBadge.detach(view)
            }
            return
        }

        // ---- 分析卡（对方消息 + 该聊天开关打开） ----
        if (!AiChatStore.isAnalyzeOn(talker)) {
            boundKeys.remove(view)
            BubbleCard.detach(view)
            return
        }
        val key = "$talker#${msgInfo.id}"
        val targetText = ContextBuilder.targetText(msgInfo.asWeMessage(), isGroup) ?: return
        val speaker = if (isGroup) groupSpeaker(msgInfo) else "对方"

        // RecyclerView 复用：绑定 key 变了就重挂
        val bound = boundKeys[view]
        if (bound != key) {
            BubbleCard.detach(view)
            boundKeys[view] = key
        }
        BubbleCard.show(view, key) { cardTextFor(key) }
        AnalysisEngine.submit(key, talker, isGroup, targetText, speaker, msgInfo.createTime * 1000)
    }

    private fun cardTextFor(key: String): String = when (val s = AnalysisEngine.stateOf(key)) {
        null, is AnalysisEngine.State.Pending -> "AI 分析中…"
        is AnalysisEngine.State.Failed -> "分析失败：${s.message}\n点击重试"
        is AnalysisEngine.State.Done -> buildString {
            append(s.result.emotionLine)
            s.result.reading?.let { append("\n解读（推测）：$it") }
            if (s.result.suggestions.isNotEmpty()) {
                append("\n建议：")
                s.result.suggestions.forEachIndexed { i, t -> append("\n${i + 1}. $t") }
            }
            if (s.result.note.isNotBlank()) append("\n${s.result.note}")
        }
    }

    private fun groupSpeaker(msgInfo: com.github.wekite.features.api.core.models.MessageInfo): String {
        return try {
            val content = msgInfo.content
            val idx = content.indexOf(":\n")
            if (idx in 1..64) {
                val wxid = content.substring(0, idx)
                com.github.wekite.features.api.core.WeDatabaseApi
                    .getGroupMemberDisplayName(msgInfo.talker, wxid).ifBlank { wxid }
            } else "对方"
        } catch (_: Exception) { "对方" }
    }

    private fun com.github.wekite.features.api.core.models.MessageInfo.asWeMessage() =
        com.github.wekite.features.api.core.models.WeMessage(
            msgId = id, msgSvrId = serverId, talker = talker,
            content = content, typeCode = typeCode, createTime = createTime, isSend = isSend,
        )

    // ==================== 长按菜单（翻译意图 / 帮我回） ====================

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> = listOf(
        WeChatMessageContextMenuApi.MenuItem(
            id = 0x414901,
            text = "翻译意图",
            drawable = circleIcon(),
            imageVector = MaterialSymbols.Outlined.Translate,
            isSupported = { it.typeCode == 1 || it.typeCode == 34 },
            multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported,
            onClick = { view, ctx, msgInfo -> onTranslateIntent(view, ctx, msgInfo) },
        ),
        WeChatMessageContextMenuApi.MenuItem(
            id = 0x414902,
            text = "帮我回",
            drawable = circleIcon(),
            imageVector = MaterialSymbols.Outlined.Chat_bubble,
            isSupported = { true },
            multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported,
            onClick = { view, ctx, msgInfo -> onSuggestReply(view, ctx, msgInfo) },
        ),
    )

    private fun circleIcon(): android.graphics.drawable.Drawable =
        object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFF4C8DFF.toInt()
                style = android.graphics.Paint.Style.FILL
            }
            override fun draw(canvas: android.graphics.Canvas) {
                val r = bounds.width() / 4f
                canvas.drawCircle(bounds.exactCenterX(), bounds.exactCenterY(), r, paint)
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha }
            override fun setColorFilter(cf: android.graphics.ColorFilter?) { paint.colorFilter = cf }
            @Deprecated("Deprecated in Java")
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }

    /** 长按单条 → 立即分析（无需打开聊天开关）。 */
    private fun onTranslateIntent(
        view: View,
        ctx: WeChatMessageContextMenuApi.ChattingContext,
        msgInfo: com.github.wekite.features.api.core.models.MessageInfo,
    ) {
        val talker = msgInfo.talker
        if (talker.isBlank()) return
        val isGroup = ContextBuilder.isGroupTalker(talker)
        if (msgInfo.isSend == 1) return  // 只支持对方消息
        val text = ContextBuilder.targetText(msgInfo.asWeMessage(), isGroup)
        if (text.isNullOrBlank()) {
            android.widget.Toast.makeText(view.context, "这条消息暂不支持分析", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // 临时打开该聊天分析（保持"单条分析不改变聊天开关"语义 → 用临时 key 直接挂卡）
        val key = "$talker#manual${msgInfo.id}"
        val speaker = if (isGroup) groupSpeaker(msgInfo) else "对方"
        boundKeys[view] = key
        BubbleCard.show(view, key) { cardTextFor(key) }
        AnalysisEngine.submit(key, talker, isGroup, text, speaker, msgInfo.createTime * 1000)
        if (!AiChatStore.isAnalyzeOn(talker)) {
            // 单条模式：30 分钟后自动清掉结果，不污染缓存
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(
                { AnalysisEngine.clearCache() }, 30 * 60 * 1000L
            )
        }
    }

    /** 二期：帮我回 → 生成 → 确认卡 → 逐条发送。 */
    private fun onSuggestReply(
        view: View,
        ctx: WeChatMessageContextMenuApi.ChattingContext,
        msgInfo: com.github.wekite.features.api.core.models.MessageInfo,
    ) {
        val talker = msgInfo.talker
        if (talker.isBlank()) return
        if (!AiChatConfig.llmConfigured) {
            android.widget.Toast.makeText(view.context, "请先在 WeKite 设置 → AI 聊天助手 配置 LLM 接口", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val activity = ctx.activity as? ComponentActivity
        if (activity == null) {
            android.widget.Toast.makeText(view.context, "当前页面不支持此操作", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        if (!AiChatStore.isReplyOn(talker)) {
            showReplyConsentDialog(activity, talker) { generateReplyDialog(activity, talker) }
            return
        }
        generateReplyDialog(activity, talker)
    }

    private fun generateReplyDialog(activity: ComponentActivity, talker: String) {
        val activityForDialog = activity
        showComposeDialog(activityForDialog, directlyDismissable = false) {
            var replies by remember { mutableStateOf<List<String>?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            var selected by remember { mutableStateOf(setOf(0)) }

            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val isGroup = ContextBuilder.isGroupTalker(talker)
                    val built = ContextBuilder.build(talker, beforeTimeMs = System.currentTimeMillis(), isGroup = isGroup)
                    val state = ChoiceProtocol.contextBlock(
                        "（请根据以上前文自然接话）", "对方", System.currentTimeMillis(), built.messages
                    )
                    val knowledge = runCatching { ReplyKnowledge.load(isGroup) }.getOrNull()
                    val messages = ReplyProtocol.replyMessages(state, if (isGroup) "群聊成员" else "朋友", "", knowledge)
                    val body = AiChatHttp.llmExchange(messages, temperature = 0.8)
                    val parsed = ReplyProtocol.parseReplies(body)
                    replies = parsed
                    selected = setOf(0)
                } catch (e: Exception) {
                    WeLogger.e(TAG, "generate reply failed", e)
                    error = e.message ?: "生成失败"
                }
            }

            AlertDialogContent(
                title = { Text("AI 建议回复") },
                text = {
                    Column {
                        if (replies == null && error == null) Text("生成中…（读取该聊天前文并请求模型）")
                        error?.let { Text("失败：$it", color = MaterialTheme.colorScheme.error) }
                        replies?.forEachIndexed { i, r ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            ) {
                                Switch(
                                    checked = i in selected,
                                    onCheckedChange = { on ->
                                        selected = if (on) selected + i else selected - i
                                    },
                                )
                                Text("${i + 1}. $r", modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                        replies?.let {
                            Text(
                                "勾选的条目将按顺序逐条发送（间隔约 1-2 秒）。",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                },
                confirmButton = {
                    Button({
                        val list = replies ?: return@Button
                        val chosen = selected.filter { it < list.size }.map { list[it] }
                        if (chosen.isEmpty()) return@Button
                        onDismiss()
                        scope.launch(Dispatchers.IO) {
                            var sent = 0
                            for (r in chosen) {
                                if (sent > 0) kotlinx.coroutines.delay(1200 + (0..800).random().toLong())
                                val ok = WeMessageApi.sendText(talker, r)
                                WeLogger.i(TAG, "manual reply talker=$talker ok=$ok")
                                if (!ok) break
                                sent++
                            }
                        }
                    }) { Text("逐条发送") }
                },
                dismissButton = { TextButton(onDismiss) { Text("关闭") } },
            )
        }
    }

    private fun showReplyConsentDialog(activity: ComponentActivity, talker: String, onAgree: () -> Unit) {
        showComposeDialog(activity) {
            AlertDialogContent(
                title = { Text("开启建议回复") },
                text = {
                    Text("AI 将根据该聊天最近消息生成分条回复建议；生成后由你勾选并确认发送，不会自动发送。")
                },
                confirmButton = {
                    Button({
                        AiChatStore.setReplyOn(talker, true)
                        onDismiss()
                        onAgree()
                    }) { Text("同意并生成") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
            )
        }
    }

    // ==================== 设置页入口 ====================

    override fun onClick(context: ComponentActivity) = showSettingsDialog(context)

    fun openSettingsDialog(activity: android.app.Activity) {
        (activity as? ComponentActivity)?.let { showSettingsDialog(it) }
    }

    private fun showSettingsDialog(activity: ComponentActivity) {
        showComposeDialog(activity) {
            var talker by remember { mutableStateOf<String>(WeCurrentConversationApi.value) }
            AlertDialogContent(
                title = { Text("AI 聊天助手") },
                text = { SettingsContent() },
                confirmButton = { Button(onDismiss) { Text("关闭") } },
            )
        }
    }
}

@Composable
private fun SettingsContent() {
    Column {
        var jevKey by remember { mutableStateOf(AiChatConfig.jevApiKey) }
        var llmEndpoint by remember { mutableStateOf(AiChatConfig.llmEndpoint) }
        var llmKey by remember { mutableStateOf(AiChatConfig.llmApiKey) }
        var llmModel by remember { mutableStateOf(AiChatConfig.llmModel) }
        val talker = WeCurrentConversationApi.value
        // v1.73 教训：粘贴的 key 常带换行 → 保存时清洗全部空白（Authorization 报 0x0a）
        fun cleanKey(v: String) = v.filterNot { it.isWhitespace() }

        OutlinedTextField(
            value = jevKey, onValueChange = {
                jevKey = it; AiChatConfig.jevApiKey = cleanKey(it)
            },
            label = { Text("OpenRouter API Key（JEV 情绪线路）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = llmEndpoint, onValueChange = {
                llmEndpoint = it; AiChatConfig.llmEndpoint = it
            },
            label = { Text("LLM Chat Completions 地址") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            singleLine = true,
        )
        OutlinedTextField(
            value = llmKey, onValueChange = {
                llmKey = it; AiChatConfig.llmApiKey = cleanKey(it)
            },
            label = { Text("LLM API Key") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        OutlinedTextField(
            value = llmModel, onValueChange = {
                llmModel = it; AiChatConfig.llmModel = it
            },
            label = { Text("LLM 模型 ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Text("自动回复总开关（还需在每个聊天里单独开启）", modifier = Modifier.padding(top = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            var consent by remember { mutableStateOf(AiChatConfig.autoReplyConsent) }
            Switch(
                checked = consent,
                onCheckedChange = { checked ->
                    if (checked && !AiChatConfig.autoReplyConsent) {
                        AiChatConfig.autoReplyConsent = true
                        consent = true
                    } else if (!checked) {
                        AiChatConfig.autoReplyConsent = false
                        consent = false
                    }
                },
            )
            Text("允许全自动回复（全局）", modifier = Modifier.padding(start = 8.dp))
        }
        if (talker.isNotBlank()) {
            Text("当前聊天：$talker", modifier = Modifier.padding(top = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = AiChatStore.isAnalyzeOn(talker),
                    onCheckedChange = { AiChatStore.setAnalyzeOn(talker, it) },
                )
                Text("自动分析本聊天", modifier = Modifier.padding(start = 8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = AiChatStore.isReplyOn(talker),
                    onCheckedChange = { AiChatStore.setReplyOn(talker, it) },
                )
                Text("建议回复（确认后发送）", modifier = Modifier.padding(start = 8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                var autoOn by remember { mutableStateOf(AiChatStore.isAutoReplyOn(talker)) }
                Switch(
                    checked = autoOn,
                    onCheckedChange = { checked ->
                        if (checked && !AiChatConfig.autoReplyConsent) {
                            AiChatConfig.autoReplyConsent = true
                        }
                        AiChatStore.setAutoReplyOn(talker, checked)
                        autoOn = AiChatStore.isAutoReplyOn(talker)
                    },
                )
                Text("自动回复本聊天（${AiChatConfig.autoReplyDelaySec}s 内可撤回）", modifier = Modifier.padding(start = 8.dp))
            }
        } else {
            Text("打开一个聊天后，这里会出现该聊天的开关", modifier = Modifier.padding(top = 12.dp))
        }
    }
}


/**
 * 「AI」小徽标：挂在全自动回复发出的消息气泡旁（仅本机可见，视图层注入）。
 */
private object AiBadge {
    private val attached = java.util.Collections.synchronizedMap(java.util.WeakHashMap<View, View>())

    fun attach(anchor: View) {
        if (attached.containsKey(anchor)) return
        val parent = anchor.parent as? android.view.ViewGroup ?: return
        if (parent !is android.widget.RelativeLayout && parent !is android.widget.FrameLayout) return
        val density = anchor.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val badge = android.widget.TextView(anchor.context).apply {
            text = "AI"
            textSize = 9f
            includeFontPadding = false
            setTextColor(0xFF9ECBFF.toInt())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(6).toFloat()
                setColor(0x269ECBFF)
                setStroke(1, 0x449ECBFF)
            }
            setPadding(dp(5), dp(2), dp(5), dp(2))
        }
        val lp = when (parent) {
            is android.widget.RelativeLayout -> {
                android.widget.RelativeLayout.LayoutParams(android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT, android.widget.RelativeLayout.LayoutParams.WRAP_CONTENT)
                    .apply {
                        addRule(android.widget.RelativeLayout.ALIGN_PARENT_RIGHT)
                        addRule(android.widget.RelativeLayout.ALIGN_TOP, anchor.id.takeIf { it != View.NO_ID } ?: return)
                        topMargin = dp(-6)
                        marginEnd = dp(4)
                    }
            }
            else -> android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.END
            ).apply { topMargin = dp(-6); marginEnd = dp(4) }
        }
        try {
            parent.addView(badge, lp)
            attached[anchor] = badge
        } catch (_: Exception) { }
    }

    fun detach(anchor: View) {
        attached.remove(anchor)?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
    }
}
