package com.github.wekite.features.items.system

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.github.wekite.features.core.Feature
import com.github.wekite.features.core.SwitchFeature
import com.github.wekite.utils.WeLogger
import java.util.WeakHashMap

/**
 * 文件预览页「右上角菜单被顶进状态栏、点不到」的修复。
 *
 * ## 现象与成因（2026-09-26 查实）
 * 用户的微信是「先装国内版、再覆盖安装 Play 版」的混合态：
 *  · Play 包（8.0.77/3141）的 manifest 里只有 `com.tencent.mm.pluginsdk.ui.tools.FileExplorerUI`
 *    这一句**壳注册**，17 个 dex 里没有它的实现类（实测 `unzip -p base.apk 'classes*.dex' | grep
 *    FileExplorer` 零命中）；
 *  · 所以预览页的实现在国内版残留的插件 dex 里（运行时从 data 分区动态加载）；
 *  · 即「残留插件 + Play 宿主 + Android 15+ 强制 edge-to-edge」的非受支持组合：插件那套界面
 *    没做状态栏 inset 适配 ⇒ 页面内容从 y=0 开始画，右上角三点菜单被系统状态栏盖住点不到。
 *    这不是模块误伤（装模块前就有，与「聊天界面沉浸」的修复无关）。微信不会修这个组合。
 *
 * ## 修法
 * 不改微信/插件的策略，只把**预览 Activity 的 content 顶部**压下去：补一段等于
 * `状态栏高度 - 顶层子视图在窗口中的 y` 的 paddingTop，让内容落到状态栏下方，与国内版的观感一致。
 *  · 判定「内容被顶进状态栏」= `顶层子视图 y < 状态栏 inset`（顶多少补多少）。
 *  · 幂等：补完后 `顶层子视图 y == 状态栏 inset` ⇒ 下一帧算出的补量为 0，不会越补越多；
 *    插件若自己加了 padding 也算得出来，自动不再补。
 *  · **只对判据命中的页面动手**（类名/组件含 fileexplorer 等关键词）；其它页面只打一行取证日志，
 *    因为微信里确实有「本来就该画到状态栏下」的页面（本模块的沉浸会话页、图片/视频全屏预览等），
 *    无差别补 padding 会把它们改坏。
 *
 * ## 钩点为什么必须在框架层
 * 预览页的实现在残留插件 dex 里，编译期拿不到那个类 ⇒ 只能从「所有 Activity 的创建」里按名字筛。
 * `Instrumentation.callActivityOnCreate` 是每次 Activity 创建都会经过的单一入口（本仓
 * `UnifiedEntryPoint.kt` 有同类先例）。
 */
@Feature(
    name = "修复文件预览状态栏遮挡",
    categories = ["系统与隐私"],
    description = "把文件预览页的内容压到状态栏下方, 修好右上角菜单被顶进状态栏点不到的问题 " +
        "(Play 版宿主 + 国内版残留预览实现 + Android 15+ 强制 edge-to-edge 的组合问题, 非模块误伤)"
)
object FixFilePreviewStatusBar : SwitchFeature() {

    private const val TAG = "FixFilePreviewStatusBar"

    /**
     * 判「这是文件预览页」的关键词（小写比对：Activity 类名 / Intent 组件名 / action / data）。
     *
     * 微信插件的实现类名虽然混淆，但**组件名不能混淆** —— 它必须与宿主 manifest 里那句壳注册
     * （`com.tencent.mm.pluginsdk.ui.tools.FileExplorerUI`）一致，否则起不来。
     */
    private val PREVIEW_KEYWORDS = listOf(
        "fileexplorer",
        "filepreview",
        "filebrowser",
        "docpreview",
        "docreader",
        "documentpreview"
    )

    /** 已盯住的窗口 → 布局监听（窗口销毁时摘掉，避免每次开预览都留一个监听）。 */
    private val watching = WeakHashMap<View, ViewTreeObserver.OnGlobalLayoutListener>()

