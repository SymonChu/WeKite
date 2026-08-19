package com.github.wekite.utils.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import com.github.wekite.utils.WeLogger
import kotlin.math.roundToInt

/**
 * 原生 View 液态玻璃背景: 捕获卡片背后(窗口级)内容 → 低分辨率 Bitmap → CPU 盒式模糊 →
 * 放大绘制(FILTER_BITMAP) → 半透明 tint 叠加。
 *
 * 全 Android 版本统一生效, 不依赖 GPU / RenderEffect (stub 与国产 ROM 兼容性双重考量)。
 *
 * 相对 v1.80/81 作废实现的安全改进(当年真机闪退):
 * 1. 不 recycle Bitmap —— 避免 "trying to use a recycled bitmap" 崩溃竞态
 * 2. 捕获期间不改卡片 visibility —— 避免 pre-draw 阶段重排导致状态混乱
 * 3. 捕获节流 ~10fps + isDirty gate —— 避免滚动时每帧全树绘制
 * 4. behind.draw 防御性 try-catch —— 微信树状态异常不向外抛
 *
 * behind 必须用 view.rootView (decorView) + 窗口坐标换算 (v1.81 教训:
 * view.parent 捕获到的是壁纸/空白, 不是卡片背后的真实消息内容)。
 */
class GlassSurfaceDrawable(
    private val glass: View,
    private val behind: View,
    private val captureScale: Float = 0.5f,
) : Drawable() {

    private companion object {
        const val TAG = "GlassSurfaceDrawable"
        /** 两次捕获的最小间隔: 滚动动画 ~60fps, 节流到 ~10fps 保主线程。 */
        const val MIN_CAPTURE_INTERVAL_MS = 100L
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint()

    private var bitmap: Bitmap? = null
    private var lastCaptureAt = 0L
    private var lastBounds: Rect? = null
    private var lastBlurRadius = -1
    private var listener: ViewTreeObserver.OnPreDrawListener? = null
    private var attached = false

    /** 模糊强度(屏幕像素半径), 内部换算成低分辨率图上的盒式模糊半径。 */
    var blurRadiusPx: Float = 20f

    /** 覆盖在模糊内容上的 tint 色 (ARGB), 暗色模式用半透明深色, 亮色用半透明白。 */
    var tintColor: Int = 0x55FFFFFF

    fun attach() {
        if (attached) return
        attached = true
        listener = ViewTreeObserver.OnPreDrawListener {
            recaptureIfDirty()
            true
        }
        behind.viewTreeObserver.addOnPreDrawListener(listener)
    }

    fun detach() {
        if (!attached) return
        attached = false
        listener?.let {
            runCatching { behind.viewTreeObserver.removeOnPreDrawListener(it) }
        }
        listener = null
        bitmap = null
        lastBounds = null
        lastBlurRadius = -1
        // 不 recycle: 交给 GC, 避免绘制竞态 "trying to use a recycled bitmap" 崩溃
    }

    private fun recaptureIfDirty() {
        if (!attached) return
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        val now = SystemClock.uptimeMillis()
        if (now - lastCaptureAt < MIN_CAPTURE_INTERVAL_MS) return
        val prev = lastBounds
        if (prev == b && !behind.isDirty) return
        lastCaptureAt = now

        val glassLoc = IntArray(2).also { glass.getLocationInWindow(it) }
        val behindLoc = IntArray(2).also { behind.getLocationInWindow(it) }
        val ox = glassLoc[0] - behindLoc[0]
        val oy = glassLoc[1] - behindLoc[1]
        if (ox + b.width() <= 0 || oy + b.height() <= 0) return

        val bw = (b.width() * captureScale).roundToInt().coerceAtLeast(2)
        val bh = (b.height() * captureScale).roundToInt().coerceAtLeast(2)
        var bmp = bitmap
        if (bmp == null || bmp.width != bw || bmp.height != bh) {
            bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bitmap = bmp
        }
        lastBounds = Rect(b)
        try {
            val canvas = Canvas(bmp)
            canvas.scale(captureScale, captureScale)
            canvas.translate(-ox.toFloat(), -oy.toFloat())
            behind.draw(canvas)
            // 低分辨率图上的盒式模糊半径: 屏幕半径 × 缩放, 两遍盒式 ≈ 高斯
            val radius = (blurRadiusPx * captureScale / 2f).roundToInt().coerceAtLeast(1)
            if (radius != lastBlurRadius) {
                lastBlurRadius = radius
            }
            if (radius > 1) boxBlur(bmp, radius)
            invalidateSelf()
        } catch (t: Throwable) {
            WeLogger.w(TAG, "capture failed: ${t.javaClass.simpleName}")
        }
    }

    /** 两遍盒式模糊(水平+垂直), 在低分辨率小图上 O(n·r), 微秒~毫秒级。 */
    private fun boxBlur(bmp: Bitmap, radius: Int) {
        val w = bmp.width
        val h = bmp.height
        val src = IntArray(w * h)
        bmp.getPixels(src, 0, w, 0, 0, w, h)
        val pass1 = IntArray(w * h)
        val out = IntArray(w * h)
        // 水平
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
                val x0 = (x - radius).coerceAtLeast(0)
                val x1 = (x + radius).coerceAtMost(w - 1)
                for (xx in x0..x1) {
                    val p = src[row + xx]
                    a += (p ushr 24) and 0xFF
                    r += (p ushr 16) and 0xFF
                    g += (p ushr 8) and 0xFF
                    b += p and 0xFF
                    n++
                }
                pass1[row + x] = (a / n shl 24) or (r / n shl 16) or (g / n shl 8) or (b / n)
            }
        }
        // 垂直
        for (y in 0 until h) {
            val y0 = (y - radius).coerceAtLeast(0)
            val y1 = (y + radius).coerceAtMost(h - 1)
            for (x in 0 until w) {
                var a = 0; var r = 0; var g = 0; var b = 0; var n = 0
                for (yy in y0..y1) {
                    val p = pass1[yy * w + x]
                    a += (p ushr 24) and 0xFF
                    r += (p ushr 16) and 0xFF
                    g += (p ushr 8) and 0xFF
                    b += p and 0xFF
                    n++
                }
                out[y * w + x] = (a / n shl 24) or (r / n shl 16) or (g / n shl 8) or (b / n)
            }
        }
        bmp.setPixels(out, 0, w, 0, 0, w, h)
    }

    override fun draw(canvas: Canvas) {
        val bmp = bitmap ?: return
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        canvas.drawBitmap(bmp, null, b, paint)
        if (Color.alpha(tintColor) > 0) {
            tintPaint.color = tintColor
            canvas.drawRect(b, tintPaint)
        }
    }

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(cf: ColorFilter?) {}

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
