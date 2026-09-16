package com.github.wekite.ui.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import com.github.wekite.utils.android.isDarkMode
import java.util.WeakHashMap

/**
 * 「圆角卡片」的视觉规格（v3.24 起只有一种样式）。
 *
 * ⚠️ v3.21 曾把「卡片样式」和「分组」拆成两个独立维度，并给了三档样式
 * （圆角卡片 / 紧凑圆角 / 极简）。v3.24 按用户要求**只保留「圆角卡片」一档**：
 * 三档之间的差别（10dp/14dp/6dp 圆角、内缩 6/10/0dp）肉眼几乎分不出来，
 * 留着只是噪音。枚举保留单一值是为了不改动已有的 pref key
 * （`list_islands_shape`），用户之前存过的旧值会在读取时回落到这一档。
 */
enum class IslandShape(
    val rowRadiusDp: Int,
    val horizontalInsetDp: Int,
    val verticalInsetDp: Int,
    val lightBackgroundColor: Int,
    val darkBackgroundColor: Int,
) {
    /** 圆角卡片：圆角 14dp、左右内缩 10dp、上下内缩 4dp。 */
    ROUNDED_CARD(14, 10, 4, 0xFFF7FAF9.toInt(), 0xFF252827.toInt()),
}

/** 一行在它所属「岛」里的位置，决定四个角哪些是圆的、哪些边是岛的外轮廓。 */
enum class IslandRowPosition { SINGLE, FIRST, MIDDLE, LAST }

/**
 * 一行 View 的原始外观快照。
 *
 * 微信会回收复用列表行 View，所以必须记住「我们改之前」的样子才能还原，
 * 并按「当前应有的样子」的指纹（[moduleKey]）决定是否需要重画。
 */
class IslandRowBaseline(
    var background: Drawable?,
    var padLeft: Int,
    var padTop: Int,
    var padRight: Int,
    var padBottom: Int,
    var moduleBackground: Drawable? = null,
    var moduleKey: String? = null,
)

/** 按 View 记录基线，弱引用避免泄漏已回收的行。 */
private val sharedBaselines = WeakHashMap<View, IslandRowBaseline>()

/**
 * 给一行套上圆角卡片外观。同一行反复调用是安全的：指纹没变就复用已构造的 Drawable。
 *
 * @param grouped true = 这一行属于某个「岛」，只在岛的两端留纵向间距；
 *                false = 这一行自己就是完整一张卡片（四角全圆、上下留白）。
 */
fun styleIslandRow(
    row: View,
    shape: IslandShape,
    grouped: Boolean,
    position: IslandRowPosition,
    unread: Boolean = false,
) {
    val baseline = sharedBaselines.getOrPut(row) {
        IslandRowBaseline(
            background = row.background,
            padLeft = row.paddingLeft,
            padTop = row.paddingTop,
            padRight = row.paddingRight,
            padBottom = row.paddingBottom,
        )
    }
    val key = "${shape.name}|$grouped|$position|$unread|${row.context.isDarkMode}"
    if (baseline.moduleKey != key || baseline.moduleBackground == null) {
        baseline.moduleBackground = buildIslandBackground(row.context, shape, grouped, position, unread)
        baseline.moduleKey = key
    }
    row.background = baseline.moduleBackground
}

/** 把一行还原为微信原样。 */
fun restoreIslandRow(row: View) {
    val baseline = sharedBaselines[row] ?: return
    if (row.background === baseline.moduleBackground) {
        row.background = baseline.background
        row.setPadding(baseline.padLeft, baseline.padTop, baseline.padRight, baseline.padBottom)
    }
    baseline.moduleKey = null
    baseline.moduleBackground = null
}

private const val UNREAD_LIGHT = 0xFFEAF8F2.toInt()
private const val UNREAD_DARK = 0xFF253E37.toInt()
private const val STROKE_DARK = 0x22FFFFFF
private const val STROKE_LIGHT = 0x16161D1C
private const val RIPPLE_DARK = 0x2AFFFFFF
private const val RIPPLE_LIGHT = 0x18006A62

