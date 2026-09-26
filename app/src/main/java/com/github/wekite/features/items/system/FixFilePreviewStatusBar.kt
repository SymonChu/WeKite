package com.github.wekite.features.items.system

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.PixelCopy
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.github.wekite.features.core.Feature
import com.github.wekite.features.core.SwitchFeature
import com.github.wekite.utils.TargetProcesses
import com.github.wekite.utils.WeLogger
import java.lang.reflect.Method
import java.util.WeakHashMap

/**
 * 文件预览页「右上角菜单被顶进状态栏、点不到」的修复。
 *
 * ## 现象与成因（2026-09-26 查实）
 * 用户的微信是「先装国内版、再覆盖安装 Play 版」的混合态：Play 包（8.0.77/3141）的 manifest 里
 * 只有 `com.tencent.mm.pluginsdk.ui.tools.FileExplorerUI` 这一句**壳注册**，17 个 dex 里没有它的
 * 实现类（实测 `unzip -p base.apk 'classes*.dex' | grep FileExplorer` 零命中）⇒ 预览页的实现来自
 * 国内版残留（data 分区运行时加载）。它不是模块误伤，与「聊天界面沉浸」的修复无关。
 *
 * ## ⚠️ v3.37 装机实测（用户日志 `wekite-2026-09-26.log`）：预览页**根本没出现在主进程**
 * 开关确实开了（`enabling 系统与隐私/修复文件预览状态栏遮挡`），钩子也确实工作
 * （主进程里 `LauncherUI`、模块设置页都打出了 probe 行），**但整份日志里没有任何一条属于预览页的
 * probe 行** ⇒ 预览页的窗口不在主进程（v3.37 只加载主进程，这个判断是错的）。
 *
 * ## ✅ v3.38 装机实测（全进程加载后一次照出来）：预览页 = `MiniQBReaderUI`，跑在 `:tools` 进程
 * ```
 * file preview probe: proc=com.tencent.mm:tools act=com.tencent.mm.pluginsdk.ui.tools.MiniQBReaderUI
 *   comp=com.tencent.mm.pluginsdk.ui.tools.MiniQBReaderUI
 *   extra=[file_name=…F.pdf,file_path=/data/user/0/com.tencent.mm/MicroMsg/…,file_ext=pdf]
 *   insetTop=140 childTop=0 overlap=140 child=FrostedContentView kids=1 contentPt=0 decorPt=0
 *   flags=0x81810100 decorKids=[LinearLayout(id=0xffffffff,pt=0,vis=0)] strips=[] loader=PathClassLoader
 * ```
 * · 真身 = `com.tencent.mm.pluginsdk.ui.tools.MiniQBReaderUI`（微信用 QQ 浏览器内核读文档的那套），
 *   **在 `:tools` 进程**（`PROC_TOOLS`，日志 `loading in process name=com.tencent.mm:tools, type=8`）；
 * · `insetTop=140 childTop=0` ⇒ 内容确实从屏幕最顶端画，被状态栏盖住；用户 19:54 截图逐像素吻合
 *   （返回箭头/标题/`…` 都在 y≈20–65，与状态栏时间/信号同带，`…` 被电量图标盖住）；
 * · `strips=[]` ⇒ 这个窗口里**没有**微信自绘状态栏的容器（`EdgeToEdgeWrapperLayout` 等），
 *   所以 v3.38 那条「把策略从 ALWAYS_HIDE 改 ALWAYS_AVOID」的兜底在这里没有作用对象，
 *   **只能自己补 padding**。
 * ⇒ v3.39 就是把 `miniqb` / `qbreader` 加进判据关键词（这条链上的名字变不了：
 *   组件名由宿主 manifest 决定，实现由 QQ 浏览器内核那套读文档的组件承载）。
 *
 * ## 修法（v3.38 = 取证版 + 两条有界修复）
 * 1. 记：每个进程里每个 Activity 类一条 `file preview probe:` 行（进程名 / 组件 / extras /
 *    状态栏 inset / 顶层子视图 y / decor 子视图 / 窗口 flags / 状态栏色块容器及其策略）。
 * 2. 修（只对**关键词命中**的窗口动手，其它窗口一行都不改）：
 *    ① 内容被顶进状态栏 ⇒ 给 content 补 `insetTop - childTop` 的 paddingTop（幂等）；
 *    ② 若该窗口里有微信自己的状态栏色块容器（`EdgeToEdgeWrapperLayout` 等），
 *       且它的策略是 ALWAYS_HIDE 而状态栏是可见的 ⇒ 改成 ALWAYS_AVOID，让微信自己补 padding
 *       （v3.36 已证明「策略驱动 padding」是 8.0.77 的真实机制，这里只是方向相反）。
 *
 * ## ⚠️ v3.39 真机验证：三点可点了，但补出来的条带透出聊天页/壁纸（用户报「预览也沉浸了」）
 * 实测（用户 02:36 截图 + 日志 `padding applied: …MiniQBReaderUI pad=140`）：
 * · 修复本身生效：三点已落到状态栏下方、可点；
 * · 副作用：`content` 被补上 140 的 paddingTop 后，**它自己的背景是透明的** ⇒ 那条 140px 的带
 *   透出的是它底下的窗口 = 沉浸中的聊天页。像素实测：y=0–140 是壁纸色 (94,73,92) 带纹理，
 *   y=140 起才是预览页自己的顶栏 (17,17,17) ⇒ 用户看到的就是「预览也有聊天界面沉浸」。
 * ⇒ v3.40 修法：**把这条带涂成预览页顶栏自己的颜色** —— 用 `PixelCopy` 从窗口表面取样顶栏
 *   （紧贴补丁下沿、最左边缘的窄列，取众数色，避开返回箭头/标题/三点），涂到 `content` 背景上。
 *   顶栏色随文档/主题变（实测 PDF 那版顶栏浅色 (237,237,237)、表格那版深色 (17,17,17)），
 *   **不能写死**，必须取样；取样失败（窗口未绘制等）退化为不涂（等同 v3.39 行为）。
 */
