package com.github.wekite.features.items.beautify.islands

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.github.wekite.features.items.beautify.ListIslands
import com.github.wekite.ui.utils.IslandRowPosition
import com.github.wekite.ui.utils.restoreIslandRow
import com.github.wekite.ui.utils.styleIslandRow
import com.github.wekite.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap

private const val TAG = "AddressIslands"

/** 宿主通讯录列表的真实类名（字段类型 = 它）。 */
private const val HOST_LIST_CLASS = "com.tencent.mm.view.recyclerview.WxRecyclerView"
/** androidx 列表基类名，用于「按名字」识别任何列表实现。 */
private const val RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"

/**
 * ⚠️⚠️ **判宿主对象类型只能比类名字符串，绝对不能用 `is` / `isAssignableFrom`**（v3.22 血案）
 *
 * 宿主对象由**微信的 ClassLoader** 加载，而模块代码里看到的
 * `androidx.recyclerview.widget.RecyclerView` 来自**模块自己的 ClassLoader** ——
 * 两者是**两个不同的 Class 对象**，所以：
 *
 * ```
 * RecyclerView::class.java.isAssignableFrom(field.type)   // 宿主字段类型 -> 恒 false
 * value is RecyclerView                                   // 宿主实例   -> 恒 false
 * ```
 *
 * v3.22 的失败正是这个：字段 `p` 明明就是 `WxRecyclerView`，却被判据筛掉，
 * 日志只留下 `address WxRecyclerView field not found on fragment`；**同一条错误也吃掉了
 * 视图树退化路径**（`root is RecyclerView`），于是两条路都没结果。
 *
 * 对照：主页用 `android.widget.BaseAdapter`（**系统框架类，模块与宿主共用同一个 Class 对象**）
 * 所以不做任何类型判定就没这个问题；发现/我页同理（不判类型）。**只有通讯录跨了 ClassLoader 判类型。**
 *
 * 推论：不只是判类型，**调方法也要挑 stub 上确实存在的那批**。
 * stub 的 `WxRecyclerView extends ViewGroup`（不是 RecyclerView），
 * 所以 `addOnScrollListener` / `addOnChildAttachStateChangeListener` / `setItemViewCacheSize`
 * 这些 RecyclerView 专有方法在编译期不可见 —— 刷新触发只能用 `View` / `ViewGroup` 的 API。
 */

private val installedLists = Collections.synchronizedSet(
    Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>()),
)

/**
 * 通讯录页的圆角岛实现。
 *
 * ## 挂钩时机（三次踩坑后定案，均以字节码实测为准）
 * - v3.20 挂外层 Activity `AddressUI.onCreate` —— 那一刻内部 Fragment 的列表根本没建
 * - v3.21 挂 `MvvmAddressUIFragment.getLayoutView()` —— **只 inflate 布局**，列表字段仍为 null
 * - **v3.22 起挂 `MvvmAddressUIFragment.l0(Bundle)`** —— 字节码实测此处才执行
 *   `check-cast -> WxRecyclerView` / `iput-object -> 字段 p` / `setAdapter` / `setLayoutManager` /
 *   `addView`，是整条链上**第一个「列表已存在」**的时点
 *
 * ## 取列表的方式（v3.23 修正）
 * 按**类名**在该 Fragment 的字段里找（字段名混淆，当前叫 `p`），沿父类链回溯；
 * 找不到再退化到视图树里按类名搜。**两条路径都只比类名字符串。**
 */
fun applyAddressIslands(feature: ListIslands, fragment: Any?) {
    if (!feature.isContactsEnabled) return
    if (fragment == null) {
        WeLogger.w(TAG, "address fragment is null; contacts islands skipped")
        return
    }

    val list = listOfFragment(fragment)
    if (list == null) {
        WeLogger.w(TAG, "address list field not found on fragment; contacts islands skipped")
        return
    }
    attach(feature, list)
}

/** 从 Fragment 实例上按类名取通讯录列表（含父类链）。 */
private fun listOfFragment(fragment: Any): ViewGroup? {
    var cls: Class<*>? = fragment.javaClass
    var depth = 0
    while (cls != null && depth < 12) {
        for (field in cls.declaredFields) {
            if (!isListType(field.type)) continue
            val value = runCatching {
                field.isAccessible = true
                field.get(fragment)
            }.getOrNull()
            if (value is ViewGroup) {
                WeLogger.i(TAG, "address list field resolved: ${field.name} -> ${field.type.name}")
                return value
            }
        }
        cls = cls.superclass
        depth++
    }

    // 退化路径：字段取不到时，从其根视图里按类名找一次（l0 已 addView，此时应已存在）
    val root = runCatching {
        fragment.javaClass.getMethod("getView").invoke(fragment) as? View
    }.getOrNull()
    return root?.let { findListInTree(it) }
}

/** 按**类名字符串**判断某个类型是不是宿主列表（跨 ClassLoader 安全）。 */
private fun isListType(type: Class<*>): Boolean = isListClass(type)

private fun isListClass(type: Class<*>): Boolean {
    var c: Class<*>? = type
    var depth = 0
    while (c != null && depth < 12) {
        val name = c.name
        if (name == HOST_LIST_CLASS || name == RECYCLER_VIEW_CLASS) return true
        // 走到系统框架类还没命中就不再上溯（避免把随便某个 View 判成列表）
        if (name.startsWith("android.view.") || name.startsWith("android.widget.")) return false
        if (name == "java.lang.Object") return false
        c = c.superclass
        depth++
    }
    return false
}

