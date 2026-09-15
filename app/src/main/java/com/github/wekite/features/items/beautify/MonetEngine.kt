package com.github.wekite.features.items.beautify

import android.app.Activity
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.TypedArray
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.util.SparseIntArray
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
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
    private const val WHITE_SURFACE_TINT = 0.12f

    /**
     * Parameters for the accent overlay applied to drawables that carry no colour value (bitmaps,
     * nine-patches, ripples, gradient shapes), in DARK mode.
     *
     * The overlay must NOT simply paint the accent over the surface: the accent is darker than the
     * surfaces it lands on (measured primary #43608A has value 0.54), so a straight `SRC_ATOP` of the
     * accent DARKENED the background — the filtered areas came out (206,210,219) while the
     * resource-tinted areas right next to them were (239,246,255), i.e. a visible mismatch.
     *
     * Hence a HIGH-VALUE, low-saturation version of the accent. `SURFACE_OVERLAY_TINT` is the DARK
     * factor; the two modes deliberately use different ones — see
     * [SURFACE_OVERLAY_LIGHT_TINT] for why the light overlay is the grey surface colour, not white.
     */
    private const val SURFACE_OVERLAY_TINT = 0.40f
    private const val SURFACE_OVERLAY_ALPHA = 0x4D

    /**
     * Saturation factor for the LIGHT-mode overlay colour: the accent's own light neutral surface,
     * i.e. the colour [recolorSurface] yields for the `#EDEDED` family (`color/i` / `BW_93_Night_Mode`
     * — WeChat's flat grey page background). `0.21` is the same strength [SURFACE_TINTS] gives that
     * literal, and it is expressed as a factor of the accent's saturation so the overlay follows the
     * user's colour instead of being a literal that rots when the accent changes.
     *
     * Why GREY and not white — the filter lands mostly on grey tiles. The 「已登录 N 台其他设备」
     * banner paints `res/k/al.9.png` (pure white, alpha 77 = 0x4D) over the page, so it composites to
     * `0.302 · overlay + 0.698 · page`. Compositing two colours that share a hue and a value yields a
     * colour with that same hue and value, and a saturation of `0.302·S_overlay + 0.698·S_page` —
     * equal to the page's own saturation only when `S_overlay == S_page`. So the exact fix for "the
     * banner looks different from what it sits on" is `overlay == page colour`, and it is unique.
     *
     * The previous value was `V = 1.0`, solved so that compositing over WHITE reproduces the resource
     * path for white. That is right for a white surface and wrong for every grey one — measured
     * 2026-09-15 with primary `#186584`, the banner rendered (189,226,242) against a (197,225,237)
     * page: R −8, a visible seam in LIGHT mode. Dark mode showed only ≤1 because
     * [SURFACE_OVERLAY_DARK_VALUE] parks the overlay in the same band as the dark surfaces.
     *
     * Cost, stated plainly: compositing this overlay onto a colourless WHITE surface yields (230,241,245)
     * instead of the (230,248,255) the resource path gives white — the scheme no longer matches the
     * resource path for white, which is exactly what the old `V = 1.0` bought. On the 2026-09-15 light
     * screenshot no such region exists: its large white areas measure (230,248,255), i.e. they take the
     * resource path (24% of the screen), while the filter path carries the grey tiles — so in practice
     * the change moves the tiles and leaves the white surfaces alone.
     */
    private const val SURFACE_OVERLAY_LIGHT_TINT = 0.21f

    /**
     * Value (HSV brightness) of the overlay colour in LIGHT mode: `237/255`, the `#EDEDED` literal the
     * light neutral surfaces use. Paired with [SURFACE_OVERLAY_LIGHT_TINT] it makes the overlay
     * IDENTICAL to `recolorSurface(#EDEDED, 0.21)` — which is the whole point: compositing the overlay
     * onto a surface of that colour is an identity, so the surface stops looking like a separate tile.
     *
     * Leaving the value at `1.0` (as the first attempt at this fix did) is not enough: only the
     * saturation was matched, the overlay stayed a bright `(211,242,255)`, and the banner still came
     * out (201,230,242) against a (197,225,237) page — a residual (4,5,5). Stated as a rule: two
     * colours composite to the base only when they are equal, so both H/S/V have to line up.
     */
    private const val SURFACE_OVERLAY_LIGHT_VALUE = 237f / 255f

    /**
     * Saturation factor for the overlay when it lands on an OPAQUE BRIGHT bitmap — the input-box
     * voice-button plate, and any other bitmap the filter cannot see through.
     *
     * Why a bitmap needs its OWN pair of overlay parameters, i.e. why one overlay cannot serve both
     * kinds (measured 2026-09-15, primary `#008AC1`, both values verified against the day screenshots):
     *
     *   filter path result = `0.302 · overlay + 0.698 · underlying`
     *
     * Compositing is a fixed-weight blend, so the overlay only disappears against a background when it
     * EQUALS that background's colour. The two drawable kinds sit on different backgrounds:
     *
     *   - the 「已登录 N 台其他设备」 banner is a TRANSLUCENT white nine-patch (alpha 0x4D) over the
     *     GREY page `#EDEDED` → its overlay must be the page colour
     *     ([SURFACE_OVERLAY_LIGHT_TINT] / [SURFACE_OVERLAY_LIGHT_VALUE]);
     *   - a bitmap carries OPAQUE pixels, and WeChat paints the input-box voice button as a white
     *     plate over the pure-WHITE input box plate (both `recolorSurface(#FFFFFF, 0.12)`) → its
     *     overlay must be the solution for white, i.e. `V = 1.0` ([SURFACE_OVERLAY_BITMAP_VALUE]).
     *
     * One constant cannot be 0 for both, which is exactly the ping-pong of v3.9 ↔ v3.10: v3.9 solved
     * for white and the banner read (−13,0,5) against the page; v3.10 solved for the page and the
     * voice-button plate read (10,−1,−5) against the input box. Splitting by drawable kind is the
     * only way to make BOTH read 0.
     *
     * `0.40` is not arbitrary either — it is the factor that makes the composited bitmap match the
     * plate it sits on. Solving `0.302 · (k · S_primary) = 0.12 · S_primary` (the white plate's own
     * weight is `0.12`, [WHITE_SURFACE_TINT]) gives `k = 0.12 / 0.302 = 0.397 ≈ 0.40`. Using `0.12`
     * here instead (the naive "same as the surface" reading) leaves the plate at `(246,252,255)`
     * against a `(224,246,255)` input box — a residual of +22 in red, i.e. the very artefact this
     * constant exists to remove.
     */
    private const val SURFACE_OVERLAY_BITMAP_TINT = 0.40f

    /**
     * Value (HSV brightness) of the overlay for an OPAQUE BRIGHT bitmap (see
     * [SURFACE_OVERLAY_BITMAP_TINT]).
     *
     * `1.0` is the solution, not a taste: `(H, S·k, 1.0)` composited at alpha [SURFACE_OVERLAY_ALPHA]
     * onto pure white reproduces `recolorSurface(#FFFFFF, k)` per channel — the same colour the
     * resource path gives the white plate the bitmap sits on, so the bitmap turns invisible against
     * it. Measured: RGB path white = `(224,246,255)`, compositing this overlay over white =
     * `(224,246,255)`, error 0.
     */
    private const val SURFACE_OVERLAY_BITMAP_VALUE = 1f

    /**
     * Value (HSV brightness) of the overlay colour when the host is in DARK mode.
     *
     * [SURFACE_OVERLAY_ALPHA] is a constant, so a fixed filter colour cannot be right for a light
     * and a dark surface family at once: compositing is `0.698 · surface + 0.302 · filter`, i.e. the
     * filter's own lightness IS the offset applied to every surface it lands on.
     *
     *   light-mode value 1.0 → #D5F0FF: over a white surface this reproduces exactly what the
     *   resource path yields for white, which is why it was solved that way (see [SURFACE_OVERLAY_TINT]).
     *
     * Over a DARK surface family that same filter is a white glow. Measured on the 2026-09-15
     * screenshot (dark + custom colour + apply-to-WeChat, primary #ff8ecff2): the voice-button halo
     * around the chat input's mic measured (92,99,105) against a (39,41,40) field — 0.698·39 +
     * 0.302·213 = 92, a per-channel +53 that reads as a ring of light.
     *
     * Dark surfaces sit in roughly #11…#2F, so the offset is zeroed by putting the filter in that
     * same band: value 0.16 → #22 26 29, i.e. `0.698·39 + 0.302·34 = 37` — within ~2 levels of the
     * surface, below perception. The value is deliberately a single constant rather than a
     * per-surface read: a colour filter cannot sample the surface it is composited onto.
     *
     * Cost, stated plainly: a BRIGHT bitmap background in dark mode is pulled toward the dark band
     * (white 255 → ~188). WeChat's dark backgrounds are nine-patches/bitmaps in the #11…#2F band,
     * which is what this constant is solved for.
     *
     * Scope note: this path only runs for drawables with NO colour value (bitmap / nine-patch /
     * ripple). Surfaces that resolve to a colour — including the title bar, whose #242424 came out
     * as the measured (34,35,37), i.e. `recolorSurface(#242424, 0.21)` = (33,35,36) — go through
     * [recolorSurface] and are NOT affected by this constant.
     */
    private const val SURFACE_OVERLAY_DARK_VALUE = 0.16f

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

    /** Shared "already logged" counter for every background entry point (see [retargetBackground]). */
    private val backgroundSurfaceHits = AtomicInteger(0)

    /**
     * Drawable classes already reported by the `pattern overlay kind` line, so the diagnostic stays
     * bounded to one line per class per session (see [handlePatternDrawable]).
     */
    private val patternKindLogged = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Cache for [overlayColor]: the accent-derived overlay colour, keyed by the accent it came from
     * plus the dark/light mode it was solved for (the two have different values, see
     * [SURFACE_OVERLAY_DARK_VALUE]).
     */
    private var overlaySource = 0
    private var overlayDark = false
    /** Whether [overlayCache] was solved for an opaque bright bitmap (see [overlayColor]). */
    private var overlayBitmap = false
    private var overlayCache = 0

    /**
     * Retargets a background drawable in place. Shared by `setBackgroundDrawable` and
     * `setBackground` so both entry points behave identically.
     *
     * Selectors are not `ColorDrawable`/`GradientDrawable` instances and would otherwise fall
     * through untouched, so the current state is unwrapped.
     */
    private fun retargetBackground(drawable: Drawable?, surfaceHits: AtomicInteger) {
        // ⚠️ MUST accept null. `View.setBackground(null)` is a legal call — Android's own
        // `View.<init>` and `View.setBackgroundDrawable` forward a possibly-null background, and
        // WeChat calls it on every inflate. Because the parameter is non-null in Kotlin, R8 can
        // drop the emitted null-check, and a null then fell through to the `else ->` branch below
        // and reached `drawable.javaClass` in [handlePatternDrawable] — a
        // NullPointerException on 'Object.getClass()' that aborted the hook (measured
        // 2026-09-14: 35 occurrences, all with `at ...View.<init>` under the stack).
        if (drawable == null) return
        when (drawable) {
            is ColorDrawable -> handleColorDrawable(drawable, surfaceHits)
            is GradientDrawable -> handleGradientDrawable(drawable, surfaceHits)
            // ⚠️ `getCurrent()` is null until the selector's state has been applied at least once
            // (`mCurrDrawable` is unset on a freshly inflated selector — exactly the
            // `setBackgroundResource` → new-selector path). Keep the guard as a REAL branch: with
            // `when (val cur = drawable.current)` the compiler assumed the platform value was
            // non-null and lowered its check to `cur.getClass()`, which threw
            // `NullPointerException: ... 'java.lang.Object.getClass()' on a null object reference`
            // inside this method and aborted the hook. That is the residual 12×/session
            // `failed to execute hook of 莫奈引擎` in the 2026-09-15 log (hosts `View.<init>`,
            // `View.setBackground`, `ImageView.onAttachedToWindow`) — the 2026-09-14 fix only
            // covered a null *background*, not a null *selector state*.
            is StateListDrawable -> {
                val cur: Drawable? = drawable.current
                if (cur == null) return
                when (cur) {
                    is ColorDrawable -> handleColorDrawable(cur, surfaceHits)
                    is GradientDrawable -> handleGradientDrawable(cur, surfaceHits)
                    else -> handlePatternDrawable(cur, surfaceHits)
                }
            }

            // A LayerDrawable is used for compounded backgrounds (an input box, a bordered cell…),
            // and its layers are NOT reachable through `current` — without recursing, the white
            // plate of a chat input box stays white while every other surface follows the accent.
            is LayerDrawable -> for (i in 0 until drawable.numberOfLayers) {
                drawable.getDrawable(i)?.let { retargetBackground(it, surfaceHits) }
            }

            // Everything left is a NON-COLOUR drawable: a bitmap, a nine-patch, a vector shape or a
            // ripple. There is no colour value to rewrite, so those surfaces used to stay untouched
            // no matter how many entry points were hooked — measured 2026-09-14: the full-width grey
            // page background and a chat voice-button circle are exactly this case.
            // A translucent colour filter is the one lever that works for all of them, and because
            // the overlay is mostly transparent it shifts the hue while keeping whatever structure
            // the drawable has.
            else -> handlePatternDrawable(drawable, surfaceHits)
        }
    }

    /**
     * Applies the accent as a translucent overlay to a drawable that has no colour value to rewrite
     * (bitmap / nine-patch / ripple / vector shape).
     *
     * Skipped for shapes that already carry an accent fill, and for a drawable that already has our
     * filter, so this cannot stack on every attach.
     *
     * ⚠️ [drawable] is nullable on purpose: this is the `else ->` arm for "not a colour drawable",
     * and a null background is one of the things that lands here (see [retargetBackground]).
     * Every use below must tolerate null.
     */
    private fun handlePatternDrawable(drawable: Drawable?, surfaceHits: AtomicInteger) {
        if (drawable == null) return
        if (drawable is GradientDrawable && drawable.color != null) return   // plain fill: handled elsewhere
        val filter = drawable.colorFilter
        if (filter is PorterDuffColorFilter) return                          // already ours
        // Bright OPAQUE bitmaps get their own overlay (see SURFACE_OVERLAY_BITMAP_TINT): an unsee-through
        // bitmap has no semi-transparent pixels, so the overlay has to match the white plate underneath
        // it — solving it for the grey page instead turned the input-box voice button into a pale
        // block (v3.10 regression). A NINE-PATCH is a translucent pattern (the banner ships at
        // alpha 0x4D), so it keeps the page/neutral solution: compositing the page colour into it is
        // what makes the banner disappear.
        val bitmapKind = drawable is BitmapDrawable
        val overlay = overlayColor(brightBitmap = bitmapKind)
        drawable.colorFilter = PorterDuffColorFilter(overlay, PorterDuff.Mode.SRC_ATOP)
        if (surfaceHits.incrementAndGet() == 1) {
            WeLogger.i(TAG, "pattern background -> accent overlay (${drawable.javaClass.simpleName})")
        }
        // Diagnostic (2026-09-15): log the FIRST hit of each drawable class, and which overlay branch
        // it took. The bitmap/page split rests on the class being right, and the previous log only
        // printed a single first-hit line, which cannot prove WHICH surface that was. Bounded to one
        // line per class so this stays ~a handful of lines per session.
        val klass = drawable.javaClass.simpleName
        if (patternKindLogged.add(klass)) {
            WeLogger.i(TAG, "pattern overlay kind: $klass -> ${if (bitmapKind) "bitmap(white)" else "page(neutral)"}")
        }
    }

    /**
     * The colour used for the pattern overlay: the accent's hue, its saturation taken down to the
     * target surface's own, and a value taken from the host's mode.
     *
     * [brightBitmap] selects the pair of parameters: `true` for an OPAQUE BRIGHT bitmap (the input-box
     * voice-button plate), which needs the white-surface solution ([SURFACE_OVERLAY_BITMAP_TINT] /
     * [SURFACE_OVERLAY_BITMAP_VALUE]); `false` for a translucent pattern over the page
     * ([SURFACE_OVERLAY_LIGHT_TINT] / [SURFACE_OVERLAY_LIGHT_VALUE] in light mode,
     * [SURFACE_OVERLAY_TINT] / [SURFACE_OVERLAY_DARK_VALUE] in dark mode).
     *
     * Dark mode keeps a single value on purpose: [SURFACE_OVERLAY_DARK_VALUE] already parks the
     * overlay in the same band as every dark surface, so the two kinds measure ≤1 apart there and the
     * split would only add a way to be wrong.
     */
    private fun overlayColor(brightBitmap: Boolean = false): Int {
        val dark = HostInfo.application.isDarkMode
        val bitmap = brightBitmap && !dark
        if (primaryColor != overlaySource || dark != overlayDark || bitmap != overlayBitmap) {
            val src = FloatArray(3)
            Color.RGBToHSV(
                (primaryColor shr 16) and 0xFF,
                (primaryColor shr 8) and 0xFF,
                primaryColor and 0xFF,
                src,
            )
            overlayCache = Color.HSVToColor(
                floatArrayOf(
                    src[0],
                    (src[1] * when {
                        bitmap -> SURFACE_OVERLAY_BITMAP_TINT
                        dark -> SURFACE_OVERLAY_TINT
                        else -> SURFACE_OVERLAY_LIGHT_TINT
                    }).coerceIn(0f, 1f),
                    when {
                        bitmap -> SURFACE_OVERLAY_BITMAP_VALUE
                        dark -> SURFACE_OVERLAY_DARK_VALUE
                        else -> SURFACE_OVERLAY_LIGHT_VALUE
                    },
                ),
            )
            overlaySource = primaryColor
            overlayDark = dark
            overlayBitmap = bitmap
        }
        return (SURFACE_OVERLAY_ALPHA shl 24) or (overlayCache and 0x00FFFFFF)
    }

    /**
     * True when [color] is a NEUTRAL (grey/white/black) — i.e. a surface, not an accent.
     *
     * This guard is mandatory before calling [recolorSurface] outside the palette path:
     * `recolorSurface` does NOT inspect its input, it unconditionally returns the accent's hue with
     * `tint` saturation and the source's value. Calling it on a saturated colour would therefore
     * REPLACE that colour (a red badge would become a washed accent tint) instead of leaving it
     * alone. Inside the palette path the input is already known-neutral by construction, which is
     * why the check lives here rather than inside `recolorSurface`.
     */
    private fun isNeutral(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b) <= 12
    }

    /** True when [color] is an opaque neutral surface, i.e. safe to tint through [recolorSurface]. */
    private fun isOpaqueNeutral(color: Int): Boolean =
        (color ushr 24) == 0xFF && isNeutral(color)

    /**
     * Tint strength for a programmatic/value-resolved neutral surface.
     *
     * MUST stay in step with [SURFACE_TINTS]: the same colour reached through a resource id and
     * through the value fallback has to render identically, otherwise a surface that is painted by
     * two different code paths shows a visible seam. White keeps its dedicated constant; everything
     * else uses the light-neutral strength (`#EDEDED` → 0.21 in [SURFACE_TINTS]).
     */
    private fun surfaceTintFor(color: Int): Float =
        if (color == WHITE_SURFACE) WHITE_SURFACE_TINT else 0.21f

    /**
     * Retargets one [ColorDrawable]: brand green → the accent, an opaque neutral → the tinted
     * surface. Shared by the direct-background path and the selector path so both behave
     * identically.
     */
    private fun handleColorDrawable(drawable: ColorDrawable, surfaceHits: AtomicInteger) {
        val original = drawable.color
        if (original == DEFAULT_COLOR) {
            WeLogger.i(TAG, "View.setBackgroundDrawable brand green -> primary")
            drawable.color = primaryColor
            return
        }
        if (!isOpaqueNeutral(original)) return          // an accent/translucent colour: leave it
        val mapped = recolorSurface(original, surfaceTintFor(original))
        if (mapped != original) {
            if (surfaceHits.incrementAndGet() == 1) {
                WeLogger.i(TAG, "View.setBackgroundDrawable neutral ColorDrawable -> tinted surface")
            }
            drawable.color = mapped
        }
    }

    /**
     * Retargets an opaque neutral fill inside a [GradientDrawable] (a shape). A shape carrying its
     * own accent colour is left untouched — see [isNeutral].
     */
    private fun handleGradientDrawable(drawable: GradientDrawable, surfaceHits: AtomicInteger) {
        val fill = drawable.color?.defaultColor ?: return
        if (!isOpaqueNeutral(fill)) return
        val mapped = recolorSurface(fill, surfaceTintFor(fill))
        if (mapped != fill) {
            if (surfaceHits.incrementAndGet() == 1) {
                WeLogger.i(TAG, "View.setBackgroundDrawable neutral GradientDrawable -> tinted surface")
            }
            drawable.setColor(mapped)
        }
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
        // The two UN_BW_* names MUST be here as well as in SURFACE_TINTS. They were previously
        // only in the tint map, which made the tint a silent no-op — the comment further down
        // warns about the opposite direction (name without tint), but the failure mode is
        // symmetrical: [recolorResource] only consults SURFACE_TINTS for ids that are already in
        // [paletteIds], so a tint-only entry can never fire.
        "color/UN_BW_100_Alpha_0_8", "color/UN_BW_93",
        // Second batch of themed neutral surfaces found by diffing the reference overlay against
        // this module's palette (2026-09-14). Each is: themed in the reference (so it IS meant to
        // follow the accent), a neutral light grey, and still unthemed on screen — `color/i`
        // (#EDEDED) is the flat grey page background on 我 / 通讯录 that the user reported.
        // Opaque greys only; the semi-transparent whites (a2s, a8b, a_l, kj, s8, vk, vm, a24) are
        // scrims and are deliberately left out — tinting a translucent overlay changes how it
        // stacks over unknown content.
        "color/i", "color/a25", "color/no", "color/on", "color/ak_",
        // Remaining resources whose literal value is #EDEDED on 8.0.77. Measured evidence: the
        // "已登录其他设备" banner and the empty area under 设置 render as #EDEDED, full width, while
        // everything around them is tinted. The value #EDEDED occurs in LIGHT mode only (the
        // dark-mode counterpart is #191919), so tinting it cannot wash out dark mode.
        // Listed by value rather than by name because a colour is what the screen shows; the
        // reference overlay does not mention these, so the justification here is the pixel
        // measurement, not the reference.
        "color/ae4", "color/ae5", "color/al8", "color/ba2", "color/ib", "color/nw",
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
        // ⚠️ 同一 literal 必须同一强度。以下 6 个值曾按资源名各配一档, 导致同一灰/白在
        // 同屏染成两种颜色 (实测 2026-09-14: #EDEDED 0.08 vs 0.21 差 31 通道 /
        // #F7F7F7 0.06 vs 0.13 差 17 / #FFFFFF 0.05 vs 0.12 差 18)。
        // 统一到**较高档**, 让所有中性面之间的跨度最小 (白 0.12 → 灰 0.21 只差 31;
        // 若白取 0.05 则差 55), 整屏才读得出"一个颜色"。
        "color/BW_BG_95" to 0.21f, "color/BW_BG_98" to 0.13f, "color/BW_BG_100" to 0.12f,
        // Second neutral family (see PALETTE_RESOURCE_NAMES). These MUST be listed here: a name in
        // the palette list but absent from this map falls through to the value-based rule, and a
        // neutral white/grey can never match a green hue window — i.e. adding the name alone would
        // be a silent no-op. Roughly ordered light→dark like the BW_BG_* block above.
        // Membership is taken from the reference overlay being a *themed* REFERENCE there, not from
        // the name looking "neutral": BW_85 / BW_90 / BW_90_K are deliberately absent because the
        // reference pins them to literals (#ffdadada, #10000000, #10000000) — i.e. they are scrims
        // and press states that must NOT follow the accent.
        "color/BW_100" to 0.12f, "color/BW_97" to 0.13f, "color/BW_93" to 0.21f,
        "color/BW_0_Alpha_0_9_White_Mode" to 0.10f,
        "color/BW_30_Alpha_0_9" to 0.14f,
        // dark variants: same saturation reads as a subtler shift on dark greys, so a touch more
        "color/BW_93_Night_Mode" to 0.21f,
        // These three share the literal 0xccffffff, so they MUST share one strength — otherwise the
        // same 80%-white scrim renders with two different hues depending on which resource a screen
        // happens to use. 0.10 is the middle of the three values previously in use.
        "color/BW_0_Alpha_0_9_night_mode" to 0.10f,
        // themed neutrals from the second-batch set (same reasoning as above)
        "color/UN_BW_100_Alpha_0_8" to 0.10f, "color/UN_BW_93" to 0.21f,
        // Second batch (see PALETTE_RESOURCE_NAMES). Tint strength is chosen so that the SAME
        // literal value always gets the SAME strength — two identical greys on screen must not be
        // tinted differently or the seam is visible:
        //   #F7F7F7 -> 0.13 (same as BW_97)   #F5F5F5 -> 0.14
        //   #F0F0F0 -> 0.17                   #EDEDED -> 0.21 (same as BW_93_Night_Mode)
        "color/a25" to 0.13f,
        "color/no" to 0.14f, "color/ak_" to 0.14f,
        "color/on" to 0.17f,
        "color/i" to 0.21f,
        // Same literal #EDEDED as `i` / `BW_93_Night_Mode`, so the same strength — otherwise the
        // banner would end up a visibly different hue than the area right below it.
        "color/ae5" to 0.21f, "color/al8" to 0.21f,
        "color/ba2" to 0.21f, "color/ib" to 0.21f, "color/nw" to 0.21f,
        // ⚠️ 待定: 实测 8.0.77 里 color/ae4 = #FF606060 (深灰), 不是注释里以为的 #EDEDED。
        // 0.21 落在深灰上不会显得脏 (结果仍是深色), 故本轮不动; 但"按色值分级"才是根治。
        "color/ae4" to 0.21f,
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

        // ── Page / window background ────────────────────────────────────────────────────────────
        // The grey block under 设置 is NOT a View background: measured full-width #EDEDED covering
        // 18.6% of the screen and no hook fires for it. It is the WINDOW background, chosen once by
        // the theme (windowBackground) and cached before the process hooks are in place. Re-tinting
        // it here is the only way to reach that surface.
        //
        // Only an OPAQUE NEUTRAL is touched, so an accented or branded window background is left
        // alone. The tinted colour is applied to the decor view (which is what carries that
        // background) rather than re-creating a drawable, so the window's insets/decor are
        // untouched.
        runCatching {
            val onCreate = Activity::class.reflekt().firstMethod {
                name = "onCreate"
                parameters(android.os.Bundle::class.java)
                returnType(Void.TYPE)
            }
            WeLogger.i(TAG, "Activity.onCreate hook bound to: ${onCreate.self}")
            val windowFixes = AtomicInteger(0)
            onCreate.hookAfter {
                val activity = thisObject as? Activity ?: return@hookAfter
                val bg = runCatching { activity.window?.decorView?.background }.getOrNull()
                val fill = when (bg) {
                    is ColorDrawable -> bg.color
                    is GradientDrawable -> bg.color?.defaultColor
                    else -> null
                } ?: return@hookAfter
                if (!isOpaqueNeutral(fill)) return@hookAfter
                val mapped = recolorSurface(fill, surfaceTintFor(fill))
                if (mapped == fill) return@hookAfter
                runCatching {
                    activity.window.decorView.setBackgroundColor(mapped)
                    if (windowFixes.incrementAndGet() == 1) {
                        WeLogger.i(TAG, "window background neutral -> tinted (was #${Integer.toHexString(fill)})")
                    }
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook Activity.onCreate for the window background", it)
        }

        // ── Belt-and-braces: fix backgrounds that were set BEFORE the hooks went in ────────────
        // Measured 2026-09-14: the grey page background behind 设置 (full width, 18.6% of the
        // screen, #EDEDED) is untouched even though every entry point is hooked and the
        // by-value neutral rule is in place. The only consistent explanation is that its
        // background was applied before [onEnable] ran — cached/pre-inflated by WeChat — so the
        // `setBackground*` call was never observed and neither the id path nor the value path
        // could fire. Re-running the mapping at attach time catches that case regardless of which
        // mechanism was used, because attaching always happens after the hooks are installed.
        runCatching {
            val attached = View::class.reflekt().firstMethod {
                name = "onAttachedToWindow"
                returnType(Void.TYPE)
            }
            WeLogger.i(TAG, "View.onAttachedToWindow hook bound to: ${attached.self}")
            attached.hookAfter {
                val view = thisObject as? View ?: return@hookAfter
                val bg = view.background ?: return@hookAfter
                retargetBackground(bg, backgroundSurfaceHits)
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook View.onAttachedToWindow", it)
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
                // `as?` not `as`: a wrong-overload bind would otherwise throw here (see the NOTE
                // above) and abort the hook instead of failing loudly-but-safely.
                val color = args[0] as? Int ?: return@hookBefore
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
            val whiteSurfaceHits = backgroundSurfaceHits
            setBackground.hookBefore {
                val drawable = args[0] as? Drawable? ?: return@hookBefore
                retargetBackground(drawable, whiteSurfaceHits)
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook View.setBackgroundDrawable", it)
        }

        // `View.setBackground(Drawable)` is a SEPARATE method from setBackgroundDrawable (the latter
        // delegates to the former on modern Android) and is what a lot of WeChat's own code calls,
        // so hooking only one of them leaves those backgrounds untouched — measured 2026-09-14: the
        // banner and a settings block stayed unthemed while every hook was bound and firing.
        runCatching {
            val setBackgroundDirect = View::class.reflekt().firstMethod {
                name = "setBackground"
                parameters(Drawable::class.java)
                returnType(Void.TYPE)
            }
            WeLogger.i(TAG, "View.setBackground hook bound to: ${setBackgroundDirect.self}")
            setBackgroundDirect.hookBefore {
                val drawable = args[0] as? Drawable? ?: return@hookBefore
                retargetBackground(drawable, backgroundSurfaceHits)
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook View.setBackground", it)
        }

        // `View.setBackgroundColor(int)` (1354 callsites on 8.0.72) never goes through the drawable
        // hook above, so a neutral surface set this way used to stay unthemed forever. Same palette
        // rules as the drawable path, applied to the colour int before it becomes a ColorDrawable.
        runCatching {
            val setBgColor = View::class.reflekt().firstMethod {
                name = "setBackgroundColor"
                parameters(Int::class.java)
                returnType(Void.TYPE)
            }
            WeLogger.i(TAG, "View.setBackgroundColor hook bound to: ${setBgColor.self}")
            val colorSurfaceHits = AtomicInteger(0)
            setBgColor.hookBefore {
                val original = args[0] as? Int ?: return@hookBefore
                if (original == DEFAULT_COLOR) {
                    args[0] = primaryColor
                    return@hookBefore
                }
                if (!isOpaqueNeutral(original)) return@hookBefore    // accents keep their own colour
                val mapped = recolorSurface(original, surfaceTintFor(original))
                if (mapped != original) {
                    if (colorSurfaceHits.incrementAndGet() == 1) {
                        WeLogger.i(TAG, "View.setBackgroundColor neutral surface -> tinted (was #${Integer.toHexString(original)})")
                    }
                    args[0] = mapped
                }
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook View.setBackgroundColor", it)
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
     *
     * The fallback is what makes this robust: WeChat ships a dozen-plus distinct resources that all
     * resolve to the SAME light grey (`#EDEDED` — measured: the title bar, the "已登录其他设备"
     * banner, the empty area under 设置, and more). Enumerating their names is whack-a-mole; the
     * colour is what the screen actually shows, so the value rule catches whichever name a given
     * screen happens to use. [recolorSurface] keeps the source's lightness, so a text grey stays a
     * text grey and only borrows a little of the accent.
     */
    private fun recolorResource(resId: Int, color: Int): Int {
        val byId = if (resId != 0 && resId in mappedResourceIds) {
            val tint = paletteTintFor(resId)
            val key = paletteKeyFor(resId)
            val mapped = if (tint != null) recolorSurface(color, tint) else recolor(color)
            if (mapped != color && paletteHits.incrementAndGet() == 1) {
                WeLogger.i(TAG, "palette resource ($key) -> #${Integer.toHexString(mapped)} (from #${Integer.toHexString(color)})")
            }
            mapped
        } else {
            color
        }

        if (byId != color) return byId
        // Not covered by id: neutral surfaces are still handled by value.
        if (isOpaqueNeutral(color)) return recolorSurface(color, surfaceTintFor(color))
        return recolor(color)
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
     * `View.setBackgroundResource(id)` which resolves through `getDrawable`.
     *
     * Three result shapes matter, and all three used to be missed except the first:
     *  - `ColorDrawable` — a plain colour resource. Recoloured through the palette (id) or the
     *    value rule.
     *  - `GradientDrawable` — a colour resource declared as a *shape*, or a shape drawable. This is
     *    the case that made "the banner / the settings block" stay grey even though its id was in
     *    the palette: the old code bailed out on anything that was not a ColorDrawable.
     *  - `StateListDrawable` — a selector; the current state is unwrapped so its inner drawable is
     *    retargeted too.
     * Real images/9-patch drawables are still left untouched.
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
                val drawable = result as? Drawable ?: return@hookAfter
                retargetResolvedDrawable(resId, drawable)
            }
        }.onFailure {
            WeLogger.w(TAG, "failed to hook $label.$methodName", it)
        }
    }

    /**
     * Retargets a drawable that came out of a resource read, where the originating [resId] is known
     * (so the palette map can be consulted) — and, failing that, any opaque neutral surface.
     */
    private fun retargetResolvedDrawable(resId: Int, drawable: Drawable) {
        when (drawable) {
            is ColorDrawable -> {
                val original = drawable.color
                val mapped = recolorBackground(resId, original)
                if (mapped != original) {
                    if (drawableHits.incrementAndGet() == 1) {
                        WeLogger.i(TAG, "drawable background -> recoloured (was #${Integer.toHexString(original)})")
                    }
                    drawable.color = mapped
                }
            }

            is GradientDrawable -> {
                val fill = drawable.color?.defaultColor ?: return
                val mapped = recolorBackground(resId, fill)
                if (mapped != fill) {
                    if (drawableHits.incrementAndGet() == 1) {
                        WeLogger.i(TAG, "shape background -> recoloured (was #${Integer.toHexString(fill)})")
                    }
                    drawable.setColor(mapped)
                }
            }

            is StateListDrawable -> {
                // ⚠️ Assigning to an explicitly-nullable local is REQUIRED here. `drawable.current`
                // is a platform type, so Kotlin treats it as non-null and both `?: return` and a
                // `when (x)` subject lower the null check into `x.getClass()` — the exact
                // NullPointerException being fixed in [retargetBackground] (the compiler flagged
                // this as "Elvis operator (?:) always returns the left operand of non-nullable
                // type 'Drawable'", i.e. the guard was dead code).
                val cur: Drawable? = drawable.current
                if (cur == null) return
                retargetResolvedDrawable(resId, cur)
            }
        }
    }

    /** Id-aware background mapping: the palette wins, then any opaque neutral surface. */
    private fun recolorBackground(resId: Int, color: Int): Int {
        if (resId != 0 && resId in mappedResourceIds) return recolorResource(resId, color)
        if (isOpaqueNeutral(color)) return recolorSurface(color, surfaceTintFor(color))
        return color
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
