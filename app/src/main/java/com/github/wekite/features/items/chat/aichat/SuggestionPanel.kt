package com.github.wekite.features.items.chat.aichat

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.github.wekite.features.api.ui.WeCurrentConversationApi
import com.github.wekite.utils.WeLogger
import java.lang.ref.WeakReference

/**
 * 输入框上方「AI 助手」面板。
 *
 * 内容：情绪摘要 / 意图解读 / 建议回复 ——
 *   点建议正文 = 填入输入框（自己改后发）；长按 = 复制；点「发」= 直接发送。
 *
 * 挂载：ChatFooter 的父容器里、紧邻输入框上方；父容器类型不支持/输入框还没就绪时会**重试**
 * （进入聊天页后 ChatFooter 与聊天上下文不是同时就绪的，一次失败就放弃会导致「面板一直不出现」）。
 */
object SuggestionPanel {
    private const val TAG = "AiPanel"
    private const val MAX_RETRY = 12
    private const val RETRY_MS = 400L

    private val main = Handler(Looper.getMainLooper())
    private var panelRef: WeakReference<View>? = null
    private var attachedActivity: WeakReference<Activity>? = null
    private var boundTalker: String? = null
    private var retries = 0

    val talker: String? get() = boundTalker

    /** 任意线程：把面板刷成 [targetTalker] 的最新状态。 */
    fun refresh(targetTalker: String) {
        main.post {
            if (boundTalker != null && boundTalker != targetTalker) return@post
            // 没有绑过 Activity 时，从输入框上下文里现取（消息到达往往早于页面 resume 回调）
            val activity = attachedActivity?.get() ?: activityFromFooter() ?: run {
                WeLogger.i(TAG, "refresh skip: no activity (talker=$targetTalker)")
                return@post
            }
            if (boundTalker == null) boundTalker = targetTalker
            attachedActivity = WeakReference(activity)
            retries = 0
            render(activity, targetTalker)
        }
    }

    private fun activityFromFooter(): Activity? {
        var ctx: android.content.Context? = WeCurrentConversationApi.chatFooter?.context
        var guard = 0
        while (ctx != null && guard++ < 8) {
            if (ctx is Activity) return ctx
            ctx = (ctx as? android.content.ContextWrapper)?.baseContext
        }
        return null
    }

    /** 进入聊天页时绑定（[talker] 为空则摘除）。 */
    fun attach(activity: Activity, talker: String?) {
        main.post {
            if (talker.isNullOrBlank()) { detach(); return@post }
            if (boundTalker != talker) { removePanel(); retries = 0 }
            boundTalker = talker
            attachedActivity = WeakReference(activity)
            render(activity, talker)
        }
    }

    fun detach() {
        main.post {
            removePanel()
            boundTalker = null
            attachedActivity = null
            retries = 0
        }
    }

    // ==================== 渲染 ====================

    private fun render(activity: Activity, targetTalker: String) {
        val state = ChatAiEngine.stateOf(targetTalker) ?: run { removePanel(); return }
        if (activity.isFinishing || activity.isDestroyed) return
        val footer = findFooter(activity)
        if (footer == null) {
            if (retries++ < MAX_RETRY) {
                WeLogger.i(TAG, "chat footer not ready, retry $retries/$MAX_RETRY")
                main.postDelayed({ render(activity, targetTalker) }, RETRY_MS)
            } else {
                WeLogger.w(TAG, "chat footer never appeared; panel stays off")
            }
            return
        }
        val panel = ensurePanel(activity, footer) ?: return
        fill(panel as LinearLayout, activity, targetTalker, state)
    }

    /** ChatFooter：优先用 WeKite 的引用，取不到就整树按类名找（不依赖混淆名）。 */
    private fun findFooter(activity: Activity): View? {
        WeCurrentConversationApi.chatFooter?.let { return it }
        val decor = activity.window?.decorView ?: return null
        return descendants(decor).firstOrNull { it.javaClass.name.endsWith(".ChatFooter") }
    }

