package com.github.wekite.features.items.beautify.islands

import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import com.github.wekite.features.items.beautify.ListIslands
import com.github.wekite.ui.utils.IslandRowPosition
import com.github.wekite.ui.utils.restoreIslandRow
import com.github.wekite.ui.utils.styleIslandRow
import com.github.wekite.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "IslandListStyler"

/**
 * 列表容器的统一「圆角卡片」处理器 —— 四个页面共用一套。
 *
 * ## 为什么要按「容器可见子项」而不是按「adapter 的 position」
 *
 * v3.21 是在 `getView` / `onBindViewHolder` 里**逐行**处理：每行自己去问 adapter
 * 「我前后是什么」来推断自己在块内的位置（主页），或按 item 类名猜分组（发现/我）。
 * 真机实测三个漏洞：
 * 1. **空白占位行判不出来**：item 是 Preference / 会话对象，没有「界面上有没有
 *    东西可看」的概念。v3.23 截图实测：发现页「朋友圈」下方一张 **81dp 全空**的卡
 *    （y=584..861 内 0 个暗像素）、我页「设置」下方一张约 35dp 的空卡。
 * 2. **表头看不见**：ListView 的表头不经过 adapter 的 `getView`，于是「我」页
 *    头像区（`AccountInfoPreference` 表头）与主页「已登录 N 台其他设备」横幅
 *    永远拿不到卡片（用户点名的正是这两处）。
 * 3. **回收复用错位**：RecyclerView 复用行时，逐行推断的位置可能来自旧行，
 *    对应「进聊天再退出后卡片形状不对」。
 *
 * 改成**按容器当前可见子项做一遍分组**：边界由各页自己的策略给出，相邻的非边界
 * 子项拼成一块；块首圆上两角、块尾圆下两角，块内接缝不画描边（描边只画外轮廓，
 * 见 [com.github.wekite.ui.utils.buildIslandBackground]）。
 *
 * ## 刷新触发（三条路都要，缺一即「有时对有时不对」）
 * ⚠️ 不能用 `RecyclerView.addOnScrollListener` —— 宿主的 RecyclerView 由**宿主
 * ClassLoader** 加载，它的 Listener 接口在模块里无法实现（跨 ClassLoader，
 * v3.22 血案）。所以只用**框架层** API：
 * 1. `View.addOnLayoutChangeListener` —— 容器自身尺寸变化（首次布局、键盘收放）
 * 2. `ViewTreeObserver.OnScrollChangedListener` —— ListView 滚动会触发；
 *    **RecyclerView 不会**（它靠 offsetChildren 移动子项，自身不 scroll）
 * 3. `ViewTreeObserver.OnPreDrawListener` + **廉价结构指纹闸门** —— 兜住 2 的盲区：
 *    每帧只读「子项数 + 首末子项的类名/top/bottom」（O(1)，不遍历子树），
 *    指纹变了才真跑分组。这样滚动出新行、表头后到、行被回收复用都能被补上，
 *    而静止时每帧的开销只有几次字段读取。
 */

/** 已挂过监听的容器。 */
private val watched = Collections.synchronizedSet(
    Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>()),
)

/** 上次处理时的廉价结构指纹。 */
private val lastSignature = Collections.synchronizedMap(WeakHashMap<ViewGroup, String>())

/** 结构诊断剩余行数（帮助定位「某些场景下形状不对」，用满即停）。 */
private val shapeLogBudget = AtomicInteger(30)

/** 上次打过结构日志的指纹（与它相同就不重复打）。 */
private val lastLoggedShape = Collections.synchronizedMap(WeakHashMap<ViewGroup, String>())

/** 某个子项之后是否要开一个新块。 */
internal fun interface GroupStart {
    fun isStart(index: Int, child: View): Boolean
}

/** 某个子项是否**不参与**分组（留白行 / 由别处单独管理的行，如设备横幅）。 */
internal fun interface ExcludedRow {
    fun isExcluded(index: Int, child: View): Boolean
}

/** 常见的排除策略：界面上没有可见内容 ⇒ 留白行。 */
internal val blankContentRow = ExcludedRow { _, child -> !hasVisibleContent(child) }

/**
 * 跑一遍分组并套卡。
 *
 * @param groupStart 新块起点判据（返回 true 的那一行会是该块的 FIRST）
 * @param excluded   不参与分组的行：**真留白行**会还原成微信原样，
 *                   有内容但不归这里管（如主页设备横幅）则原样不动
 * @param unreadOf   该行是否按「未读」配色（仅主页用）
 */
internal fun styleContainerChildren(
    feature: ListIslands,
    container: ViewGroup,
    groupStart: GroupStart,
    excluded: ExcludedRow,
    unreadOf: (View) -> Boolean = { false },
) {
    val childCount = container.childCount
    if (childCount == 0) return

    val grouped = feature.groupingEnabled
    var blockStart = -1

    for (index in 0 until childCount) {
        val child = container.getChildAt(index)

        if (excluded.isExcluded(index, child)) {
            // 真留白行还原成原样；由别处管理的行（横幅）保持现状。
            if (!hasVisibleContent(child)) restoreIslandRow(child)
            blockStart = -1
            continue
        }

        if (!grouped) {
            styleIslandRow(
                child, feature.shape,
                grouped = false, position = IslandRowPosition.SINGLE, unread = unreadOf(child),
            )
            continue
        }

        if (blockStart < 0 || groupStart.isStart(index, child)) blockStart = index
        val isFirst = index == blockStart

        // 块尾：下一个子项被排除、要开新块、或已到末尾
        val next = index + 1
        val isLast = next >= childCount ||
            excluded.isExcluded(next, container.getChildAt(next)) ||
            groupStart.isStart(next, container.getChildAt(next))

        val position = when {
            isFirst && isLast -> IslandRowPosition.SINGLE
            isFirst -> IslandRowPosition.FIRST
            isLast -> IslandRowPosition.LAST
            else -> IslandRowPosition.MIDDLE
        }
        styleIslandRow(
            child, feature.shape,
            grouped = true, position = position, unread = unreadOf(child),
        )
    }
}

