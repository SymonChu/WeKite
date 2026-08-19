package com.github.wekite.utils.android

import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.View
import com.github.wekite.utils.WeLogger

/**
 * GPU 毛玻璃: 给原生 View 挂 RenderEffect (Android 12+, API 31) 的 backdrop 模糊,
 * 由 HWUI 渲染管线在 GPU 上实时完成 —— 零主线程捕获、零 Java 模糊循环, 滚动不卡。
 *
 * 全反射调用 android.graphics.RenderEffect, 绕开 stubs compileOnly 缺类导致的
 * 编译期 Unresolved reference (v1.74 曾因直接引用编译失败而退回纯 CPU 捕获实现)。
 *
 * 不可用 (API < 31 / 反射失败 / 国产 ROM 异常) 时 [applyGpu] 返回 false,
 * 调用方回退 CPU 捕获方案 [GlassSurfaceDrawable]。
 */
object GlassEffect {

    private const val TAG = "GlassEffect"

    private var supported: Boolean? = null
    private var setRenderEffect: java.lang.reflect.Method? = null
    private var createBlurEffect: java.lang.reflect.Method? = null
    private var createBackdropEffect: java.lang.reflect.Method? = null
    private var tileModeClamp: Any? = null

    /** 延迟解析一次, 缓存结果。 */
    private fun resolve(): Boolean {
        supported?.let { return it }
        if (Build.VERSION.SDK_INT < 31) {
            supported = false
            return false
        }
        supported = try {
            val renderEffect = Class.forName("android.graphics.RenderEffect")
            val tileMode = Class.forName("android.graphics.Shader\$TileMode")
            tileModeClamp = tileMode.getField("CLAMP").get(null)
            createBlurEffect = renderEffect.getMethod(
                "createBlurEffect", java.lang.Float.TYPE, java.lang.Float.TYPE, tileMode
            )
            createBackdropEffect = renderEffect.getMethod("createBackdropEffect", renderEffect)
            setRenderEffect = View::class.java.getMethod("setRenderEffect", renderEffect)
            true
        } catch (t: Throwable) {
            WeLogger.w(
                TAG,
                "RenderEffect unavailable, falling back to CPU glass: ${t.javaClass.simpleName}"
            )
            false
        }
        return supported!!
    }

    /**
     * 应用 GPU 毛玻璃: 背景换成半透明 tint, 背后内容由 RenderEffect backdrop blur 实时模糊。
     *
     * @param blurRadiusPx 模糊半径 (屏幕像素, 由 pref 的 dp 值 × density 换算)
     * @param tintArgb 半透明叠加色 (暗色模式深色 / 亮色模式白色)
     * @return true = GPU 已接管 view 背景; false = 不可用, 调用方需回退 CPU 方案
     */
    fun applyGpu(view: View, blurRadiusPx: Float, tintArgb: Int): Boolean {
        if (!resolve()) return false
        return try {
            val radius = blurRadiusPx.coerceAtLeast(1f)
            val blur = createBlurEffect!!.invoke(null, radius, radius, tileModeClamp)
            val backdrop = createBackdropEffect!!.invoke(null, blur)
            setRenderEffect!!.invoke(view, backdrop)
            val current = view.background
            if (current !is ColorDrawable || current.color != tintArgb) {
                view.background = ColorDrawable(tintArgb)
            }
            true
        } catch (t: Throwable) {
            WeLogger.w(TAG, "applyGpu failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /** 清除 GPU 毛玻璃 (恢复无 renderEffect)。 */
    fun clearGpu(view: View) {
        if (!resolve()) return
        runCatching { setRenderEffect!!.invoke(view, null) }
    }
}