@Feature(
    name = "修复文件预览状态栏遮挡",
    categories = ["系统与隐私"],
    description = "把文件预览页的内容压到状态栏下方, 修好右上角菜单被顶进状态栏点不到的问题"
)
object FixFilePreviewStatusBar : SwitchFeature() {

    private const val TAG = "FixFilePreviewStatusBar"

    /** 判「这是文件预览页」的关键词（小写比对：类名 / 组件名 / action / data / extras）。 */
    private val PREVIEW_KEYWORDS = listOf(
        // ⭐ v3.38 实测命中的真身：com.tencent.mm.pluginsdk.ui.tools.MiniQBReaderUI（:tools 进程）
        "miniqb",
        "qbreader",
        // 兜底：文件类 Intent 一定会带这些 extra（`file_ext` 比 `file_path` 更专一，后者分享/保存流程也用）
        "file_ext",
        "fileexplorer",
        "filepreview",
        "filebrowser",
        "docpreview",
        "docreader",
        "documentpreview",
        "file_preview",
        "doc_preview"
    )

    /** 微信「自绘系统栏色块」的容器类名（跨 ClassLoader 只能按类名链判，不 import 宿主类）。 */
    private val STRIP_HOSTS = listOf(
        "com.tencent.mm.ui.statusbar.DrawStatusBarFrameLayout",
        "com.tencent.mm.ui.widget.EdgeToEdgeWrapperLayout"
    )

    /**
     * ⚠️ v3.37 只加载主进程 ⇒ 漏掉 appbrand（预览是 liteapp，跑在那儿）。本版**全进程加载**。
     * 钩子本身极轻（一次 Activity 创建判断一次），风险可忽略。
     */
    override val shouldLoadInCurrentProcess: Boolean get() = true

    /** 已盯住的窗口 → 布局监听（窗口销毁时摘掉）。 */
    private val watching = WeakHashMap<View, ViewTreeObserver.OnGlobalLayoutListener>()

    /** 已打过 probe 行的「进程名 + Activity 类名」（每进程每类一行）。 */
    private val probed = HashSet<String>()

    /** 已记过「真的补了 padding」的「进程名 + 类名」。 */
    private val appliedClasses = HashSet<String>()

    /** 已把补出来的条带涂成预览页顶栏色的窗口（幂等；键 = decor）。 */
    private val stripPainted = WeakHashMap<View, Int>()

    /** `getStatusBarStrategy()` 反射入口缓存（按类）。 */
    private val strategyGetters = WeakHashMap<Class<*>, Method?>()