/**
 * 挂监听 + 立刻跑一次分组。同一容器只挂一次监听，之后每次调用只重跑分组
 * （[styleIslandRow] 内部按指纹缓存，重复调用不会重建 Drawable）。
 */
internal fun ensureContainerStyled(
    feature: ListIslands,
    container: ViewGroup,
    groupStart: GroupStart,
    excluded: ExcludedRow,
    unreadOf: (View) -> Boolean = { false },
) {
    runGrouping(feature, container, groupStart, excluded, unreadOf)

    if (!watched.add(container)) return

    container.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        runGrouping(feature, container, groupStart, excluded, unreadOf)
        lastSignature[container] = signatureOf(container)
    }
    runCatching {
        container.viewTreeObserver.addOnScrollChangedListener {
            runGrouping(feature, container, groupStart, excluded, unreadOf)
            lastSignature[container] = signatureOf(container)
        }
    }
    runCatching {
        // 廉价闸门：指纹没变就不重跑（静止时每帧只读几个字段）。
        container.viewTreeObserver.addOnPreDrawListener {
            val now = signatureOf(container)
            if (lastSignature[container] != now) {
                lastSignature[container] = now
                runGrouping(feature, container, groupStart, excluded, unreadOf)
            }
            true
        }
    }
}

private fun runGrouping(
    feature: ListIslands,
    container: ViewGroup,
    groupStart: GroupStart,
    excluded: ExcludedRow,
    unreadOf: (View) -> Boolean,
) {
    styleContainerChildren(feature, container, groupStart, excluded, unreadOf)
    // 只在结构真的变了时留痕，且总量有预算（用户报「某些场景形状不对」时靠它定案）
    val sig = signatureOf(container)
    if (lastLoggedShape[container] != sig && shapeLogBudget.decrementAndGet() > 0) {
        lastLoggedShape[container] = sig
        logContainerShape(container)
    }
}

/**
 * 廉价结构指纹：子项数 + 首/末子项的类名与纵向位置。
 *
 * 只读 O(1) 个字段，**不遍历子树**，所以可以每帧调用。
 * 滚动（子项位移/换实例）、表头后到（子项数变化）、行被回收复用都会让它变化。
 */
private fun signatureOf(container: ViewGroup): String {
    val n = container.childCount
    if (n == 0) return "0"
    val first = container.getChildAt(0)
    val last = container.getChildAt(n - 1)
    return buildString(96) {
        append(n).append('|')
        append(first.javaClass.name).append(':').append(first.top).append(':').append(first.bottom)
        append('|')
        append(last.javaClass.name).append(':').append(last.top).append(':').append(last.bottom)
    }
}

/**
 * 打一行容器结构：每个可见子项的「位置 / 是否有内容 / 高宽」。
 *
 * 用途（用户报「进聊天再退出主页后卡片形状不对」时的定位依据）：
 * 卡片形状完全由**容器当前可见子项**决定，所以只要这行日志在「正常」与
 * 「异常」两种状态下一致，形状就不可能不同；若不一致，日志会直接指出是哪个
 * 子项变了（有没有内容 / 位置 / 高度）。
 */
private fun logContainerShape(container: ViewGroup) {
    val sb = StringBuilder("container shape: n=").append(container.childCount)
    for (i in 0 until container.childCount) {
        val c = container.getChildAt(i)
        sb.append(" | ").append(i)
            .append(':').append(c.javaClass.simpleName)
            .append(" c=").append(if (hasVisibleContent(c)) 1 else 0)
            .append(' ').append(c.width).append('x').append(c.height)
    }
    WeLogger.i(TAG, sb.toString())
}

/**
 * 从行 View 往上找列表容器。
 *
 * 用**类名字符串**匹配（跨 ClassLoader 安全 —— 模块里的 `androidx.recyclerview`
 * 与宿主自带的是两个 Class，`is` / `isAssignableFrom` 恒 false，v3.23 血案）。
 */
internal fun findListContainer(row: View): ViewGroup? {
    var current: View? = row.parent as? View
    var depth = 0
    while (current != null && depth < 16) {
        if (current is ViewGroup && isListContainerClass(current.javaClass)) return current
        current = current.parent as? View
        depth++
    }
    return null
}

private fun isListContainerClass(type: Class<*>): Boolean {
    var c: Class<*>? = type
    var depth = 0
    while (c != null && depth < 12) {
        when (c.name) {
            "android.widget.ListView",
            "android.widget.GridView",
            "android.widget.AbsListView",
            "androidx.recyclerview.widget.RecyclerView",
            "com.tencent.mm.view.recyclerview.WxRecyclerView",
            -> return true
        }
        if (c.name == "java.lang.Object") return false
        c = c.superclass
        depth++
    }
    return false
}

/**
 * 容器是不是 ListView 家族。
 *
 * ⚠️ `android.widget.ListView` 是**框架类**，模块与宿主共用同一个 Class，
 * 所以这里的 `is` 判断安全（与 `androidx.recyclerview` 不同，后者模块自带一份，
 * 必须用类名字符串比）。因此 `firstVisiblePosition` / `adapter` 可以放心用。
 */
internal fun asListView(container: ViewGroup): ListView? = container as? ListView
