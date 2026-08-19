package com.github.wekite.utils.android

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import com.github.wekite.utils.WeLogger
import kotlin.math.roundToInt

/**
 * 原生 View 液态玻璃背景 —— 与模块自身界面 (miuix / AndroidLiquidGlass 管线) 同构:
 * **降频捕获背后窗口内容 → GPU 模糊 (RenderEffect on RenderNode) → 放大绘制 → tint 叠加**。
 *
 * 捕获: OnPreDrawListener 挂在 behind (rootView) 上, 仅 dirty 时重捕获 + 150ms 节流
 * (滚动时 ~6fps, 静止零开销) —— 全树绘制的 CPU 成本是卡顿大头, 必须降频。
 *
 * 模糊: Android 12+ (API 31) 且硬件 canvas 时走 GPU —— RenderNode 缓存复用,
 * 捕获的 0.5x bitmap 放大录进 RenderNode, 挂 createBlurEffect, 硬件渲染器 GPU 模糊。
 * 反射调用绕开 stubs compileOnly 缺类 (v1.74 直接引用 RenderEffect 编译失败的教训)。
 * API < 31 或软件 canvas 回退 CPU 两遍盒式模糊 (v1.74 实现, 捕获时完成)。
 *
 * 相对 v1.80/81 作废实现的安全改进 (当年真机闪退):
 * 1. 不 recycle Bitmap —— 避免 "trying to use a recycled bitmap" 崩溃竞态
 * 2. 捕获期间只临时摘 background (不碰 visibility) —— 不触发重排, 避免 pre-draw 状态混乱
 * 3. 捕获节流 + isDirty gate —— 避免滚动时每帧全树绘制
 * 4. behind.draw 防御性 try-catch —— 微信树状态异常不向外抛
 * 5. 背景守护 —— 微信覆盖 background 时 (输入框键盘/面板切换) 自动重挂
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

        /** 两次捕获的最小间隔: 全树绘制是主线程大头, 滚动时 ~6fps 即可 (iOS 毛玻璃同思路)。 */
        const val MIN_CAPTURE_INTERVAL_MS = 150L
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint()

    private var bitmap: Bitmap? = null
    private var lastCaptureAt = 0L
    private var lastBounds: Rect? = null
    private var listener: ViewTreeObserver.OnPreDrawListener? = null
    private var attached = false

    /** 模糊强度(屏幕像素半径)。 */
    var blurRadiusPx: Float = 20f

    /** 覆盖在模糊内容上的 tint 色 (ARGB), 暗色模式用半透明深色, 亮色用半透明白。 */
    var tintColor: Int = 0x55FFFFFF

    // ---- GPU 模糊 (RenderNode + RenderEffect) ----
    private val gpuBlur = GpuBlur.INSTANCE
    private var gpuNode: Any? = null
    private var gpuNodeKey: GpuKey? = null
    private var gpuWarned = false

    private data class GpuKey(val bmp: Bitmap?, val radius: Int, val w: Int, val h: Int)

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
        gpuNodeKey = null
        if (gpuNode != null) {
            runCatching { gpuBlur.discardDisplayList(gpuNode!!) }
            gpuNode = null
        }
        // 不 recycle: 交给 GC, 避免绘制竞态 "trying to use a recycled bitmap" 崩溃
    }

    private fun recaptureIfDirty() {
        if (!attached) return
        // 背景守护: 微信可能在键盘/面板切换时覆盖 glass 背景, 被换就重挂
        if (glass.background !== this) {
            glass.background = this
            WeLogger.w(TAG, "background replaced by host, re-attached")
        }
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
        // 捕获期间把玻璃自身背景临时摘掉, 防止把上次的模糊结果画进这次捕获
        // (v1.74 输入框"发白/脏"根因; 只动 background 不碰 visibility, 不触发重排)
        val savedBg = glass.background
        glass.background = null
        try {
            val canvas = Canvas(bmp)
            canvas.scale(captureScale, captureScale)
            canvas.translate(-ox.toFloat(), -oy.toFloat())
            behind.draw(canvas)
            // CPU 兜底路径的模糊在捕获时做 (GPU 路径交给绘制时的 RenderEffect)
            if (!gpuBlur.available) {
                // 低分辨率图上的盒式模糊半径: 屏幕半径 × 缩放, 两遍盒式 ≈ 高斯
                val radius = (blurRadiusPx * captureScale / 2f).roundToInt().coerceAtLeast(1)
                if (radius > 1) boxBlur(bmp, radius)
            }
            invalidateSelf()
        } catch (t: Throwable) {
            WeLogger.w(TAG, "capture failed: ${t.javaClass.simpleName}")
        } finally {
            glass.background = savedBg
        }
    }

    override fun draw(canvas: Canvas) {
        val bmp = bitmap ?: return
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        if (gpuBlur.available && canvas.isHardwareAccelerated && drawGpu(canvas, bmp, b)) {
            drawTint(canvas, b)
            return
        }
        // CPU 路径: 放大绘制 (模糊已在捕获时完成)
        canvas.drawBitmap(bmp, null, b, paint)
        drawTint(canvas, b)
    }

    private fun drawGpu(canvas: Canvas, bmp: Bitmap, b: Rect): Boolean {
        val radius = blurRadiusPx.roundToInt().coerceAtLeast(1)
        val key = GpuKey(bmp, radius, b.width(), b.height())
        return try {
            if (gpuNode == null || gpuNodeKey != key) {
                val node = gpuNode ?: gpuBlur.createRenderNode().also { gpuNode = it }
                gpuBlur.setPosition(node, 0, 0, b.width(), b.height())
                gpuBlur.setContentSize(node, b.width(), b.height())
                val rc = gpuBlur.beginRecording(node)
                rc.scale(1f / captureScale, 1f / captureScale)
                rc.drawBitmap(bmp, 0f, 0f, paint)
                gpuBlur.endRecording(node)
                gpuBlur.setRenderEffect(node, gpuBlur.createBlur(radius.toFloat(), radius.toFloat()))
                gpuNodeKey = key
            }
            gpuBlur.drawRenderNode(canvas, gpuNode!!)
            true
        } catch (t: Throwable) {
            if (!gpuWarned) {
                gpuWarned = true
                WeLogger.w(TAG, "gpu blur failed, cpu fallback: ${t.javaClass.simpleName}")
            }
            false
        }
    }

    private fun drawTint(canvas: Canvas, b: Rect) {
        if (Color.alpha(tintColor) > 0) {
            tintPaint.color = tintColor
            canvas.drawRect(b, tintPaint)
        }
    }

    /** 两遍盒式模糊(水平+垂直), 在低分辨率小图上 O(n·r), CPU 兜底路径用。 */
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

    override fun setAlpha(alpha: Int) {}

    override fun setColorFilter(cf: ColorFilter?) {}

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/**
 * RenderNode + RenderEffect 的 GPU 模糊反射工具 (Android 12+, API 31)。
 *
 * 全反射调用 android.graphics.RenderNode / RenderEffect —— stubs compileOnly 缺类,
 * 直接引用会报 Unresolved reference (v1.74 教训); 类名/方法名全用字符串,
 * resolve 失败时 [available] = false, 调用方回退 CPU 盒式模糊。
 */
