package com.github.wekite.features.items.beautify.islands

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.wekite.features.items.beautify.ListIslands
import com.github.wekite.ui.utils.IslandRowPosition
import com.github.wekite.ui.utils.restoreIslandRow
import com.github.wekite.ui.utils.styleIslandRow
import com.github.wekite.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap

private const val TAG = "AddressIslands"

private val installedRecyclers = Collections.synchronizedSet(
    Collections.newSetFromMap(WeakHashMap<RecyclerView, Boolean>()),
)

/**
 * 通讯录页的圆角岛实现。
 *
 * ## 挂钩时机（两次踩坑后定案，2026-09-16 离线实测）
 * - v3.20 挂外层 Activity `AddressUI.onCreate` —— 更早，列表根本没建
 * - v3.21 挂 `MvvmAddressUIFragment.getLayoutView()` —— **只返回 inflate 出来的布局**，
 *   那一刻列表字段仍是 null。真机日志实证：
 *   `address RecyclerView not found under fragment root; contacts islands skipped`
 * - **v3.22 起挂 `MvvmAddressUIFragment.l0(Bundle)`** —— 字节码实测这里才执行
 *   `findViewById(...) -> WxRecyclerView` 赋值给字段、随后 `setAdapter` / `setLayoutManager` /
 *   `addView`。是整条链上**第一个列表已存在**的时点。
 *
 * ## 取列表的方式
 * 优先**按类型直接读该 Fragment 的字段**（那个字段就是 `WxRecyclerView`），
 * 而不是在视图树里递归搜索 —— 后者会受时序与层级变化影响，是前两次失败的同源原因。
 * 字段名是混淆的（当前为 `p`），所以按**类型**而非名字匹配，换版本不受名字变化影响。
 */
fun applyAddressIslands(feature: ListIslands, fragment: Any?) {
    if (!feature.isContactsEnabled) return
    if (fragment == null) {
        WeLogger.w(TAG, "address fragment is null; contacts islands skipped")
        return
    }

    val recycler = recyclerOfFragment(fragment)
    if (recycler == null) {
        WeLogger.w(TAG, "address WxRecyclerView field not found on fragment; contacts islands skipped")
        return
    }
    attach(feature, recycler)
}

/** 从 Fragment 实例上按类型取那个 `WxRecyclerView` 字段（含父类链）。 */
private fun recyclerOfFragment(fragment: Any): RecyclerView? {
    var cls: Class<*>? = fragment.javaClass
    while (cls != null) {
        for (field in cls.declaredFields) {
            if (!RecyclerView::class.java.isAssignableFrom(field.type)) continue
            val value = runCatching {
                field.isAccessible = true
                field.get(fragment)
            }.getOrNull()
            if (value is RecyclerView) return value
        }
        cls = cls.superclass
    }
    // 退化路径：字段取不到时，再从其 View 树里找一次（l0 已 addView，此时应已存在）。
    val root = runCatching {
        fragment.javaClass.getMethod("getView").invoke(fragment) as? View
    }.getOrNull()
    return root?.let { findRecyclerView(it) }
}

private fun attach(feature: ListIslands, recycler: RecyclerView) {
    styleVisibleChildren(feature, recycler)
    if (!installedRecyclers.add(recycler)) return

    recycler.addOnChildAttachStateChangeListener(
        object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                styleVisibleChildren(feature, recycler)
            }

            override fun onChildViewDetachedFromWindow(view: View) = Unit
        },
    )
    recycler.addOnScrollListener(
        object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                styleVisibleChildren(feature, recycler)
            }
        },
    )
    recycler.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        styleVisibleChildren(feature, recycler)
    }
    // ⚠️ l0() 返回时子项可能尚未完成首次布局（childCount=0），
    // 若那一刻什么都没处理，再补一次异步处理，避免「挂钩对了但没效果」。
    if (recycler.childCount == 0) {
        recycler.post { styleVisibleChildren(feature, recycler) }
    }
    WeLogger.i(TAG, "address islands attached (children=${recycler.childCount})")
}

private fun styleVisibleChildren(feature: ListIslands, recycler: RecyclerView) {
    val shape = feature.shape
    val grouped = feature.groupingEnabled
    val childCount = recycler.childCount
    if (childCount == 0) return

    if (!feature.isContactsEnabled) {
        for (index in 0 until childCount) restoreIslandRow(recycler.getChildAt(index))
        return
    }

    // 先给每个可见子项打上「分组归属指纹」，再据此切边界。
    // 同一指纹的相邻行属于同一块；指纹变化处就是分组边界。
    // ⚠️ 指纹必须**顺序继承**：字母标题行携带自己的指纹（alpha:A），
    // 其下的联系人行不带标题，要继承上面最近一次出现的指纹，否则所有字母的联系人会并成一块。
    val keys = ArrayList<String>(childCount)
    var carried = "entry"
    for (index in 0 until childCount) {
        val own = ownGroupKeyOf(recycler.getChildAt(index))
        if (own != null) carried = own
        keys.add(carried)
    }

    val hasAnyBoundary = keys.toSet().size > 1
    var groupStart = 0
    for (index in 0 until childCount) {
        if (!grouped) {
            styleIslandRow(recycler.getChildAt(index), shape, false, IslandRowPosition.SINGLE)
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
        styleIslandRow(recycler.getChildAt(index), shape, true, position)
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

private fun findRecyclerView(root: View): RecyclerView? {
    if (root is RecyclerView) return root
    if (root !is ViewGroup) return null
    for (index in 0 until root.childCount) {
        findRecyclerView(root.getChildAt(index))?.let { return it }
    }
    return null
}
