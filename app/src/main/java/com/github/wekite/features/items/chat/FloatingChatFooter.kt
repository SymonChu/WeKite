package com.github.wekite.features.items.chat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Outline
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.tencent.mm.pluginsdk.ui.chat.ChatFooterBottom
import com.tencent.mm.pluginsdk.ui.chat.ChattingUILayout
import dev.ujhhgtg.reflekt.reflekt
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
import com.github.wekite.ui.content.dialogSliderColors
import com.github.wekite.ui.content.dialogSwitchColors
import com.github.wekite.ui.utils.ListItem
import com.github.wekite.ui.utils.allViews
import com.github.wekite.ui.utils.findViewWhich
import com.github.wekite.ui.utils.showComposeDialog
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

    private const val DEFAULT_BLUR_RADIUS = 8
    private const val MIN_BLUR_RADIUS = 0
    private const val MAX_BLUR_RADIUS = 40

    /** 微信原版「x条新消息」气泡与输入行之间的留白 (支持版本恒为 44dp)。 */
    private const val NEW_MSG_BUBBLE_GAP_DP = 44

    /** 聊天内容区类名 (壁纸 + 消息列表的宿主), 玻璃层的采样源。 */
    private const val CHATTING_CONTENT_CLASS = "com.tencent.mm.pluginsdk.ui.chat.ChattingContent"

    /** 气泡图标的宿主类名 (布局 XML 直接引用, 不会被混淆)。 */
    private const val WE_CHAT_ICON_VIEW = "com.tencent.mm.ui.widget.imageview.WeImageView"

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

    /** 已注册的 pre-draw 监听, 重挂会话时先摘掉旧的再挂新的, 避免监听失效。 */
    private val navInsetPreDraws = WeakHashMap<View, ViewTreeObserver.OnPreDrawListener>()

    /** 每个 footer 对应的聊天内容区 (ChattingContent), 玻璃层的采样源。 */
    private val chatContents = WeakHashMap<View, View>()

    /** 每个 footer 对应的真正药丸输入框宿主，不是外层 ChatFooter。 */
    private val inputPills = WeakHashMap<View, View>()

    /** 已经报过"找不到内容区"警告的 footer, 避免每帧刷日志。 */
    private val chatContentWarned = WeakHashMap<View, Boolean>()

    private var cornerRadiusDp by prefOption("floating_chat_footer_corner_radius", DEFAULT_CORNER_RADIUS)
    private var sideMarginDp by prefOption("floating_chat_footer_side_margin", DEFAULT_SIDE_MARGIN)
    private var bottomGapDp by prefOption("floating_chat_footer_bottom_gap", DEFAULT_BOTTOM_GAP)
    private var elevationDp by prefOption("floating_chat_footer_elevation", DEFAULT_ELEVATION)
    private var useGlass by prefOption("floating_chat_footer_glass", false)
    private var blurRadiusDp by prefOption("floating_chat_footer_blur_radius", DEFAULT_BLUR_RADIUS)


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

        // 绘制属性不依赖 LayoutParams, 构造完就能设。
        // 注意: 通知路径下 ChatFooter 可能由微信布局预取线程提前 inflate, 构造 hook 会在
        // 后台线程触发, 视图操作必须跳回主线程 (见 v2.11 修复③)。
        ChatFooter::class.constructor.hookAfter {
            val footer = thisObject as ChatFooter
            runOnMainThread { applyDrawingStyle(footer) }
        }

        // 结构改造与边距必须等 LayoutParams 就位 —— 它由父容器 (ChattingScrollLayout)
        // 在 addView() 时写入, 构造函数返回时还没有, 到 onAttachedToWindow 保证非空。
        //
        // applyDrawingStyle 在这里再来一次 (幂等): 运行时才打开本特性的话, 当前会话的
        // ChatFooter 早已构造完毕, 构造函数 hook 会整个错过。
        reflekt.firstMethod { name = "onAttachedToWindow" }.hookAfter {
            val footer = thisObject as ChatFooter
            runOnMainThread {
                applyDrawingStyle(footer)
                applySideMargins(footer)
                applyBottomGap(footer)
                trackNavBarInset(footer)
            }
        }

        // 微信在这里写入 bottomMargin = -面板高, 我们在它之后覆盖掉。
        methodRefreshBottomHeight.hookAfter {
            applyBottomGap(thisObject as ChatFooter)
        }
    }

    /**
     * 视图操作统一跳回主线程。hook 回调不保证在主线程: 通知路径下微信布局预取线程会提前
     * inflate ChattingUILayout/ChatFooter, 裸 WeakHashMap 读写 + 视图操作在后台线程执行
     * 会 ConcurrentModificationException 闪退 (见 v2.11 修复③)。主线程上直接执行, 零开销。
     */
    private inline fun runOnMainThread(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            Handler(Looper.getMainLooper()).post { block() }
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
        applyGlass(footer)
        trackOutlineWhileScrolling(footer)
        WeLogger.d(TAG, "applied drawing style: corner=${cornerRadiusDp}dp elev=${elevationDp}dp")
    }

    /**
     * 给底部药丸输入框本体垫液态玻璃。每帧调用, [FloatingChatGlass.apply] 内部幂等。
     *
     * 目标是可输入控件的直接父容器 (药丸宿主), 不是 ChatFooter 外层。这样玻璃只覆盖
     * 用户看到的药丸, 不会把整条微信底栏误当成药丸。
     */
    private fun applyGlass(footer: ChatFooter) {
        val pill = footer.inputPill()
        FloatingChatGlass.detach(footer)
        if (pill == null) return
        FloatingChatGlass.apply(
            owner = this,
            card = pill,
            source = footer.chatContent(),
            cornerRadiusDp = cornerRadiusDp,
            blurRadiusDp = blurRadiusDp,
            enabled = useGlass,
            liquid = true,
        )
    }

    /**
     * 定位开启悬浮输入框后底部显示的药丸本体。
     *
     * 微信不同版本的输入控件不一定仍然是 EditText, 但真正可输入的控件都会具备
     * focusableInTouchMode。玻璃挂在它的直接父 ViewGroup 上；不能挂在 ChatFooter，
     * 也不能挂在 TextView/EditText 自身，否则会覆盖光标/文字绘制或整栏铺满。
     */
    private fun ChatFooter.inputPill(): View? {
        inputPills[this]?.takeIf { it.isAttachedToWindow && it.parent != null }?.let { return it }
        val input = allViews.firstOrNull {
            it !== this && it is TextView && it.isFocusable && it.isFocusableInTouchMode
        }
        val pill = (input?.parent as? ViewGroup)?.takeIf { it !== this }
        if (pill != null) {
            inputPills[this] = pill
            WeLogger.d(
                TAG,
                "input pill host: ${pill.javaClass.name}, input=${input.javaClass.name}, " +
                    "size=${pill.width}x${pill.height}"
            )
        } else if (input != null) {
            WeLogger.w(TAG, "input control found but pill parent is unavailable: ${input.javaClass.name}")
        } else if (chatContentWarned.put(this, true) == null) {
            WeLogger.w(TAG, "editable input control not found in ChatFooter subtree")
        }
        return pill
    }

    /**
     * 同层的聊天内容区 (`ChattingContent`): 先在 footer 的父容器 (`ChattingScrollLayout`)
     * 的直接子 View 里找 —— 布局 XML 里两者就是兄弟。找不到 (版本差异) 再退到整棵会话页树里搜。
     */
    private fun ChatFooter.chatContent(): View? {
        chatContents[this]?.takeIf { it.isAttachedToWindow }?.let { return it }
        var found: View? = null
        // Footer 在不同微信版本/通知路径中的 parent 层级并不稳定。不能只查直接兄弟，
        // 也不能只沿 parent 链找 ChattingUILayout；8.0.72 的 footer 有时与内容区隔着
        // 预加载容器。和标题栏统一，从当前窗口根节点 DFS 查找真实 ChattingContent。
        // 该结果会缓存，只有缓存失效时才再次扫描。
        found = rootView?.allViews?.firstOrNull {
            it.javaClass.name == CHATTING_CONTENT_CLASS && it !== this
        }
        if (found != null) {
            chatContents[this] = found
        } else if (chatContentWarned.put(this, true) == null) {
            WeLogger.w(TAG, "ChattingContent not found, glass stays off for this footer")
        }
        return found
    }

    /** 沿 parent 链找最近的 ChattingUILayout 祖先。 */
    private fun View.ancestorChattingUiLayout(): View? {
        var parent = this.parent
        while (parent != null) {
            if (parent is ChattingUILayout) return parent
            parent = parent.parent
        }
        return null
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

    /**
     * translationY 变了不会自动重算 outline, 得手动 invalidate。
     *
     * 不会自激: 下一帧 translationY 没变就不再 invalidate。
     */
    private fun trackOutlineWhileScrolling(footer: ChatFooter) {
        if (outlineTrackers.put(footer, true) != null) return
        var last = Float.NaN
        footer.viewTreeObserver.addOnPreDrawListener {
            if (footer.translationY != last) {
                last = footer.translationY
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
            // 每帧自愈玻璃层 (幂等, 零成本): 与 Header 的 applyIfReady 行为对称 ——
            // 首帧 ChattingContent 尚未 add/查不到时, FloatingChatGlass.apply 会 early-return,
            // 下一帧兄弟节点就位后自动补挂, 不再"整生不生效" (见 v2.11 修复①)。
            applyGlass(footer)
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
        if (visible > 0) applyChatListPadding(footer, visible + extraBottom)
    }

    override fun onDisable() {
        FloatingChatGlass.detachAll(this)
        navInsetPreDraws.entries.toList().forEach { (footer, listener) ->
            runCatching { footer.viewTreeObserver.removeOnPreDrawListener(listener) }
        }
        navInsetPreDraws.clear()
        chatContents.clear()
        inputPills.clear()
        chatContentWarned.clear()
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var cornerInput by remember { mutableFloatStateOf(cornerRadiusDp.toFloat()) }
            var sideInput by remember { mutableFloatStateOf(sideMarginDp.toFloat()) }
            var gapInput by remember { mutableFloatStateOf(bottomGapDp.toFloat()) }
            var elevInput by remember { mutableFloatStateOf(elevationDp.toFloat()) }
            var useGlassInput by remember { mutableStateOf(useGlass) }
            var blurRadiusInput by remember { mutableFloatStateOf(blurRadiusDp.toFloat()) }


            AlertDialogContent(
                title = { Text("悬浮输入框") },
                text = {
                    DefaultColumn(
                        Modifier
                            .heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
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
                        ListItem(
                            content = { Text("启用液态玻璃效果") },
                            supportingContent = {
                                Text("输入框背后实时模糊聊天内容, 需 Android 12 以上")
                            },
                            trailingContent = {
                                Switch(
                                    useGlassInput,
                                    { useGlassInput = it },
                                    colors = dialogSwitchColors()
                                )
                            }
                        )
                        if (useGlassInput) {
                            ListItem(
                                content = {
                                    val r = blurRadiusInput.roundToInt()
                                    Text(if (r <= 0) "模糊半径: 关闭 (完全透明)" else "模糊半径: $r")
                                },
                                supportingContent = {
                                    Slider(
                                        value = blurRadiusInput,
                                        onValueChange = { blurRadiusInput = it },
                                        valueRange = MIN_BLUR_RADIUS.toFloat()..MAX_BLUR_RADIUS.toFloat(),
                                        steps = MAX_BLUR_RADIUS - MIN_BLUR_RADIUS - 1,
                                        colors = dialogSliderColors()
                                    )
                                }
                            )
                        }
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        cornerRadiusDp = cornerInput.roundToInt()
                        sideMarginDp = sideInput.roundToInt()
                        bottomGapDp = gapInput.roundToInt()
                        elevationDp = elevInput.roundToInt()
                        useGlass = useGlassInput
                        blurRadiusDp = blurRadiusInput.roundToInt()
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }
}
