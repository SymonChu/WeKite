package com.github.wekite.features.items.chat.aichat

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import com.github.wekite.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap

/**
 * 「AI」小徽标：挂在**全自动回复发出**的消息行上（仅本机可见，视图层注入，对方看不到）。
 * 手动确认发送的建议回复不打标。
 *
 * ⚠️ 两条实测教训：
 * 1. 只能挂进**消息行 View 自身**（或它的子容器）。挂到 RecyclerView 上无效
 *    （列表子项由 LayoutManager 管，外部 addView 不参与布局、看不见）。
 * 2. **配色必须高对比**：初版用「浅蓝文字 + 半透明浅蓝底」，在浅色聊天背景（如浅蓝海底主题）
 *    上几乎看不见（2026-09-28 用户实测「没有看到 AI 徽标」，而日志已显示挂载成功）。
 *    现改为**实色蓝底 + 白色粗体**，任何背景上都醒目。
 */
object AiBadge {
    private const val TAG = "AiBadge"
    private val BG = 0xFF1F6FEB.toInt()      // 实色蓝（高对比）
    private val FG = Color.WHITE
    private val attached = Collections.synchronizedMap(WeakHashMap<View, View>())

    fun attach(anchor: View) {
        if (attached.containsKey(anchor)) return
        val host = anchor as? ViewGroup ?: run {
            WeLogger.i(TAG, "skip badge: message view is not a ViewGroup (${anchor.javaClass.simpleName})")
            return
        }
        val density = anchor.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val badge = TextView(anchor.context).apply {
            text = "AI"
            textSize = 9f
            includeFontPadding = false
            setTextColor(FG)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                cornerRadius = dp(4).toFloat()
                setColor(BG)
            }
            setPadding(dp(6), dp(2), dp(6), dp(2))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = dp(2).toFloat()
        }
        val ok = try {
            when (host) {
                is RelativeLayout -> {
                    host.addView(
                        badge,
                        RelativeLayout.LayoutParams(
                            RelativeLayout.LayoutParams.WRAP_CONTENT,
                            RelativeLayout.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            addRule(RelativeLayout.ALIGN_PARENT_END)
                            addRule(RelativeLayout.ALIGN_PARENT_TOP)
                            marginEnd = dp(6)
                            topMargin = dp(2)
                        }
                    )
                    true
                }
                is LinearLayout -> {
                    host.addView(
                        badge,
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { gravity = Gravity.END; topMargin = dp(2); marginEnd = dp(6) }
                    )
                    true
                }
                else -> {
                    host.addView(
                        badge,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.TOP or Gravity.END,
                        ).apply { marginEnd = dp(6); topMargin = dp(2) }
                    )
                    true
                }
            }
        } catch (e: Exception) {
            WeLogger.w(TAG, "attach failed: ${e.javaClass.simpleName}")
            false
        }
        if (!ok) return
        attached[anchor] = badge
        // 布局完成后再记一次几何：能直接判断「挂上了但没显示」还是「位置不对」
        badge.post {
            if (!badge.isAttachedToWindow) return@post
            val p = IntArray(2).also { badge.getLocationOnScreen(it) }
            val hp = IntArray(2).also { host.getLocationOnScreen(it) }
            WeLogger.i(
                TAG,
                "badge placed host=${host.javaClass.simpleName} hostRect=${hp[0]},${hp[1]} " +
                    "${host.width}x${host.height} badge=${badge.width}x${badge.height} at ${p[0]},${p[1]} " +
                    "visible=${badge.isShown}"
            )
        }
        WeLogger.i(TAG, "badge attached (host=${host.javaClass.simpleName})")
    }

    fun detach(anchor: View) {
        attached.remove(anchor)?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }
}
