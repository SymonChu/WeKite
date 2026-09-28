package com.github.wekite.features.items.chat.aichat

import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.github.wekite.utils.WeLogger
import com.github.wekite.utils.android.isDarkMode
import java.util.Collections
import java.util.LinkedHashSet
import java.util.WeakHashMap

/**
 * 气泡下的分析卡（**沿用上游 yanwai 的做法**：卡片追加在消息气泡下方，用户 2026-09-28 定案）。
 *
 * 为什么回到这条路：悬浮在输入框上的面板/弹窗都会被 WeKite 的**悬浮输入框**盖住或需要对齐，
 * 而卡片长在消息列表里 ⇒ **结构上就不会被遮挡**（这正是上游的选择）。
 *
 * 实现要点（都踩过坑）：
 * - 只往「竖直 LinearLayout + WRAP_CONTENT」的气泡父容器里 **append**，不动微信原有子 View；
 * - 绑定关系存私有 WeakHashMap，**绝不用 `view.tag`**（微信自己往 tag 存数据）；
 * - **自动日/夜间配色**：按 `Context.isDarkMode` 选色（日间浅底深字、夜间深底浅字），
 *   微信切深色模式会重建聊天页 ⇒ 卡片随之重建，无需自己监听；
 * - 毛玻璃简化为「半透明圆角 + 细描边」（上游的共享 backdrop 每帧截屏，列表滚动开销大）。
 */
object BubbleCard {
    private const val TAG = "AiBubbleCard"

    /** 锚点（气泡行内 View）→ 卡片 */
    private val attached = Collections.synchronizedMap(WeakHashMap<View, View>())
    /** 锚点 → 它代表的消息（刷新时按消息取状态） */
    private val anchorMsg = Collections.synchronizedMap(WeakHashMap<View, Triple<String, Long, View>>())
    private val anchorKey = Collections.synchronizedMap(WeakHashMap<View, String>())
    /** 「bind 时 parent 还没就绪」的去重日志（每 key 一次） */
    private val quietSkip = Collections.synchronizedSet(LinkedHashSet<String>())

