package io.github.we.lite.features.items.beautify

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import dev.ujhhgtg.reflekt.reflekt
import io.github.we.lite.features.core.Feature
import io.github.we.lite.features.core.SwitchFeature
import io.github.we.lite.preferences.WePrefs.Companion.prefOption
import io.github.we.lite.utils.WeLogger

/**
 * 圆角气泡 — 将微信聊天气泡改为更大的圆角样式。
 *
 * 通过 hook View.setBackgroundDrawable / setBackground 拦截聊天气泡背景:
 * - 微信气泡背景是 GradientDrawable / StateListDrawable(内层 GradientDrawable)
 * - 把圆角半径替换为用户设定的值 (默认 16dp)
 *
 * 判定"气泡"特征: 通过反射读取 GradientDrawable 的 mFillColor (有填充色)
 * 且 bounds 不是全屏尺寸, 避免误伤列表项、按钮、页面背景。
 */
@Feature(name = "圆角气泡", categories = ["界面美化"], description = "将聊天气泡改为更大的圆角样式")
object RoundedChatBubbles : SwitchFeature() {

    private const val TAG = "RoundedChatBubbles"

    /** 圆角半径 (dp), 默认 16 */
    var cornerRadiusDp by prefOption("rounded_bubble_corner", 16)

    private val cornerRadiusPx: Float
        get() = cornerRadiusDp * android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            1f,
            android.content.res.Resources.getSystem().displayMetrics
        )

    override fun onEnable() {
        View::class.reflekt()
            .firstMethod { name = "setBackgroundDrawable" }
            .hookBefore {
                val drawable = args[0] as? Drawable ?: return@hookBefore
                applyRoundedCorners(drawable, 0)
            }

        // 部分版本微信直接 setBackground(Drawable), 一并拦截
        View::class.reflekt()
            .firstMethod { name = "setBackground" }
            .hookBefore {
                val drawable = args[0] as? Drawable ?: return@hookBefore
                applyRoundedCorners(drawable, 0)
            }
    }

    private fun applyRoundedCorners(drawable: Drawable, depth: Int) {
        if (depth > 3) return // 防止 StateListDrawable 无限递归
        try {
            when (drawable) {
                is GradientDrawable -> {
                    if (hasSolidFill(drawable) && !isFullScreen(drawable)) {
                        drawable.cornerRadius = cornerRadiusPx
                    }
                }
                is StateListDrawable -> {
                    var idx = 0
                    while (idx < drawable.stateCount) {
                        drawable.getStateDrawable(idx)?.let { applyRoundedCorners(it, depth + 1) }
                        idx++
                    }
                }
            }
        } catch (t: Throwable) {
            WeLogger.d(TAG, "applyRoundedCorners error: ${t.message}")
        }
    }

    /** 反射读取 GradientDrawable 私有字段 mFillColor, 判断是否有填充色 */
    private fun hasSolidFill(drawable: GradientDrawable): Boolean {
        return try {
            val field = GradientDrawable::class.java.getDeclaredField("mFillColor")
            field.isAccessible = true
            val value = field.get(drawable)
            value != null
        } catch (t: Throwable) {
            // 反射失败时保守返回 true, 让尺寸判断兜底
            true
        }
    }

    /** 全屏尺寸背景视为页面级背景, 跳过 (气泡背景通常远小于屏幕) */
    private fun isFullScreen(drawable: GradientDrawable): Boolean {
        val bounds = drawable.bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val screenWidth = android.content.res.Resources.getSystem().displayMetrics.widthPixels
        return bounds.width() >= screenWidth * 0.8
    }
}
