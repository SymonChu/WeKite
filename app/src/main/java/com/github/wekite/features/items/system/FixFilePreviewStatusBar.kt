package com.github.wekite.features.items.system

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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
 * probe 行** ⇒ 预览页的窗口不在主进程。旁证：用户三次点开预览的时间点
 * （19:11:14 / 19:12:11 / 19:13:28）与日志里 `:appbrand0/:appbrand1`（type=4）**三次进程冷启动
 * 逐一对应** ⇒ 预览是**小程序/liteapp**，跑在 appbrand 进程里，而 v3.37 的特性只加载主进程。
 * ⇒ 本版第一件事：**在所有进程都加载**；第二件事：把每个 Activity 的窗口形态完整打出来，一次装机定案。
 *
 * ## 修法（v3.38 = 取证版 + 两条有界修复）
 * 1. 记：每个进程里每个 Activity 类一条 `file preview probe:` 行（进程名 / 组件 / extras /
 *    状态栏 inset / 顶层子视图 y / decor 子视图 / 窗口 flags / 状态栏色块容器及其策略）。
 * 2. 修（只对**关键词命中**的窗口动手，其它窗口一行都不改）：
 *    ① 内容被顶进状态栏 ⇒ 给 content 补 `insetTop - childTop` 的 paddingTop（幂等）；
 *    ② 若该窗口里有微信自己的状态栏色块容器（`EdgeToEdgeWrapperLayout` 等），
 *       且它的策略是 ALWAYS_HIDE 而状态栏是可见的 ⇒ 改成 ALWAYS_AVOID，让微信自己补 padding
 *       （v3.36 已证明「策略驱动 padding」是 8.0.77 的真实机制，这里只是方向相反）。
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
        }
        // 兜底：微信自己的容器按策略算 padding（v3.36 已证实）。这里方向相反 —— 让开状态栏。
        // 只在「状态栏可见（insetTop>0）而策略是 ALWAYS_HIDE」时才改，其余一律不碰。
        if (insetTop > 0) forceAvoidStatusBar(decor, proc)
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