/**
 * 按样式 / 是否分组 / 行位置构造该行背景。
 *
 * - [grouped] = false：四角全圆、上下都留内缩 —— 每行各自一张卡片。
 * - [grouped] = true：组内首行圆上两角、末行圆下两角、中间行不圆，
 *   且只在组的两端留纵向内缩 —— 「整组」拼成一块连续的圆角卡片。
 */
fun buildIslandBackground(
    context: Context,
    shape: IslandShape,
    grouped: Boolean,
    position: IslandRowPosition,
    unread: Boolean,
): Drawable {
    val isDark = context.isDarkMode
    val effectivePosition = if (grouped) position else IslandRowPosition.SINGLE

    val card = IslandCardDrawable(
        fillColor = when {
            unread && isDark -> UNREAD_DARK
            unread -> UNREAD_LIGHT
            isDark -> shape.darkBackgroundColor
            else -> shape.lightBackgroundColor
        },
        strokeColor = if (isDark) STROKE_DARK else STROKE_LIGHT,
        radiusPx = shape.rowRadiusDp.dpToPx(context).toFloat(),
        strokePx = 1.dpToPx(context).coerceAtLeast(1).toFloat(),
        position = effectivePosition,
    )

    val horizontalInset = shape.horizontalInsetDp.dpToPx(context)
    val verticalInset = shape.verticalInsetDp.dpToPx(context)
    val topInset = if (grouped) {
        when (effectivePosition) {
            IslandRowPosition.SINGLE, IslandRowPosition.FIRST -> verticalInset
            IslandRowPosition.MIDDLE, IslandRowPosition.LAST -> 0
        }
    } else {
        verticalInset
    }
    val bottomInset = if (grouped) {
        when (effectivePosition) {
            IslandRowPosition.SINGLE, IslandRowPosition.LAST -> verticalInset
            IslandRowPosition.FIRST, IslandRowPosition.MIDDLE -> 0
        }
    } else {
        verticalInset
    }

    val inset = InsetDrawable(card, horizontalInset, topInset, horizontalInset, bottomInset)
    val rippleColor = if (isDark) RIPPLE_DARK else RIPPLE_LIGHT
    return RippleDrawable(ColorStateList.valueOf(rippleColor), inset, null)
}

/**
 * 圆角卡片背景（自绘）。
 *
 * ⚠️⚠️ **为什么不能直接用 [android.graphics.drawable.GradientDrawable]**（v3.23 血案）
 *
 * GradientDrawable 只能对**整圈**描边（`setStroke`）：
 * ```
 * setCornerRadii(角); setStroke(1dp, color)   // 四条边 + 四个角全画
 * ```
 * 分组开启时同一岛内相邻两行各自画一整圈 1dp 描边，两条描边在接缝处**叠成一条
 * 2dp、颜色更深的横线**。真机截图实测（v3.23）：
 * 那条线 3~4px 宽、颜色 `(219,221,220)`，横跨卡片全宽（x=131..1232），
 * 而微信自己的分隔线是 `(229,229,229)` 且会内缩 —— 两者不同源，用户报的
 * 「岛内还有很小的间隔」就是它。
 *
 * 本类改为**只画岛的外轮廓**：
 * - 内向边（FIRST 的下边 / MIDDLE 的上下边 / LAST 的上边）**完全不画描边**
 * - 圆角只给外向的那两个角，内向角是直角 —— 于是相邻行的填色无缝拼接
 *
 * `getOutline` 用与填色一致的路径，让 RecyclerView 回收行切换岛内位置时
 * 不会因为硬件加速的圆角裁剪残留上一行的像素（「退出聊天回到首页后卡片变形」）。
 */