private fun findListInTree(root: View): ViewGroup? {
    if (isListClass(root.javaClass)) return root as? ViewGroup
    if (root !is ViewGroup) return null
    for (index in 0 until root.childCount) {
        findListInTree(root.getChildAt(index))?.let { return it }
    }
    return null
}

private fun attach(feature: ListIslands, list: ViewGroup) {
    styleVisibleChildren(feature, list)
    if (!installedLists.add(list)) return

    // 刷新触发：只用 stub 上确实存在的 View / ViewGroup API。
    // ⚠️ 不能用 RecyclerView 的 addOnScrollListener / addOnChildAttachStateChangeListener
    //    （stub 的 WxRecyclerView 继承 ViewGroup，这些方法编译期不可见）。
    list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        styleVisibleChildren(feature, list)
    }
    runCatching {
        list.viewTreeObserver.addOnScrollChangedListener { styleVisibleChildren(feature, list) }
    }

    // l0() 返回时子项可能尚未完成首次布局（childCount=0），补一次异步处理，
    // 避免「挂钩对了但没效果」。
    if (list.childCount == 0) {
        list.post { styleVisibleChildren(feature, list) }
    }
    WeLogger.i(TAG, "address islands attached (children=${list.childCount})")
}

private fun styleVisibleChildren(feature: ListIslands, list: ViewGroup) {
    val shape = feature.shape
    val grouped = feature.groupingEnabled
    val childCount = list.childCount
    if (childCount == 0) return

    if (!feature.isContactsEnabled) {
        for (index in 0 until childCount) restoreIslandRow(list.getChildAt(index))
        return
    }

    // 先给每个可见子项打上「分组归属指纹」，再据此切边界。
    // 同一指纹的相邻行属于同一块；指纹变化处就是分组边界。
    // ⚠️ 指纹必须**顺序继承**：字母标题行携带自己的指纹（alpha:A），
    // 其下的联系人行不带标题，要继承上面最近一次出现的指纹，否则所有字母的联系人会并成一块。
    val keys = ArrayList<String>(childCount)
    var carried = "entry"
    for (index in 0 until childCount) {
        val own = ownGroupKeyOf(list.getChildAt(index))
        if (own != null) carried = own
        keys.add(carried)
    }

    val hasAnyBoundary = keys.toSet().size > 1
    var groupStart = 0
    for (index in 0 until childCount) {
        if (!grouped) {
            styleIslandRow(list.getChildAt(index), shape, false, IslandRowPosition.SINGLE)
            continue
        }
        // 指纹变化 ⇒ 新的一块
        if (hasAnyBoundary && index > 0 && keys[index] != keys[index - 1]) groupStart = index
        val isFirstInGroup = index == groupStart
        val isLastInGroup = index == childCount - 1 ||
            (hasAnyBoundary && keys[index + 1] != keys[index])
        val position = when {
            isFirstInGroup && isLastInGroup -> IslandRowPosition.SINGLE
            isFirstInGroup -> IslandRowPosition.FIRST
            isLastInGroup -> IslandRowPosition.LAST
            else -> IslandRowPosition.MIDDLE
        }
        styleIslandRow(list.getChildAt(index), shape, true, position)
    }
}

/**
 * 这一行**自己**声明的分组指纹；返回 null 表示「无自己的指纹、应继承上一行的」。
 *
 * 规则（对应用户指定）：
 *  - 字母分组标题行 → `alpha:A` / `alpha:B` …，于是每个字母自成一块
 *  - 含「我的企业」的行（我的企业 / 企业联系人）→ `mine`，这两项合为一块
 *  - 其余行（联系人条目、新的朋友~服务号这批固定入口）→ null，继承上一个指纹；
 *    开头这批固定入口因无前置指纹，继承初值 `entry`，于是「新的朋友 → 服务号」自成一块
 */
private fun ownGroupKeyOf(row: View): String? {
    if (isSectionHeaderRow(row)) {
        val label = singleShortText(row)?.trim().orEmpty()
        if (label.isNotEmpty()) {
            // 单个字母/字符的标题（A~Z、#）按字母分组；更长的标题按标题文本分组
            return if (label.length == 1) "alpha:$label" else "section:$label"
        }
    }
    val text = collectTexts(row).joinToString(" ")
    if (text.contains("我的企业")) return "mine"
    return null
}

/**
 * 判定一个子项是否是通讯录的分组标题行。
 *
 * 结构性判据（不依赖文案与混淆类名）：不可点击、高度明显小于普通联系人行、只有一个短文本。
 */
private fun isSectionHeaderRow(row: View): Boolean {
    if (row.isClickable) return false
    val density = row.resources.displayMetrics.density
    val maxHeaderHeightPx = (40f * density).toInt()
    if (row.height <= 0 || row.height > maxHeaderHeightPx) return false
    val label = singleShortText(row) ?: return false
    return label.length <= 6
}

/** 行内只有唯一一个非空文本时返回它（分组标题的特征），否则 null。 */
private fun singleShortText(root: View): String? {
    val texts = collectTexts(root)
    return if (texts.size == 1) texts.first() else null
}

private fun collectTexts(root: View): List<String> {
    val out = ArrayList<String>()
    collectTextsInto(root, out)
    return out
}

private fun collectTextsInto(root: View, out: MutableList<String>) {
    if (root is ViewGroup) {
        for (index in 0 until root.childCount) collectTextsInto(root.getChildAt(index), out)
    }
    if (root is TextView) {
        val text = root.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) out += text
    }
}
