package com.github.wekite.features.items.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Outline
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.recyclerview.widget.RecyclerView
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.tencent.mm.pluginsdk.ui.chat.ChatFooterBottom
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import java.util.concurrent.CopyOnWriteArrayList
import com.github.wekite.dexkit.abc.IResolveDex
import com.github.wekite.dexkit.dsl.dexMethod
import com.github.wekite.features.core.ClickableFeature
import com.github.wekite.features.core.Feature
import com.github.wekite.features.items.chat.FloatingChatFooter.offscreenHeight
import com.github.wekite.preferences.WePrefs.Companion.prefOption
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.Button
import com.github.wekite.ui.content.DefaultColumn
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.utils.ListItem
import com.github.wekite.ui.utils.allViews
import com.github.wekite.ui.utils.findViewWhich
import com.github.wekite.ui.utils.showComposeDialog
import com.github.wekite.utils.HostInfo
import com.github.wekite.utils.WeLogger
import com.github.wekite.utils.android.constructor
import java.util.WeakHashMap
import kotlin.math.roundToInt

@Feature(
    name = "悬浮输入框",
    categories = ["界面美化"],
    description = "将聊天输入框改为悬浮卡片形式, 带有圆角、阴影和侧边距\n" +
        "建议同时启用「聊天/聊天界面沉浸」"
)
object FloatingChatFooter : ClickableFeature(), IResolveDex {

    private const val TAG = "FloatingChatFooter"

    private const val DEFAULT_CORNER_RADIUS = 24
    private const val DEFAULT_SIDE_MARGIN = 12
    private const val DEFAULT_BOTTOM_GAP = 8
    private const val DEFAULT_ELEVATION = 4

    private const val MIN_CORNER_RADIUS = 0
    private const val MAX_CORNER_RADIUS = 32
    private const val MIN_SIDE_MARGIN = 0
    private const val MAX_SIDE_MARGIN = 32
    private const val MIN_BOTTOM_GAP = 0
    private const val MAX_BOTTOM_GAP = 24
    private const val MIN_ELEVATION = 0
    private const val MAX_ELEVATION = 16

    /** 微信原版「x条新消息」气泡与输入行之间的留白 (支持版本恒为 44dp)。 */
    private const val NEW_MSG_BUBBLE_GAP_DP = 44

    /** 气泡图标的宿主类名 (布局 XML 直接引用, 不会被混淆)。 */
    private const val WE_CHAT_ICON_VIEW = "com.tencent.mm.ui.widget.imageview.WeImageView"

    /** 会话页滚动容器与消息列表宿主 (布局 XML 直接引用, 不会被混淆)。 */
    private const val CHATTING_SCROLL_LAYOUT_CLASS = "com.tencent.mm.pluginsdk.ui.chat.ChattingScrollLayout"
    private const val MM_CHATTING_LIST_VIEW_CLASS = "com.tencent.mm.ui.chatting.view.MMChattingListView"

    /** ChatFooter 本身 (sdk 插件类, 类名不混淆)。 */
    private const val CHAT_FOOTER_CLASS = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter"

    /** 每个 footer 对应的消息列表宿主, 避免每次高度刷新都做整树 DFS。 */
    private val chatListHosts = WeakHashMap<ChatFooter, View>()

    /** 消息列表 RecyclerView 由微信自己设的原始 bottom padding (一般是 6dp)。 */
    private val chatListBasePaddings = WeakHashMap<View, Int>()

    /** 已经装过 outline 追踪器的 footer, 防止重复注册 OnPreDrawListener。 */
    private val outlineTrackers = WeakHashMap<View, Boolean>()

    /** 每个 footer 对应的消息列表 RecyclerView, 避免每次高度刷新都做整树 DFS。 */
    private val chatListRecyclers = WeakHashMap<View, View>()

    /** 已经报过"找不到列表"警告的 footer, 避免每帧刷日志。 */
    private val chatListLookupWarned = WeakHashMap<View, Boolean>()

    /** 每个 footer 对应的「x条新消息」气泡, 避免每次高度刷新都做整树 DFS。 */
    private val newMessageBubbles = WeakHashMap<View, View>()

    /** 已经报过"找不到气泡"警告的 footer, 避免每帧刷日志。 */
    private val newMessageBubbleLookupWarned = WeakHashMap<View, Boolean>()

    /** 已注册的 pre-draw 监听, 重进会话时先摘掉旧的再挂新的, 避免监听失效。 */
    private val navInsetPreDraws = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()

    private var cornerRadiusDp by prefOption("floating_chat_footer_corner_radius", DEFAULT_CORNER_RADIUS)
    private var sideMarginDp by prefOption("floating_chat_footer_side_margin", DEFAULT_SIDE_MARGIN)
    private var bottomGapDp by prefOption("floating_chat_footer_bottom_gap", DEFAULT_BOTTOM_GAP)
    private var elevationDp by prefOption("floating_chat_footer_elevation", DEFAULT_ELEVATION)

