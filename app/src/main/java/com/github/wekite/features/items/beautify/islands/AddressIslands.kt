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
 * ⚠️ v3.21 重写（v3.20 用户实测「通讯录不生效」）：
 * v3.20 挂在外层 Activity `AddressUI.onCreate`，那时内部 Fragment 的列表尚未建立。
 * 离线核验 8.0.77：真列表是 `MvvmAddressUIFragment` 内的 `WxRecyclerView`
 * （该类中仅 `l0(Bundle)` 与 `D0(I)` 触碰它）。现在由该 Fragment 的根视图向下找，
 * 与真列表同源。
 *
 * 分组规则（用户明确指定）：
 *  1. 「新的朋友」→「服务号」为**一块**（微信这批固定入口条目之间没有分组标题）
 *  2. 「我的企业 及 企业联系人」为**一块**
 *  3. 下面联系人按 **A / B / C / D …** 每个字母**各自一块**
 */
fun applyAddressIslands(feature: ListIslands, fragmentRoot: View?) {
    if (!feature.isContactsEnabled) return
    val recycler = fragmentRoot?.let { findRecyclerView(it) }
    if (recycler == null) {
        WeLogger.w(TAG, "address RecyclerView not found under fragment root; contacts islands skipped")
        return
    }
    styleVisibleChildren(feature, recycler)

    if (installedRecyclers.add(recycler)) {
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
        WeLogger.i(TAG, "address islands attached to RecyclerView")
    }
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
 *    开头这批固定入口因无前置指纹，继承初值 `entry`，于是「新的朋友 →≥ 服务号」自成一块
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
