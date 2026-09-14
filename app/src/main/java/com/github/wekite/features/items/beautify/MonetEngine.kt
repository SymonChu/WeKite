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
import dev.ujhhgtg.reflekt.reflected.ReflectedMethod
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
 *
 * Two layers are needed to actually cover WeChat's UI, and both were established by measurement
 * rather than assumption (2026-09-14, 8.0.77):
 *
 *  1. **Resource reads** — most colour is a `color` resource read at runtime, so the palette is
 *     redirected by resource **id** (`Resources.getColor/getDrawable`, `TypedArray.*` with the
 *     index recovered via `getResourceId`). Matching by colour value alone can never work for the
 *     neutral surfaces: they are white/grey, not green.
 *  2. **Programmatic backgrounds** — `View.setBackgroundColor/Resource` never goes through a colour
 *     read, so it is invisible to layer 1. Both `ColorDrawable` and `GradientDrawable` (a white
 *     *shape*) are handled there; the leftover white rectangle on the 我 page was a
 *     `GradientDrawable fill=#ffffffff`, which is why palette entries alone kept missing it.
 */
@Feature(name = "莫奈引擎", categories = ["API"], description = "根据模块设置的自定义配色为微信原生组件上色")
object MonetEngine : ApiFeature() {

    private const val TAG = "MonetEngine"

    /** WeChat's hardcoded brand green — the pixels we replace. */
    private const val DEFAULT_COLOR = -16268960 // 0xFF07C160

    /**
     * Surface tint used when a view is given an OPAQUE WHITE background programmatically.
     *
     * `View.setBackgroundColor/Resource` → `setBackgroundDrawable` bypasses the resource-read hooks
     * entirely (the id is never seen again), so such a surface can never be matched by resource id.
     * Measured 2026-09-14 on the 我 page: the rectangle right of 状态/刷新 stayed `#FFFFFFFF` — the
     * untinted value of `BW_BG_100` — while every resource-derived surface around it had already
     * been tinted to `#F8F9FE`. Scoping the match to exactly opaque white (not "any light colour")
     * keeps this from becoming a blanket tint of every white surface.
     */
    private const val WHITE_SURFACE = -1 // 0xFFFFFFFF

    /** Same tint strength the `BW_BG_100` resource uses, so both paths render identically. */
    private const val WHITE_SURFACE_TINT = 0.05f

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
    private val paletteHits = AtomicInteger(0)
    private val drawableHits = AtomicInteger(0)

    /**
     * Diagnostic (temporary, cheap): names of resources used as VIEW BACKGROUNDS whose resolved
     * fill is opaque white — i.e. candidates for "the one rectangle that never changes colour".
     *
     * This is the LOW-NOISE half of the earlier investigation and is kept only because it costs one
     * set lookup per distinct background resource. It is the signal that identified the culprit
     * (`drawable/bxm` → `GradientDrawable fill=#ffffffff`), which the palette could never reach.
     * Remove together with [describeDrawable] once the 我 page is confirmed clean.
     *
     * Only `View.setBackgroundResource(id)` is used here because it unambiguously means "this id
     * paints a background" — unlike `getColor`, which also serves text.
     */
    private val whiteBgResourceNames = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /** Diagnostic (temporary): one-line description of a background drawable; used by the log above. */
    private fun describeDrawable(d: Drawable?): String = when (d) {
        null -> "(no background)"
        is ColorDrawable -> "color=#${Integer.toHexString(d.color)}"
        is GradientDrawable -> run {
            val fill = d.color?.defaultColor?.let { "#${Integer.toHexString(it)}" } ?: "null"
            "fill=$fill alpha=${d.alpha} corner=${d.cornerRadius}"
        }

        is StateListDrawable -> "stateList current=${d.current?.javaClass?.simpleName}"
        else -> ""
    }

    /** Name of the host package whose resources are being remapped (WeChat). */
    private const val HOST_PACKAGE = "com.tencent.mm"

    /** Memo for [paletteKeyFor]; [SparseIntArray] has no null, so misses are simply absent. */
    private val paletteKeyCache = HashMap<Int, String>()

