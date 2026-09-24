package com.github.wekite.ui.content.animation

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SweepGradient
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 弹窗卡片边缘的「旋转流光」动效（用户 2026-09-24 要求：围绕弹窗加一个旋转动效）。
 *
 * 观感 = 一道青 → 淡紫的彗尾光带，沿着卡片的圆角矩形边缘绕圈跑；跑了就再从起点来。
 * 用途见 [com.github.wekite.ui.content.AlertDialogContent] 的 `rotatingBorder` 参数
 * （当前只有「群聊新消息 AI 分析」的弹窗打开它）。
 *
 * 实现要点（踩坑记录）：
 * - **不改尺寸**：光带是画在卡片自身边缘上的（向内缩半个线宽），所以挂件/弹窗的几何
 *   （宽高、左右 12dp、上下 20mm/15mm 的留白）一个像素都不动。
 * - **只转渐变、不转画布**：`SweepGradient` 配 `Matrix.setRotate(angle, cx, cy)`。
 *   若改成 `DrawScope.rotate()` 转画布，圆角矩形会跟着被转成**倾斜的矩形**（错的）。
 * - **不用 `Brush.rotate` / `ShaderBrush`**：它们的 shader 按 size 缓存，角度变化可能不刷新；
 *   这里直接走 `canvas.nativeCanvas` + `android.graphics.Paint`，角度每帧生效。
 * - **柔光用「更粗+更低透明度」再画一遍**，不用 `BlurMaskFilter`（硬件加速下对线条不可靠）。
 * - ⚠️ 角度必须在 **draw 阶段**读取（`drawWithContent` 里读 `State.value`），这样只触发重绘、
 *   不触发重组，60fps 也便宜。
 */

/** 流光配色（与「群聊新消息 AI 分析」胶囊的蓝色梦幻同系）。 */
private val RING_HEAD = Color(0xFF7FD4FF)
private val RING_TAIL = Color(0xFFB07CFF)

/** 绕一圈的时长（毫秒）。 */
private const val RING_PERIOD_MS = 2600

/**
 * 旋转角度（0..360 无限循环）。
 * ⚠️ 只在真的要用时才调用（例如 `if (enabled) rememberRotatingRingAngle() else null`）——
 * 常开的无限动画即使不画也会一直占着帧回调。
 */
@Composable
fun rememberRotatingRingAngle(periodMs: Int = RING_PERIOD_MS): State<Float> {
    val transition = rememberInfiniteTransition()
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )
}

/**
 * 在自身内容之上画一圈旋转流光，紧贴圆角矩形边缘；不改布局尺寸。
 *
 * @param angle [rememberRotatingRingAngle] 的角度
 * @param cornerRadius 卡片圆角（要与被画的 surface 形状一致，否则光环会和边缘错开）
 * @param strokeWidth 亮线宽度
 * @param glow 是否再叠一圈更粗的柔光
 */
fun Modifier.rotatingBorderRing(
    angle: State<Float>,
    cornerRadius: Dp = 28.dp,
    strokeWidth: Dp = 3.dp,
    glow: Boolean = true
): Modifier = drawWithContent {
    drawContent()

    val stroke = strokeWidth.toPx()
    val w = size.width
    val h = size.height
    // 太小的卡片不画（避免算出负的圆角半径）
    if (w <= stroke * 3f || h <= stroke * 3f) return@drawWithContent

    val cx = w / 2f
    val cy = h / 2f
    val rotation = angle.value
    val canvas = drawContext.canvas.nativeCanvas

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    // 同一条光带画两遍：宽而淡（柔光）+ 窄而实（亮线）
    fun ring(width: Float, alphaScale: Float) {
        val inset = width / 2f
        val shader = SweepGradient(
            cx, cy,
            intArrayOf(
                argb(RING_HEAD, 0f),
                argb(RING_HEAD, alphaScale),
                argb(RING_TAIL, alphaScale),
                argb(RING_TAIL, 0f)
            ),
            // 0 → 0.09 → 0.23 → 0.47 为光带本身，0.47 ~ 1.0 留空（彗尾后的间隙）
            floatArrayOf(0f, 0.09f, 0.23f, 0.47f)
        )
        shader.setLocalMatrix(Matrix().apply { setRotate(rotation, cx, cy) })
        paint.shader = shader
        paint.strokeWidth = width
        val r = (cornerRadius.toPx() - inset).coerceAtLeast(0f)
        canvas.drawRoundRect(inset, inset, w - inset, h - inset, r, r, paint)
    }

    if (glow) ring(stroke * 2.6f, 0.30f)
    ring(stroke, 1f)
}

private fun argb(color: Color, alphaScale: Float): Int =
    color.copy(alpha = (color.alpha * alphaScale).coerceIn(0f, 1f)).toArgb()
