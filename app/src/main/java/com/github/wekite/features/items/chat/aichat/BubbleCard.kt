package com.github.wekite.features.items.chat.aichat

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap

/**
 * 气泡下分析卡（借鉴 yanwai 的 append-only 策略，独立实现）：
 * - 找到气泡所在「竖直 LinearLayout 且 WRAP_CONTENT」的父容器，把卡片 addView 到气泡之后
 * - 只追加，不替换、不重排微信原有 child（避免 RecyclerView 回收错位）
 * - 回收复用由调用方（Feature 的 onBindView hook）驱动：每次 bind 先 [detachIfStale]
 *
 * 毛玻璃效果在低风险前提下简化为半透明深色圆角背景 + 细描边
 * （yanwai 的共享 backdrop 方案需每窗口 PreDraw 截屏，微信列表滚动时开销大，先不做）。
 */
object BubbleCard {
    private const val TAG = "AiBubbleCard"

    /** view（气泡行内锚点）-> 卡片 */
    private val attached = ConcurrentHashMap<View, TextView>()

    var onRetry: (() -> Unit)? = null

    fun show(anchor: View, msgKey: String, textProvider: () -> String): Boolean {
        val parent = anchor.parent as? ViewGroup ?: return false
        val existing = attached[anchor]
        if (existing != null && existing.parent === parent) {
            existing.text = textProvider()
            return true
        }
        if (existing != null) detach(anchor)
        if (parent !is LinearLayout || parent.orientation != LinearLayout.VERTICAL) return false
        if (parent.layoutParams?.height != ViewGroup.LayoutParams.WRAP_CONTENT) return false

        val density = anchor.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val card = TextView(anchor.context).apply {
            textSize = 12f
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setTextColor(0xFFE6E9F0.toInt())
            setLineSpacing(dp(1).toFloat(), 1f)
            gravity = Gravity.START
            // 半透明深底 + 圆角：深浅主题都可读
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xCC23262E.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
            setOnClickListener {
                if (AnalysisEngine.stateOf(msgKey) is AnalysisEngine.State.Failed) {
                    onRetry?.invoke()
                }
            }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(3)
            bottomMargin = dp(5)
            marginStart = dp(8)
            marginEnd = dp(32)
        }
        return try {
            card.text = textProvider()
            parent.addView(card, lp)
            attached[anchor] = card
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "attach card failed", e)
            false
        }
    }

    fun detach(anchor: View) {
        attached.remove(anchor)?.let { card ->
            (card.parent as? ViewGroup)?.removeView(card)
        }
    }

}