    override fun onEnable() {
        // 预览页的实现在残留插件 dex / liteapp 里，编译期拿不到那个类 ⇒ 钩框架层。
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

    /** 盯住这个窗口的每次布局（`OnGlobalLayoutListener` 只在布局真的发生时回调，成本远低于每帧检查）。 */
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

    /** 该 Activity 是不是文件预览页（类名 / 组件 / action / data / extras 任一命中关键词）。 */
    private fun looksLikeFilePreview(activity: Activity): Boolean {
        val intent = activity.intent
        val haystack = buildString {
            append(activity.javaClass.name)
            append('|').append(intent?.component?.className ?: "")
            append('|').append(intent?.action ?: "")
            append('|').append(intent?.dataString ?: "")
            append('|').append(extrasSummary(intent))
        }.lowercase()
        return PREVIEW_KEYWORDS.any { haystack.contains(it) }
    }

    /** extras 摘要（最多 6 项、每项 48 字）—— liteapp id / appid 就藏在这里，这是认预览页最可靠的入口。 */
    private fun extrasSummary(intent: Intent?): String {
        val extras = intent?.extras ?: return "-"
        return runCatching {
            extras.keySet().filterNotNull().take(6).joinToString(",") { key ->
                val value = runCatching { extras.get(key) }.getOrNull()
                key + "=" + (value?.toString() ?: "null").take(48)
            }
        }.getOrDefault("-")
    }

    /**
     * 一次布局后的判定：先取证，再（仅当关键词命中时）修。
     *
     * ⚠️ 判据用 `content.rootWindowInsets`（**窗口**的 inset，不受 View 是否已消费影响）——
     * 非 edge-to-edge 的窗口里顶层子视图本来就在状态栏下方，算出的补量自然是 0。
     */
    private fun settle(decor: View, activity: Activity, keywordMatch: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val content = decor.findViewById<ViewGroup>(android.R.id.content) ?: return
        val insets = content.rootWindowInsets ?: return
        val insetTop = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
        val child = content.getChildAt(0)
        val childTop = child?.let {
            val location = IntArray(2)
            it.getLocationInWindow(location)
            location[1]
        } ?: 0
        val overlap = (insetTop - childTop).coerceAtLeast(0)

        val proc = TargetProcesses.currentName
        if (probed.add("$proc|${activity.javaClass.name}")) {
            // 取证（每个进程每个 Activity 类一行）：把「谁在哪个进程、窗口什么形态」一次说清。
            WeLogger.d(
                TAG,
                "file preview probe: proc=$proc act=${activity.javaClass.name} " +
                    "keyword=$keywordMatch comp=${activity.intent?.component?.className ?: "-"} " +
                    "action=${activity.intent?.action ?: "-"} extra=[${extrasSummary(activity.intent)}] " +
                    "insetTop=$insetTop childTop=$childTop overlap=$overlap " +
                    "child=${child?.javaClass?.simpleName ?: "-"} kids=${childCountOf(content)} " +
                    "contentPt=${content.paddingTop} decorPt=${decor.paddingTop} " +
                    "flags=0x${Integer.toHexString(activity.window?.attributes?.flags ?: 0)} " +
                    "decorKids=[${decorChildrenOf(decor)}] strips=[${stripHostsOf(decor)}] " +
                    "loader=${activity.javaClass.classLoader?.javaClass?.simpleName} " +
                    "sdk=${Build.VERSION.SDK_INT}"
            )
        }
        if (!keywordMatch) return

        if (overlap > 0) {
            content.setPadding(
                content.paddingLeft,
                content.paddingTop + overlap,
                content.paddingRight,
                content.paddingBottom
            )
            if (appliedClasses.add("$proc|${activity.javaClass.name}")) {
                WeLogger.d(
                    TAG,
                    "file preview status bar padding applied: proc=$proc " +
                        "act=${activity.javaClass.name} pad=$overlap insetTop=$insetTop childTop=$childTop"
                )
            }
            // ⚠️ v3.39 真机副作用：补出来的那条带自身背景透明，会透出底下的窗口 ——
            //    实测透出的是**沉浸中的聊天页/壁纸**（用户报「预览也有聊天界面沉浸」）。
            //    取样预览页顶栏自己的颜色涂上去，这条带就归预览页自己，不再透底。
            paintStrip(activity, decor, content, overlap, proc)
        }
        // 兜底：微信自己的容器按策略算 padding（v3.36 已证实）。这里方向相反 —— 让开状态栏。
        // 只在「状态栏可见（insetTop>0）而策略是 ALWAYS_HIDE」时才改，其余一律不碰。
        if (insetTop > 0) forceAvoidStatusBar(decor, proc)
    }

    /**
     * 把补出来的那条 140px 带涂成预览页顶栏自己的颜色（v3.40）。
     *
     * ## ⚠️ v3.40 装机实测：PixelCopy 取样拿到 **#00000000**（全透明），还被当成有效颜色涂了上去
     * 日志：`strip painted: … color=#00000000 pad=140` ×7（每打开一次预览一行）⇒ 屏幕上毫无变化。
     * 两条教训：**空/全透明采样必须判失败**（我漏了这一步）；**失败结果绝不能缓存**（缓存成 0 后
     * 永不重试）。⇒ v3.41 改为**确定性取样**：不依赖窗口表面/合成器，直接把 `content` 从 y=pad 起的
     * 那一条**软件渲染**进 Bitmap（`content.draw(Canvas)` 画出来的就是 App 自己画的东西），
     * 在 `[pad+16, pad+112)`（顶栏内部）取**不透明像素**的众数色；占比 <30% 或没有不透明像素 ⇒ 判失败。
     * PixelCopy 降为退路，且同样要过「不透明 + 占比」双判定。
     */
    private fun paintStrip(
        activity: Activity,
        decor: View,
        content: ViewGroup,
        pad: Int,
        proc: String,
        attempt: Int = 0,
    ) {
        if (stripPainted.containsKey(decor)) return
        // ① 确定性：把 content 顶部那一条自己画一遍再取众数色（不依赖窗口表面/合成器）
        val render = renderBandStat(content, pad)
        if (render.color != null) {
            commitStrip(activity, decor, content, render, "render", proc, pad)
            return
        }
        // ② 退路：从窗口表面取样（同样要过「不透明 + 占比」双判定）
        val window = activity.window
        if (window != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && content.width > 0) {
            val rect = Rect(0, pad + 16, content.width, pad + 112)
            runCatching {
                val bitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
                PixelCopy.request(window, rect, bitmap, { result ->
                    val stat = if (result == PixelCopy.SUCCESS) bandStatOf(bitmap) else BandStat(null, 0, 0)
                    bitmap.recycle()
                    if (stat.color != null) {
                        commitStrip(activity, decor, content, stat, "pcopy", proc, pad)
                    } else {
                        retryStrip(
                            activity, decor, content, pad, proc, attempt,
                            "render[${render.describe()}] pcopy[${stat.describe()}] result=$result"
                        )
                    }
                }, Handler(Looper.getMainLooper()))
            }.onFailure {
                retryStrip(
                    activity, decor, content, pad, proc, attempt,
                    "render[${render.describe()}] pcopy threw ${it.javaClass.simpleName}"
                )
            }
            return
        }
        retryStrip(activity, decor, content, pad, proc, attempt, "render[${render.describe()}] no-window")
    }

    /** 取样失败时的重试/放弃（**绝不缓存失败结果**，否则永远不再尝试）。 */
    private fun retryStrip(
        activity: Activity,
        decor: View,
        content: ViewGroup,
        pad: Int,
        proc: String,
        attempt: Int,
        detail: String,
    ) {
        if (attempt >= 3) {
            WeLogger.d(
                TAG,
                "file preview status bar strip paint skipped (no opaque band color): proc=$proc " +
                    "act=${activity.javaClass.name} $detail"
            )
            return
        }
        content.postDelayed({ paintStrip(activity, decor, content, pad, proc, attempt + 1) }, 220)
    }

    private fun commitStrip(
        activity: Activity,
        decor: View,
        content: ViewGroup,
        stat: BandStat,
        src: String,
        proc: String,
        pad: Int,
    ) {
        val color = stat.color ?: return
        stripPainted[decor] = color
        content.setBackgroundColor(color)
        WeLogger.d(
            TAG,
            "file preview status bar strip painted: proc=$proc act=${activity.javaClass.name} " +
                "color=#${String.format("%08X", color)} src=$src pad=$pad (${stat.describe()})"
        )
    }

    /** 把 `content` 从 y=pad 起的那一条软件渲染出来做统计（= App 自己画的东西，不依赖合成器）。 */
    private fun renderBandStat(content: ViewGroup, pad: Int): BandStat {
        val width = content.width
        val height = content.height
        if (width <= 0 || height <= pad + 36) return BandStat(null, 0, 0)
        val top = pad + 16                                  // 再往下 16px：避开圆角/阴影过渡带
        val bandHeight = minOf(96, height - top)
        if (bandHeight <= 8) return BandStat(null, 0, 0)
        val bitmap = Bitmap.createBitmap(width, bandHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.translate(0f, -top.toFloat())
        content.draw(canvas)
        val stat = bandStatOf(bitmap)
        bitmap.recycle()
        return stat
    }

    /** 一条取样带的统计：众数色（**不透明且占比 ≥30%** 才算数）+ 不透明占比 + 众数占比。 */
    private class BandStat(val color: Int?, val opacity: Int, val share: Int) {
        fun describe(): String {
            val top = color?.let { "#" + String.format("%08X", it) } ?: "-"
            return "opacity=$opacity% top=$top share=$share%"
        }
    }

    /**
     * 位图取样统计。
     *
     * ⚠️ **空采样（全透明）必须判失败** —— v3.40 把 `#00000000` 当有效颜色涂上去，等于没涂
     * （用户 2026-09-27 05:01 日志报 `strip painted: … color=#00000000` ×7，屏幕上毫无变化）。
     * 众数占比 <30% 也算失败：宁可退回 v3.39 的样子，也不涂一个错颜色。
     */
    private fun bandStatOf(bitmap: Bitmap): BandStat {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return BandStat(null, 0, 0)
        val counts = HashMap<Int, Int>()
        var sampled = 0
        var opaque = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                sampled++
                val pixel = bitmap.getPixel(x, y)
                if (pixel ushr 24 == 0xFF) {
                    counts[pixel] = (counts[pixel] ?: 0) + 1
                    opaque++
                }
                x += 2
            }
            y += 2
        }
        val entry = counts.maxByOrNull { it.value }
        val share = if (entry != null && opaque > 0) entry.value * 100 / opaque else 0
        val opacity = if (sampled > 0) opaque * 100 / sampled else 0
        val color = if (entry != null && share >= 30) entry.key else null
        return BandStat(color, opacity, share)
    }