    /**
     * WeChat's NAMED colour palette, resolved once on 8.0.77. Remapping by resource **id** is what
     * actually recolors the whole UI — matching by colour value alone can never work, because the
     * background palette is neutral white/grey (`BW_BG_100` = `0xFFFFFFFF`), not green.
     *
     * The reference module (WeChatMonet Pro v26S4) overrides exactly these resources in an RRO
     * overlay, which is what proves this is the right target set. It rewrites them to
     * `TYPE_REFERENCE` entries pointing at its own monet colours — i.e. the whole named palette is
     * redirected to the dynamic colour, not just the green.
     *
     * `Brand_K` (`0x7F0600A5`) is a duplicate of `Brand_100`. `Brand_110` / `Brand_130` do NOT exist
     * on 8.0.77 (only `Brand_BG_110/130`) — that is why they are absent from this table, and it is
     * why the id list is resolved by NAME at runtime instead of hardcoding fragile ids.
     */
    private val PALETTE_RESOURCE_NAMES = listOf(
        // brand accent — the green family that we also catch by value
        "color/Brand_80", "color/Brand_90", "color/Brand_100", "color/Brand_120", "color/Brand_170",
        "color/Brand_80_CARE", "color/Brand_90_CARE",
        "color/Brand_100_CARE", "color/Brand_120_CARE", "color/Brand_170_CARE",
        "color/Brand_K",
        // bare family aliases (reference RRO covers these; they resolve by name on 8.0.77)
        "color/Brand", "color/LightGreen", "color/Link", "color/Red",
        // brand surfaces
        "color/Brand_BG_90", "color/Brand_BG_100", "color/Brand_BG_110", "color/Brand_BG_130",
        "color/Brand_BG_90_CARE", "color/Brand_BG_100_CARE",
        "color/Brand_BG_110_CARE", "color/Brand_BG_130_CARE",
        // light green accents
        "color/LightGreen_80", "color/LightGreen_90", "color/LightGreen_100",
        "color/LightGreen_80_CARE", "color/LightGreen_90_CARE", "color/LightGreen_100_CARE",
        // neutral surfaces — see SURFACE_TINTS
        "color/BW_BG_19", "color/BW_BG_20", "color/BW_BG_30",
        "color/BW_BG_95", "color/BW_BG_98", "color/BW_BG_100",
        // neutral surfaces NOT in SURFACE_TINTS: the reference overlay rewrites these too, and
        // they are what "the background" actually resolves to on several screens (BW_93 is the
        // chat/contact list surface, BW_90 the 10% black scrim). Their RRO values are plain or
        // reference-to-monet, not tinted, so they go through the same tint path as BW_BG_*.
        "color/BW_0_Alpha_0_9_White_Mode", "color/BW_0_Alpha_0_9_night_mode",
        "color/BW_100", "color/BW_30_Alpha_0_9", "color/BW_85", "color/BW_90", "color/BW_90_K",
        "color/BW_93", "color/BW_93_Night_Mode", "color/BW_97",
        // caution accents
        "color/Yellow_90", "color/Yellow_100", "color/Yellow_BG_90", "color/Yellow_BG_100",
        "color/Yellow_BG_100_CARE",
        // link / caution accents the reference covers (named, stable)
        "color/Link_100", "color/Link_100_CARE", "color/LinkFinder_100", "color/LinkFinder_100_CARE",
        "color/Red_90", "color/Red_90_CARE", "color/Red_100",
        // NOTE (deliberate omission): the reference overlay's remaining ~200 entries are WeChat's
        // obfuscated names (a71, bb, m, aa4, ...). WeChat reshuffles those every release — that is
        // exactly why the reference had to generate its map by diffing two APKs — so they are NOT
        // hardcoded here. Enumerating them would rot on the next WeChat bump.
        // Bubble/red-envelope colours are also left out on purpose: the reference recolours them,
        // but the bubble architecture differs between Play (which the reference targets) and
        // mainland builds, and this module only ever claims to follow the accent.
    )