    /** 已打过取证日志的 Activity 类名（每类一行）。 */
    private val probedClasses = HashSet<String>()

    /** 已记过「真的补了 padding」的 Activity 类名（每类一行）。 */
    private val appliedClasses = HashSet<String>()

    override fun onEnable() {
        val target = runCatching {
            "android.app.Instrumentation".toClass().reflekt().firstMethodOrNull {
                name = "callActivityOnCreate"
                parameterCount(2)
            }
        }.getOrNull()
        if (target == null) {
            WeLogger.w(TAG, "Instrumentation.callActivityOnCreate hook target not found")
            return
        }
        target.hookAfter {
            val activity = args[0] as? Activity ?: return@hookAfter
            val decor = activity.window?.decorView ?: return@hookAfter
            watch(decor, activity)
        }
    }

    /**
     * 盯住这个窗口的**每次布局**（而不是每帧）：预览内容是异步加载的，工具栏可能出现在第几帧
     * 之后；`OnGlobalLayoutListener` 只在布局真的发生时回调，成本远低于 pre-draw 每帧检查。
     */
    private fun watch(decor: View, activity: Activity) {
        if (watching.containsKey(decor)) return
        val keywordMatch = looksLikeFilePreview(activity)
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            settle(decor, activity, keywordMatch)
        }
        watching[decor] = listener
        decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit

            override fun onViewDetachedFromWindow(v: View) {
                val installed = watching.remove(decor) ?: return
                runCatching { decor.viewTreeObserver.removeOnGlobalLayoutListener(installed) }
            }
        })
    }

    /** 该 Activity 是不是文件预览页（类名 / Intent 组件 / action / data 任一命中关键词）。 */
    private fun looksLikeFilePreview(activity: Activity): Boolean {
        val intent = activity.intent
        val haystack = buildString {
            append(activity.javaClass.name)
            append('|').append(intent?.component?.className ?: "")
            append('|').append(intent?.action ?: "")
            append('|').append(intent?.dataString ?: "")
        }.lowercase()
        return PREVIEW_KEYWORDS.any { haystack.contains(it) }
    }

    /**
     * 一次布局后的判定：内容被顶进状态栏就补 padding。
     *
     * ⚠️ 判据用 `content.rootWindowInsets`（**窗口**的 inset，不受 View 是否已消费影响）——
     * 非 edge-to-edge 的窗口里顶层子视图本来就在状态栏下方，算出的补量自然是 0。
     */
    private fun settle(decor: View, activity: Activity, keywordMatch: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val content = decor.findViewById<ViewGroup>(android.R.id.content) ?: return
        val insets = content.rootWindowInsets ?: return
        val insetTop = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        val child = content.getChildAt(0) ?: return
        val location = IntArray(2)
        child.getLocationInWindow(location)
        val childTop = location[1]
        val overlap = (insetTop - childTop).coerceAtLeast(0)

        if (overlap > 0 && probedClasses.add(activity.javaClass.name)) {
            // 取证：一行说明「谁、被顶进多少、视图树什么形态」。下次报「还不对」时靠它定位。
            WeLogger.d(
                TAG,
                "file preview probe: act=${activity.javaClass.name} keyword=$keywordMatch " +
                    "insetTop=$insetTop childTop=$childTop overlap=$overlap " +
                    "contentPt=${content.paddingTop} decorPt=${decor.paddingTop} " +
                    "child=${child.javaClass.simpleName} " +
                    "loader=${activity.javaClass.classLoader?.javaClass?.simpleName} " +
                    "sdk=${Build.VERSION.SDK_INT}"
            )
        }
        if (overlap <= 0 || !keywordMatch) return

        content.setPadding(
            content.paddingLeft,
            content.paddingTop + overlap,
            content.paddingRight,
            content.paddingBottom
        )
        if (appliedClasses.add(activity.javaClass.name)) {
            WeLogger.d(
                TAG,
                "file preview status bar padding applied: act=${activity.javaClass.name} " +
                    "pad=$overlap insetTop=$insetTop childTop=$childTop"
            )
        }
    }
}
