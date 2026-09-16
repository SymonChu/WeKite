package com.github.wekite.ui.utils

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import com.github.wekite.utils.android.isDarkMode
import java.util.WeakHashMap

/**
 * 「列表圆角岛」的视觉规格。
 *
 * ⚠️ v3.21 起**卡片样式与分组是两个独立维度**（用户要求「置顶分组岛要与其他几个选项
 * 同时可以生效，而不是一个单独的功能」）：
 *   - [IslandShape]  决定圆角大小 / 内缩 / 底色 —— 「每个岛长什么样」
 *   - [grouped]      决定相邻同组行是否拼成一块 —— 「分成几块」
 * 两者自由组合，例：圆角卡片 × 分组开 = 置顶区一个岛 + 普通区一个岛；
 * 圆角卡片 × 分组关 = 每一行各自一张卡片。
 */
enum class IslandShape(
    val rowRadiusDp: Int,
    val horizontalInsetDp: Int,
    val verticalInsetDp: Int,
    val lightBackgroundColor: Int,
    val darkBackgroundColor: Int,
) {
    /** 舒适卡片：圆角 14dp、左右内缩 10dp、上下内缩 4dp。 */
    ROUNDED_CARD(14, 10, 4, 0xFFF7FAF9.toInt(), 0xFF252827.toInt()),

    /** 紧凑圆角：圆角更小、内缩更少、底色更接近纯白。 */
    COMPACT(10, 6, 2, 0xFFF9FBFA.toInt(), 0xFF272928.toInt()),

    /** 极简：只有轻微圆角，几乎不留白。 */
    MINIMAL(6, 0, 0, 0xFFFCFCFC.toInt(), 0xFF232323.toInt()),
}

/** 一行在它所属「岛」里的位置，决定四个角哪些是圆的。 */
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
 * 给一行套上圆角岛外观。同一行反复调用是安全的：指纹没变就复用已构造的 Drawable。
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
 *   且只在组的两端留纵向内缩 —— 「整组」拼成一块连续的圆角岛。
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

    val card = GradientDrawable().apply {
        this.shape = GradientDrawable.RECTANGLE
        if (grouped) {
            setCornerRadii(cornerRadii(context, shape.rowRadiusDp, effectivePosition))
        } else {
            cornerRadius = shape.rowRadiusDp.dpToPx(context).toFloat()
        }
        setColor(
            when {
                unread && isDark -> UNREAD_DARK
                unread -> UNREAD_LIGHT
                isDark -> shape.darkBackgroundColor
                else -> shape.lightBackgroundColor
            },
        )
        setStroke(1.dpToPx(context).coerceAtLeast(1), if (isDark) STROKE_DARK else STROKE_LIGHT)
    }

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

/** 八个角半径：左上、右上、右下、左下 各两个分量。 */
private fun cornerRadii(
    context: Context,
    radiusDp: Int,
    position: IslandRowPosition,
): FloatArray {
    val radius = radiusDp.dpToPx(context).toFloat()
    val zero = 0f
    return when (position) {
        IslandRowPosition.SINGLE ->
            floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
        IslandRowPosition.FIRST ->
            floatArrayOf(radius, radius, radius, radius, zero, zero, zero, zero)
        IslandRowPosition.MIDDLE ->
            floatArrayOf(zero, zero, zero, zero, zero, zero, zero, zero)
        IslandRowPosition.LAST ->
            floatArrayOf(zero, zero, zero, zero, radius, radius, radius, radius)
    }
}

/**
 * 把一串「已按顺序排好的行」按分组边界批量套用样式。
 *
 * @param isGroupStart 第 i 行是否为某个新分组的**第一行**（即它前面是分组边界）
 */
fun styleIslandRows(
    rows: List<View>,
    shape: IslandShape,
    grouped: Boolean,
    isGroupStart: (Int) -> Boolean,
) {
    if (rows.isEmpty()) return
    var groupStart = 0
    for (index in rows.indices) {
        if (index > 0 && isGroupStart(index)) groupStart = index
        val isFirst = index == groupStart
        val isLast = index == rows.lastIndex || isGroupStart(index + 1)
        val position = when {
            isFirst && isLast -> IslandRowPosition.SINGLE
            isFirst -> IslandRowPosition.FIRST
            isLast -> IslandRowPosition.LAST
            else -> IslandRowPosition.MIDDLE
        }
        styleIslandRow(rows[index], shape, grouped, position)
    }
}
