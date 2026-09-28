package com.github.wekite.features.items.chat.aichat

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.github.wekite.features.api.ui.WeCurrentConversationApi
import com.github.wekite.utils.WeLogger
import java.lang.ref.WeakReference

/**
 * 输入框上方「AI 助手」面板（v3.44 起替代气泡下分析卡）。
 *
 * 位置：挂在 ChatFooter（输入框）的父容器里、紧邻输入框上方。
 * 内容：情绪摘要 / 意图解读 / 建议回复若干条 ——
 *   点建议正文 = 填入输入框（自己改后发）；点「发」= 直接发送。
 *
 * 纯原生 View（宿主窗口里不能依赖模块的 Compose/资源表，微信资源表里没有我们的 id）。
 */
object SuggestionPanel {
    private const val TAG = "AiPanel"

    private val main = Handler(Looper.getMainLooper())
    private var panelRef: WeakReference<View>? = null
    private var attachedActivity: WeakReference<Activity>? = null
    private var boundTalker: String? = null

    /** 当前面板绑定的聊天（用于判断该不该渲染） */
    val talker: String? get() = boundTalker

    /** 从任意线程调用：把面板内容刷成 [targetTalker] 的最新状态。 */
    fun refresh(targetTalker: String) {
        main.post {
            if (boundTalker != null && boundTalker != targetTalker) return@post
            val activity = attachedActivity?.get() ?: return@post
            if (activity.isFinishing) return@post
            render(activity, targetTalker)
        }
    }

    /** 进入聊天页时挂载（[talker] 为空则摘除）。 */
    fun attach(activity: Activity, talker: String?) {
        main.post {
            if (talker.isNullOrBlank()) { detach(); return@post }
            boundTalker = talker
            attachedActivity = WeakReference(activity)
            render(activity, talker)
        }
    }

    fun detach() {
        main.post {
            panelRef?.get()?.let { (it.parent as? ViewGroup)?.removeView(it) }
            panelRef = null
            boundTalker = null
            attachedActivity = null
        }
    }

    // ==================== 渲染 ====================

    private fun render(activity: Activity, targetTalker: String) {
        val state = ChatAiEngine.stateOf(targetTalker)
        // 没有内容也没在跑 → 不占位置
        if (state == null) { removePanel(); return }
        val footer = WeCurrentConversationApi.chatFooter ?: run {
            WeLogger.w(TAG, "no chat footer, panel stays off")
            return
        }
        val panel = ensurePanel(activity, footer) ?: return
        fill(panel as LinearLayout, activity, targetTalker, state)
    }

    /** 建面板并插到输入框上方（只做一次，之后复用）。 */
    private fun ensurePanel(activity: Activity, footer: View): View? {
        panelRef?.get()?.let { if (it.parent != null) return it }
        val parent = footer.parent as? ViewGroup ?: run {
            WeLogger.w(TAG, "chat footer has no parent")
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
        when (parent) {
            is LinearLayout -> {
                val idx = parent.indexOfChild(footer).coerceAtLeast(0)
                parent.addView(
                    root, idx,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginStart = dp(8); marginEnd = dp(8); bottomMargin = dp(6)
                    }
                )
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
            }
            else -> {
                WeLogger.w(TAG, "unsupported chat footer parent: ${parent.javaClass.name}")
                return null
            }
        }
        panelRef = WeakReference(root)
        WeLogger.i(TAG, "panel attached above chat footer (parent=${parent.javaClass.simpleName})")
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

        // 标题行：AI · 状态 + 关闭
        val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val title = when (state) {
            is ChatAiEngine.State.Working -> "AI · 分析中…"
            is ChatAiEngine.State.Failed -> "AI · ${state.message}"
            is ChatAiEngine.State.Done -> "AI · ${state.result.emotionLine}" + if (state.result.autoSent) " · 已自动回复" else ""
        }
        header.addView(textView(title, 12f, 0xFF9ECBFF.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(textView("收起", 12f, 0xFF8A93A5.toInt()).apply {
            setPadding(dp(8), dp(2), 0, dp(2))
            setOnClickListener {
                // 收起 = 关闭该聊天开关（保持语义一致：面板由开关驱动）
                boundTalker?.let { AiChatStore.setAnalyzeOn(it, false) }
                removePanel()
            }
        })
        root.addView(header)

        when (state) {
            is ChatAiEngine.State.Failed -> {
                root.addView(textView("点此重试", 12f, 0xFFFFB4AB.toInt()).apply {
                    setPadding(0, dp(6), 0, 0)
                    setOnClickListener { ChatAiEngine.retry(targetTalker) }
                })
            }
            is ChatAiEngine.State.Working -> Unit
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
                    val body = textView("${i + 1}. $reply", 13f, 0xFFF1F3F8.toInt()).apply {
                        setPadding(0, dp(7), dp(6), dp(7))
                        setOnClickListener {
                            if (InputBar.fill(reply)) {
                                android.widget.Toast.makeText(activity, "已填入输入框", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(activity, "填入失败，可长按复制", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                        setOnLongClickListener {
                            val cm = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("reply", reply))
                            android.widget.Toast.makeText(activity, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                            true
                        }
                    }
                    row.addView(body, LinearLayout.LayoutParams(0, -2, 1f))
                    row.addView(textView("发", 12f, 0xFF9ECBFF.toInt(), true).apply {
                        setPadding(dp(10), dp(6), dp(4), dp(6))
                        setOnClickListener {
                            if (InputBar.send(targetTalker, reply)) {
                                android.widget.Toast.makeText(activity, "已发送", android.widget.Toast.LENGTH_SHORT).show()
                            } else {
                                android.widget.Toast.makeText(activity, "发送失败", android.widget.Toast.LENGTH_SHORT).show()
                            }
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
}