    /** 把命中窗口里微信自己的状态栏色块容器的策略从 ALWAYS_HIDE 改成 ALWAYS_AVOID（让微信自己补 padding）。 */
    private fun forceAvoidStatusBar(decor: View, proc: String) {
        for (host in stripHostsIn(decor)) {
            val current = statusBarStrategyOf(host) ?: continue
            if (current != "ALWAYS_HIDE") continue
            val applied = runCatching {
                val setter = host.javaClass.methods.firstOrNull {
                    it.name == "setStatusBarStrategy" && it.parameterCount == 1
                } ?: return@runCatching false
                val enumClass = setter.parameterTypes.first()
                val avoid = enumClass.enumConstants
                    ?.firstOrNull { (it as? Enum<*>)?.name == "ALWAYS_AVOID" } ?: return@runCatching false
                setter.isAccessible = true
                setter.invoke(host, avoid)
                true
            }.getOrDefault(false)
            if (applied && appliedClasses.add("strategy|$proc|${host.javaClass.simpleName}")) {
                WeLogger.d(
                    TAG,
                    "file preview status bar strategy forced ALWAYS_AVOID: proc=$proc " +
                        "host=${host.javaClass.simpleName}"
                )
            }
        }
    }

    /** 窗口里微信自绘系统栏的容器（整树扫，按类名链判）。 */
    private fun stripHostsIn(decor: View): List<View> {
        val found = ArrayList<View>(2)
        walk(decor) { view ->
            var cls: Class<*>? = view.javaClass
            while (cls != null && cls != View::class.java) {
                if (cls.name in STRIP_HOSTS) {
                    found.add(view)
                    break
                }
                cls = cls.superclass
            }
        }
        return found
    }

