package com.github.wekite.features.items.chat

import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import com.github.wekite.utils.WeLogger
import com.github.wekite.utils.android.GlassSurfaceDrawable
import com.github.wekite.utils.android.isDarkMode
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Shared visual treatment for floating chat cards.
 *
 * Light mode keeps WeChat's own backgrounds intact. Dark mode needs an explicit surface and a
 * hairline border because Android elevation is barely visible on near-black chat backgrounds.
 *
 * 液态玻璃: 降频捕获卡片背后真实像素 (PixelCopy, 窗口 Surface 拷贝) → GPU 模糊
 * (RenderNode + RenderEffect, API 31+) 或 CPU 盒式模糊兜底 → 放大绘制 → 40% 半透明主题色
 * 叠加 → 1dp 细高光描边 —— 与首页悬浮底栏 (FloatingBottomBar 的 containerColor.copy(0.4f))
 * 观感一致。全 Android 版本可用 (捕获 API 26+, 模糊 API<31 走 CPU)。
 */
internal object FloatingChatCardVisuals {

    private const val TAG = "FloatingChatCardVisuals"

    private const val DARK_SURFACE_COLOR = 0xFF242424.toInt()
    private const val DARK_STROKE_COLOR = 0x24FFFFFF
    private const val DARK_STROKE_WIDTH_DP = 1

    /** 液态玻璃叠色透明度 (40%, 对齐首页悬浮底栏)。 */
    private const val GLASS_TINT_ALPHA_PERCENT = 40

    private const val GLASS_LIGHT_TINT = 0xFFFFFFFF.toInt()
    private const val GLASS_DARK_TINT = 0xFF101014.toInt()

    private data class AppliedStyle(val cornerRadiusDp: Int, val strokeWidthPx: Int)

    private val originalBackgrounds = WeakHashMap<View, Drawable?>()
    private val appliedBackgrounds = WeakHashMap<View, Drawable>()
    private val appliedStyles = WeakHashMap<View, AppliedStyle>()

    /** 液态玻璃: 原始背景 + 当前捕获 drawable, 与暗色浮层互不干扰。 */
    private val glassOriginals = WeakHashMap<View, Drawable?>()
    private val glassDrawables = WeakHashMap<View, GlassSurfaceDrawable>()

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
     * 液态玻璃卡片面: PixelCopy 捕获背后 → GPU 模糊 → 40% tint + 高光描边。
     * 幂等 —— drawable 已存在且背景未被微信换掉时只更新参数, 每帧可安全重放。
     */
    fun applyGlass(view: View, cornerRadiusDp: Int, blurRadiusDp: Int) {
        if (!glassOriginals.containsKey(view)) {
            glassOriginals[view] = view.background
        }

        val existing = glassDrawables[view]
        val density = view.resources.displayMetrics.density
        if (existing != null) {
            existing.blurRadiusPx = blurRadiusDp * density
            existing.tintColor = glassTint(view)
            existing.cornerRadiusPx = cornerRadiusDp * density
            existing.strokeWidthPx = (DARK_STROKE_WIDTH_DP * density).roundToInt().toFloat()
            if (view.background !== existing) {
                view.background = existing
            }
            return
        }

        // rootView 未就绪 (构造 hook 阶段) 时跳过, preDraw 自愈会再进来
        val root = view.rootView
        if (root == null) {
            WeLogger.w(TAG, "glass skipped: rootView not ready (${view.javaClass.simpleName})")
            return
        }

        val d = GlassSurfaceDrawable(view, root).also {
            view.background = it
            it.attach()
        }
        d.blurRadiusPx = blurRadiusDp * density
        d.tintColor = glassTint(view)
        d.cornerRadiusPx = cornerRadiusDp * density
        d.strokeWidthPx = (DARK_STROKE_WIDTH_DP * density).roundToInt().toFloat()
        glassDrawables[view] = d
        WeLogger.d(TAG, "glass attached: ${view.javaClass.simpleName}")
    }

    /** 玻璃叠色: 40% 白 (亮色) / 40% 深色 (暗色), 对齐首页悬浮底栏。 */
    private fun glassTint(view: View): Int {
        val alpha = GLASS_TINT_ALPHA_PERCENT * 255 / 100
        val base = if (view.context.isDarkMode) GLASS_DARK_TINT else GLASS_LIGHT_TINT
        return (base and 0x00FFFFFF) or (alpha shl 24)
    }

    /** 摘掉液态玻璃: 停捕获并恢复原始背景 (背景已被微信换掉时不覆盖)。 */
    fun clearGlass(view: View) {
        val d = glassDrawables.remove(view)
        if (d != null) {
            d.detach()
            WeLogger.d(TAG, "glass detached (${view.javaClass.simpleName})")
            if (view.background === d) {
                view.background = glassOriginals[view]
            }
        }
        glassOriginals.remove(view)
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
