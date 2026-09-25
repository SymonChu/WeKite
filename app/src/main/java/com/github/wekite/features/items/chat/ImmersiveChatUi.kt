package com.github.wekite.features.items.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.widget.ListView
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.get
import androidx.core.view.WindowCompat
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.github.wekite.features.core.Feature
import com.github.wekite.features.core.SwitchFeature
import com.github.wekite.ui.utils.allViews
import com.github.wekite.ui.utils.findViewWhich
import com.github.wekite.utils.WeLogger
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.WeakHashMap

@Suppress("DEPRECATION")
@Feature(
    name = "聊天界面沉浸",
    categories = ["界面美化"],
    description = "聊天界面启用 edge-to-edge: 内容延伸到状态栏背后, 消息可以滚到状态栏下方; " +
        "同时适配相关页面 (如服务消息盒子) 在沉浸模式下的布局"
)
object ImmersiveChatUi : SwitchFeature() {

    private const val TAG = "ImmersiveChatUi"

    /** 整树扫「系统栏色块容器」最多扫几帧（首帧未挂全时不把「n=0」缓存死, 见 stripHostsFor）。 */
    private const val STRIP_SCAN_MAX_FRAMES = 20

    /** 每个窗口是否已应用聊天 edge-to-edge (只应用一次, 不恢复)。 */
    private val edgeToEdgeApplied = WeakHashMap<Window, Boolean>()

    /** ConvBox 页面激活的窗口, 期间拦截微信控制器对状态栏颜色的每帧重设。 */
    private val convBoxWindows = WeakHashMap<Window, Boolean>()

    /** ChattingUILayout.fitSystemWindows 入口时、还没被微信加料前的原始导航栏 inset。 */
    private val navBarInsetsBeforeFit = WeakHashMap<View, Int>()

    /** 我们自己写状态栏颜色时置位, 避免被上面的拦截误伤。 */
    private var settingConvBoxColor = false

    /** ConvBoxServiceConversationUI 页面各自的修复状态 (标题栏/列表缓存, 避免每帧整树扫描)。 */
    private val convBoxFixStates = WeakHashMap<View, ConvBoxFixState>()

    private class ConvBoxFixState {
        var titleBar: View? = null
        var toolbar: View? = null
        var list: View? = null
        var wrapper: View? = null
        var titleBarMissingWarned = false
        var applied = false
        var finished = false
        var color = 0
        var inset = 0
        var lastTitleBottom = Int.MIN_VALUE
        var lastListTop = Int.MIN_VALUE
        var stableFrames = 0
    }

    /** 每个会话页布局当前生效的状态栏偏移, 每帧刷新, 供悬浮标题栏读取。 */
    private val statusBarOffsets = WeakHashMap<View, Int>()

    /** 每个会话页布局的状态栏偏移刷新监听。 */
    private val offsetPreDraws = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()

    /** 已把微信 EdgeToEdgeWrapperLayout 的底条颜色/状态栏色块压透明的窗口包装, 避免每帧反射。 */
    private val wrapperStripsNeutralized = WeakHashMap<View, Boolean>()

    /** 每个 wrapper 类上「画状态栏色块的 Paint」字段（按类型找, 不碰混淆名）, 避免每帧重扫。 */
    private val stripPaintFields = WeakHashMap<Class<*>, List<Field>>()

    /** 每个会话页整树扫出的「系统栏色块容器」列表（一次性, 之后每帧只复检 Paint）。 */
    private val stripHostsByLayout = WeakHashMap<View, List<View>>()

    /**
     * 已被判定为「自绘系统栏色块」的容器 —— 它们的 `willNotDraw()` 由钩子强制返回 true
     * （8.0.77 反编译：`DrawStatusBarFrameLayout.dispatchDraw` 开头就用 `willNotDraw()` 当绘制闸门，
     * 而微信把 `setWillNotDraw()` 覆写成空实现 ⇒ 这是唯一能关掉那条色块的入口）。
     */
    private val stripDrawSuppressed = WeakHashMap<View, Boolean>()

    /** 只记一次「钩子真的拦下绘制」的日志（`willNotDraw()` 每帧可能被问多次）。 */
    private val stripSuppressLogged = WeakHashMap<View, Boolean>()

    /** 整树扫描过的帧数（扫到东西才缓存, 否则最多试 [STRIP_SCAN_MAX_FRAMES] 帧）。 */
    private val stripScanAttempts = WeakHashMap<View, Int>()

    /** 已 dump 过窗口结构的窗口（每次进聊天页一行, 用来判状态栏区域到底是谁在画）。 */
    private val stackDumpedWindows = WeakHashMap<Window, Boolean>()