    /** 取证用：容器类名 + 其 paddingTop + 状态栏策略名。 */
    private fun stripHostsOf(decor: View): String =
        stripHostsIn(decor).joinToString(",") { host ->
            "${host.javaClass.simpleName}|pt=${host.paddingTop}|strategy=${statusBarStrategyOf(host) ?: "-"}"
        }

    /** `getStatusBarStrategy()` 反射读（按类缓存）；读不到返回 null。 */
    private fun statusBarStrategyOf(view: View): String? {
        val getter = strategyGetters.getOrPut(view.javaClass) {
            runCatching {
                view.javaClass.methods.firstOrNull { it.name == "getStatusBarStrategy" }
                    ?.apply { isAccessible = true }
            }.getOrNull()
        } ?: return null
        return runCatching { (getter.invoke(view) as? Enum<*>)?.name }.getOrNull()
    }

    private fun childCountOf(content: ViewGroup): Int = content.childCount

    private fun decorChildrenOf(decor: View): String {
        val group = decor as? ViewGroup ?: return "-"
        return (0 until minOf(group.childCount, 5)).joinToString(",") { i ->
            val c = group.getChildAt(i)
            "${c.javaClass.simpleName}(id=0x${Integer.toHexString(c.id)},pt=${c.paddingTop},vis=${c.visibility})"
        }
    }

    /** 有界整树遍历（防病态深树/环）：最多 600 个节点。 */
    private fun walk(root: View, action: (View) -> Unit) {
        var budget = 600
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        while (stack.isNotEmpty() && budget-- > 0) {
            val view = stack.removeLast()
            action(view)
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) stack.addLast(view.getChildAt(i))
            }
        }
    }
}
