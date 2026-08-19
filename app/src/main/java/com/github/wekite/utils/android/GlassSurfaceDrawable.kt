package com.github.wekite.utils.android

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import com.github.wekite.utils.WeLogger
import java.lang.reflect.Proxy
import kotlin.math.roundToInt

/**
 * 原生 View 液态玻璃背景 —— 与模块自身界面 (miuix / AndroidLiquidGlass 管线) 同构:
 * **降频捕获背后真实像素 → GPU 模糊 (RenderEffect on RenderNode) → 放大绘制 → tint 叠加**。
 *
 * 捕获用 **PixelCopy** (API 26+): 直接从窗口 Surface 拷贝 GPU 合成后的真实渲染像素,
 * 含所有硬件层/TextureView —— 解决 v1.74~v1.76 输入框玻璃不显示的根因:
 * 旧实现 `behind.draw(softwareCanvas)` 绘制微信 View 树时, 树内有 View 重写 draw
 * 调 `Canvas.drawRenderNode` (硬件 API), 软件 Canvas 上必抛 IllegalArgumentException
 * ("Software rendering doesn't support drawRenderNode"), 捕获每帧失败 → bitmap 恒空。
 * 日志特征: 每 ~150ms 一条 `capture failed: IllegalArgumentException`。
 * PixelCopy 不可用时回退旧 View.draw 软件捕获 (try-catch 防御, 多数设备不会走)。
 *
 * 频率: dirty gate + 150ms 节流 + 串行 (上一次拷贝未完成不叠加), 静止零开销。
 * 模糊: API 31+ 且硬件 canvas 走 GPU (RenderNode + RenderEffect, 反射绕 stub),
 * 否则 CPU 两遍盒式模糊 (捕获回调里做)。
 *
 * 安全设计 (v1.80/81 闪退史教训): 不 recycle 自持 bitmap (交给 GC); 捕获期间
 * 只临时摘 background (不碰 visibility, 不触发重排); 全流程 try-catch 不外抛。
 */