    /**
     * Tint strength per NEUTRAL surface resource — the `BW_BG_*` family, which is what the user
     * means by "background". These are white/grey in WeChat, so the value-based rule can never see
     * them; they are tinted toward the accent here instead.
     *
     * The number is how much of the accent's SATURATION to apply (0..1), **not** a lightness. It
     * must stay small — the surface keeps its own lightness and only borrows a whisper of hue, so a
     * white background becomes a barely-tinted light surface rather than a saturated block.
     * Darker greys take slightly more, because the same saturation reads as a subtler shift there.
     */
    private val SURFACE_TINTS = mapOf(
        "color/BW_BG_19" to 0.18f, "color/BW_BG_20" to 0.16f, "color/BW_BG_30" to 0.14f,
        "color/BW_BG_95" to 0.08f, "color/BW_BG_98" to 0.06f, "color/BW_BG_100" to 0.05f,
        // Second neutral family (see PALETTE_RESOURCE_NAMES). These MUST be listed here: a name in
        // the palette list but absent from this map falls through to the value-based rule, and a
        // neutral white/grey can never match a green hue window — i.e. adding the name alone would
        // be a silent no-op. Roughly ordered light→dark like the BW_BG_* block above.
        // Membership is taken from the reference overlay being a *themed* REFERENCE there, not from
        // the name looking "neutral": BW_85 / BW_90 / BW_90_K are deliberately absent because the
        // reference pins them to literals (#ffdadada, #10000000, #10000000) — i.e. they are scrims
        // and press states that must NOT follow the accent.
        "color/BW_100" to 0.05f, "color/BW_97" to 0.06f, "color/BW_93" to 0.08f,
        "color/BW_0_Alpha_0_9_White_Mode" to 0.10f,
        "color/BW_30_Alpha_0_9" to 0.14f,
        // dark variants: same saturation reads as a subtler shift on dark greys, so a touch more
        "color/BW_93_Night_Mode" to 0.14f, "color/BW_0_Alpha_0_9_night_mode" to 0.14f,
        // themed neutrals from the second-batch set (same reasoning as above)
        "color/UN_BW_100_Alpha_0_8" to 0.08f, "color/UN_BW_93" to 0.08f,
    )

    /** Resolves a `type/name` key to a resource id in the host package (0 when absent). */
    private fun hostResourceId(key: String): Int = try {
        HostInfo.application.resources.getIdentifier(
            key.substringAfter('/'), key.substringBefore('/'), HOST_PACKAGE,
        )
    } catch (t: Throwable) {
        0
    }

    /** Resource ids of the palette, resolved once; missing entries are simply absent. */
    private val paletteIds: Set<Int> by lazy {
        val resolved = PALETTE_RESOURCE_NAMES.associateWith(::hostResourceId).filterValues { it != 0 }
        WeLogger.i(TAG, "palette resources resolved: ${resolved.size}/${PALETTE_RESOURCE_NAMES.size}")
        resolved.values.toSet()
    }

    /** Ids of the neutral surfaces, resolved once. */
    private val surfaceIds: Set<Int> by lazy {
        SURFACE_TINTS.keys.map(::hostResourceId).filter { it != 0 }.toSet()
    }

