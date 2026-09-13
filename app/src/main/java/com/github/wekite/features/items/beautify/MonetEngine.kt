package com.github.wekite.features.items.beautify

import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.Paint
import android.util.SparseIntArray
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

    /**
     * WeChat's own `android.content.res.Resources` subclasses that override `getColor(int)`
     * (discovered on 8.0.77). Hooking only the framework base class would miss calls that dispatch
     * to these overrides. Names are obfuscated, hence version-specific.
     */
    private val RESOURCES_SUBCLASS_CANDIDATES = listOf(
        "pc5.a",
        "pc5.j",
        "com.tencent.mm.plugin.appbrand.widget.a",
    )

    private val resourceColorHits = AtomicInteger(0)
    private val stateListFailureLogged = java.util.concurrent.atomic.AtomicBoolean(false)

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

    /**
     * Memo for [recolor]. `getColor`/`getColorStateList` are read on nearly every UI write, and
     * nothing that renders should pay an HSV round-trip per read, so each distinct input value is
     * resolved once. Keyed by ARGB; absence is signalled by [SparseIntArray.indexOfKey] < 0.
     */
    private val colorCache = SparseIntArray()

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

        // ── Resource layer (the part that actually recolors the whole UI) ──────────────────
        // Most of WeChat's color is NOT a literal in dex (which is all the hooks above can see) but
        // a color RESOURCE read at runtime. Measured on 8.0.77: Resources.getColor(int) has 6426
        // callsites, and TypedArray.getColor/getColorStateList are what the framework itself calls
        // when inflating XML color attributes (textColor / background / tint). Without these hooks
        // the brand green survives everywhere the color comes from a resource — which is exactly
        // why "同时对微信生效" used to leave the UI looking unchanged.
        //
        // These methods RETURN the color, so the mapping is applied to `result` in hookAfter.
        //
        // ⚠️ Both overloads of each are hooked on purpose. `Context.getColor(id)` — which is what
        // androidx `ContextCompat.getColor` resolves to on API 23+ — delegates to
        // `Resources.getColor(id, theme)`, so the plain (int) overload alone would MISS every modern
        // call path. That is also why the (int, Theme) overload shows zero direct callsites in
        // WeChat's own dex while still being the busiest one in practice.
        hookColorReturn("Resources", Resources::class.java, "getColor", Int::class.java)
        hookColorReturn(
            "Resources", Resources::class.java, "getColor", Int::class.java,
            Resources.Theme::class.java,
        )
        hookStateListReturn("Resources", Resources::class.java, "getColorStateList", Int::class.java)
        hookStateListReturn(
            "Resources", Resources::class.java, "getColorStateList", Int::class.java,
            Resources.Theme::class.java,
        )

        // TypedArray is the highest-leverage target: it is how the framework reads every color
        // written as an XML attribute, so it covers screens that never call Resources.getColor.
        hookColorReturn("TypedArray", TypedArray::class.java, "getColor", Int::class.java, Int::class.java)
        hookStateListReturn("TypedArray", TypedArray::class.java, "getColorStateList", Int::class.java)

        // WeChat ships its OWN Resources subclasses that override these methods. A hook on the base
        // class does not see calls that resolve to the override, so those classes are hooked too.
        // ⚠️ These names are obfuscated and therefore version-specific (8.0.77 — verified present,
        // each declares getColor(int) exactly as expected). A miss is not fatal (the base-class
        // hooks still cover everything routed through super), but it IS logged so a future WeChat
        // build can be re-checked instead of silently losing coverage.
        RESOURCES_SUBCLASS_CANDIDATES.forEach { className ->
            val sub = runCatching { className.toClass() }.getOrNull()
            if (sub == null) {
                WeLogger.i(TAG, "$className not present on this WeChat build (non-fatal, base hooks cover super)")
                return@forEach
            }
            hookColorReturn(className, sub, "getColor", Int::class.java)
            hookStateListReturn(className, sub, "getColorStateList", Int::class.java)
        }
    }

    /**
     * Hooks a framework method that returns a color int and rewrites the returned value through
     * [recolor]. Failures are logged and swallowed — one missing interception point should degrade
     * the recoloring, not disable the whole feature.
     */
    private fun hookColorReturn(
        label: String,
        owner: Class<*>,
        methodName: String,
        vararg params: Class<*>,
    ) {
        runCatching {
            val method = owner.reflekt().firstMethod {
                name = methodName
                parameters(*params)
                returnType(Int::class)
            }
            WeLogger.i(TAG, "$label.$methodName hook bound to: ${method.self}")
            method.hookAfter {
                val original = result as? Int ?: return@hookAfter
                val mapped = recolor(original)
                if (mapped != original) {
                    if (resourceColorHits.incrementAndGet() == 1) {
                        WeLogger.i(TAG, "resource color -> primary (first hit via $label.$methodName, was #${Integer.toHexString(original)})")
                    }
                    result = mapped
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook $label.$methodName", it)
        }
    }

    /** Same as [hookColorReturn] for the [ColorStateList]-returning overloads. */
    private fun hookStateListReturn(
        label: String,
        owner: Class<*>,
        methodName: String,
        vararg params: Class<*>,
    ) {
        runCatching {
            val method = owner.reflekt().firstMethod {
                name = methodName
                parameters(*params)
                returnType(ColorStateList::class)
            }
            WeLogger.i(TAG, "$label.$methodName hook bound to: ${method.self}")
            method.hookAfter {
                val original = result as? ColorStateList ?: return@hookAfter
                recolorStateList(original)?.let { result = it }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook $label.$methodName", it)
        }
    }

    /**
     * Rebuilds a [ColorStateList] with every state color mapped through [recolor], or null when
     * there is nothing to change (so callers can leave the original object in place — swapping in
     * an equivalent copy would invalidate cached drawables for no reason).
     */
    private fun recolorStateList(list: ColorStateList): ColorStateList? {
        return try {
            // `mStateSpecs` / `mColors` are the backing fields but are @hide, so the SDK stubs this
            // module compiles against do not expose them (nor any public getter). Reflection is the
            // only way; if the platform ever blocks it we simply leave this ColorStateList alone —
            // the int-returning hooks still cover the bulk of the UI.
            val specsField = ColorStateList::class.java.getDeclaredField("mStateSpecs")
            val colorsField = ColorStateList::class.java.getDeclaredField("mColors")
            specsField.isAccessible = true
            colorsField.isAccessible = true
            val states = specsField.get(list) as? Array<IntArray> ?: return null
            val colors = colorsField.get(list) as? IntArray ?: return null
            if (colors.isEmpty()) return null

            var changed = false
            val mapped = IntArray(colors.size) { i ->
                val m = recolor(colors[i])
                if (m != colors[i]) changed = true
                m
            }
            if (!changed) null else ColorStateList(states, mapped)
        } catch (t: Throwable) {
            if (stateListFailureLogged.compareAndSet(false, true)) {
                WeLogger.w(TAG, "color state lists left as-is (reflection unavailable on this build)", t)
            }
            null
        }
    }

    /**
     * Rewrites a WeChat brand-green-family color to [primaryColor], preserving the original
     * lightness/saturation so the whole green palette keeps its internal contrast.
     *
     * Why HSL and not a lookup table of literal colors: WeChat's green family is not one value but
     * a set of shades (`#07C160` plus `#06AE56`/`#2AAE67`/`#3EB575`/`#38CD7F`/... dividers, pressed
     * states, disabled states). A literal table would miss shades introduced by future WeChat
     * builds; a hue window plus HSL transfer follows any shade in the family.
     *
     * Idempotent by construction: the result carries the destination hue, so recoloring an already
     * recolored value (same resource read twice) leaves it untouched — no cumulative drift.
     *
     * Called from hooks on very hot framework methods (`Resources.getColor` has thousands of
     * callsites), so: integer fast-path first, and every computed mapping is memoized.
     */
    private fun recolor(color: Int): Int {
        if ((color ushr 24) == 0) return color // fully transparent — nothing to see
        val idx = colorCache.indexOfKey(color)
        if (idx >= 0) return colorCache.valueAt(idx)

        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF

        val mapped = if (isBrandGreenFamily(r, g, b)) {
            val dest = FloatArray(3)
            Color.colorToHSV(primaryColor, dest)
            val src = FloatArray(3)
            Color.RGBToHSV(r, g, b, src)
            // Keep WeChat's own lightness/saturation relationships; take our hue (and our
            // saturation when the source was dull, so muted shades stay muted).
            val h = dest[0]
            val s = src[1] * dest[1] / 0.55f
            val v = src[2]
            Color.HSVToColor(color ushr 24, floatArrayOf(h, s.coerceAtMost(1f), v))
        } else {
            color
        }

        colorCache.put(color, mapped)
        return mapped
    }

    /**
     * Whether (r,g,b) sits in WeChat's brand-green family. Measured on 8.0.77's resources: the
     * family clusters at hue 147.7-148.7 deg (`#07C160`, `#06AE56`, `#2AAE67`, `#3EB575`, ...) with
     * a second, older member at 119.6 deg (`#1AAD19`). The lemon-yellow cluster at ~79 deg is a
     * DIFFERENT palette (excluded on purpose). The windows are wide enough to cover shades but
     * narrow enough to leave WeChat's other accents (link blue `#576B95`, red packets) alone.
     */
    private fun isBrandGreenFamily(r: Int, g: Int, b: Int): Boolean {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max == 0) return false
        // must be the green channel that dominates
        if (g < r || g < b) return false
        val saturation = (max - min).toFloat() / max
        if (saturation < 0.25f) return false
        val hue = FloatArray(3)
        Color.RGBToHSV(r, g, b, hue)
        val h = hue[0]
        return (h >= 112f && h <= 135f) || (h >= 142f && h <= 160f)
    }

    /** Whether [this] drawable's fill is WeChat's brand green (checks common state-list wrappers). */
    private fun Drawable.hasBrandGreen(): Boolean = when (this) {
        is ColorDrawable -> color == DEFAULT_COLOR || recolor(color) != color
        is GradientDrawable -> color?.defaultColor == DEFAULT_COLOR ||
            (color?.defaultColor?.let { recolor(it) != it } ?: false)
        is StateListDrawable -> current.takeIf { it !== this }?.hasBrandGreen() == true
        else -> false
    }
}