    override fun onEnable() {
        // 聊天页 attach 时把所在窗口切成 edge-to-edge: 内容延伸到状态栏背后。
        // 每个窗口只应用一次, 不做恢复 —— 其他页面 (如服务消息盒子) 因此也处于
        // edge-to-edge, 由各自的针对性布局修复来适配。
        ChattingUILayout::class.reflekt().firstConstructorOrNull {
            parameters(Context::class, AttributeSet::class)
        }?.hookAfter {
            val layout = thisObject as? ChattingUILayout ?: return@hookAfter
            layout.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    applyChatEdgeToEdge(layout)
                    trackStatusBarOffset(layout)
                }

                override fun onViewDetachedFromWindow(v: View) {}
            })
        } ?: WeLogger.w(TAG, "ChattingUILayout constructor hook target not found")

        // 通知半屏/全屏路径下 ChatFooter 一定存在且稳定 attach, 借它兜底应用 edge-to-edge:
        // 这些路径的 ChattingUILayout 可能由微信布局预取线程提前 inflate, 构造 hook/attach 监听会漏。
        ChatFooter::class.reflekt().firstMethodOrNull { name = "onAttachedToWindow" }?.hookAfter {
            val footer = thisObject as? ChatFooter ?: return@hookAfter
            val layout = footer.findAncestorChattingUILayout() ?: return@hookAfter
            applyChatEdgeToEdge(layout)
            trackStatusBarOffset(layout)
        } ?: WeLogger.w(TAG, "ChatFooter.onAttachedToWindow hook target not found")

        // 运行中才打开本特性时, 已有会话的布局早已构造完, attach 监听不会再触发;
        // 下一次布局 (切会话/键盘/旋转) 到来时补挂状态栏偏移追踪。
        ChattingUILayout::class.reflekt().firstMethodOrNull {
            name = "onLayout"
            superclass()
        }?.hookAfter {
            val layout = thisObject
            if (layout !is ChattingUILayout) return@hookAfter
            if (offsetPreDraws[layout] == null) {
                applyChatEdgeToEdge(layout)
                trackStatusBarOffset(layout)
            }
        } ?: WeLogger.w(TAG, "onLayout hook target not found")

        // 聊天页启用 edge-to-edge 后, ChattingUILayout 会把状态栏 inset 吃进 paddingTop,
        // 消息列表因此仍从状态栏下方开始。这里把顶部 padding 清零, 让列表真正延伸到
        // 状态栏背后。
        //
        // 底部: 微信 8.0.72+ 还会把导航栏 inset 吃进 bottom padding, 并在布局底部画一条
        // 全宽底条 (亮色=灰, 暗色=黑) 盖住消息。这里只从 padding 里减掉导航栏那部分
        // (微信额外加的 inset 保留), 并把底条画笔调成透明。
        ChattingUILayout::class.reflekt().firstMethodOrNull { name = "fitSystemWindows" }?.let { fit ->
            fit.hookBefore {
                val layout = thisObject as? View ?: return@hookBefore
                navBarInsetsBeforeFit[layout] = (args[0] as? Rect)?.bottom ?: 0
            }
            fit.hookAfter {
                val layout = thisObject as? View ?: return@hookAfter
                zeroChatLayoutTopPadding(layout)
                val originalBottom = navBarInsetsBeforeFit.remove(layout) ?: 0
                val keep = (layout.paddingBottom - originalBottom).coerceAtLeast(0)
                if (layout.paddingBottom != keep) {
                    layout.setPadding(layout.paddingLeft, layout.paddingTop, layout.paddingRight, keep)
                }
                suppressNavBarStrip(layout)
            }
        } ?: WeLogger.w(TAG, "ChattingUILayout.fitSystemWindows hook target not found")

        // ConvBoxServiceConversationUI 的对话列表页: 祖先容器会把状态栏 inset 吃进
        // paddingTop, 把标题栏+内容一起顶下去产生空白, 见 applyConvBoxLayoutFix。
        $$"com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI$ConvBoxServiceConversationFmUI"
            .toClass().reflekt().firstMethodOrNull {
                name = "onActivityCreated"
            }?.hookAfter {
                val fragment = thisObject ?: return@hookAfter
                val activity = runCatching {
                    fragment.javaClass.getMethod("getActivity").invoke(fragment) as? Activity
                }.getOrNull() ?: return@hookAfter
                val root = runCatching {
                    fragment.javaClass.getMethod("getView").invoke(fragment) as? View
                }.getOrNull() ?: return@hookAfter
                fixConvBoxListLayout(activity, root)
            } ?: WeLogger.w(TAG, "ConvBoxServiceConversationFmUI hook target not found")

        // ConvBox 页面激活期间, 微信控制器每帧重设状态栏颜色, 会把状态栏区域涂成
        // 和页面不一致的颜色。把状态栏保持为透明, 让页面背景直接延伸到状态栏背后。
        // 注意 Window.setStatusBarColor 是抽象方法不能 hook, 要 hook 具体实现 PhoneWindow;
        // 我们自己写颜色时通过 settingConvBoxColor 放行。
        "com.android.internal.policy.PhoneWindow".toClass().reflekt().firstMethodOrNull {
            name = "setStatusBarColor"
        }?.hookBefore {
            val window = thisObject as? Window ?: return@hookBefore
            if (convBoxWindows[window] == true && !settingConvBoxColor) {
                result = null
            }
        } ?: WeLogger.w(TAG, "PhoneWindow.setStatusBarColor hook target not found")

        // ⭐ 关掉微信自绘的状态栏色块（用户在「从搜索进入的聊天页」反复看到的那一条）。
        //
        // 8.0.77 反编译（classes7.dex）定案：
        //   · `DrawStatusBarFrameLayout.dispatchDraw` 开头就是闸门 ——
        //       if (h > 0 && v && n && !willNotDraw()) { paint.setColor(i); canvas.drawRect(0,0,w,h,paint) }
        //   · 同一类把 `setWillNotDraw(boolean)` 覆写成 **空实现**（`public final`、body 只有 return-void）
        //     ⇒ 外界**没有任何 API** 能设那个框架标志；而 `willNotDraw()` 只是转调 `View.willNotDraw()`。
        //   · 色块颜色 i 每帧由 `setColor(i)` 现场写入 ⇒ 之前「把 Paint alpha 压 0」的做法必然被覆盖
        //     （v3.33 真机无效的真因）。
        // ⇒ 唯一入口 = **钩住 `willNotDraw()`，对我们标记过的容器强制返回 true**。
        //   只标记「聊天页所在窗口里扫到的那些」⇒ 其它页面不受影响。
        "com.tencent.mm.ui.statusbar.DrawStatusBarFrameLayout".toClass().reflekt()
            .firstMethodOrNull { name = "willNotDraw" }?.hookAfter {
                val v = thisObject as? View ?: return@hookAfter
                if (stripDrawSuppressed[v] == true) {
                    result = true
                    if (stripSuppressLogged.put(v, true) == null) {
                        WeLogger.d(TAG, "strip container drawing suppressed: ${v.javaClass.simpleName}")
                    }
                }
            } ?: WeLogger.w(TAG, "DrawStatusBarFrameLayout.willNotDraw hook target not found")
    }

    /** 悬浮标题栏读取当前状态栏偏移 (本特性未启用时返回 0, 悬浮标题栏退化为非沉浸布局)。 */
    fun statusBarOffset(layout: View): Int = statusBarOffsets[layout] ?: 0

    // ---- 聊天页 edge-to-edge ----

    /**
     * 聊天页进入时把所在窗口切成 edge-to-edge: 内容延伸到状态栏背后, 消息可以滚到
     * 状态栏下方。每个窗口只应用一次, 不做恢复。
     */
    private fun applyChatEdgeToEdge(layout: View) {
        val activity = layout.context.activityOrNull() ?: return
        val window = activity.window ?: return
        if (edgeToEdgeApplied[window] == true) return
        edgeToEdgeApplied[window] = true
        WindowCompat.setDecorFitsSystemWindows(window, false)
        runCatching { window.statusBarColor = Color.TRANSPARENT }
        zeroChatLayoutTopPadding(layout)
        // 运行中才开启本特性时, 会话页可能已经吃下了导航栏 padding 并画上了底条,
        // 当场把导航栏那部分 padding 去掉, 并把底条画笔调成透明; 之后的每次
        // fitSystemWindows 由上面的 hook 兜底。
        val navInset = currentNavBarInset(layout)
        val keep = (layout.paddingBottom - navInset).coerceAtLeast(0)
        if (layout.paddingBottom != keep) {
            layout.setPadding(layout.paddingLeft, layout.paddingTop, layout.paddingRight, keep)
        }
        suppressNavBarStrip(layout)
        dumpChatWindowStack(layout)
        WeLogger.d(TAG, "chat edge-to-edge applied")
    }

    private fun isChatEdgeToEdge(layout: View): Boolean {
        val activity = layout.context.activityOrNull() ?: return false
        return edgeToEdgeApplied[activity.window] == true
    }

    /** 聊天页当前应补偿的状态栏偏移: edge-to-edge 生效时消息列表从屏幕顶开始, 卡片/间距要加回 inset。 */
    private fun currentStatusBarOffset(layout: View): Int {
        if (!isChatEdgeToEdge(layout)) return 0
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        return layout.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
    }

    private fun zeroChatLayoutTopPadding(layout: View) {
        if (layout.paddingTop != 0) {
            layout.setPadding(layout.paddingLeft, 0, layout.paddingRight, layout.paddingBottom)
        }
    }

    private fun currentNavBarInset(layout: View): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        return layout.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
    }

    /**
     * 8.0.72+ 的 ChattingUILayout 有一个 private final Paint 字段, 专门画那条全宽底条。
     * 按类型找字段 (不碰混淆名), 把 alpha 置 0; fitSystemWindows 每次 setColor 之后
     * hookAfter 会再把它归零。老版本没有这个字段, 直接跳过。
     */
    private fun suppressNavBarStrip(layout: View) {
        val paintField = layout.javaClass.declaredFields.firstOrNull {
            !Modifier.isStatic(it.modifiers) && it.type == Paint::class.java
        } ?: return
        runCatching {
            paintField.isAccessible = true
            (paintField.get(layout) as? Paint)?.alpha = 0
        }
    }

    /** 微信会在聊天页里自己设置状态栏颜色, 会盖住背后的消息; 聊天页在台上时压回透明。 */
    private fun reassertEdgeToEdgeStatusBar(layout: View) {
        val activity = layout.context.activityOrNull() ?: return
        val window = activity.window
        if (edgeToEdgeApplied[window] != true) return
        runCatching {
            if (window.statusBarColor != Color.TRANSPARENT) {
                window.statusBarColor = Color.TRANSPARENT
            }
        }
    }

    /** 每个聊天页布局挂 pre-draw: 每帧刷新状态栏偏移, 供悬浮标题栏/列表 padding 使用。 */
    private fun trackStatusBarOffset(layout: View) {
        val old = offsetPreDraws.remove(layout)
        old?.let { listener ->
            runCatching { layout.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
        val listener = ViewTreeObserver.OnPreDrawListener {
            statusBarOffsets[layout] = currentStatusBarOffset(layout)
            reassertEdgeToEdgeStatusBar(layout)
            neutralizeChatWrapper(layout)
            true
        }
        offsetPreDraws[layout] = listener
        layout.viewTreeObserver.addOnPreDrawListener(listener)
    }

    /**
     * 微信自己的 EdgeToEdgeWrapperLayout 会按 statusBarStrategy 重新给整个聊天内容加状态栏
     * padding, 半屏切全屏时还会从 ALWAYS_HIDE 切回 ALWAYS_AVOID 再刷一次 padding。这里每帧
     * 把 wrapper 的四边 padding 归零, 并把它的状态栏/导航栏色块压成透明, 保证沉浸不被打回。
     */
    private fun neutralizeChatWrapper(layout: View) {
        layout.findEdgeToEdgeWrapper()?.let { wrapper ->
            if (wrapper.paddingTop != 0 || wrapper.paddingBottom != 0) {
                wrapper.setPadding(wrapper.paddingLeft, 0, wrapper.paddingRight, 0)
            }
            suppressStripPaint(wrapper)
            neutralizeWrapperStripsOnce(wrapper)
            // 关键：钩子据此把 willNotDraw() 强制为 true ⇒ dispatchDraw 里的状态栏色块不再画。
            stripDrawSuppressed[wrapper] = true
        }
        // 独立 ChattingUI（搜索/通知半屏等入口）下, 画色块的 wrapper **不保证**是 ChattingUILayout
        // 的祖先 —— 整树扫一次（按类名链判, 不 import 宿主类/不用 is, 避免跨 ClassLoader 恒假）,
        // 结果缓存; 之后每帧只复检 Paint（微信自己的 setColor 会把 alpha 一并写回）。
        for (host in stripHostsFor(layout)) {
            suppressStripPaint(host)
            neutralizeWrapperStripsOnce(host)
            stripDrawSuppressed[host] = true
        }
    }

    /**
     * 整树扫「系统栏色块容器」。
     *
     * ⚠️ **只在扫到东西（或已扫够 [STRIP_SCAN_MAX_FRAMES] 帧）时才缓存** —— 首帧时视图树可能还没
     * 挂全（wrapper 由微信在内容 inflate 前后创建）, 若把「n=0」缓存下来就永远不会重扫,
     * 等于这个兜底静默失效。返回值每帧都要用, 但扫描本身最多 [STRIP_SCAN_MAX_FRAMES] 次。
     */
    private fun stripHostsFor(layout: View): List<View> {
        stripHostsByLayout[layout]?.let { return it }
        val attempts = (stripScanAttempts[layout] ?: 0) + 1
        stripScanAttempts[layout] = attempts
        val found = layout.rootView.allViews.filter { it.looksLikeStatusBarStripHost() }.toList()
        if (found.isNotEmpty() || attempts >= STRIP_SCAN_MAX_FRAMES) {
            stripHostsByLayout[layout] = found
            WeLogger.d(
                TAG,
                "chat strip hosts: n=${found.size} [" +
                    found.joinToString(",") { it.javaClass.simpleName } + "]"
            )
        }
        return found
    }

    /** 一次性的「颜色字段」中和（策略驱动的绘制关不掉, 但颜色仍按旧语义留着, 一并压透明）。 */
    private fun neutralizeWrapperStripsOnce(wrapper: View) {
        if (wrapperStripsNeutralized[wrapper] != null) return
        runCatching {
            wrapper.javaClass.getMethod(
                "setNavigationBarBackgroundColor",
                Int::class.javaPrimitiveType
            ).invoke(wrapper, Color.TRANSPARENT)
            wrapper.javaClass.getMethod("setStatusBarColor", Int::class.javaPrimitiveType)
                .invoke(wrapper, Color.TRANSPARENT)
        }
        wrapperStripsNeutralized[wrapper] = true
        WeLogger.d(TAG, "chat wrapper strips neutralized: ${wrapper.javaClass.simpleName}")
    }

    /** 按类名链判「微信自绘系统栏色块的容器」（不 import 宿主类, 避免跨 ClassLoader 判类型恒假）。 */
    private fun View.looksLikeStatusBarStripHost(): Boolean {
        var cls: Class<*>? = javaClass
        while (cls != null && cls != View::class.java) {
            val name = cls.name
            if (name == "com.tencent.mm.ui.statusbar.DrawStatusBarFrameLayout" ||
                name == "com.tencent.mm.ui.widget.EdgeToEdgeWrapperLayout"
            ) {
                return true
            }
            cls = cls.superclass
        }
        return false
    }

    /**
     * 把「画状态栏色块」的那支 Paint 置全透明（**辅助手段**，主力是 willNotDraw 钩子）。
     *
     * 8.0.77 反编译 `DrawStatusBarFrameLayout.dispatchDraw`：每次绘制前都会
     * `iget i` + `Paint.setColor(i)` —— **颜色（含 alpha）每帧被现场写回**，所以这里的 alpha=0
     * 只在微信停止重设颜色时才有意义（v3.33 只做了这一步 ⇒ 真机无效）。保留它是因为成本极低，
     * 且对「不再 setColor 的版本/路径」仍能兜住。
     *
     * 按**字段类型**找 Paint（不碰混淆名）并缓存; 沿类链找（Paint 声明在父类
     * `DrawStatusBarFrameLayout.g` 上）。
     */
    private fun suppressStripPaint(wrapper: View) {
        val fields = stripPaintFields.getOrPut(wrapper.javaClass) {
            val found = ArrayList<Field>(2)
            var cls: Class<*>? = wrapper.javaClass
            while (cls != null && cls != View::class.java) {
                for (f in cls.declaredFields) {
                    if (!Modifier.isStatic(f.modifiers) && f.type == Paint::class.java) {
                        found.add(f)
                    }
                }
                cls = cls.superclass
            }
            found
        }
        for (f in fields) {
            runCatching {
                f.isAccessible = true
                val paint = f.get(wrapper) as? Paint ?: return@runCatching
                if (paint.alpha != 0) paint.alpha = 0
            }
        }
    }

    /**
     * 每次进聊天页 dump 一次窗口结构（一行）——用来判定「状态栏区域那条东西」到底是谁在画。
     *
     * 背景：搜索等入口进入的聊天窗口, 模块内部状态全部健康（`chat edge-to-edge applied` 有、
     * `statusBarOffset=140` 有）, 但用户仍看到一条非沉浸的顶部区域 ⇒ 画它的一定是模块之外的
     * 视图。这一行把从 ChattingUILayout 到窗口根的每一层（类名 / paddingTop / willNotDraw /
     * 背景类型）打出来, 外加窗口根的直接子（含 `statusBarBackground`）, 一次装机即可定案。
     */
    private fun dumpChatWindowStack(layout: View) {
        val activity = layout.context.activityOrNull() ?: return
        val window = activity.window ?: return
        if (stackDumpedWindows[window] == true) return
        stackDumpedWindows[window] = true
        val chain = StringBuilder()
        var cur: View? = layout
        var depth = 0
        while (cur != null && depth < 12) {
            chain.append(cur.javaClass.simpleName)
            chain.append("(t=").append(cur.top)
            chain.append(",pt=").append(cur.paddingTop)
            // 几何三件套一起打：只打 padding 无法区分「内容被 inset 顶下去」与「色块被画出来」。
            chain.append(",mt=").append(
                (cur.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
            )
            chain.append(",ty=").append(cur.translationY.toInt())
            chain.append(",nd=").append(if (cur.willNotDraw()) 1 else 0)
            chain.append(",bg=").append(cur.background?.javaClass?.simpleName ?: "-")
            chain.append(") ")
            cur = cur.parent as? View
            depth++
        }
        WeLogger.d(TAG, "chat window stack: $chain")
        val root = layout.rootView as? ViewGroup ?: return
        val kids = StringBuilder()
        for (i in 0 until root.childCount) {
            val c = root.getChildAt(i)
            kids.append(c.javaClass.simpleName)
            kids.append("#0x").append(Integer.toHexString(c.id))
            kids.append("(vis=").append(c.visibility).append(",pt=").append(c.paddingTop).append(") ")
        }
        WeLogger.d(TAG, "chat window decor children: $kids")
    }

    private fun View.findEdgeToEdgeWrapper(): View? {
        var current: View? = this
        while (current != null) {
            if (current.javaClass.name == "com.tencent.mm.ui.widget.EdgeToEdgeWrapperLayout") {
                return current
            }
            current = current.parent as? View
        }
        return null
    }

    private fun View.findAncestorChattingUILayout(): ChattingUILayout? {
        var parent = parent
        while (parent != null) {
            if (parent is ChattingUILayout) return parent
            parent = parent.parent
        }
        return null
    }

    // ---- ConvBoxServiceConversationUI 对话列表的 edge-to-edge 适配 ----

    /**
     * 根因 (bar chain dump 证实): 标题栏的祖先容器里有一个 LinearLayout, 微信把状态栏
     * inset 吃进它的 paddingTop, 把整个标题栏+内容一起顶到状态栏下方, 标题栏上方
     * 于是露出一条空白。标题栏自身高度被微信锁死 (actionBarSize), 改它的高度/padding
     * 都会被抢回去。
     *
     * 修复:
     * 1. 每帧清掉祖先链 (含标题栏自身) 的 paddingTop 并关掉 fitsSystemWindows ——
     *    标题栏自然落到 y=0, 空白区域被标题栏背景盖住;
     * 2. Toolbar 单独下移 inset, 内容保持在状态栏下方;
     * 3. 标题栏和 Toolbar 强制铺不透明背景 (同一采样色), 消除"文字悬空"与滚动透底;
     * 4. 列表 padding 按当前实际几何逐帧校准, 第一项贴齐标题内容 (Toolbar) 下沿,
     *    不加任何额外间距;
     * 5. 停掉 DrawStatusBarFrameLayout 系列自绘的状态栏色块 (fixStatusbar 机型)。
     */
    private fun fixConvBoxListLayout(activity: Activity, root: View) {
        // ConvBox 页面可能在没有先进过聊天页的情况下直接打开, 窗口还没被标记为
        // edge-to-edge —— 这里直接对它的窗口应用, 修复不依赖"先进聊天页"。
        ensureConvBoxWindowEdgeToEdge(activity)
        // 收敛后立即摘掉监听: 之后滚动路径上零写入、零 requestLayout, 不会抖动。
        val layoutListener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (applyConvBoxLayoutFix(activity, root)) {
                    runCatching { root.viewTreeObserver.removeOnGlobalLayoutListener(this) }
                }
            }
        }
        val preDrawListener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val done = applyConvBoxLayoutFix(activity, root)
                if (done) {
                    runCatching { root.viewTreeObserver.removeOnPreDrawListener(this) }
                }
                return true
            }
        }
        root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
        root.viewTreeObserver.addOnPreDrawListener(preDrawListener)
        // 布局事件还没来之前先 post 一次, 尽早开始收敛
        root.post { applyConvBoxLayoutFix(activity, root) }
    }

    /**
     * 返回 true 表示处理完成: 视图就绪、几何连续两次观察一致、列表 padding 已应用,
     * 调用方应摘掉监听。完成之后不再有任何逐帧写入。视图暂时缺失时返回 false 继续等。
     */
    private fun applyConvBoxLayoutFix(activity: Activity, root: View): Boolean {
        ensureConvBoxWindowEdgeToEdge(activity)
        if (edgeToEdgeApplied[activity.window] != true) return false
        val state = convBoxFixStates.getOrPut(root) { ConvBoxFixState() }
        if (state.finished) return true
        var titleBar = state.titleBar?.takeIf { it.isAttachedToWindow }
        if (titleBar == null && !state.titleBarMissingWarned) {
            titleBar = activity.window.decorView.findViewWhich<View> {
                it.javaClass.name == "androidx.appcompat.widget.ActionBarContainer"
            }
            if (titleBar == null) {
                state.titleBarMissingWarned = true
                WeLogger.w(TAG, "conv box title bar not found, layout fix keeps retrying")
                return false
            }
            state.titleBar = titleBar
        }
        if (titleBar == null) return false
        var list = state.list?.takeIf { it.isAttachedToWindow }
        if (list == null) {
            list = root.findViewWhich<View> { it is ListView }
            if (list == null) return false
            state.list = list
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        if (titleBar.height <= 0) return false

        val barGroup = titleBar as? ViewGroup ?: return false
        val toolbar = barGroup.findViewWhich<View> {
            it.javaClass.name == "androidx.appcompat.widget.Toolbar"
        } ?: barGroup.getChildAt(0) ?: return false
        val inset = root.rootWindowInsets?.getInsets(WindowInsets.Type.statusBars())?.top ?: 0
        state.toolbar = toolbar
        state.inset = inset

        // 收敛阶段几何写入 (目标值已达成时全部是 no-op, 不会触发 requestLayout):
        // 清掉吃进 inset 的 paddingTop 并关掉 fitsSystemWindows, 防止微信在后续布局里
        // 重新把标题栏顶下去。
        var ancestor: View? = titleBar
        while (ancestor != null && ancestor !== activity.window.decorView) {
            if (ancestor.paddingTop != 0) {
                ancestor.setPadding(ancestor.paddingLeft, 0, ancestor.paddingRight, ancestor.paddingBottom)
            }
            if (ancestor.fitsSystemWindows) ancestor.fitsSystemWindows = false
            ancestor = ancestor.parent as? View
        }
        if (titleBar.translationY != 0f) titleBar.translationY = 0f
        if (toolbar.translationY != inset.toFloat()) toolbar.translationY = inset.toFloat()
        barGroup.clipChildren = false
        (titleBar.parent as? ViewGroup)?.let {
            it.clipChildren = false
            it.clipToPadding = false
        }
        if (titleBar.elevation != 2f) titleBar.elevation = 2f
        if (toolbar.elevation != 2f) toolbar.elevation = 2f

        // 停掉微信自己画的状态栏色块 (DrawStatusBarFrameLayout / EdgeToEdgeWrapperLayout
        // 机型; 本机链条里没有, 但 8.0.65-8.0.74 的 fixStatusbar 页面会用到)
        val wrapper = state.wrapper?.takeIf { it.isAttachedToWindow }
            ?: findStatusBarStripWrapper(activity).also { state.wrapper = it }
        if (wrapper != null && !wrapper.willNotDraw()) wrapper.setWillNotDraw(true)

        if (!state.applied) {
            // 一次性: 采样颜色、铺不透明背景、状态栏透明
            val color = sampleOpaqueBackground(titleBar)
                ?: sampleOpaqueBackground(list)
                ?: Color.WHITE
            state.color = color
            titleBar.background = color.toDrawable()
            toolbar.background = color.toDrawable()
            runCatching { activity.window.setBackgroundDrawable(color.toDrawable()) }
            if (wrapper != null) wrapper.background = color.toDrawable()
            activity.window.decorView.findViewById<View>(android.R.id.statusBarBackground)?.visibility = View.GONE

            convBoxWindows[activity.window] = true
            settingConvBoxColor = true
            try {
                activity.window.statusBarColor = Color.TRANSPARENT
            } finally {
                settingConvBoxColor = false
            }
            state.applied = true
        }

        // 收敛阶段兜底: 背景/状态栏被微信改回去时纠正 (值没变则 no-op)
        runCatching {
            val barBg = titleBar.background
            if (barBg !is ColorDrawable || barBg.color != state.color) {
                titleBar.background = state.color.toDrawable()
            }
            val toolBg = toolbar.background
            if (toolBg !is ColorDrawable || toolBg.color != state.color) {
                toolbar.background = state.color.toDrawable()
            }
            val decorBg = activity.window.decorView.background
            if (decorBg !is ColorDrawable || decorBg.color != state.color) {
                activity.window.setBackgroundDrawable(state.color.toDrawable())
            }
            if (wrapper != null) {
                if (!wrapper.willNotDraw()) wrapper.setWillNotDraw(true)
                val wrapperBg = wrapper.background
                if (wrapperBg !is ColorDrawable || wrapperBg.color != state.color) {
                    wrapper.background = state.color.toDrawable()
                }
            }
            activity.window.decorView.findViewById<View>(android.R.id.statusBarBackground)?.let {
                if (it.visibility != View.GONE) it.visibility = View.GONE
            }
            if (activity.window.statusBarColor != Color.TRANSPARENT) {
                settingConvBoxColor = true
                try {
                    activity.window.statusBarColor = Color.TRANSPARENT
                } finally {
                    settingConvBoxColor = false
                }
            }
        }

        // 列表 padding: 连续两次观察几何一致才算稳定, 稳定后才写一次 —— 不会在滚动
        // 过程中反复 setPadding 触发 requestLayout。
        val toolbarLoc = IntArray(2)
        val listLoc = IntArray(2)
        toolbar.getLocationOnScreen(toolbarLoc)
        list.getLocationOnScreen(listLoc)
        val titleBottom = toolbarLoc[1] + toolbar.height
        val listTop = listLoc[1]
        if (state.lastTitleBottom != titleBottom || state.lastListTop != listTop) {
            state.lastTitleBottom = titleBottom
            state.lastListTop = listTop
            state.stableFrames = 0
            return false
        }
        state.stableFrames++
        if (state.stableFrames < 2) {
            // 保证第二次观察一定发生: 即使没有新的布局/绘制事件, post 也会补一次
            root.post { applyConvBoxLayoutFix(activity, root) }
            return false
        }

        val needed = (titleBottom - listTop).coerceAtLeast(0)
        if (list.paddingTop != needed) {
            list.setPadding(list.paddingLeft, needed, list.paddingRight, list.paddingBottom)
            (list as? ViewGroup)?.clipToPadding = false
            WeLogger.d(
                TAG,
                "conv box list top padding: ${list.paddingTop} -> $needed (titleBottom=$titleBottom listTop=$listTop)"
            )
        }
        state.finished = true
        WeLogger.d(TAG, "conv box layout fix finished: inset=$inset color=${state.color}")
        return true
    }

    /** ConvBox 页面自己的窗口应用 edge-to-edge (可能先于任何聊天页打开, 不能依赖聊天页的 attach)。 */
    private fun ensureConvBoxWindowEdgeToEdge(activity: Activity) {
        val window = activity.window ?: return
        if (edgeToEdgeApplied[window] == true) return
        edgeToEdgeApplied[window] = true
        WindowCompat.setDecorFitsSystemWindows(window, false)
        runCatching { window.statusBarColor = Color.TRANSPARENT }
        WeLogger.d(TAG, "conv box edge-to-edge applied")
    }

    /** 定位微信自绘状态栏色块的容器 (DrawStatusBarFrameLayout 及继承它的 EdgeToEdgeWrapperLayout)。 */
    private fun findStatusBarStripWrapper(activity: Activity): View? {
        val cls = runCatching {
            "com.tencent.mm.ui.statusbar.DrawStatusBarFrameLayout".toClass(activity.classLoader)
        }.getOrNull() ?: return null
        return activity.window.decorView.findViewWhich<View> { cls.isInstance(it) }
    }

    /** 按面积从大到小取第一个不透明背景的颜色, 画到 1x1 位图采样, 兼容任意 drawable 类型。 */
    private fun sampleOpaqueBackground(view: View): Int? {
        // 视图自身的纯色背景直接读, 颜色精确无位图误差
        (view.background as? ColorDrawable)?.let {
            if (Color.alpha(it.color) >= 0xCC) return it.color
        }
        val candidates = listOf(view) + view.allViews
            .filter { it !== view && it.background != null }
            .sortedByDescending { it.width * it.height }
        for (candidate in candidates) {
            val drawable = candidate.background ?: continue
            val color = runCatching {
                val bitmap = createBitmap(1, 1)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, 1, 1)
                drawable.draw(canvas)
                bitmap[0, 0]
            }.getOrNull() ?: continue
            if (Color.alpha(color) >= 0xCC) return color
        }
        return null
    }

    private tailrec fun Context.activityOrNull(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activityOrNull()
        else -> null
    }
}
