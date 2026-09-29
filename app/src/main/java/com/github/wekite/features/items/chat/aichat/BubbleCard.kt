package com.github.wekite.features.items.chat.aichat

import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.github.wekite.utils.WeLogger
import com.github.wekite.utils.android.isDarkMode
import java.util.Collections
import java.util.WeakHashMap

/**
 * 气泡下的分析卡（**沿用上游 yanwai 的做法**：卡片追加在消息气泡下方，用户 2026-09-28 定案）。
 *
 * v3.48 真机日志教训：`row not attached yet at bind` 在等 attach 之后**零条 `card attached`**
 * —— 根因是我只查了消息行的**直接父容器**，而微信气泡行的多级包裹里那个「竖直 LinearLayout +
 * WRAP_CONTENT」容器几乎从不是直接父级。上游的做法是：**从气泡锚点逐层向上爬**，
 * 爬行期间必须仍在消息行（root）之内，第一个满足形状的容器就是要插入的 target；
 * 行根若是 RelativeLayout 再按 RelativeLayout 规则兜底。
 */
object BubbleCard {
    private const val TAG = "AiBubbleCard"

    /** 消息行（onCreateView 的 view）→ 卡片记录 */
    private val cards = Collections.synchronizedMap(WeakHashMap<View, Card>())
    /** "聊天#消息" → 行 View（WeakReference，结果晚于绑定时补挂用） */
    private val rows = Collections.synchronizedMap(
        object : LinkedHashMap<String, java.lang.ref.WeakReference<View>>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, java.lang.ref.WeakReference<View>>?) = size > 300
        }
    )
    /** 行根 → 它当前绑定的 key（复用换内容时摘旧卡） */
    private val rowKey = Collections.synchronizedMap(WeakHashMap<View, String>())
    /** attach 监听（防复用错挂：attach 时 key 变了就放弃） */
    private val pendingAttach = Collections.synchronizedMap(WeakHashMap<View, View.OnAttachStateChangeListener>())
    /** 同一句日志按 key 去重 */
    private val quietSkip = Collections.synchronizedSet(LinkedHashSet<String>())

    private class Card(
        val key: String,
        val view: LinearLayout,
        val target: ViewGroup,
        val anchor: View,
    )

    /** 每次消息行绑定都登记（由 AiChatAssistant.onCreateView 调用，开销极小）。 */
    fun onRowBound(row: View, talker: String, msgId: Long) {
        if (talker.isBlank() || msgId <= 0) return
        val key = "$talker#$msgId"
        rows[key] = java.lang.ref.WeakReference(row)

        // 复用的行换了内容：摘掉上一条消息的卡
        rowKey[row]?.let { old ->
            if (old != key) {
                cards.remove(row)?.let { c -> (c.view.parent as? ViewGroup)?.removeView(c.view) }
                quietSkip.remove(old)
            }
        }
        rowKey[row] = key

        // 已有状态就立即试挂（复用的行通常已 attach）
        if (show(row, talker, msgId)) {
            pendingAttach.remove(row)?.let { row.removeOnAttachStateChangeListener(it) }
            pendingAttach.remove(row)
            return
        }
        // 新消息行 bind 时通常尚未 attach ⇒ 一次性监听，真上屏后再挂。
        pendingAttach.remove(row)?.let { row.removeOnAttachStateChangeListener(it) }
        val listener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                pendingAttach.remove(v)
                if (rowKey[v] != key) return  // 已重绑成别的消息 ⇒ 新监听接管
                if (show(v, talker, msgId)) quietSkip.remove(key)
            }

            override fun onViewDetachedFromWindow(v: View) {}
        }
        pendingAttach[row] = listener
        row.addOnAttachStateChangeListener(listener)
        if (quietSkip.add(key)) {
            WeLogger.i(TAG, "row not attached yet at bind, waiting for attach msgId=$msgId")
        }
    }

    /** 状态变化后刷新：主线程调度。 */
    fun refresh(talker: String) = android.os.Handler(android.os.Looper.getMainLooper()).post { refreshNow(talker) }

    private fun refreshNow(talker: String) {
        // ① 更新已挂的
        val snapshot = synchronized(cards) { cards.entries.toList() }
        for ((row, card) in snapshot) {
            if (!card.key.startsWith("$talker#")) continue
            val msgId = card.key.substringAfterLast('#').toLongOrNull() ?: continue
            val state = ChatAiEngine.stateFor(talker, msgId)
            if (state == null) { clearRow(row); continue }
            if (card.view.parent != null && row.isAttachedToWindow) {
                fill(card.view, row, state, talker)
            }
        }
        // ② 补挂：分析结果晚于消息行绑定
        for ((msgId, state) in ChatAiEngine.statesForTalker(talker)) {
            val key = "$talker#$msgId"
            val row = rows[key]?.get() ?: continue
            if (row.parent == null) continue
            val have = cards[row]
            if (have != null && have.key == key) {
                if (have.view.parent != null && row.isAttachedToWindow) fill(have.view, row, state, talker)
            } else {
                show(row, talker, msgId)
            }
        }
    }

    /** 挂卡：找到插入容器并 append；失败返回 false（不吞异常信息，记一次日志）。 */
    fun show(row: View, talker: String, msgId: Long): Boolean {
        val key = "$talker#$msgId"
        val state = ChatAiEngine.stateFor(talker, msgId) ?: return false
        if (row !is ViewGroup) { WeLogger.i(TAG, "row is not ViewGroup"); return false }

        val existing = cards[row]
        if (existing != null && existing.key == key && existing.view.parent != null) {
            fill(existing.view, row, state, talker)
            return true
        }
        cards.remove(row)?.let { c -> (c.view.parent as? ViewGroup)?.removeView(c.view) }

        // 上游同款：先在行内找气泡（文本 View），再从气泡逐层向上爬到仍在行内的
        // 「竖直 LinearLayout + WRAP_CONTENT」容器（气泡的多级包裹，直接父级几乎不是）。
        val anchor = findBubble(row)
        if (anchor == null) {
            val sig = "no-bubble:" + row.javaClass.name
            if (quietSkip.add(sig)) WeLogger.i(TAG, "bubble anchor not found in row: ${row.javaClass.name}")
            return false
        }
        var branch: View = anchor
        var target: ViewGroup? = null
        var parent = branch.parent as? ViewGroup
        while (parent != null && isInside(parent, row)) {
            if (parent is LinearLayout && parent.orientation == LinearLayout.VERTICAL &&
                parent.layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT
            ) {
                target = parent
                break
            }
            if (parent === row) break
            branch = parent
            parent = branch.parent as? ViewGroup
        }
        // 行根是 RelativeLayout 时按「气泡之下」兜底（上游同款）
        if (target == null && row is RelativeLayout && branch.parent === row &&
            row.layoutParams?.height == ViewGroup.LayoutParams.WRAP_CONTENT
        ) {
            val bp = branch.layoutParams as? RelativeLayout.LayoutParams ?: return false
            if (bp.getRule(RelativeLayout.ALIGN_PARENT_BOTTOM) != 0) return false
            if (branch.id == View.NO_ID) branch.id = View.generateViewId()
            val card = makeCard(row, anchor)
            // 宽度同样交给 CardLayout 自适应（WIDTH == WRAP_CONTENT），缩进/边距沿用原逻辑
            val lp = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.BELOW, branch.id)
                addRule(RelativeLayout.ALIGN_PARENT_LEFT)
                // 卡片与气泡左对齐再右移 3.5dp（3dp 对齐小三角 + 用户 2026-09-29 晚：再右移 0.5dp）
                leftMargin = leftOf(anchor, row) + dp(row, 3.5f)
                topMargin = dp(row, 3)
                bottomMargin = dp(row, 6)
            }
            row.addView(card, lp)
            cards[row] = Card(key, card, row, anchor)
            fill(card, row, state, talker)
            WeLogger.i(TAG, "card attached (relative) msgId=$msgId")
            return true
        }
        if (target == null) {
            val sig = "unsupported:" + row.javaClass.name + "/" + (anchor.parent?.javaClass?.name ?: "?")
            if (quietSkip.add(sig)) WeLogger.i(TAG, "unsupported bubble layout: $sig")
            return false
        }

        val card = makeCard(row, anchor)
        // 宽度跟内容走（用户 2026-09-28 反馈忽宽忽细长）：旧实现挂卡时一次性算死固定宽度，
        // 上限 300dp 撑满、行未布局完时算出负值跌到 100dp 下限。现在 WRAP_CONTENT + 上下限。
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            // 卡片与气泡左对齐再右移 3.5dp（3dp 对齐小三角 + 用户 2026-09-29 晚：再右移 0.5dp）
            leftMargin = leftOf(anchor, target) + dp(row, 3.5f)
            topMargin = dp(row, 3)
            bottomMargin = dp(row, 6)
        }
        // Append only：不动微信原有子 View 的顺序
        target.addView(card, lp)
        cards[row] = Card(key, card, target, anchor)
        fill(card, row, state, talker)
        WeLogger.i(TAG, "card attached msgId=$msgId target=${target.javaClass.simpleName}")
        return true
    }

    // ==================== 查找辅助（上游同款思路） ====================

    /** 行内找气泡锚点：微信文本气泡 View（MMNeat7extView）优先，退而求其次取最深的可见 TextView。 */
    private fun findBubble(row: ViewGroup): View? {
        fun find(view: View, depth: Int): View? {
            if (depth > 24 || view.visibility != View.VISIBLE) return null
            if (view.javaClass.name.endsWith(".MMNeat7extView")) return view
            if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i), depth + 1)?.let { return it }
            return null
        }
        return find(row, 0)
    }

    private fun isInside(view: View, root: View): Boolean {
        var cur: View? = view
        while (cur != null) {
            if (cur === root) return true
            cur = cur.parent as? View
        }
        return false
    }

    private fun leftOf(anchor: View, ancestor: View): Int {
        val ap = IntArray(2).also { anchor.getLocationOnScreen(it) }
        val pp = IntArray(2).also { ancestor.getLocationOnScreen(it) }
        return (ap[0] - pp[0] - ancestor.paddingLeft).coerceAtLeast(0)
    }

    /** 卡片容器：固定宽度（用户 2026-09-29：按最宽气泡的宽度，别跟当前消息气泡/内容变宽变窄）。 */
    private class CardLayout(context: android.content.Context, val widthPx: Int) :
        LinearLayout(context) {
        init {
            orientation = LinearLayout.VERTICAL
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            super.onMeasure(
                android.view.View.MeasureSpec.makeMeasureSpec(widthPx, android.view.View.MeasureSpec.EXACTLY),
                heightMeasureSpec,
            )
        }
    }

    private fun makeCard(row: View, anchor: View? = null): CardLayout {
        // 宽度上限 = 气泡宽度上限（屏宽 68%），不是行宽：微信最宽文本气泡 ≈ 1280px/6.8 寸
        // 真机 49mm（用户尺量 + 09-28 截图像素 18%→87% 双源吻合 ≈ 872px/68%）。旧行为拿
        // 行宽（≈ 全屏）当上限 ⇒ 卡片比气泡宽出一截（用户 2026-10-09 报「卡片过宽」）。
        // 行未布局完时按屏幕宽 65% 兜底（v3.50「超出屏幕」的根因就是固定 300dp 兜底值）。
        val screenW = row.resources.displayMetrics.widthPixels
        val bubbleCap = (screenW * 0.68f).toInt()
        val rowCap = (row.width - leftOf(anchor ?: row, row) - dp(row, 16)).takeIf { row.width > 0 }
        val fallback = (screenW * 0.65f).toInt()
        // +1.5dp 加宽（用户 2026-09-29 晚）：必须加在 coerceAtMost 之后——v3.56 真机日志
        // w=870=bubbleCap 说明宽度上限在生效，若加在上限之前会被 cap 吃掉、等于没加。
        val w = ((rowCap ?: fallback).coerceAtMost(bubbleCap) + dp(row, 1.5f)).coerceAtLeast(dp(row, 140))
        WeLogger.i(TAG, "card width w=$w (rowCap=$rowCap bubbleCap=$bubbleCap)")
        return CardLayout(row.context, w)
    }

    /** 摘掉某聊天已挂的全部卡片（关开关 / 清状态时用）。 */
    fun clear(talker: String) {
        val snapshot = synchronized(cards) { cards.entries.toList() }
        for ((row, card) in snapshot) {
            if (!card.key.startsWith("$talker#")) continue
            clearRow(row)
        }
    }

    private fun clearRow(row: View) {
        cards.remove(row)?.let { c -> (c.view.parent as? ViewGroup)?.removeView(c.view) }
    }

    private fun dp(v: View, n: Int) = (n * v.resources.displayMetrics.density).toInt()

    private fun dp(v: View, n: Float) = (n * v.resources.displayMetrics.density).toInt()

    // ==================== 内容 ====================

    private fun fill(card: LinearLayout, row: View, state: ChatAiEngine.State, talker: String) {
        card.removeAllViews()
        val dark = row.context.isDarkMode
        val bgColor = if (dark) 0xD02B2D34.toInt() else 0xE6F2F4F8.toInt()
        val strokeColor = if (dark) 0x32E3E8F0 else 0x33707A8C
        val titleColor = if (dark) 0xFF9ECBFF.toInt() else 0xFF1F6FEB.toInt()
        val bodyColor = if (dark) 0xFFE6E9F0.toInt() else 0xFF2B2F36.toInt()
        val subColor = if (dark) 0xFF9AA3B2.toInt() else 0xFF6B7280.toInt()

        card.background = GradientDrawable().apply {
            cornerRadius = dp(row, 10).toFloat()
            setColor(bgColor)
            setStroke(dp(row, 1), strokeColor)
        }
        card.setPadding(dp(row, 10), dp(row, 6), dp(row, 10), dp(row, 6))

        fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(row.context).apply {
            text = value
            textSize = size
            setTextColor(color)
            includeFontPadding = false
            setLineSpacing(dp(row, 1).toFloat(), 1f)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        when (state) {
            is ChatAiEngine.State.Working -> {
                card.addView(text("AI · 分析中…", 11f, titleColor, true))
            }
            is ChatAiEngine.State.Failed -> {
                card.addView(text("AI · ${state.message}", 11f, titleColor, true))
                card.addView(text("点此重试", 11f, subColor).apply {
                    setPadding(0, dp(row, 4), 0, 0)
                    setOnClickListener { ChatAiEngine.retry(talker) }
                })
            }
            is ChatAiEngine.State.Done -> {
                val r = state.result
                card.addView(text("AI · ${r.emotionLine}", 11f, titleColor, true))
                if (r.reading.isNotBlank()) {
                    card.addView(text("解读（推测）：${r.reading}", 12f, bodyColor).apply {
                        setPadding(0, dp(row, 4), 0, 0)
                    })
                }
                // 建议列表只在手动模式显示（用户 2026-09-29：全自动时不显示，反正会自动发）
                if (!AiChatStore.isAutoReplyOn(talker)) {
                    r.replies.forEachIndexed { i, reply ->
                        val rowLine = LinearLayout(row.context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = android.view.Gravity.CENTER_VERTICAL
                            setPadding(0, dp(row, 4), 0, 0)
                        }
                        rowLine.addView(
                            text("${i + 1}. $reply", 12f, bodyColor).apply {
                                setOnClickListener {
                                    val ok = InputBar.fill(reply)
                                    android.widget.Toast.makeText(
                                        row.context,
                                        if (ok) "已填入输入框" else "填入失败（可长按复制）",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                                setOnLongClickListener {
                                    val cm = row.context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                            as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("reply", reply))
                                    android.widget.Toast.makeText(row.context, "已复制", android.widget.Toast.LENGTH_SHORT).show()
                                    true
                                }
                            },
                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        )
                        rowLine.addView(text("发送", 12f, titleColor, true).apply {
                            setPadding(dp(row, 8), dp(row, 2), dp(row, 2), dp(row, 2))
                            setOnClickListener {
                                val ok = InputBar.send(talker, reply)
                                android.widget.Toast.makeText(
                                    row.context, if (ok) "已发送" else "发送失败",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        })
                        card.addView(rowLine)
                    }
                }
                if (r.note.isNotBlank()) {
                    card.addView(text(r.note, 10f, subColor).apply { setPadding(0, dp(row, 3), 0, 0) })
                }
            }
        }
    }
}
