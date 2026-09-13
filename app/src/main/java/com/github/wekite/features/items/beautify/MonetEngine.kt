package com.github.wekite.features.items.beautify

import android.content.res.ColorStateList
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import java.util.concurrent.atomic.AtomicInteger
import com.github.wekite.features.core.ApiFeature
import com.github.wekite.features.core.Feature
import com.github.wekite.ui.utils.theme.SeedResolver
import com.github.wekite.ui.utils.theme.ThemeSettings
import com.github.wekite.utils.HostInfo
import com.github.wekite.utils.WeLogger
import com.github.wekite.utils.android.isDarkMode

/**
 * Recolors the parts of WeChat that hardcode the brand green ([DEFAULT_COLOR], 0xFF07C160) so they
 * follow the user's custom color instead. Driven entirely by the module's theme settings — it is
 * NOT a user-toggleable feature (hence [ApiFeature] + the API category, so it stays out of the
 * feature list). The hooks only install when the user opted their custom color into WeChat
 * ([ThemeSettings.applyToWechat] with 自定义颜色 on); the accent comes from the same seed the injected
 * WeKite UI uses ([SeedResolver.customSeed] → the wallpaper accent or the chosen seed color, run
 * through the selected palette style + color spec). Colors are resolved once per WeChat launch
 * (restart required for a change to apply).
 */
@Feature(name = "莫奈引擎", categories = ["API"], description = "根据模块设置的自定义配色为微信原生组件上色")
object MonetEngine : ApiFeature() {

    private const val TAG = "MonetEngine"

    /** WeChat's hardcoded brand green — the pixels we replace. */
    private const val DEFAULT_COLOR = -16268960 // 0xFF07C160

    private val scheme by lazy {
        try {
            val dark = HostInfo.application.isDarkMode
            SeedResolver.materialScheme(SeedResolver.customSeed(HostInfo.application, dark), dark)
        } catch (e: Exception) {
            WeLogger.w(TAG, "failed to resolve monet scheme, device may not support dynamic colors", e)
            throw e
        }
    }

    /** Accent that replaces the brand green (M3 `primary`). */
    private val primaryColor by lazy {
        try {
            scheme.primary.toArgb()
        } catch (e: Exception) {
            WeLogger.w(TAG, "failed to get primary color from scheme", e)
            DEFAULT_COLOR // fallback to WeChat green so hooks are no-ops
        }
    }

    /** Legible foreground for content sitting on [primaryColor] (M3 `onPrimary`). */
    private val onPrimaryColor by lazy {
        try {
            scheme.onPrimary.toArgb()
        } catch (e: Exception) {
            WeLogger.w(TAG, "failed to get onPrimary color from scheme", e)
            -1 // white fallback
        }
    }

