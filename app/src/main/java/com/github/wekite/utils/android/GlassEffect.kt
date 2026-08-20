package com.github.wekite.utils.android

import android.os.Build
import android.view.View

/** Best-effort Android 12+ GPU backdrop blur, resolved entirely by reflection. */
internal object GlassEffect {
    private var resolved = false
    private var renderEffectClass: Class<*>? = null
    private var setRenderEffect: java.lang.reflect.Method? = null
    private var createBlurEffect: java.lang.reflect.Method? = null
    private var createBackdropEffect: java.lang.reflect.Method? = null
    private var tileModeClamp: Any? = null

    private fun resolve() {
        if (resolved) return
        resolved = true
        if (Build.VERSION.SDK_INT < 31) return
        runCatching {
            renderEffectClass = Class.forName("android.graphics.RenderEffect")
            val shaderClass = Class.forName("android.graphics.Shader\$TileMode")
            tileModeClamp = shaderClass.getField("CLAMP").get(null)
            setRenderEffect = View::class.java.getMethod("setRenderEffect", renderEffectClass)
            createBlurEffect = renderEffectClass!!.getMethod(
                "createBlurEffect", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType, shaderClass
            )
            createBackdropEffect = renderEffectClass!!.getMethod("createBackdropEffect", renderEffectClass)
        }
    }

    fun apply(view: View, blurRadiusPx: Float): Boolean {
        resolve()
        if (Build.VERSION.SDK_INT < 31) return false
        return runCatching {
            val blur = createBlurEffect?.invoke(null, blurRadiusPx, blurRadiusPx, tileModeClamp)
            val backdrop = createBackdropEffect?.invoke(null, blur)
            setRenderEffect?.invoke(view, backdrop)
            blur != null && backdrop != null
        }.getOrDefault(false)
    }

    fun clear(view: View) {
        resolve()
        runCatching { setRenderEffect?.invoke(view, null) }
    }
}
