package com.github.wekite.features.items.chat

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.github.wekite.utils.android.GlassEffect
import com.github.wekite.utils.android.isDarkMode
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Shared visual treatment for floating chat cards.
 *
 * Light mode keeps WeChat's own backgrounds intact. Dark mode needs an explicit surface and a
 * hairline border because Android elevation is barely visible on near-black chat backgrounds.
 *
 * 液态玻璃: Android 12+ (API 31+) 用系统 RenderEffect backdrop blur 做 GPU 实时模糊,
 * 叠 40% 半透明主题色 —— 与首页悬浮底栏 (FloatingBottomBar 的 containerColor.copy(0.4f))
 * 观感一致。API 31 以下自动降级为纯半透明 (无模糊), 不会报错也不会闪退。
 */
internal object FloatingChatCardVisuals {

    private const val DARK_SURFACE_COLOR = 0xFF242424.toInt()
    private const val DARK_STROKE_COLOR = 0x24FFFFFF
    private const val DARK_STROKE_WIDTH_DP = 1

    /** 液态玻璃叠色透明度 (40%, 对齐首页悬浮底栏)。 */
    private const val GLASS_TINT_ALPHA_PERCENT = 40

    private const val GLASS_LIGHT_TINT = 0xFFFFFFFF.toInt()
    private const val GLASS_DARK_TINT = 0xFF101014.toInt()

    /** 玻璃边缘细高光 (25% 白), 对齐首页底栏 Highlight 的玻璃感。 */
    private const val GLASS_EDGE_HIGHLIGHT = 0x40FFFFFF

    private data class AppliedStyle(val cornerRadiusDp: Int, val strokeWidthPx: Int)

    private data class AppliedGlass(val cornerRadiusDp: Int, val blurRadiusDp: Int)

    private val originalBackgrounds = WeakHashMap<View, Drawable?>()
    private val appliedBackgrounds = WeakHashMap<View, Drawable>()
    private val appliedStyles = WeakHashMap<View, AppliedStyle>()

    /** 液态玻璃独立的原背景/当前背景/样式缓存, 与暗色浮层互不干扰。 */
    private val glassOriginals = WeakHashMap<View, Drawable?>()
    private val glassBackgrounds = WeakHashMap<View, Drawable>()
    private val glassStyles = WeakHashMap<View, AppliedGlass>()

    fun applyDarkSurface(view: View, cornerRadiusDp: Int) {
        if (!view.context.isDarkMode) {
            restoreOriginalBackground(view)
            return
        }

        if (!originalBackgrounds.containsKey(view)) {
            originalBackgrounds[view] = view.background
        }

        val density = view.resources.displayMetrics.density
        val strokeWidthPx = (DARK_STROKE_WIDTH_DP * density).roundToInt().coerceAtLeast(1)
        val style = AppliedStyle(cornerRadiusDp, strokeWidthPx)
        val appliedBackground = appliedBackgrounds[view]
        if (appliedStyles[view] == style && view.background === appliedBackground) return

        val radiusPx = cornerRadiusDp * density
        val background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(DARK_SURFACE_COLOR)
            setStroke(strokeWidthPx, DARK_STROKE_COLOR)
        }
        view.background = background
        appliedBackgrounds[view] = background
        appliedStyles[view] = style
    }

    private fun restoreOriginalBackground(view: View) {
        if (!originalBackgrounds.containsKey(view)) return
        val appliedBackground = appliedBackgrounds[view]
        if (view.background === appliedBackground) {
            view.background = originalBackgrounds[view]
        }
        originalBackgrounds.remove(view)
        appliedBackgrounds.remove(view)
        appliedStyles.remove(view)
    }

    /**
     * 液态玻璃卡片面: 40% 半透明主题色圆角背景 + GPU backdrop blur。
     * 幂等 —— 样式没变 (圆角/模糊值/背景未被微信覆盖) 时直接返回, 每帧可安全重放。
     */
    fun applyGlass(view: View, cornerRadiusDp: Int, blurRadiusDp: Int) {
        val style = AppliedGlass(cornerRadiusDp, blurRadiusDp)
        val background = glassBackgrounds[view]
        if (glassStyles[view] == style && view.background === background) return

        if (!glassOriginals.containsKey(view)) {
            glassOriginals[view] = view.background
        }

        val density = view.resources.displayMetrics.density
        val radiusPx = cornerRadiusDp * density
        val base = if (view.context.isDarkMode) GLASS_DARK_TINT else GLASS_LIGHT_TINT
        val alpha = GLASS_TINT_ALPHA_PERCENT * 255 / 100
        val tint = (base and 0x00FFFFFF) or (alpha shl 24)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(tint)
            // 细高光描边, 模拟首页悬浮底栏的玻璃边缘高光
            setStroke((1 * density).roundToInt().coerceAtLeast(1), GLASS_EDGE_HIGHLIGHT)
        }
        view.background = bg
        glassBackgrounds[view] = bg
        glassStyles[view] = style
        GlassEffect.apply(view, blurRadiusDp * density)
    }

    /** 摘掉液态玻璃: 清 GPU 效果并恢复原始背景。 */
    fun clearGlass(view: View) {
        GlassEffect.clear(view)
        val background = glassBackgrounds[view]
        if (background != null && view.background === background) {
            view.background = glassOriginals[view]
        }
        glassOriginals.remove(view)
        glassBackgrounds.remove(view)
        glassStyles.remove(view)
    }

    /** 悬浮卡统一入口: 玻璃开 → 液态玻璃; 关 → 摘玻璃并回到暗色浮层/原背景。 */
    fun applyGlassOrDarkSurface(view: View, cornerRadiusDp: Int, glassEnabled: Boolean, glassBlurDp: Int) {
        if (glassEnabled) {
            applyGlass(view, cornerRadiusDp, glassBlurDp)
        } else {
            clearGlass(view)
            applyDarkSurface(view, cornerRadiusDp)
        }
    }
}
