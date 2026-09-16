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
 * 预设数值取自上游 WeKit `BeautifyConversationList`，用户已确认「沿用上游，和主页一样」，
 * 因此四个页面共用同一套预设与圆角/内缩参数，保证跨页面观感一致。
 */
enum class IslandPreset(
    val rowRadiusDp: Int,
    val horizontalInsetDp: Int,
    val verticalInsetDp: Int,
    val lightBackgroundColor: Int,
    val darkBackgroundColor: Int,
) {
    /** 不套布局，恢复微信原始外观。 */
    NO_LAYOUT(0, 0, 0, 0, 0),

    /** 每一行都是一个独立的圆角卡片。 */
    COMFORT_CARD(14, 10, 4, 0xFFF7FAF9.toInt(), 0xFF252827.toInt()),

    /** 置顶区与普通区各自成为一个圆角「岛」，组内相邻行紧贴、组间留缝。 */
    PINNED_GROUPED_CARD(14, 10, 4, 0xFFF7FAF9.toInt(), 0xFF252827.toInt()),

    /** 圆角更小、内缩更少的紧凑卡片。 */
    COMPACT_ROUNDED(10, 6, 2, 0xFFF9FBFA.toInt(), 0xFF272928.toInt()),

    /** 只有圆角与极窄内缩的极简列表。 */
    MINIMAL_LIST(6, 0, 0, 0xFFFCFCFC.toInt(), 0xFF232323.toInt()),
}

/** 一行在它所属「岛」里的位置，决定四个角哪些是圆的。 */
enum class IslandRowPosition { SINGLE, FIRST, MIDDLE, LAST }

/** 该预设是否使用「分组岛」语义（相邻行拼成一块连续圆角）。 */
val IslandPreset.isGrouped: Boolean
    get() = this == IslandPreset.PINNED_GROUPED_CARD

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
 */
fun styleIslandRow(
    row: View,
    preset: IslandPreset,
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
    val key = "${preset.name}|$position|$unread|${row.context.isDarkMode}"
    if (baseline.moduleKey != key || baseline.moduleBackground == null) {
        baseline.moduleBackground =
            ListIslandStyle.buildRowBackground(row.context, preset, position, unread)
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

/**
 * 按预设与行位置构造该行的背景。
 *
 * - 非分组预设：四角全圆、上下都留内缩，每行各自是一张卡片。
 * - 分组预设：组内首行圆上两角、末行圆下两角、中间行不圆，且只有组的两端留纵向内缩，
 *   于是「整组」拼成一块连续的圆角岛。
 */
object ListIslandStyle {

    private const val UNREAD_LIGHT = 0xFFEAF8F2.toInt()
    private const val UNREAD_DARK = 0xFF253E37.toInt()
    private const val STROKE_DARK = 0x22FFFFFF
    private const val STROKE_LIGHT = 0x16161D1C
    private const val RIPPLE_DARK = 0x2AFFFFFF
    private const val RIPPLE_LIGHT = 0x18006A62

    fun buildRowBackground(
        context: Context,
        preset: IslandPreset,
        position: IslandRowPosition,
        unread: Boolean,
    ): Drawable {
        val isDark = context.isDarkMode
        val card = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            if (preset.isGrouped) {
                setCornerRadii(cornerRadii(context, preset.rowRadiusDp, position))
            } else {
                cornerRadius = preset.rowRadiusDp.dpToPx(context).toFloat()
            }
            setColor(
                when {
                    unread && isDark -> UNREAD_DARK
                    unread -> UNREAD_LIGHT
                    isDark -> preset.darkBackgroundColor
                    else -> preset.lightBackgroundColor
                },
            )
            setStroke(1.dpToPx(context).coerceAtLeast(1), if (isDark) STROKE_DARK else STROKE_LIGHT)
        }

        val horizontalInset = preset.horizontalInsetDp.dpToPx(context)
        val verticalInset = preset.verticalInsetDp.dpToPx(context)
        val topInset = if (preset.isGrouped) {
            when (position) {
                IslandRowPosition.SINGLE, IslandRowPosition.FIRST -> verticalInset
                IslandRowPosition.MIDDLE, IslandRowPosition.LAST -> 0
            }
        } else {
            verticalInset
        }
        val bottomInset = if (preset.isGrouped) {
            when (position) {
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
}
