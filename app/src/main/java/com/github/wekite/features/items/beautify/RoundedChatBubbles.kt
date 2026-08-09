package com.github.wekite.features.items.beautify

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.NinePatchDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import dev.ujhhgtg.reflekt.reflekt
import com.github.wekite.features.core.Feature
import com.github.wekite.features.core.SwitchFeature
import com.github.wekite.preferences.WePrefs.Companion.prefOption
import com.github.wekite.utils.WeLogger

/**
 * 圆角气泡 — 将微信聊天气泡改为更大的圆角样式。
 *
 * 微信 8.0.x 的气泡背景是 NinePatchDrawable(九宫格图, 圆角已固化在图片里), 旧实现只处理
 * GradientDrawable/StateListDrawable, 所以完全不生效。新实现:
 * - 同时 hook setBackgroundDrawable / setBackground 两个入口, 并显式限定 Drawable 重载
 *   (setBackground 还有一个已废弃的 Bitmap 重载, 不限定参数可能 hook 到错误重载导致全部失效);
 * - GradientDrawable: 直接改写圆角半径;
 * - NinePatchDrawable: 用 [RoundedClipDrawable] 包一层, 按圆角矩形裁剪绘制, 保留九宫格内边距;
 * - 两种都延迟到布局完成后用"气泡特征"判定 (尺寸/非全屏), 避免误伤按钮、图标、输入栏、页面背景;
 * - StateListDrawable / LayerDrawable / InsetDrawable 等包装类型递归取内层。
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
            .firstMethod { name = "setBackgroundDrawable"; parameters(Drawable::class) }
            .hookBefore {
                val view = thisObject as? View ?: return@hookBefore
                val drawable = args[0] as? Drawable ?: return@hookBefore
                onBackgroundSet(view, drawable, 0)
            }

        // 部分版本微信直接 setBackground(Drawable), 一并拦截 (显式参数类型, 避免命中 Bitmap 重载)
        View::class.reflekt()
            .firstMethod { name = "setBackground"; parameters(Drawable::class) }
            .hookBefore {
                val view = thisObject as? View ?: return@hookBefore
                val drawable = args[0] as? Drawable ?: return@hookBefore
                onBackgroundSet(view, drawable, 0)
            }
    }

    private fun onBackgroundSet(view: View, drawable: Drawable, depth: Int) {
        if (depth > 3) return // 防止包装类型无限递归
        try {
            when (drawable) {
                is GradientDrawable -> roundLater(view, drawable)
                is NinePatchDrawable -> wrapLater(view, drawable)
                else -> {
                    val inner = when (drawable) {
                        is StateListDrawable -> {
                            val list = ArrayList<Drawable>()
                            var idx = 0
                            while (idx < drawable.stateCount) {
                                drawable.getStateDrawable(idx)?.let(list::add)
                                idx++
                            }
                            list
                        }
                        // RippleDrawable 继承自 LayerDrawable, 一并覆盖
                        is LayerDrawable -> {
                            val list = ArrayList<Drawable>()
                            for (i in 0 until drawable.numberOfLayers) {
                                drawable.getDrawable(i)?.let(list::add)
                            }
                            list
                        }
                        is InsetDrawable -> listOfNotNull(drawable.drawable)
                        else -> emptyList()
                    }
                    inner.forEach { onBackgroundSet(view, it, depth + 1) }
                }
            }
        } catch (t: Throwable) {
            WeLogger.d(TAG, "onBackgroundSet error: ${t.message}")
        }
    }

    /** 布局完成后判定为气泡再改圆角 (此时 bounds 已知, 可排除按钮/输入栏/页面背景) */
    private fun roundLater(view: View, drawable: GradientDrawable) {
        view.post {
            val bounds = drawable.bounds
            if (!isBubbleLike(bounds)) return@post
            drawable.cornerRadius = clampRadius(bounds)
        }
    }

    /** 布局完成后判定为气泡, 再用圆角裁剪包装九宫格背景 (保留九宫格内边距) */
    private fun wrapLater(view: View, drawable: NinePatchDrawable) {
        view.post {
            val current = view.background
            // 背景已被替换 (视图复用/重新设置) 或已被包装过 — 跳过, 避免双重包装
            if (current !== drawable || current is RoundedClipDrawable) return@post
            val bounds = drawable.bounds
            if (!isBubbleLike(bounds)) return@post
            view.setBackgroundDrawable(RoundedClipDrawable(drawable, clampRadius(bounds)))
        }
    }

    /**
     * 气泡特征判定: 宽度约为屏幕 20%~92%, 高度不超过屏幕 60%, 且长宽都不为 0。
     * 按钮/图标 (太窄)、输入栏与工具栏 (接近全宽)、页面/对话框背景 (高占比) 都会被排除。
     */
    private fun isBubbleLike(bounds: Rect): Boolean {
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        val dm = android.content.res.Resources.getSystem().displayMetrics
        val widthRatio = bounds.width().toFloat() / dm.widthPixels
        val heightRatio = bounds.height().toFloat() / dm.heightPixels
        return widthRatio in 0.2f..0.92f && heightRatio in 0.035f..0.6f
    }

    /** 圆角不超过短边一半, 避免小尺寸元素变成畸形圆 */
    private fun clampRadius(bounds: Rect): Float =
        minOf(cornerRadiusPx, bounds.width() / 2f, bounds.height() / 2f)

    /**
     * 圆角裁剪包装: 把内层 drawable (九宫格气泡) 按圆角矩形裁剪绘制。
     * DrawableWrapper 会代理 setState/tint/内边距 (getPadding 转发给九宫格, 文字缩进不受影响)。
     */
    private class RoundedClipDrawable(
        delegate: Drawable,
        private val radiusPx: Float,
    ) : DrawableWrapper(delegate) {

        private val path = Path()

        override fun draw(canvas: Canvas) {
            canvas.save()
            path.reset()
            path.addRoundRect(RectF(bounds), radiusPx, radiusPx, Path.Direction.CW)
            canvas.clipPath(path)
            super.draw(canvas)
            canvas.restore()
        }
    }
}