    /**
     * The only resource ids this feature can ever change a returned colour for: the palette plus
     * the tinted surfaces. Every id-mapped hook call first tests membership here.
     *
     * Why this exists (perf): `recolor` runs an HSV round-trip and the surface path runs two more,
     * on methods the framework calls thousands of times per second — measured 10 000 `getColor`
     * calls within ~20 s of a single launch. The overwhelming majority of those ids are NOT in the
     * palette, so without a cheap membership test every one of them paid a full HSV conversion to
     * be told "unchanged", which is what the user felt as input lag. Returning early keeps the
     * palette hits intact (they still fall through to [recolorResource]) while skipping the work
     * for everything else.
     */
    private val mappedResourceIds: Set<Int> by lazy { paletteIds + surfaceIds }

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
    private val surfaceCache = SparseIntArray()

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
        // This path also carries programmatic backgrounds (`setBackgroundColor` → `setBackground-
        // Drawable`), which never touch the resource hooks — so an opaque white here has to be
        // tinted explicitly or it renders as the one unthemed rectangle on the screen.
        //
        // GradientDrawable matters just as much: a white SHAPE (`android:shape="rectangle"
        // <solid android:color="#FFFFFFFF"/>`) is what the leftover rectangle on the 我 page turned
        // out to be — measured 2026-09-14: `setBackgroundResource` reported
        // `drawable/bxm drawable=GradientDrawable fill=#ffffffff alpha=255 corner=21.0`, and no
        // amount of palette entries could reach it because it is not a colour resource read.
        runCatching {
            val setBackground = View::class.reflekt().firstMethod { name = "setBackgroundDrawable" }
            WeLogger.i(TAG, "View.setBackgroundDrawable hook bound to: ${setBackground.self}")
            val whiteSurfaceHits = AtomicInteger(0)
            setBackground.hookBefore {
                val drawable = args[0] as? Drawable? ?: return@hookBefore
                when (drawable) {
                    is ColorDrawable -> when (drawable.color) {
                        DEFAULT_COLOR -> {
                            WeLogger.i(TAG, "View.setBackgroundDrawable brand green -> primary")
                            drawable.color = primaryColor
                        }

                        WHITE_SURFACE -> {
                            if (whiteSurfaceHits.incrementAndGet() == 1) {
                                WeLogger.i(TAG, "View.setBackgroundDrawable opaque white -> tinted surface")
                            }
                            drawable.color = recolorSurface(WHITE_SURFACE, WHITE_SURFACE_TINT)
                        }
                    }

                    // A white shape: only the DEFAULT fill is retargeted, and only when it is opaque
                    // white — a shape with its own accent colour keeps it.
                    is GradientDrawable -> {
                        if (drawable.color?.defaultColor == WHITE_SURFACE) {
                            if (whiteSurfaceHits.incrementAndGet() == 1) {
                                WeLogger.i(TAG, "View.setBackgroundDrawable white GradientDrawable -> tinted surface")
                            }
                            drawable.setColor(recolorSurface(WHITE_SURFACE, WHITE_SURFACE_TINT))
                        }
                    }
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook View.setBackgroundDrawable", it)
        }

        // The decisive signal for "which resource is that white rectangle": only this call path
        // means "id X paints a background", unlike getColor which also serves text.
        runCatching {
            val setBgRes = View::class.reflekt().firstMethod {
                name = "setBackgroundResource"
                parameters(Int::class.java)
                returnType(Void.TYPE)
            }
            WeLogger.i(TAG, "View.setBackgroundResource hook bound to: ${setBgRes.self}")
            setBgRes.hookAfter {
                val id = args[0] as? Int ?: return@hookAfter
                if (id == 0) return@hookAfter
                val name = runCatching { HostInfo.application.resources.getResourceName(id) }
                    .getOrNull() ?: return@hookAfter
                if (whiteBgResourceNames.size >= 40) return@hookAfter
                if (!whiteBgResourceNames.add(name)) return@hookAfter
                // Only report backgrounds that are actually OPAQUE WHITE — that is the whole point
                // (the unthemed surface). Filtering here keeps this from becoming a per-call logger.
                // Resolved by inspecting the DRAWABLE, not `resources.getColor(id)`: getColor throws
                // for drawable resources, which is what made an earlier version report every drawable
                // as "not white" and hide the real culprit.
                val view = thisObject as? View
                val bg = view?.background
                val fill = when (bg) {
                    is ColorDrawable -> bg.color
                    is GradientDrawable -> bg.color?.defaultColor
                    else -> null
                }
                if (fill != WHITE_SURFACE) return@hookAfter
                WeLogger.i(TAG, "DIAG WHITE BG name=$name drawable=${bg?.javaClass?.simpleName} ${describeDrawable(bg)}")
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook View.setBackgroundResource", it)
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
        hookColorReturn("Resources", Resources::class.java, "getColor", 0, Int::class.java)
        hookColorReturn(
            "Resources", Resources::class.java, "getColor", 0, Int::class.java,
            Resources.Theme::class.java,
        )
        hookStateListReturn("Resources", Resources::class.java, "getColorStateList", 0, Int::class.java)
        hookStateListReturn(
            "Resources", Resources::class.java, "getColorStateList", 0, Int::class.java,
            Resources.Theme::class.java,
        )

        // Backgrounds do NOT come through getColor: WeChat calls View.setBackgroundResource(id)
        // (1858 callsites) which resolves via getDrawable and yields a ColorDrawable for colour
        // resources. Without these two hooks the UI background stays white no matter how well the
        // colour hooks work.
        hookDrawableReturn("Resources", Resources::class.java, "getDrawable", 0, Int::class.java)
        hookDrawableReturn(
            "Resources", Resources::class.java, "getDrawable", 0, Int::class.java,
            Resources.Theme::class.java,
        )

        // TypedArray is the highest-leverage target: it is how the framework reads every color
        // written as an XML attribute, so it covers screens that never call Resources.getColor.
        // Args are (index, defValue); `index` is NOT a resource id — the id is recovered inside the
        // hook via `getResourceId(index, 0)` (see [hookColorReturn]). Passing 0 keeps `args[0]` (the
        // index) available for that lookup.
        hookColorReturn("TypedArray", TypedArray::class.java, "getColor", 0, Int::class.java, Int::class.java)
        hookStateListReturn("TypedArray", TypedArray::class.java, "getColorStateList", 0, Int::class.java)
        // `android:background="@color/..."` is read through TypedArray.getDrawable during inflate —
        // this is the missing entry point behind "the background never changes". Absent from the
        // 8.0.77 observation set because only Resources.getDrawable was counted before.
        hookDrawableReturn("TypedArray", TypedArray::class.java, "getDrawable", 0, Int::class.java)

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
            hookColorReturn(className, sub, "getColor", 0, Int::class.java)
            hookStateListReturn(className, sub, "getColorStateList", 0, Int::class.java)
        }
    }

    /**
     * The single mapping entry point used by every resource-layer hook: decides by resId first
     * (the only way to reach the neutral background palette) and falls back to the value-based rule
     * for colours read through code that carries no id.
     */
    private fun recolorResource(resId: Int, color: Int): Int {
        if (resId == 0 || resId !in mappedResourceIds) return recolor(color)

        val tint = paletteTintFor(resId)
        val key = paletteKeyFor(resId)
        val mapped = if (tint != null) recolorSurface(color, tint) else recolor(color)
        if (mapped != color && paletteHits.incrementAndGet() == 1) {
            WeLogger.i(TAG, "palette resource ($key) -> #${Integer.toHexString(mapped)} (from #${Integer.toHexString(color)})")
        }
        return mapped
    }

    /** Tint strength when [resId] is one of the neutral surfaces, else null. */
    private fun paletteTintFor(resId: Int): Float? {
        if (!surfaceIds.contains(resId)) return null
        return SURFACE_TINTS[paletteKeyFor(resId)]
    }

    /** Reverse lookup of the palette key for a resolved resource id (only called on an id hit). */
    private fun paletteKeyFor(resId: Int): String? {
        paletteKeyCache[resId]?.let { return it }
        for (key in PALETTE_RESOURCE_NAMES) {
            if (hostResourceId(key) == resId) {
                paletteKeyCache[resId] = key
                return key
            }
        }
        return null
    }

    /**
     * Recovers the real resource id behind a `TypedArray` accessor call.
     *
     * `TypedArray.getColor/getDrawable(index, …)`: `args[0]` is an INDEX into the array, NOT a
     * resource id, so passing it straight to the id-based palette map silently degrades every
     * XML-declared colour to the value-based rule — and a neutral white/grey can never match a
     * green hue window. That is the whole reason `android:background="@color/BW_BG_100"` stayed
     * white. `getResourceId(index, 0)` maps that index back to the attribute's resource id; it
     * returns 0 for literal (non-resource) attributes, which correctly falls through to the value
     * rule. Resolution is lazy+shared: one reflective lookup for every TypedArray hook.
     */
    private val typedArrayResourceId: ReflectedMethod<TypedArray>? by lazy {
        runCatching {
            TypedArray::class.reflekt().firstMethod {
                name = "getResourceId"
                parameters(Int::class, Int::class)
                returnType(Int::class)
            }
        }.onFailure {
            WeLogger.w(TAG, "TypedArray.getResourceId unavailable — XML colours cannot be id-mapped", it)
        }.getOrNull()
    }

    /** Effective resId for a hook call: the argument for `Resources`, a lookup for `TypedArray`. */
    private fun effectiveResId(target: Any?, arg: Int): Int =
        if (target is TypedArray) {
            runCatching { typedArrayResourceId?.invoke(target, arg, 0) as? Int ?: 0 }.getOrDefault(0)
        } else {
            arg
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
        resIdParamIndex: Int,
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
                val argIdx = if (resIdParamIndex in args.indices) args[resIdParamIndex] as? Int ?: 0 else 0
                val resId = effectiveResId(thisObject, argIdx)

                val mapped = recolorResource(resId, original)
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

    /**
     * Hooks `getDrawable(int)` — this is how BACKGROUNDS are actually recoloured. WeChat calls
     * `View.setBackgroundResource(id)` (1858 callsites on 8.0.77) which resolves through
     * `getDrawable`, creating a `ColorDrawable` for colour resources; that path never touches
     * `getColor`, which is why the background stayed white before this hook existed. Only
     * `ColorDrawable` results are touched — real drawables (images, shape XML) are left alone.
     */
    private fun hookDrawableReturn(
        label: String,
        owner: Class<*>,
        methodName: String,
        resIdParamIndex: Int,
        vararg params: Class<*>,
    ) {
        runCatching {
            val method = owner.reflekt().firstMethod {
                name = methodName
                parameters(*params)
                returnType(Drawable::class)
            }
            WeLogger.i(TAG, "$label.$methodName hook bound to: ${method.self}")
            method.hookAfter {
                val argIdx = if (resIdParamIndex in args.indices) args[resIdParamIndex] as? Int ?: 0 else 0
                // Same index→id recovery as the colour hooks: on the TypedArray path `args[0]` is an
                // index, so without this the palette map never fires for XML backgrounds (measured
                // 2026-09-14: resId came back as 0x4, the index itself).
                val resId = effectiveResId(thisObject, argIdx)

                val drawable = result as? ColorDrawable ?: return@hookAfter
                val original = drawable.color
                val mapped = recolorResource(resId, original)
                if (mapped != original) {
                    if (drawableHits.incrementAndGet() == 1) {
                        WeLogger.i(TAG, "drawable background -> recoloured (first hit via $label.$methodName, was #${Integer.toHexString(original)})")
                    }
                    drawable.color = mapped
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
        resIdParamIndex: Int,
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
                val resId = if (resIdParamIndex in args.indices) args[resIdParamIndex] as? Int ?: 0 else 0
                recolorStateList(original, resId)?.let { result = it }
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
    private fun recolorStateList(list: ColorStateList, resId: Int = 0): ColorStateList? {
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
                val m = recolorResource(resId, colors[i])
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
     * Tints a NEUTRAL surface colour (WeChat's `BW_BG_*`: white/grey) toward the accent, so the
     * background follows the user's colour instead of staying white.
     *
     * Kept separate from [recolor] because a neutral colour carries no usable hue — the hue must
     * come entirely from the accent. The surface's own lightness is preserved as-is (no clamping:
     * a dark grey must stay dark), and only a fraction [tint] of the accent's saturation is
     * blended in, so a white background becomes a barely-tinted light surface, not a coloured block.
     */
    private fun recolorSurface(color: Int, tint: Float): Int {
        if (tint <= 0f) return recolor(color)
        val alpha = color ushr 24
        if (alpha == 0) return color
        val idx = surfaceCache.indexOfKey(color)
        if (idx >= 0) return surfaceCache.valueAt(idx)

        val dest = FloatArray(3)
        Color.colorToHSV(primaryColor, dest)
        val src = FloatArray(3)
        Color.RGBToHSV((color shr 16) and 0xFF, (color shr 8) and 0xFF, color and 0xFF, src)

        // Blend the accent's hue in only as far as `tint` allows, and never across a lightness
        // boundary: the result keeps this surface's value and takes a small slice of accent chroma.
        val saturation = (dest[1] * tint).coerceIn(0f, 1f)
        val mapped = Color.HSVToColor(alpha, floatArrayOf(dest[0], saturation, src[2]))
        surfaceCache.put(color, mapped)
        return mapped
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