    /**
     * Locates ChatFooter.refreshBottomHeight() by the unique log string WeChat emits at the
     * start of the method. The intentional typo "keyborPx" is WeChat's own, copied faithfully.
     */
    private val methodRefreshBottomHeight by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings("MicroMsg.ChatFooter", "[refreshBottomHeight] keyborPx:%d")
        }
    }

    override fun onEnable() {
        val reflekt = ChatFooter::class.reflekt()

        // 绘制属性不依赖 LayoutParams, 构造完就能设
        ChatFooter::class.constructor.hookAfter {
            applyDrawingStyle(thisObject as ChatFooter)
        }

        // 结构改造与边距必须等 LayoutParams 就位 —— 它由父容器 (ChattingScrollLayout)
        // 在 addView() 时写入, 构造函数返回时还没有, 到 onAttachedToWindow 保证非空。
        //
        // applyDrawingStyle 在这里再来一次 (幂等): 运行时才打开本特性的话, 当前会话的
        // ChatFooter 早已构造完毕, 构造函数 hook 会整个错过。
        reflekt.firstMethod { name = "onAttachedToWindow" }.hookAfter {
            val footer = thisObject as ChatFooter
            applyDrawingStyle(footer)
            applySideMargins(footer)
            applyBottomGap(footer)
            trackNavBarInset(footer)
            installPanelCollapseGuard(footer)
        }

        // 微信在这里写入 bottomMargin = -面板高, 我们在它之后覆盖掉。
        methodRefreshBottomHeight.hookAfter {
            applyBottomGap(thisObject as ChatFooter)
        }
    }

    /**
     * 软键盘当前占据的高度, 未显示时为 0。
     *
     * API 30 以下拿不到可靠的 IME inset, 一律按"未显示"处理 —— 那些设备退化为微信原生
     * 行为 (面板与键盘互斥), 不会走到需要两者共存的分支, 因此退化是安全的。
     */
    private val View.imeHeight: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            rootWindowInsets?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
        } else {
            0
        }

    private val View.isImeVisible: Boolean
        get() = imeHeight > 0

    /** ChatFooter 子树里的表情/工具面板容器。 */
    private val ChatFooter.bottomPanel: ChatFooterBottom?
        get() = findViewWhich<View> { it is ChatFooterBottom } as ChatFooterBottom?

    /** 设置 outline / 圆角裁剪 / 阴影 / 暗色浮层 —— 全是不依赖 LayoutParams 的绘制属性, 可重复调用。 */
    private fun applyDrawingStyle(footer: ChatFooter) {
        val density = footer.resources.displayMetrics.density
        footer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = view.resources.displayMetrics.density * cornerRadiusDp
                val bottom = (view.height - offscreenHeight(footer)).coerceAtLeast(1)
                outline.setRoundRect(0, 0, view.width, bottom, r)
            }
        }
        footer.clipToOutline = true
        footer.elevation = elevationDp * density
        FloatingChatCardVisuals.applyDarkSurface(footer, cornerRadiusDp)
        trackOutlineWhileScrolling(footer)
        WeLogger.d(TAG, "applied drawing style: corner=${cornerRadiusDp}dp elev=${elevationDp}dp")
    }

    /**
     * footer 有多少高度悬在屏幕外, 不该被算进圆角矩形。
     *
     * 面板在输入行下方: `bottomMargin = -面板高` 把面板那段挤到 LinearLayout 底边之外,
     * 微信靠 translationY 把整个 footer 上移来"展开"面板。于是悬在外面的高度是
     * `面板高 + translationY` (translationY ∈ [-面板高, 0]): 收起时等于面板高, 圆角落在
     * 输入行下沿; 完全展开时归零, 圆角落在 footer 真正的底边。中间过程连续。
     *
     * 键盘弹出是个例外, 得单独算。微信开面板前一定先收键盘 (configPanel 里的 hideVKB),
     * 所以**键盘可见 ⇒ 面板必然收起**, 卡片下沿恒等于输入行下沿, 与 translationY 无关。
     * 而键盘态下微信正拿 translationY 把 footer 整体上移, 上面那个公式会把裁剪下边界多
     * 留出一个底部间距的高度: 间距里于是露出面板背景 (实测 #F7F7F7 一条), 下方两个圆角
     * 也被顶到间距下沿变成直角。改用面板 LayoutParams 的高度 (与 [applyBottomGap] 算
     * `visible` 同一份数据), 裁到卡片真实底沿 —— 间距恢复透出壁纸、圆角完整。
     */
    private fun offscreenHeight(footer: ChatFooter): Int {
        val panel = footer.bottomPanel ?: return 0
        if (footer.isImeVisible) return panel.layoutParams?.height?.coerceAtLeast(0) ?: 0
        return (panel.height + footer.translationY).toInt().coerceAtLeast(0)
    }

    // ── 8.0.79 面板收起兜底 ────────────────────────────────────────────────
    // 现象：面板/键盘展开后点聊天区不自动收起。**8.0.77 与 8.0.79 都有** —— v3.78 之前
    // 「8.0.77 同配置正常」的结论已由 2026-10-15 真机日志作废（同一台机器切到 8.0.77 后
    // `panel collapse fired` 13 次 / 失败 91 次，表情面板态照样不收）。
    //
    // 字节级取证（2026-10-15，dexdump 逐指令，用户真机同一 build 8.0.79.3182 + 对照 8.0.77）：
    // 1. 原生监听器的挂载点：`ChattingUIFragment.p1()` 里
    //    `t = MMChattingListView.getListView(); t.setOnTouchListener(new w8())`。
    // 2. 原生收起时机是 **ACTION_DOWN**（UP 分支只把 this.d 置 false 就 return）。
    // 3. 77/79 两版 onTouch 逐指令同构，只差最后一次调用：77 `L0(Z)` / 79 `K0(Z)`；
    //    79 的 `K0(Z)` 体 = `Q(0,z,-1)`（`setBottomPanelVisibility(GONE)` 也走它）——
    //    **只改面板状态位、不碰 IME** ⇒ 面板归它管、键盘不归它管。
    //
    // 真机日志（用户 10-14 / 10-15 两份 + v3.77 新日志）给出的关键判据：
    // - v3.70~v3.76 钩的外层视图（MMPullDownView.dispatchTouchEvent）与原生监听器流
    //   147/295 vs 20/10 **零重合**；
    // - 但 v3.77 的 `panel collapse views: … innerInOuterTree=true` 证明内层列表**确实**在外层子树里
    //   ⇒「点消息不经过外层」这个解释不成立，真因另有其物（例如页内存在不止一棵列表/不止一个 footer）；
    // - v3.77 三条钩子在同一页里**零 "fired"**：要么钩子没被叫到、要么状态门全 false，
    //   而上一版那条「原生监听器 UP 计数」诊断已删 ⇒ 该版日志无法自证。
    // ⇒ v3.78 定策：**不再猜哪个视图** —— 跟着宿主自己的 OnTouchListener 走（先结构取证再钩实例），
    //    并给每条钩子做入口记账（前 8 次打 action/view/ty/两条键盘判据），键盘判据同时用
    //    insets 与「可见帧差」，避免单条判据失灵导致静默跳过。
    //
    // ⇒ v3.79 定案（2026-10-15 真机日志 + 两个 base.apk 逐指令反汇编）：**钩子机制本身没问题** ——
    //    8.0.77 上三条钩子都装上了、也都叫到了（`panel collapse guard installed on native
    //    listener com.tencent.mm.ui.chatting.a9`；`panel collapse touch` 56 次、`fired` 13 次），
    //    **只有最后那次反射调用找不到方法**：`ChatFooter` 里没有 `K0`（那是 8.0.79 的名字），
    //    8.0.77 的等价物叫 `L0(Z)`。修法见 [collapsePanelNative] 的三级探测。
    //    另一条实测结论：键盘态在 8.0.77 上「看着会收」是 [hideImeFor] 救的（日志里键盘态
    //    同样 `failed`，只是系统 IME 被收起后卡片自己落回去了），面板态没有这层侥幸。
    private val collapseListViews = WeakHashMap<View, ChatFooter>()
    private val collapseHooked = HashSet<String>()
    private val collapseEntryBudget = HashMap<String, Int>()
    private var collapseEpisodeLogged = false
    private var listenerHostLogs = 0

    /** 每条钩子入口日志条数上限（诊断用，避免刷屏）。 */
    private const val ENTRY_LOG_BUDGET = 8

    private fun installPanelCollapseGuard(footer: ChatFooter) {
        val hosts = footer.listViewsForCollapse()
        if (hosts.isEmpty()) {
            WeLogger.w(TAG, "panel collapse guard: chat list view not found (will retry on next attach)")
            return
        }
        for (v in hosts) {
            collapseListViews[v] = footer
            installTouchHooks(v)
        }
        val outer = hosts.first()
        val inner = hosts.getOrNull(1)
        WeLogger.i(
            TAG,
            "panel collapse views: outer=${outer.javaClass.name}#${System.identityHashCode(outer)}" +
                if (inner != null) {
                    ", inner=${inner.javaClass.name}#${System.identityHashCode(inner)}, " +
                        "innerInOuterTree=${isInTreeOf(inner, outer)}"
                } else {
                    ", inner=<getListView() returned null>"
                }
        )
        installNativeListenerHooks(hosts)
        logTouchListenerHosts(footer)
    }

    private fun isInTreeOf(v: View, root: View): Boolean {
        var node: View? = v
        while (node != null) {
            if (node === root) return true
            node = node.parent as? View
        }
        return false
    }

    /**
     * 读视图自己的 OnTouchListener（`View.mListenerInfo.mOnTouchListener`，framework 字段名不混淆）。
     * 读不到返回 null（时机未到 / 该视图本来没装）。
     */
    private fun touchListenerOf(v: View): Any? = runCatching {
        val info = View::class.java.getDeclaredField("mListenerInfo")
            .apply { isAccessible = true }.get(v) ?: return@runCatching null
        info.javaClass.getDeclaredField("mOnTouchListener")
            .apply { isAccessible = true }.get(info)
    }.getOrNull()

    /**
     * 结构取证：把本页所有「自带 OnTouchListener」的视图列出来（类名 + 身份哈希 + 监听器类名）。
     *
     * 这是判「微信的收面板监听器到底挂在哪个对象上、跟我们注册的是不是同一个」的唯一直接证据 ——
     * v3.77 的日志缺了它，才无法自证「钩子没被叫到」。每进程只打前 2 次。
     */
    private fun logTouchListenerHosts(footer: ChatFooter) {
        if (listenerHostLogs >= 2) return
        listenerHostLogs++
        val root = runCatching { footer.rootView }.getOrNull() ?: return
        val found = ArrayList<String>()
        var visited = 0
        val stack = java.util.ArrayDeque<View>()
        stack.addLast(root)
        while (stack.isNotEmpty() && visited < 6000 && found.size < 8) {
            val v = stack.removeLast()
            visited++
            val li = touchListenerOf(v)
            if (li != null) {
                found += "${v.javaClass.name}#${System.identityHashCode(v)} -> ${li.javaClass.name}" +
                    if (v in collapseListViews) " [registered]" else ""
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) stack.addLast(v.getChildAt(i))
        }
        WeLogger.i(TAG, "touch-listener hosts (${found.size}, visited=$visited): ${found.joinToString(" | ")}")
    }

    private fun installNativeListenerHooks(hosts: List<View>) {
        for (v in hosts) tryInstallListenerHook(v, retry = true)
    }

    /**
     * 钩微信自己那个收面板监听器（`ChattingUIFragment.p1`: `t.setOnTouchListener(new w8())`）。
     *
     * 从视图实例里取出监听器对象、钩**它自己声明的** `onTouch` —— 类来自实例，**不写死混淆名**，
     * 触发点与微信原生收起完全同点（真机日志里这条流每次都到；原生自己在 79 里被门挡住不收，
     * 模块替它收）。
     *
     * ⚠️ v3.77 实测：footer `onAttachedToWindow` 时监听器**可能还没装上**（`p1` 稍后
     * `setOnTouchListener`），当时是静默 `continue` ⇒ 整个入口静默失效。本版改为
     * 「下一帧重试一次 + 失败打 W 日志」，不再有静默分支。
     */
    private fun tryInstallListenerHook(v: View, retry: Boolean) {
        val listener = touchListenerOf(v)
        if (listener == null) {
            if (retry) {
                runCatching { v.post { tryInstallListenerHook(v, retry = false) } }
                return
            }
            WeLogger.w(TAG, "native listener hook: no OnTouchListener on ${v.javaClass.name} (${v.javaClass.simpleName})")
            return
        }
        val cls = listener.javaClass
        if (!collapseHooked.add("L:${cls.name}")) return
        val target = runCatching {
            cls.reflekt().firstMethod {
                name = "onTouch"
                parameters { it.size == 2 && MotionEvent::class.java.isAssignableFrom(it[1]) }
            }
        }.getOrNull()
        if (target == null) {
            WeLogger.w(TAG, "native listener hook: ${cls.name} has no onTouch(View, MotionEvent)")
            return
        }
        target.hookBefore {
            handleCollapseTouch(
                args.getOrNull(0) as? View ?: return@hookBefore,
                args.getOrNull(1) as? MotionEvent ?: return@hookBefore,
                "native-listener:${cls.simpleName}"
            )
        }
        WeLogger.i(TAG, "panel collapse guard installed on native listener ${cls.name} (host=${v.javaClass.simpleName})")
    }

    /**
     * 收起判定的唯一入口（触摸钩与原生监听器钩共用）。
     *
     * 每条钩子前 [ENTRY_LOG_BUDGET] 次调用都记账（action / 被摸视图 / ty / 两条键盘判据），
     * 之后只在「展开态回合」打一行 fired —— 这样下一份日志无论成功失败都能自证：
     * 钩子没被叫到 ⇒ 没有 touch 行；被叫到但判据全 false ⇒ touch 行的 ty/ime/kbFrame 会说明为什么。
     */
    private fun handleCollapseTouch(touched: View?, ev: MotionEvent, source: String) {
        val action = ev.actionMasked
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP) return
        val lv = touched ?: return
        val footer = collapseListViews[lv]
            ?: footerAbove(lv)?.also {
                collapseListViews[lv] = it
                WeLogger.i(TAG, "panel collapse: no map entry for ${lv.javaClass.name}, resolved from tree")
            }
        val budget = collapseEntryBudget[source] ?: 0
        val kbFrame = footer?.imeHeightByFrame() ?: 0
        if (budget < ENTRY_LOG_BUDGET) {
            collapseEntryBudget[source] = budget + 1
            WeLogger.i(
                TAG,
                "panel collapse touch [$source]: action=$action view=${lv.javaClass.name}" +
                    "#${System.identityHashCode(lv)} footer=${footer != null} " +
                    "ty=${footer?.translationY} ime=${footer?.isImeVisible} kbFrame=$kbFrame"
            )
        }
        val f = footer ?: return
        val rootH = f.rootView.height
        // 键盘判据两条并用：insets（现代）与「可见帧差」（传统/兜底）——
        // v3.77 只信 insets，一旦该信号为 false 就会静默跳过整个判定。
        val imeVisible = f.isImeVisible || (rootH > 0 && kbFrame > rootH / 6)
        val ty = f.translationY
        if (ty > -8f && !imeVisible) {
            collapseEpisodeLogged = false
            return
        }
        if (!collapseEpisodeLogged) {
            collapseEpisodeLogged = true
            WeLogger.i(
                TAG,
                "panel collapse fired: action=$action view=${lv.javaClass.name} ty=$ty " +
                    "ime=${f.isImeVisible} kbFrame=$kbFrame src=$source"
            )
        }
        collapsePanelNative(f)
        // 键盘态再补一手 IME 收起：微信的 K0 只改面板状态位（79 的 K0(Z) = Q(0,z,-1)），
        // 键盘不归它管。用系统 IME API 自己收，收完微信自己的 inset 回调会把卡片位置拉回来。
        if (imeVisible) hideImeFor(f)
    }

    /**
     * 键盘高度（传统判据：可见帧底边与根视图底边之差），insets 不可靠时的第二判据。
     * 未显示键盘时该值 ≈ 导航栏高度（远小于根高 1/6），故不会被误判。
     */
    private fun View.imeHeightByFrame(): Int = runCatching {
        val r = android.graphics.Rect()
        getWindowVisibleDisplayFrame(r)
        (rootView.height - r.bottom).coerceAtLeast(0)
    }.getOrDefault(0)

    /**
     * 兜底要注册的视图：外层 MMChattingListView + 它的内层消息列表（`getListView()`）。
     *
     * 内层列表与 `ChattingUIFragment.p1` 里 `t = MMChattingListView.getListView()` 是同一次调用的
     * 结果 ⇒ 意图是「和微信挂监听器的是同一个对象」。⚠️ 但 v3.77 日志证明这个假设**不足以**让钩子
     * 被叫到（同一页里三条钩子零 "fired"，而原生监听器流照样有），所以本版同时走
     * [logTouchListenerHosts]（结构取证）与 [tryInstallListenerHook]（直接钩监听器实例）。
     */
    private fun ChatFooter.listViewsForCollapse(): List<View> {
        val outer = chatListView() ?: return emptyList()
        val out = LinkedHashSet<View>()
        out.add(outer)
        val inner = runCatching {
            outer.reflekt().firstMethod { name = "getListView" }.invoke() as? View
        }.getOrNull()
        if (inner != null) out.add(inner)
        return out.toList()
    }

    /**
     * 给一个视图挂触摸钩：`onTouchEvent` 与 `dispatchTouchEvent` **都挂**（各自沿继承链找声明类）。
     *
     * ⚠️ 两者都要：`dispatchTouchEvent` 对「所有分发到该视图的触摸」都会触发（v3.76 实测外层靠它收到过
     * 20 次），而 `onTouchEvent` 只在没有子视图消费时触发 —— v3.77 只挂 onTouchEvent 是倒退。
     * 落到 `android.view.View`/`ViewGroup` 基类则放弃（那是全进程公共路径）。
     */
    private fun installTouchHooks(v: View) {
        for (mName in listOf("onTouchEvent", "dispatchTouchEvent")) {
            val key = "M:${v.javaClass.name}:$mName"
            if (key in collapseHooked) continue
            collapseHooked.add(key)
            val target = runCatching {
                v.javaClass.reflekt().firstMethod {
                    name = mName
                    superclass(true)
                    parameters { it.size == 1 && MotionEvent::class.java.isAssignableFrom(it[0]) }
                }
            }.getOrNull()
            if (target == null) {
                WeLogger.w(TAG, "panel collapse guard: no $mName on ${v.javaClass.name}")
                continue
            }
            val declaring = runCatching { target.self.declaringClass.name }.getOrDefault(v.javaClass.name)
            if (declaring == "android.view.View" || declaring == "android.view.ViewGroup") {
                WeLogger.w(TAG, "panel collapse guard: $mName only on $declaring, skipped (${v.javaClass.simpleName})")
                continue
            }
            target.hookBefore {
                handleCollapseTouch(
                    thisObject as? View ?: return@hookBefore,
                    args.getOrNull(0) as? MotionEvent ?: return@hookBefore,
                    "$declaring.$mName"
                )
            }
            WeLogger.i(TAG, "panel collapse guard installed: ${v.javaClass.simpleName} -> $declaring.$mName")
        }
    }

    /** 收起入口的一条候选（列表顺序即优先级）。 */
    private class CollapseEntry(val label: String, val invoke: (ChatFooter) -> Unit)

    /**
     * 微信自己的收起入口。**不能写死混淆名** —— 2026-10-15 真机实测（同一台机器切宿主，
     * 日志 `DexCacheManager: host version changed: 8.0.793182 -> 8.0.773141`）：
     * - 8.0.79(3182)：`ChatFooter.K0(Z)V` 存在（体 = `Q(0,z,-1)`）⇒ 兜底生效（fired 119 / failed 0）；
     * - 8.0.77(3141)：**没有 K0**，等价物叫 `L0(Z)V`（体 = `N(0,z,-1)`，与 79 的 K0 逐指令同构）
     *   ⇒ v3.78 的兜底在这台上 91 次调用全部失败
     *   （`native panel collapse failed: No method matching conditions in …ChatFooter`），
     *   表现就是「表情面板态点聊天区不收」；键盘态靠 [hideImeFor] 侥幸还能收。
     *
     * 三级探测（顺序即优先级，命中即缓存，不再每次触摸都做反射查找）：
     * ① `K0(Z)` —— 8.0.79 的现状路径，先试它保证已生效的行为一字不变；
     * ② `setBottomPanelVisibility(I)` —— **8.0.77 / 8.0.79 两版同名且未混淆**（`ChatFooter` 是 sdk 类），
     *    方法体 39 条指令逐条同构：参数 ≠ 0 时调 K0/L0(true)，之后还会通知面板可见性监听者
     *    ⇒ 比直接调 L0 更完整，也是抗混淆的长期入口；
     * ③ `L0(Z)` —— 8.0.77 的等价物，②万一将来也被改名时的保底。
     */
    private val collapseEntries = listOf(
        CollapseEntry("K0(Z)") { footer ->
            CHAT_FOOTER_CLASS.toClass().reflekt()
                .firstMethod {
                    name = "K0"
                    parameters { it.size == 1 && it[0] == Boolean::class.javaPrimitiveType }
                }.invoke(footer, true)
        },
        CollapseEntry("setBottomPanelVisibility(GONE)") { footer ->
            CHAT_FOOTER_CLASS.toClass().reflekt()
                .firstMethod {
                    name = "setBottomPanelVisibility"
                    parameters { it.size == 1 && it[0] == Int::class.javaPrimitiveType }
                }.invoke(footer, View.GONE)
        },
        CollapseEntry("L0(Z)") { footer ->
            CHAT_FOOTER_CLASS.toClass().reflekt()
                .firstMethod {
                    name = "L0"
                    parameters { it.size == 1 && it[0] == Boolean::class.javaPrimitiveType }
                }.invoke(footer, true)
        },
    )

    /** 已命中的收起入口（null = 尚未解析；[collapseEntryResolved] 为 true 而本值仍 null = 三级全无）。 */
    private var collapseEntry: CollapseEntry? = null
    private var collapseEntryResolved = false

    private fun collapsePanelNative(footer: ChatFooter) {
        val entry = collapseEntry
        if (entry != null) {
            runCatching { entry.invoke(footer) }.onFailure {
                WeLogger.w(TAG, "panel collapse via ${entry.label} threw: ${it.message}")
            }
            return
        }
        // 三级全无时只记一行 —— v3.78 在 8.0.77 上把同一句失败日志刷了 91 遍。
        if (collapseEntryResolved) return
        collapseEntryResolved = true
        for (candidate in collapseEntries) {
            if (runCatching { candidate.invoke(footer) }.isSuccess) {
                collapseEntry = candidate
                WeLogger.i(TAG, "panel collapse entry resolved: ${candidate.label}")
                return
            }
        }
        WeLogger.w(
            TAG,
            "native panel collapse unavailable on ${HostInfo.versionName}: none of " +
                collapseEntries.joinToString { it.label } + " matched $CHAT_FOOTER_CLASS"
        )
    }

    private fun hideImeFor(footer: ChatFooter) {
        runCatching {
            val imm = footer.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(footer.windowToken, 0)
        }.onFailure {
            WeLogger.w(TAG, "hide ime failed: ${it.message}")
        }
    }

    /**
     * 从被触摸的视图往上找 ChattingScrollLayout，再在里面找 ChatFooter。
     *
     * 兜底反查用（WeakHashMap 里的映射万一过期/换页也不至于整块失效）；
     * 不按 activity/高度判断 —— v1.31 scope guard 的教训。
     */
    private fun footerAbove(v: View): ChatFooter? {
        var node: View? = v
        while (node != null) {
            if (node.javaClass.name == CHATTING_SCROLL_LAYOUT_CLASS) {
                return node.allViews.firstOrNull { it is ChatFooter } as ChatFooter?
            }
            node = node.parent as? View
        }
        return null
    }

    /**
     * translationY 变了不会自动重算 outline, 得手动 invalidate。
     *
     * 不会自激: 下一帧 translationY 没变就不再 invalidate。
     */
    private fun trackOutlineWhileScrolling(footer: ChatFooter) {
        if (outlineTrackers.put(footer, true) != null) return
        var lastY = Float.NaN
        var lastBottom = -1
        footer.viewTreeObserver.addOnPreDrawListener {
            // translationY 变了不会自动重算 outline: 展开动画/滚动时手动纠正。
            val y = footer.translationY
            if (y != lastY) {
                lastY = y
                footer.invalidateOutline()
            }
            // 面板高度(offscreenHeight 决定裁剪下边界)就位/变化时同步重算, 否则折叠重开等
            // 场景首帧面板未就位会把圆角矩形盖满整卡、灰板延伸到屏幕底。此处对「底部变为
            // 非 0」也会响应 —— 首帧 offscreenHeight=0 时记下, 面板一到就重算。
            val bottom = offscreenHeight(footer)
            if (bottom != lastBottom) {
                lastBottom = bottom
                footer.invalidateOutline()
            }
            true
        }
    }

    /**
     * 导航栏 inset 变化后重算 footer 底边距。键盘弹出/收起、旋转、手势模式切换都会触发
     * 一帧 pre-draw。apply* 内部都有"目标值没变就不动"的判断, 所以每帧调用是幂等的,
     * 还能兜底纠正面板开关瞬间 refreshBottomHeight 用旧高度算出的瞬时错误值。
     */
    private fun trackNavBarInset(footer: ChatFooter) {
        val listener = ViewTreeObserver.OnPreDrawListener {
            applyBottomGap(footer)
            liftNewMessageBubble(footer)
            true
        }
        // 会话页会复用同一个 footer 实例: 重挂前先摘旧监听, 防止旧 observer 已失效或重复触发
        navInsetPreDraws.remove(footer)?.let { old ->
            runCatching { footer.viewTreeObserver.removeOnPreDrawListener(old) }
        }
        navInsetPreDraws[footer] = listener
        footer.viewTreeObserver.addOnPreDrawListener(listener)
    }

    /**
     * 「x条新消息」气泡 (HistoryMsgTongueComponent 的 mGoBackToHistoryMsgLayout, 布局 id
     * bm4) 是 ChattingContent 的直接子 View, 始终贴着内容底边。悬浮卡片用负 topMargin
     * 盖住了内容底部一截, 微信自己的 44dp 底边距就不够用了, 气泡下半截会被卡片挡住。
     * 这里按 footer 实际绘制位置算出卡片盖住的高度, 在微信设定的 margin 之上补足,
     * 保留原版的气泡与输入框间距。
     */
    private fun liftNewMessageBubble(footer: ChatFooter) {
        val bubble = footer.newMessageBubble() ?: return
        val lp = bubble.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.gravity and Gravity.VERTICAL_GRAVITY_MASK != Gravity.BOTTOM) return
        val content = bubble.parent as? View ?: return
        val contentBottom = content.bottom + content.translationY
        val cardTop = footer.top + footer.translationY
        val overlap = (contentBottom - cardTop).toInt().coerceAtLeast(0)
        if (overlap <= 0) return
        val gap = (NEW_MSG_BUBBLE_GAP_DP * footer.resources.displayMetrics.density).toInt()
        val target = maxOf(lp.bottomMargin, overlap + gap)
        if (lp.bottomMargin == target) return
        lp.bottomMargin = target
        bubble.layoutParams = lp
    }

    /**
     * 从 ChattingScrollLayout 里定位「x条新消息」气泡。不碰资源表, 也不依赖逐版本混淆的
     * 资源名/类名: 气泡是 ChattingContent (footer 的兄弟节点) 的直接子 LinearLayout,
     * 内容区居中, 由 WeImageView + TextView 组成。微信同屏的「翻到顶部/底部」提示条虽然
     * 也是 WeImageView + TextView, 但内容是 center_vertical 且带 elevation, 不会命中;
     * 引用气泡用普通 ImageView; 其余提示视图都嵌在更深层, 只查直接子节点即可排除。
     */
    private fun ChatFooter.newMessageBubble(): View? {
        newMessageBubbles[this]?.takeIf {
            it.isAttachedToWindow && (it.parent as? ViewGroup)?.parent === parent
        }?.let { return it }
        val scrollLayout = parent as? ViewGroup ?: return null
        for (i in 0 until scrollLayout.childCount) {
            val sibling = scrollLayout.getChildAt(i) as? ViewGroup ?: continue
            if (sibling === this) continue
            for (j in 0 until sibling.childCount) {
                val candidate = sibling.getChildAt(j)
                if (candidate.isNewMessageBubble()) {
                    newMessageBubbles[this] = candidate
                    return candidate
                }
            }
        }
        if (newMessageBubbleLookupWarned.put(this, true) == null) {
            WeLogger.w(TAG, "new message bubble not found, lift skipped")
        }
        return null
    }

    private fun View.isNewMessageBubble(): Boolean {
        if (this !is LinearLayout) return false
        val g = gravity
        if (g and Gravity.VERTICAL_GRAVITY_MASK != Gravity.CENTER_VERTICAL) return false
        if (g and Gravity.HORIZONTAL_GRAVITY_MASK != Gravity.CENTER_HORIZONTAL) return false
        var hasIcon = false
        var hasText = false
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (!hasIcon && child.javaClass.name == WE_CHAT_ICON_VIEW) hasIcon = true
            if (!hasText && child is TextView) hasText = true
            if (hasIcon && hasText) return true
        }
        return false
    }

    /**
     * 需要补到 footer 底边的导航栏 inset。
     *
     * 只有窗口真的铺到导航栏后面 (微信 edge-to-edge 开关打开) 才补 —— 否则内容区本身就
     * 在导航栏上方结束, 再补会把输入框顶高。键盘弹出时导航栏被 IME 盖住,
     * getInsets(navigationBars()) 自然返回 0, 不用单独判断键盘状态。
     */
    private fun bottomNavBarInsetToAdd(footer: ChatFooter): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        if (!footer.isWindowBehindNavBar()) return 0
        return footer.rootWindowInsets?.getInsets(WindowInsets.Type.navigationBars())?.bottom ?: 0
    }

    @Suppress("DEPRECATION")
    private fun View.isWindowBehindNavBar(): Boolean {
        val activity = context.activityOrNull() ?: return false
        return activity.window.decorView.systemUiVisibility and
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION != 0
    }

    private tailrec fun Context.activityOrNull(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activityOrNull()
        else -> null
    }

    /**
     * 从 footer 所在的 ChattingScrollLayout 里定位消息列表宿主 MMChattingListView。
     * 兜底 hook 的目标类就是它；找不到 recycler（如 8.0.79 换了实现）也能注册。
     *
     * 只认「本 footer 自己那棵树」里的实例：**不能**用「已注册的任意一个还挂着的列表视图」
     * 顶上（v3.70~v3.76 的写法）—— 会话页换页后那个实例属于别的页面，会把映射写成
     * key=旧实例、value=新 footer，于是真实触摸永远查不到 footer（表现就是「刚进来能用、
     * 用着用着就不收」）。
     */
    private fun ChatFooter.chatListView(): View? {
        chatListHosts[this]?.takeIf { it.isAttachedToWindow }?.let { return it }
        val scrollLayout = parent as? View
        if (scrollLayout?.javaClass?.name != CHATTING_SCROLL_LAYOUT_CLASS) {
            logChatListLookup("parent=${scrollLayout?.javaClass?.name}")
            return null
        }
        return scrollLayout.allViews.firstOrNull {
            it.javaClass.name == MM_CHATTING_LIST_VIEW_CLASS
        }?.also { chatListHosts[this] = it } ?: run {
            logChatListLookup("MMChattingListView missing")
            null
        }
    }

    /** 从 footer 所在的 ChattingScrollLayout 里定位消息列表的 RecyclerView。 */
    private fun ChatFooter.chatRecycler(): View? {
        chatListRecyclers[this]?.takeIf { it.isAttachedToWindow }?.let { return it }
        val found = findChatRecycler(this)
        if (found != null) chatListRecyclers[this] = found
        return found
    }

    private fun View.findChatRecycler(footer: ChatFooter): View? {
        val scrollLayout = parent as? View
        if (scrollLayout?.javaClass?.name != "com.tencent.mm.pluginsdk.ui.chat.ChattingScrollLayout") {
            footer.logChatListLookup("parent=${scrollLayout?.javaClass?.name}")
            return null
        }
        // 只用类名字符串定位, 不用 `is` 跨类加载器判断: 模块自带的 androidx 类
        // 与微信进程里的类不是同一个 Class 对象, `is RecyclerView` 会静默失败。
        val listHost = scrollLayout.allViews.firstOrNull {
            it.javaClass.name == "com.tencent.mm.ui.chatting.view.MMChattingListView"
        }
        if (listHost == null) {
            footer.logChatListLookup("MMChattingListView missing")
            return null
        }
        val recycler = listHost.allViews.firstOrNull { it.isChatRecycler() }
        if (recycler == null) footer.logChatListLookup("chat recycler missing")
        return recycler
    }

    private fun View.isChatRecycler(): Boolean {
        val name = javaClass.name
        if (name == "com.tencent.mm.pluginsdk.ui.tools.ScrollControlRecyclerView" ||
            name == "com.tencent.mm.pluginsdk.ui.tools.ChattingRecyclerView"
        ) {
            return true
        }
        // 兜底: 用视图自己的 classloader 判定宿主 RecyclerView 子类
        val hostRecycler = runCatching {
            Class.forName(
                "androidx.recyclerview.widget.RecyclerView",
                false,
                javaClass.classLoader
            )
        }.getOrNull() ?: return false
        return hostRecycler.isInstance(this)
    }

    private fun ChatFooter.logChatListLookup(reason: String) {
        if (chatListLookupWarned.put(this, true) == null) {
            WeLogger.w(TAG, "chat list recycler not found ($reason), bottom blank skipped")
        }
    }

    /**
     * 悬浮卡片实际占用的底部高度（= 给消息列表补的 padding，含额外间距）。
     *
     * 供同页其它悬浮挂件避让：「AI分析」胶囊必须浮在卡片上沿之上，否则被卡片盖住
     * （v3.23 真机实测：胶囊贴底 85px，卡片占用 241px ⇒ 完全看不到）。不悬浮时为 0。
     */
    @Volatile
    var reservedBottomPx: Int = 0
        private set

    private val zoneListeners = CopyOnWriteArrayList<(Int) -> Unit>()

    /** 订阅「卡片占用高度」变化（参数 = 新的 [reservedBottomPx]），挂件据此重新定位。 */
    fun addZoneListener(listener: (Int) -> Unit) {
        zoneListeners.addIfAbsent(listener)
    }

    fun removeZoneListener(listener: (Int) -> Unit) {
        zoneListeners.remove(listener)
    }

    private fun publishReservedBottom(px: Int) {
        if (reservedBottomPx == px) return
        reservedBottomPx = px
        for (l in zoneListeners) runCatching { l(px) }
    }

    override fun onDisable() {
        reservedBottomPx = 0
        zoneListeners.clear()
    }

    /**
     * 卡片悬浮在列表之上后, 给消息列表底部补 [extra] 的 padding, 让最后一条消息在滚到底时
     * 停在卡片上沿而不是藏在卡片后面。RecyclerView 本身 clipToPadding=false, 滚动时消息
     * 会正常从卡片和小白条背后穿过。
     *
     * 补 padding 本身不会移动现有滚动位置: 如果列表此刻正停在旧的底端, 还要顺着新 padding
     * 再往下滚一段, 最新消息才会立刻从卡片后面露出。
     */
    private fun applyChatListPadding(footer: ChatFooter, extra: Int) {
        val recycler = footer.chatRecycler()
        if (recycler == null) {
            footer.logChatListLookup("lookup failed")
            return
        }
        val base = chatListBasePaddings.getOrPut(recycler) { recycler.paddingBottom }
        val target = base + extra
        val old = recycler.paddingBottom
        publishReservedBottom(extra)
        if (old == target) return
        val wasAtBottom = !recycler.canScrollVertically(1)
        recycler.setPadding(recycler.paddingLeft, recycler.paddingTop, recycler.paddingRight, target)
        WeLogger.d(
            TAG,
            "chat list bottom padding: $old -> $target (extra=$extra atBottom=$wasAtBottom)"
        )
        if (wasAtBottom && target > old) {
            // 滚动到新的 padding 底端, 让最新消息从卡片后露出; 用户正翻旧消息时不打扰
            recycler.scrollBy(0, target - old)
        }
    }

    /** 左右留白, 让 footer 看起来是一张与屏幕边缘脱开的悬浮卡。 */
    private fun applySideMargins(footer: ChatFooter) {
        val lp = footer.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val sideMarginPx = (sideMarginDp * footer.resources.displayMetrics.density).toInt()
        lp.leftMargin = sideMarginPx
        lp.rightMargin = sideMarginPx
        footer.requestLayout()
        WeLogger.d(TAG, "applied side margins: side=${sideMarginDp}dp")
    }

    /**
     * 底部间距 + 悬浮。面板在输入行下方, 微信写入的 `bottomMargin = -面板高` 必须保留
     * (否则输入行会被推出屏幕), 只能在它之上叠加。
     *
     * 直接从面板的 LayoutParams 取那个"面板高"重算, 而不是在现值上做加法 —— 加法在
     * onAttachedToWindow 与 refreshBottomHeight 都会调到时会重复累加。
     *
     * **悬浮靠负 topMargin**: 只补 bottomMargin 时 footer 仍在 LinearLayout 流里实占
     * 「输入行高 + 间距」, 把 weight=1 的 ChattingContent 挤短同样的高度 —— 而壁纸
     * (ChattingImageBGView) 是 ChattingContent 的子 View, 它画到哪儿壁纸就到哪儿, 于是
     * 卡片下方与两侧露出窗口底色 (#EDEDED), 看着像卡片后面垫了一条灰底栏。把 topMargin
     * 设成 `-(可见高 + 间距)` 让 footer 净占 0 高度, 卡片位置一像素不变 (卡片顶 = H -
     * 可见高 - 间距, 与原先 ChattingContent 结束处重合), 但壁纸恢复铺到屏幕底边,
     * 卡片才真正是悬浮的胶囊。
     */
    private fun applyBottomGap(footer: ChatFooter) {
        val lp = footer.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val panelHeight = footer.bottomPanel?.layoutParams?.height ?: return
        if (panelHeight <= 0) return
        val gapPx = (bottomGapDp * footer.resources.displayMetrics.density).toInt()
        val extraBottom = gapPx + bottomNavBarInsetToAdd(footer)
        val targetBottom = -panelHeight + extraBottom
        // 卡片可见的那一段 = footer 总高扣掉被负 bottomMargin 挤到屏幕外的面板。
        val visible = footer.height - panelHeight
        // 还没量到真实高度 (onAttachedToWindow 首次调用) 时退回"不悬浮", 由 pre-draw
        // 监听在下一帧补上, 免得拿一个错的 topMargin 把输入行拉飞。
        val targetTop = if (visible > 0) -(visible + extraBottom) else 0
        if (lp.bottomMargin != targetBottom || lp.topMargin != targetTop) {
            lp.bottomMargin = targetBottom
            lp.topMargin = targetTop
            footer.requestLayout()
        }
        // 卡片现在盖在列表上方, 给列表底部补出同样的高度, 最后一条消息才不会藏在卡片后面
        if (visible > 0) {
            applyChatListPadding(footer, visible + extraBottom)
        } else {
            // 不悬浮（面板未就位/被关掉）⇒ 底部不再被占，挂件可以贴底
            publishReservedBottom(0)
        }
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var cornerInput by remember { mutableFloatStateOf(cornerRadiusDp.toFloat()) }
            var sideInput by remember { mutableFloatStateOf(sideMarginDp.toFloat()) }
            var gapInput by remember { mutableFloatStateOf(bottomGapDp.toFloat()) }
            var elevInput by remember { mutableFloatStateOf(elevationDp.toFloat()) }

            AlertDialogContent(
                title = { Text("悬浮输入框") },
                text = {
                    DefaultColumn {
                        ListItem(
                            content = { Text("圆角半径: ${cornerInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = cornerInput,
                                    onValueChange = { cornerInput = it },
                                    valueRange = MIN_CORNER_RADIUS.toFloat()..MAX_CORNER_RADIUS.toFloat(),
                                    steps = MAX_CORNER_RADIUS - MIN_CORNER_RADIUS - 1
                                )
                            }
                        )
                        ListItem(
                            content = { Text("侧边距: ${sideInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = sideInput,
                                    onValueChange = { sideInput = it },
                                    valueRange = MIN_SIDE_MARGIN.toFloat()..MAX_SIDE_MARGIN.toFloat(),
                                    steps = MAX_SIDE_MARGIN - MIN_SIDE_MARGIN - 1
                                )
                            }
                        )
                        ListItem(
                            content = { Text("底部间距: ${gapInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = gapInput,
                                    onValueChange = { gapInput = it },
                                    valueRange = MIN_BOTTOM_GAP.toFloat()..MAX_BOTTOM_GAP.toFloat(),
                                    steps = MAX_BOTTOM_GAP - MIN_BOTTOM_GAP - 1
                                )
                            }
                        )
                        ListItem(
                            content = { Text("阴影强度: ${elevInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = elevInput,
                                    onValueChange = { elevInput = it },
                                    valueRange = MIN_ELEVATION.toFloat()..MAX_ELEVATION.toFloat(),
                                    steps = MAX_ELEVATION - MIN_ELEVATION - 1
                                )
                            }
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        cornerRadiusDp = cornerInput.roundToInt()
                        sideMarginDp = sideInput.roundToInt()
                        bottomGapDp = gapInput.roundToInt()
                        elevationDp = elevInput.roundToInt()
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }
}