private class IslandCardDrawable(
    private val fillColor: Int,
    private val strokeColor: Int,
    private val radiusPx: Float,
    private val strokePx: Float,
    private val position: IslandRowPosition,
) : Drawable() {

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
        strokeJoin = Paint.Join.MITER
    }
    private val rect = RectF()
    private val tmpRect = RectF()
    private val fillPath = Path()
    private val strokePath = Path()

    /** 外向上边是否为圆角。 */
    private val roundTop: Boolean
        get() = position == IslandRowPosition.FIRST || position == IslandRowPosition.SINGLE

    /** 外向下边是否为圆角。 */
    private val roundBottom: Boolean
        get() = position == IslandRowPosition.LAST || position == IslandRowPosition.SINGLE

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty || radiusPx < 0f) return
        rect.set(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat())

        val rTL = if (roundTop) radiusPx else 0f
        val rTR = if (roundTop) radiusPx else 0f
        val rBR = if (roundBottom) radiusPx else 0f
        val rBL = if (roundBottom) radiusPx else 0f

        // ── 填色：只圆外向的两个角，内向角保持直角（相邻行填色因此无缝拼接）──
        fillPath.reset()
        fillPath.addRoundRect(
            rect,
            floatArrayOf(rTL, rTL, rTR, rTR, rBR, rBR, rBL, rBL),
            Path.Direction.CW,
        )
        fillPaint.style = Paint.Style.FILL
        fillPaint.color = fillColor
        canvas.drawPath(fillPath, fillPaint)

        // ── 描边：只画岛的外轮廓 ──
        if (strokePx <= 0f) return
        val half = strokePx / 2f
        val l = rect.left + half
        val t = rect.top + half
        val r = rect.right - half
        val btm = rect.bottom - half
        if (r <= l || btm <= t) return

        strokePaint.strokeWidth = strokePx
        strokePaint.color = strokeColor

        strokePath.reset()
        // 左竖边
        strokePath.moveTo(l, if (roundTop) t + rTL else t)
        strokePath.lineTo(l, if (roundBottom) btm - rBL else btm)
        // 右竖边
        strokePath.moveTo(r, if (roundTop) t + rTR else t)
        strokePath.lineTo(r, if (roundBottom) btm - rBR else btm)
        // 上边 + 上两角（仅外向有圆角的行画）
        if (roundTop) {
            strokePath.moveTo(l, t + rTL)
            tmpRect.set(l, t, l + 2 * rTL, t + 2 * rTL)
            strokePath.arcTo(tmpRect, 180f, 90f, false)
            strokePath.lineTo(r - rTR, t)
            tmpRect.set(r - 2 * rTR, t, r, t + 2 * rTR)
            strokePath.arcTo(tmpRect, 270f, 90f, false)
        }
        // 下边 + 下两角
        if (roundBottom) {
            strokePath.moveTo(r, btm - rBR)
            tmpRect.set(r - 2 * rBR, btm - 2 * rBR, r, btm)
            strokePath.arcTo(tmpRect, 0f, 90f, false)
            strokePath.lineTo(l + rBL, btm)
            tmpRect.set(l, btm - 2 * rBL, l + 2 * rBL, btm)
            strokePath.arcTo(tmpRect, 90f, 90f, false)
        }
        canvas.drawPath(strokePath, strokePaint)
    }

    /**
     * 用与填色一致的路径报告轮廓。
     *
     * [android.graphics.Outline] 只能表达「单个半径的圆角矩形」或「Path」，
     * 而这里需要「上圆下直」这类形状，所以只有 API 30+ 的 `setPath` 能如实表达；
     * 低版本返回直角轮廓（不设圆角）——宁可裁剪得不圆，也不要四个角裁出缺口。
     */
    override fun getOutline(outline: android.graphics.Outline) {
        val b = bounds
        if (b.isEmpty) {
            outline.setEmpty()
            return
        }
        val single = position == IslandRowPosition.SINGLE
        val r = radiusPx.toInt()
        if (r <= 0 || (!roundTop && !roundBottom)) {
            outline.setRect(b)
            return
        }
        if (single) {
            outline.setRoundRect(b, radiusPx)
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            fillPath.reset()
            fillPath.addRoundRect(
                RectF(b),
                floatArrayOf(
                    if (roundTop) radiusPx else 0f, if (roundTop) radiusPx else 0f,
                    if (roundTop) radiusPx else 0f, if (roundTop) radiusPx else 0f,
                    if (roundBottom) radiusPx else 0f, if (roundBottom) radiusPx else 0f,
                    if (roundBottom) radiusPx else 0f, if (roundBottom) radiusPx else 0f,
                ),
                Path.Direction.CW,
            )
            outline.setPath(fillPath)
        } else {
            outline.setRect(b)
        }
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        strokePaint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        strokePaint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Drawable", ReplaceWith("PixelFormat.TRANSLUCENT"))
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getPadding(padding: Rect): Boolean = false
}
