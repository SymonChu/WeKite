package io.github.we.lite.features.items.beautify

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import androidx.core.graphics.drawable.solidColor
import androidx.core.graphics.drawable.strokeWidth
import dev.ujhhgtg.reflekt.reflekt
import io.github.we.lite.features.core.Feature
import io.github.we.lite.features.core.SwitchFeature
import io.github.we.lite.preferences.WePrefs.Companion.prefOption

/**
 * 圆角气泡 — 将微信聊天气泡改为更大的圆角样式。
 *
 * 通过 hook View.setBackgroundDrawable 拦截聊天气泡背景:
 * - 微信气泡背景是 GradientDrawable / StateListDrawable(内层 GradientDrawable)
 * - 把圆角半径替换为用户设定的值 (默认 16dp)
 *
 * 仅处理"气泡"特征明显的背景: 有 solid 填充色 + 无描边 + 非全屏背景,
 * 避免误伤列表项、按钮等其他组件。
 */
@Feature(name = "圆角气泡", categories = ["界面美化"], description = "将聊天气泡改为更大的圆角样式")
object RoundedChatBubbles : SwitchFeature() {

    private const val TAG = "RoundedChatBubbles"

    /** 圆角半径 (dp), 默认 16 */
    var cornerRadiusDp by prefOption("rounded_bubble_corner", 16)

    /** 是否也处理纯色背景气泡 (GradientDrawable.solidColor 非空) */
    var applyToSolidBubbles by prefOption("rounded_bubble_solid", true)

    private val cornerRadiusPx: Float
        get() = cornerRadiusDp * (android.util.TypedValue.applyDimension(
            android.util.TypedValue.COMPLEX_UNIT_DIP,
            1f,
            android.content.res.Resources.getSystem().displayMetrics
        ))

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
        when (drawable) {
            is GradientDrawable -> {
                // 只处理"看起来像气泡"的: 有填充色, 无描边, 且不是全屏大背景
                val solid = drawable.solidColor
                val hasStroke = drawable.strokeWidth > 0
                if (solid != null && !hasStroke && isBubbleSized(drawable)) {
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
    }

    /** 粗略判断是否为气泡: 背景不是全屏尺寸 (列表项背景会被 setBackground 设置多次) */
    private fun isBubbleSized(drawable: GradientDrawable): Boolean {
        val bounds = drawable.bounds
        if (bounds.width() <= 0 || bounds.height() <= 0) return true // 尚未布局, 先应用
        // 超过屏幕 80% 宽度的背景视为页面级背景, 跳过
        val screenWidth = android.content.res.Resources.getSystem().displayMetrics.widthPixels
        return bounds.width() < screenWidth * 0.8
    }
}