private object GpuBlur {

    val INSTANCE = this
    val available: Boolean by lazy { resolve() }

    private var createRenderNode: java.lang.reflect.Method? = null
    private var setPosition: java.lang.reflect.Method? = null
    private var setContentSize: java.lang.reflect.Method? = null
    private var beginRecording: java.lang.reflect.Method? = null
    private var endRecording: java.lang.reflect.Method? = null
    private var setRenderEffect: java.lang.reflect.Method? = null
    private var discardDisplayList: java.lang.reflect.Method? = null
    private var drawRenderNode: java.lang.reflect.Method? = null
    private var createBlurEffect: java.lang.reflect.Method? = null
    private var tileModeClamp: Any? = null

    private const val TAG = "GpuBlur"

    private fun resolve(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return false
        return try {
            val renderNode = Class.forName("android.graphics.RenderNode")
            val renderEffect = Class.forName("android.graphics.RenderEffect")
            val tileMode = Class.forName("android.graphics.Shader\$TileMode")
            tileModeClamp = tileMode.getField("CLAMP").get(null)
            createRenderNode = renderNode.getMethod("create", String::class.java, renderNode)
            setPosition = renderNode.getMethod(
                "setPosition",
                java.lang.Integer.TYPE, java.lang.Integer.TYPE,
                java.lang.Integer.TYPE, java.lang.Integer.TYPE
            )
            setContentSize = renderNode.getMethod(
                "setContentSize", java.lang.Integer.TYPE, java.lang.Integer.TYPE
            )
            beginRecording = renderNode.getMethod("beginRecording")
            endRecording = renderNode.getMethod("endRecording")
            setRenderEffect = renderNode.getMethod("setRenderEffect", renderEffect)
            discardDisplayList = renderNode.getMethod("discardDisplayList")
            drawRenderNode = Canvas::class.java.getMethod("drawRenderNode", renderNode)
            createBlurEffect = renderEffect.getMethod(
                "createBlurEffect", java.lang.Float.TYPE, java.lang.Float.TYPE, tileMode
            )
            true
        } catch (t: Throwable) {
            WeLogger.w(TAG, "RenderNode GPU blur unavailable, cpu fallback: ${t.javaClass.simpleName}")
            false
        }
    }

    fun createRenderNode(): Any =
        createRenderNode!!.invoke(null, "WeKiteGlass", null)

    fun setPosition(node: Any, l: Int, t: Int, r: Int, b: Int) {
        setPosition!!.invoke(node, l, t, r, b)
    }

    fun setContentSize(node: Any, w: Int, h: Int) {
        setContentSize!!.invoke(node, w, h)
    }

    fun beginRecording(node: Any): Canvas = beginRecording!!.invoke(node) as Canvas

    fun endRecording(node: Any) {
        endRecording!!.invoke(node)
    }

    fun setRenderEffect(node: Any, effect: Any) {
        setRenderEffect!!.invoke(node, effect)
    }

    fun discardDisplayList(node: Any) {
        discardDisplayList!!.invoke(node)
    }

    fun drawRenderNode(canvas: Canvas, node: Any) {
        drawRenderNode!!.invoke(canvas, node)
    }

    fun createBlur(rx: Float, ry: Float): Any =
        createBlurEffect!!.invoke(null, rx, ry, tileModeClamp)
}