    private fun ensurePanel(activity: Activity, footer: View): View? {
        panelRef?.get()?.let { if (it.parent != null) return it }
        val parent = footer.parent as? ViewGroup ?: run {
            WeLogger.w(TAG, "chat footer has no parent (${footer.javaClass.simpleName})")
            return null
        }
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(0xF21F2128.toInt())
                setStroke(dp(1), 0x3D8AB4F8)
            }
        }
        val placed = when (parent) {
            is LinearLayout -> {
                val idx = parent.indexOfChild(footer).coerceAtLeast(0)
                parent.addView(
                    root, idx,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginStart = dp(8); marginEnd = dp(8); bottomMargin = dp(6) }
                )
                true
            }
            is RelativeLayout -> {
                if (footer.id == View.NO_ID) footer.id = View.generateViewId()
                parent.addView(
                    root,
                    RelativeLayout.LayoutParams(
                        RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        addRule(RelativeLayout.ABOVE, footer.id)
                        marginStart = dp(8); marginEnd = dp(8); bottomMargin = dp(6)
                    }
                )
                true
            }
            is FrameLayout -> {
                // 叠在输入框正上方：以输入框实测高度做底部留白
                val gap = if (footer.height > 0) footer.height + dp(6) else dp(60)
                parent.addView(
                    root,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                    ).apply { bottomMargin = gap; marginStart = dp(8); marginEnd = dp(8) }
                )
                WeLogger.i(TAG, "overlay mode (FrameLayout parent), gap=$gap")
                true
            }
            else -> {
                WeLogger.w(TAG, "unsupported chat footer parent: ${parent.javaClass.name}")
                false
            }
        }
        if (!placed) return null
        panelRef = WeakReference(root)
        WeLogger.i(TAG, "panel attached (parent=${parent.javaClass.simpleName})")
        return root
    }

    private fun removePanel() {
        panelRef?.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
        panelRef = null
    }

    private fun fill(root: LinearLayout, activity: Activity, targetTalker: String, state: ChatAiEngine.State) {
        root.removeAllViews()
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        fun textView(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = when (state) {
            is ChatAiEngine.State.Working -> "AI · 分析中…"
            is ChatAiEngine.State.Failed -> "AI · ${state.message}"
            is ChatAiEngine.State.Done ->
                "AI · ${state.result.emotionLine}" + if (state.result.autoSent) " · 已自动回复" else ""
        }
        header.addView(textView(title, 12f, 0xFF9ECBFF.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(textView("收起", 12f, 0xFF8A93A5.toInt()).apply {
            setPadding(dp(8), dp(2), 0, dp(2))
            setOnClickListener {
                boundTalker?.let { AiChatStore.setAnalyzeOn(it, false) }
                removePanel()
            }
        })
        root.addView(header)

        when (state) {
            is ChatAiEngine.State.Working -> Unit
            is ChatAiEngine.State.Failed -> {
                root.addView(textView("点此重试", 12f, 0xFFFFB4AB.toInt()).apply {
                    setPadding(0, dp(6), 0, 0)
                    setOnClickListener { ChatAiEngine.retry(targetTalker) }
                })
            }
            is ChatAiEngine.State.Done -> {
                val r = state.result
                if (r.reading.isNotBlank()) {
                    root.addView(textView("解读（推测）：${r.reading}", 12f, 0xFFD6DAE3.toInt()).apply {
                        setPadding(0, dp(6), 0, 0)
                    })
                }
                r.replies.forEachIndexed { i, reply ->
                    val row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    row.addView(
                        textView("${i + 1}. $reply", 13f, 0xFFF1F3F8.toInt()).apply {
                            setPadding(0, dp(7), dp(6), dp(7))
                            setOnClickListener {
                                val ok = InputBar.fill(reply)
                                android.widget.Toast.makeText(
                                    activity, if (ok) "已填入输入框" else "填入失败（可长按复制）",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            setOnLongClickListener {
                                val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                    as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("reply", reply))
                                android.widget.Toast.makeText(activity, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                                true
                            }
                        },
                        LinearLayout.LayoutParams(0, -2, 1f)
                    )
                    row.addView(textView("发", 12f, 0xFF9ECBFF.toInt(), true).apply {
                        setPadding(dp(10), dp(6), dp(4), dp(6))
                        setOnClickListener {
                            val ok = InputBar.send(targetTalker, reply)
                            android.widget.Toast.makeText(
                                activity, if (ok) "已发送" else "发送失败", android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    })
                    root.addView(row)
                }
                if (r.note.isNotBlank()) {
                    root.addView(textView(r.note, 11f, 0xFF8A93A5.toInt()).apply { setPadding(0, dp(4), 0, 0) })
                }
                root.addView(textView("点建议=填入输入框 · 长按=复制 · 「发」=直接发送", 10f, 0xFF7E8798.toInt()).apply {
                    setPadding(0, dp(6), 0, 0)
                })
            }
        }
    }

    private fun descendants(root: View): List<View> = buildList {
        fun walk(view: View, depth: Int) {
            if (depth > 30 || size >= 2000) return
            add(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
        }
        walk(root, 0)
    }
}