    /**
     * 消息行索引："聊天#消息" → 行 View。
     * ⚠️ 必要性：消息行往往在**分析开始之前**就绑定完了，等分析出结果时那一行不会再 bind
     * ⇒ 必须记下行的引用，状态出来后再把卡片挂上去（否则「分析结果永远不出现」）。
     */
    private val rows = Collections.synchronizedMap(
        object : LinkedHashMap<String, java.lang.ref.WeakReference<View>>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, java.lang.ref.WeakReference<View>>?) = size > 300
        }
    )

    /** 每次消息行绑定都登记（由 AiChatAssistant.onCreateView 调用，开销极小）。 */
    fun onRowBound(view: View, talker: String, msgId: Long) {
        if (talker.isBlank() || msgId <= 0) return
        val key = "$talker#$msgId"
        rows[key] = java.lang.ref.WeakReference(view)

        // 复用的行换了内容：摘掉上一条消息的卡（否则卡片跟错消息）
        anchorKey[view]?.let { old ->
            if (old != key) {
                attached.remove(view)?.let { (it.parent as? ViewGroup)?.removeView(it) }
                anchorMsg.remove(view)
                quietSkip.remove(old)
            }
        }
        anchorKey[view] = key

        // 立即试挂（回收复用的行此时已 attach，能直接成功）
        if (show(view, talker, msgId)) {
            pendingAttach.remove(view)
            return
        }
        // bind 时机 RecyclerView 还没 attach（新消息/预取绑定都如此，实测日志
        // 「anchor has no parent」30+ 条）⇒ 挂一次性 attach 监听，真挂上屏幕后再挂卡。
        // 监听按 key 防复用错挂：attach 时若该行已重绑成别的消息（anchorKey 变了）就放弃。
        pendingAttach.remove(view)?.let { view.removeOnAttachStateChangeListener(it) }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                pendingAttach.remove(v)
                if (anchorKey[v] != key) return   // 已重绑成别的消息 ⇒ 新的监听会接管
                if (show(v, talker, msgId)) quietSkip.remove(key)
            }
            override fun onViewDetachedFromWindow(v: View) {}
        }
        pendingAttach[view] = listener
        view.addOnAttachStateChangeListener(listener)
        if (quietSkip.add(key)) {
            WeLogger.i(TAG, "row not attached yet at bind, waiting for attach msgId=$msgId")
        }
    }

    /** bind 时尚未 attach 的行 → 一次性 attach 监听 */
    private val pendingAttach = Collections.synchronizedMap(WeakHashMap<View, View.OnAttachStateChangeListener>())

    /**
     * 在该消息气泡下挂/更新分析卡。
     * @param anchor 消息行内的锚点 View（来自 onCreateView）
     * @return 是否成功挂上
     */
    fun show(anchor: View, talker: String, msgId: Long): Boolean {
        val state = ChatAiEngine.stateFor(talker, msgId) ?: return false
        val parent = anchor.parent as? ViewGroup ?: return false   // 未 attach：由 attach 监听接管
        // 只挂进「竖直 LinearLayout + WRAP_CONTENT」的容器（上游同款判据，不满足就不强插）
        if (parent !is LinearLayout || parent.orientation != LinearLayout.VERTICAL) return false
        if (parent.layoutParams?.height != ViewGroup.LayoutParams.WRAP_CONTENT) return false

        val existing = attached[anchor]
        if (existing != null && existing.parent === parent) {
            fill(existing as LinearLayout, anchor, talker, msgId, state)
            return true
        }
        attached[anchor]?.let { (it.parent as? ViewGroup)?.removeView(it) }

        val card = LinearLayout(anchor.context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(anchor, 4) }
        }
        parent.addView(card)
        attached[anchor] = card
        anchorMsg[anchor] = Triple(talker, msgId, card)
        anchorKey[anchor] = "$talker#$msgId"
        fill(card, anchor, talker, msgId, state)
        WeLogger.i(TAG, "card attached msgId=$msgId parent=${parent.javaClass.simpleName}")
        return true
    }

    /** 状态变化后刷新：① 已挂卡片更新内容；② 有状态但还没挂的（行先绑完、分析后到）补挂。 */
    fun refresh(talker: String) = android.os.Handler(android.os.Looper.getMainLooper()).post { refreshNow(talker) }

    private fun refreshNow(talker: String) {
        // ① 更新已挂的
        val snapshot = synchronized(anchorMsg) { anchorMsg.entries.toList() }
        for ((anchor, meta) in snapshot) {
            val (t, msgId, card) = meta
            if (t != talker) continue
            val state = ChatAiEngine.stateFor(talker, msgId)
            if (state == null) {
                (card.parent as? ViewGroup)?.removeView(card)
                attached.remove(anchor)
                anchorMsg.remove(anchor)
                anchorKey.remove(anchor)
                continue
            }
            fill(card as LinearLayout, anchor, talker, msgId, state)
        }
        // ② 补挂：分析结果晚于消息行绑定
        for ((msgId, state) in ChatAiEngine.statesForTalker(talker)) {
            val key = "$talker#$msgId"
            val anchor = rows[key]?.get() ?: continue
            if (anchor.parent == null) continue
            if (attached[anchor] != null && anchorKey[anchor] == key) {
                (attached[anchor] as? LinearLayout)?.let { fill(it, anchor, talker, msgId, state) }
                continue
            }
            show(anchor, talker, msgId)
        }
    }

    /** 摘掉某聊天已挂的全部卡片（关开关 / 清状态时用）。 */
    fun clear(talker: String) {
        val snapshot = synchronized(anchorMsg) { anchorMsg.entries.toList() }
        for ((anchor, meta) in snapshot) {
            if (meta.first != talker) continue
            (meta.third.parent as? ViewGroup)?.removeView(meta.third)
            attached.remove(anchor)
            anchorMsg.remove(anchor)
            anchorKey.remove(anchor)
        }
    }

    private fun dp(v: View, value: Int) = (value * v.resources.displayMetrics.density).toInt()

    private fun fill(
        card: LinearLayout,
        anchor: View,
        talker: String,
        msgId: Long,
        state: ChatAiEngine.State,
    ) {
        card.removeAllViews()
        val dark = anchor.context.isDarkMode
        val bgColor = if (dark) 0xD02B2D34.toInt() else 0xE6F2F4F8.toInt()
        val strokeColor = if (dark) 0x32E3E8F0 else 0x33707A8C
        val titleColor = if (dark) 0xFF9ECBFF.toInt() else 0xFF1F6FEB.toInt()
        val bodyColor = if (dark) 0xFFE6E9F0.toInt() else 0xFF2B2F36.toInt()
        val subColor = if (dark) 0xFF9AA3B2.toInt() else 0xFF6B7280.toInt()

        card.background = GradientDrawable().apply {
            cornerRadius = dp(anchor, 10).toFloat()
            setColor(bgColor)
            setStroke(dp(anchor, 1), strokeColor)
        }
        card.setPadding(dp(anchor, 10), dp(anchor, 6), dp(anchor, 10), dp(anchor, 6))

        fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(anchor.context).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            gravity = Gravity.START
            setLineSpacing(dp(anchor, 1).toFloat(), 1f)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        when (state) {
            is ChatAiEngine.State.Working -> {
                card.addView(text("AI · 分析中…", 11f, titleColor, true))
            }
            is ChatAiEngine.State.Failed -> {
                card.addView(text("AI · ${state.message}", 11f, titleColor, true))
                card.addView(text("点此重试", 11f, subColor).apply {
                    setPadding(0, dp(anchor, 4), 0, 0)
                    setOnClickListener { ChatAiEngine.retry(talker) }
                })
            }
            is ChatAiEngine.State.Done -> {
                val r = state.result
                card.addView(text("AI · ${r.emotionLine}", 11f, titleColor, true))
                if (r.reading.isNotBlank()) {
                    card.addView(text("解读（推测）：${r.reading}", 12f, bodyColor).apply {
                        setPadding(0, dp(anchor, 4), 0, 0)
                    })
                }
                r.replies.forEachIndexed { i, reply ->
                    val row = LinearLayout(anchor.context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(0, dp(anchor, 4), 0, 0)
                    }
                    row.addView(
                        text("${i + 1}. $reply", 12f, bodyColor).apply {
                            setOnClickListener {
                                val ok = InputBar.fill(reply)
                                android.widget.Toast.makeText(
                                    anchor.context,
                                    if (ok) "已填入输入框" else "填入失败（可长按复制）",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                            setOnLongClickListener {
                                val cm = anchor.context
                                    .getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("reply", reply))
                                android.widget.Toast.makeText(anchor.context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                                true
                            }
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    row.addView(text("发", 12f, titleColor, true).apply {
                        setPadding(dp(anchor, 8), dp(anchor, 2), dp(anchor, 2), dp(anchor, 2))
                        setOnClickListener {
                            val ok = InputBar.send(talker, reply)
                            android.widget.Toast.makeText(
                                anchor.context, if (ok) "已发送" else "发送失败",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    })
                    card.addView(row)
                }
                if (r.note.isNotBlank()) {
                    card.addView(text(r.note, 10f, subColor).apply { setPadding(0, dp(anchor, 3), 0, 0) })
                }
            }
        }
    }
}