    override fun onEnable() {
        if (!(ThemeSettings.applyToWechat && ThemeSettings.customColor)) {
            WeLogger.i(TAG, "apply-to-wechat off, not recoloring")
            return
        }

        try {
            // Force resolution now so failures are caught early
            val resolvedPrimary = primaryColor
            val resolvedOnPrimary = onPrimaryColor
            WeLogger.i(TAG, "monet colors resolved: primary=#${Integer.toHexString(resolvedPrimary)}, onPrimary=#${Integer.toHexString(resolvedOnPrimary)}")
        } catch (e: Exception) {
            WeLogger.w(TAG, "monet color resolution failed, recoloring disabled", e)
            return
        }

        // NOTE (diagnostic): on 8.0.72/8.0.77 this hook is expected to be a STRUCTURAL NO-OP —
        // the class holds no brand-green constant at all (its colors come from TypedArray
        // resources), so no int field can ever equal DEFAULT_COLOR. The counters below exist to
        // prove that from the log: "constructor entered" present + zero "field" lines = the hook
        // runs and finds nothing (i.e. it can never recolor anything), rather than not running.
        val switchConstructions = AtomicInteger(0)
        val switchFieldHits = AtomicInteger(0)
        runCatching {
            val constructors = "com.tencent.mm.ui.widget.MMSwitchBtn".toClass().constructors
            WeLogger.i(TAG, "MMSwitchBtn: hooking ${constructors.size} constructor(s)")
            constructors.forEach {
                it.hookAfter {
                    if (switchConstructions.incrementAndGet() == 1) {
                        WeLogger.i(TAG, "MMSwitchBtn constructor hook ENTERED (first call)")
                    }
                    thisObject!!.reflekt()
                        .fields {
                            type = Int::class
                            superclass()
                        }.forEach { field ->
                            if (field.get()!! as Int == DEFAULT_COLOR) {
                                WeLogger.i(TAG, "MMSwitchBtn brand green -> primary (hit #${switchFieldHits.incrementAndGet()})")
                                field.set(primaryColor)
                            }
                        }
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook MMSwitchBtn", it)
        }

        // GradientDrawable/PaintDrawable fills (incl. WeChat's green button shapes) draw through
        // Paint.setColor, so swapping the brand green here recolors those backgrounds to primary.
        //
        // NOTE (fix): the previous spec `firstMethod { name = "setColor" }` pinned nothing but the
        // name, so the overload was chosen by `declaredMethods` order — which the JVM does NOT
        // specify. On Android 10+ Paint declares BOTH setColor(int) and setColor(long); binding to
        // the (long) overload makes `args[0] as Int` throw on every call, and since the (long)
        // overload is essentially never called in WeChat the net effect was a silent no-op: no
        // recoloring AND no error logs. Pin the (int) overload explicitly.
        runCatching {
            val setColor = Paint::class.reflekt().firstMethod {
                name = "setColor"
                parameters(Int::class)
                returnType(Void.TYPE)
            }
            WeLogger.i(TAG, "Paint hook bound to: ${setColor.self}")
            val paintCalls = AtomicInteger(0)
            val paintHits = AtomicInteger(0)
            setColor.hookBefore {
                val call = paintCalls.incrementAndGet()
                val color = args[0] as Int
                if (color != DEFAULT_COLOR) {
                    // If the hook is dispatching but this is the only line we ever see, the calls
                    // we care about never reach it (e.g. JIT-inlined callers on a bridge without
                    // deoptimization support).
                    if (call == 1) {
                        WeLogger.i(TAG, "Paint.setColor dispatched (first call #${Integer.toHexString(color)}), brand green not seen yet")
                    }
                    return@hookBefore
                }
                val hits = paintHits.incrementAndGet()
                if (hits == 1) {
                    WeLogger.i(TAG, "Paint.setColor brand green -> primary (first hit at call #$call)")
                }
                args[0] = primaryColor
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook Paint.setColor", it)
        }

        // ColorDrawable draws via Canvas.drawColor (not Paint.setColor), so it needs its own swap.
        runCatching {
            val setBackground = View::class.reflekt().firstMethod { name = "setBackgroundDrawable" }
            WeLogger.i(TAG, "View.setBackgroundDrawable hook bound to: ${setBackground.self}")
            setBackground.hookBefore {
                val drawable = args[0] as? Drawable? ?: return@hookBefore
                if (drawable is ColorDrawable && drawable.color == DEFAULT_COLOR) {
                    WeLogger.i(TAG, "View.setBackgroundDrawable brand green -> primary")
                    drawable.color = primaryColor
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook View.setBackgroundDrawable", it)
        }

        // Green (brand) buttons already get their background recolored to primary by the Paint /
        // ColorDrawable hooks above. Neutral / cancel buttons keep their own colors —
        // we deliberately don't blanket-tint every Button.
        runCatching {
            val onFinishInflate = View::class.reflekt().firstMethod { name = "onFinishInflate" }
            WeLogger.i(TAG, "View.onFinishInflate hook bound to: ${onFinishInflate.self}")
            val buttonsSeen = AtomicInteger(0)
            val buttonsTinted = AtomicInteger(0)
            onFinishInflate.hookAfter {
                val button = thisObject as? Button ?: return@hookAfter
                if (buttonsSeen.incrementAndGet() == 1) {
                    WeLogger.i(TAG, "View.onFinishInflate hook ENTERED (first Button instance seen)")
                }
                if (button.background?.hasBrandGreen() == true) {
                    if (buttonsTinted.incrementAndGet() == 1) {
                        WeLogger.i(TAG, "brand-green Button -> primary bg + onPrimary text")
                    }
                    button.setTextColor(onPrimaryColor)
                    button.backgroundTintList = ColorStateList.valueOf(primaryColor)
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook View.onFinishInflate", it)
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }

        runCatching {
            val onAttached = TextView::class.reflekt().firstMethod { name = "onAttachedToWindow" }
            WeLogger.i(TAG, "TextView.onAttachedToWindow hook bound to: ${onAttached.self}")
            val cursorTinted = AtomicInteger(0)
            onAttached.hookAfter {
                val editText = thisObject as? EditText? ?: return@hookAfter
                editText.apply {
                    textCursorDrawable?.apply {
                        setTint(primaryColor)
                        editText.textCursorDrawable = this
                    }

                    // android views are weird
                    val handle = textSelectHandle ?: return@apply
                    handle.mutate()
                    setTextSelectHandle(handle)
                    textSelectHandle!!.setTint(primaryColor)
                    if (cursorTinted.incrementAndGet() == 1) {
                        WeLogger.i(TAG, "EditText cursor/selection handle tinted to primary (first time)")
                    }
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook EditText cursor tinting", it)
        }
    }

    /** Whether [this] drawable's fill is WeChat's brand green (checks common state-list wrappers). */
    private fun Drawable.hasBrandGreen(): Boolean = when (this) {
        is ColorDrawable -> color == DEFAULT_COLOR
        is GradientDrawable -> color?.defaultColor == DEFAULT_COLOR
        is StateListDrawable -> current.takeIf { it !== this }?.hasBrandGreen() == true
        else -> false
    }
}