class GlassSurfaceDrawable(
    private val glass: View,
    private val behind: View,
    private val captureScale: Float = 0.5f,
) : Drawable() {

    private companion object {
        const val TAG = "GlassSurfaceDrawable"

        /** 两次捕获的最小间隔: 滚动时 ~2.5fps 即可, 静止零开销 (iOS 毛玻璃同思路,
         *  再短会导致滚动时捕获+重绘太频繁 → 卡顿, v1.77 实测)。 */
        const val MIN_CAPTURE_INTERVAL_MS = 400L

        /** 软件捕获连续失败多少次后熔断 (微信 View 树含 RuntimeShader, 软件 Canvas 必炸)。
         *  不熔断的话每 400ms × 2 视图的全树软件绘制尝试 = 失败风暴, 主线程卡顿元凶。 */
        const val SOFTWARE_CAPTURE_MAX_FAILURES = 5

        /** 熔断后至少等这么久才重试 (视图尺寸大变化时立即重试)。 */
        const val SOFTWARE_CAPTURE_RETRY_MS = 30_000L
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val tintPaint = Paint()

    private var bitmap: Bitmap? = null
    private var lastCaptureAt = 0L
    private var lastBounds: Rect? = null
    private var listener: ViewTreeObserver.OnPreDrawListener? = null
    private var attached = false

    /** PixelCopy 串行门: 上一次拷贝未完成不再发起新请求。 */
    private var pendingCopy = false

    /** 捕获期间摘掉的玻璃背景 (防上一次模糊帧被画进这次的拷贝 → 反馈发白), 回调里恢复。 */
    private var savedGlassBg: Drawable? = null

    /** 软件捕获连续失败计数 + 熔断时间戳 (微信树 RuntimeShader 必炸时停止全树绘制尝试)。 */
    private var softwareCaptureFailures = 0
    private var softwareCaptureDisabledAt = 0L

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
        pendingCopy = false
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
        // 上一次 PixelCopy 未完成时不叠加发起 (回调会置回 false)
        if (pendingCopy) return

        if (PixelCopyCompat.request(glass, this::onPixels)) {
            pendingCopy = true
            // 捕获期间摘掉玻璃背景: 防把上一次的模糊帧画进这次的拷贝 (反馈发白, v1.74 教训)。
            // 只动 background 不碰 visibility, 不触发重排; onPixels 回调里恢复。
            savedGlassBg = glass.background
            glass.background = null
            return
        }
        // PixelCopy 不可用/失败: 回退 View.draw 软件捕获。熔断期间暂停尝试,
        // 防每 400ms 全树软件绘制风暴 (微信树 RuntimeShader 必炸); 尺寸大变化 (键盘/面板) 立即重试。
        if (softwareCaptureDisabledAt != 0L) {
            val sizeChanged = prev == null ||
                prev.width() != b.width() || prev.height() != b.height()
            if (sizeChanged) softwareCaptureDisabledAt = 0L
            else if (SystemClock.uptimeMillis() - softwareCaptureDisabledAt < SOFTWARE_CAPTURE_RETRY_MS) return
        }
        captureViaViewDraw(b)
    }

    private fun onPixels(result: Int, src: Bitmap?) {
        pendingCopy = false
        // 恢复捕获期间摘掉的玻璃背景; 微信若已重贴其它背景则不覆盖, 由 recaptureIfDirty 守护重挂
        if (glass.background == null && savedGlassBg != null) {
            glass.background = savedGlassBg
        }
        savedGlassBg = null
        if (result != 0 || src == null) {
            WeLogger.w(TAG, "pixelcopy result=$result bitmap=${src != null} (${glass.javaClass.simpleName})")
            return
        }
        val b = bounds
        if (b.width() <= 0 || b.height() <= 0) return
        try {
            val sw = (src.width * captureScale).roundToInt().coerceAtLeast(2)
            val sh = (src.height * captureScale).roundToInt().coerceAtLeast(2)
            val small = if (sw == src.width && sh == src.height) src
            else Bitmap.createScaledBitmap(src, sw, sh, true)
            if (small !== src) src.recycle()  // PixelCopy 每次回调新 bitmap, 副本已建即可回收
            bitmap = small
            lastBounds = Rect(b)
            if (!gpuBlur.available) {
                val radius = (blurRadiusPx * captureScale / 2f).roundToInt().coerceAtLeast(1)
                if (radius > 1) boxBlur(small, radius)
            }
            WeLogger.d(
                TAG,
                "pixelcopy ok: src=${src.width}x${src.height} stored=${small.width}x${small.height} " +
                    "bounds=${b.width()}x${b.height()} (${glass.javaClass.simpleName})"
            )
            invalidateSelf()
        } catch (t: Throwable) {
            WeLogger.w(TAG, "onPixels failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 回退路径: 软件 Canvas 全树绘制 (PixelCopy 不可用/异常时)。 */
    private fun captureViaViewDraw(b: Rect) {
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
        val savedBg = glass.background
        glass.background = null
        try {
            val canvas = Canvas(bmp)
            canvas.scale(captureScale, captureScale)
            canvas.translate(-ox.toFloat(), -oy.toFloat())
            behind.draw(canvas)
            if (!gpuBlur.available) {
                val radius = (blurRadiusPx * captureScale / 2f).roundToInt().coerceAtLeast(1)
                if (radius > 1) boxBlur(bmp, radius)
            }
            softwareCaptureFailures = 0
            invalidateSelf()
        } catch (t: Throwable) {
            WeLogger.w(TAG, "view-draw capture failed: ${t.javaClass.simpleName}: ${t.message}")
            if (++softwareCaptureFailures >= SOFTWARE_CAPTURE_MAX_FAILURES) {
                softwareCaptureDisabledAt = SystemClock.uptimeMillis()
                WeLogger.w(
                    TAG,
                    "software capture disabled: $softwareCaptureFailures consecutive failures"
                )
            }
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
        // bitmap 尺寸可能与 bounds×captureScale 不完全一致 (PixelCopy rect 裁剪),
        // 按实际比例放大; 模糊半径按放大倍数换算到 node 像素空间。
        val scale = b.width().toFloat() / bmp.width.coerceAtLeast(1)
        val radius = (blurRadiusPx / scale).roundToInt().coerceAtLeast(1)
        val key = GpuKey(bmp, radius, b.width(), b.height())
        return try {
            if (gpuNode == null || gpuNodeKey != key) {
                val node = gpuNode ?: gpuBlur.createRenderNode().also { gpuNode = it }
                gpuBlur.setPosition(node, 0, 0, b.width(), b.height())
                val rc = gpuBlur.beginRecording(node, b.width(), b.height())
                rc.scale(scale, scale)
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
 * PixelCopy 反射封装 (API 26+): 从窗口 Surface 拷贝真实渲染像素。
 * stubs compileOnly 缺类, 全反射调用; [request] 返回 false 时调用方回退 View.draw。
 */
private object PixelCopyCompat {

    private const val TAG = "PixelCopyCompat"
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 旧签名 (API 26-35): request(Window, Rect, listener, Handler), 回调带 (result, bitmap)。 */
    private const val VARIANT_CALLBACK_BITMAP = 1

    /** 新签名 (API 36+): request(Window, Rect, Bitmap dest, listener, Handler),
     *  必须预分配目标 Bitmap, 回调只有 result 码。v1.78 死磕旧签名在用户手机
     *  (SDK 36) 上 NoSuchMethodException → 回退软件捕获又被微信树 RuntimeShader
     *  打死 → 玻璃不显示 + 失败风暴卡顿, 日志铁证。 */
    private const val VARIANT_DEST_BITMAP = 2

    private var available: Boolean? = null
    private var requestMethod: java.lang.reflect.Method? = null
    private var listenerClass: Class<*>? = null
    private var variant = 0

    private fun resolve(): Boolean {
        available?.let { return it }
        available = try {
            listenerClass = Class.forName("android.view.PixelCopy\$OnPixelCopyFinishedListener")
            val pixelCopy = Class.forName("android.view.PixelCopy")
            if (Build.VERSION.SDK_INT >= 36) {
                requestMethod = pixelCopy.getMethod(
                    "request", Window::class.java, Rect::class.java, Bitmap::class.java,
                    listenerClass, Handler::class.java
                )
                variant = VARIANT_DEST_BITMAP
            } else {
                requestMethod = pixelCopy.getMethod(
                    "request", Window::class.java, Rect::class.java, listenerClass, Handler::class.java
                )
                variant = VARIANT_CALLBACK_BITMAP
            }
            true
        } catch (t: Throwable) {
            WeLogger.w(TAG, "PixelCopy unavailable: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
        return available!!
    }

    /**
     * 请求拷贝 [glass] 所在窗口的对应区域像素; 回调在 [onDone] (主线程)。
     * @return true = 请求已发起 (结果异步到 onDone); false = 不可用/发起失败 (调用方回退 View.draw)
     */
    fun request(
        glass: View,
        onDone: (result: Int, bitmap: Bitmap?) -> Unit,
    ): Boolean {
        if (Build.VERSION.SDK_INT < 26 || !resolve()) {
            WeLogger.w(TAG, "pixelcopy unavailable sdk=${Build.VERSION.SDK_INT}")
            return false
        }
        val w = glass.width
        val h = glass.height
        if (w <= 0 || h <= 0) {
            WeLogger.w(TAG, "pixelcopy skipped: view size ${w}x${h}")
            return false
        }
        // 微信 View 的 context 可能是 ContextWrapper 链, 逐层解包找 Activity
        val activity = unwrapActivity(glass.context)
        if (activity == null) {
            WeLogger.w(TAG, "pixelcopy skipped: no activity in context chain " +
                "(${glass.context.javaClass.name})")
            return false
        }
        val window = activity.window ?: run {
            WeLogger.w(TAG, "pixelcopy skipped: activity window null")
            return false
        }
        val loc = IntArray(2).also { glass.getLocationInWindow(it) }
        val rect = Rect(loc[0], loc[1], loc[0] + w, loc[1] + h)
        val decor = window.decorView
        rect.intersect(0, 0, decor.width, decor.height)
        if (rect.isEmpty) {
            WeLogger.w(TAG, "pixelcopy skipped: rect empty after intersect")
            return false
        }

        // SDK 36+ 必须预分配 dest Bitmap (mutable, 尺寸与 srcRect 一致, 内容缩放适配)
        val dest = if (variant == VARIANT_DEST_BITMAP) {
            Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
        } else null
        val listener = Proxy.newProxyInstance(
            listenerClass!!.classLoader,
            arrayOf(listenerClass),
        ) { _, method, args ->
            if (method.name == "onPixelCopyFinished") {
                if (variant == VARIANT_DEST_BITMAP) {
                    onDone(args[0] as Int, dest)
                } else {
                    onDone(args[0] as Int, args[1] as? Bitmap)
                }
            }
            null
        }
        return try {
            if (variant == VARIANT_DEST_BITMAP) {
                requestMethod!!.invoke(null, window, rect, dest, listener, mainHandler)
            } else {
                requestMethod!!.invoke(null, window, rect, listener, mainHandler)
            }
            true
        } catch (t: Throwable) {
            WeLogger.w(TAG, "pixelcopy request failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** 逐层解包 ContextWrapper 链, 找到 Activity (微信 View 的 context 常被包装)。 */
    private fun unwrapActivity(context: android.content.Context): Activity? {
        var c: android.content.Context = context
        while (true) {
            if (c is Activity) return c
            if (c !is android.content.ContextWrapper) return null
            c = c.baseContext
        }
    }
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

    /** API 29+ 是 public 构造器 RenderNode(String); 更早 @hide, getDeclaredConstructor + setAccessible。
     *  老代码找 create(String, RenderNode) —— 该签名从来不存在 (实际是 create(String, AnimationHost)),
     *  任何 SDK 都 NoSuchMethodException, GPU 路径从 v1.76 起一直在静默走 CPU 兜底。 */
    private var renderNodeCtor: java.lang.reflect.Constructor<*>? = null
    private var setPosition: java.lang.reflect.Method? = null
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
            renderNodeCtor = runCatching { renderNode.getConstructor(String::class.java) }
                .getOrElse { renderNode.getDeclaredConstructor(String::class.java) }
            renderNodeCtor!!.isAccessible = true
            setPosition = renderNode.getMethod(
                "setPosition",
                java.lang.Integer.TYPE, java.lang.Integer.TYPE,
                java.lang.Integer.TYPE, java.lang.Integer.TYPE
            )
            // setContentSize 在 API 34/36 的 RenderNode 里都不存在 (老代码会 NoSuchMethodException);
            // 内容尺寸由 beginRecording(w, h) 携带。
            beginRecording = renderNode.getMethod(
                "beginRecording", java.lang.Integer.TYPE, java.lang.Integer.TYPE
            )
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
        renderNodeCtor!!.newInstance("WeKiteGlass")

    fun setPosition(node: Any, l: Int, t: Int, r: Int, b: Int) {
        setPosition!!.invoke(node, l, t, r, b)
    }

    fun beginRecording(node: Any, w: Int, h: Int): Canvas =
        beginRecording!!.invoke(node, w, h) as Canvas

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
