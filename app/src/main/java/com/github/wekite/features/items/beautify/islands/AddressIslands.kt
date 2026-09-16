package com.github.wekite.features.items.beautify.islands

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.wekite.features.items.beautify.ListIslands
import com.github.wekite.ui.utils.IslandPreset
import com.github.wekite.ui.utils.IslandRowPosition
import com.github.wekite.ui.utils.restoreIslandRow
import com.github.wekite.ui.utils.styleIslandRow
import com.github.wekite.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
import java.util.Collections
import java.util.WeakHashMap

private const val TAG = "AddressIslands"

private val installedRecyclers = Collections.synchronizedSet(
    Collections.newSetFromMap(WeakHashMap<RecyclerView, Boolean>()),
)

/**
 * 通讯录页（`com.tencent.mm.ui.contact.AddressUI`）的圆角岛实现。
 *
 * 通讯录是四个 tab 里唯一用 `RecyclerView` 的页面（主页与发现/我是 ListView）。
 * 这里不挂钩混淆过的适配器类，而是直接接管 RecyclerView 的子项视图：
 *
 *  - 分组边界按「用户指定的现有分隔」判定 —— 微信通讯录用字母/入口分组标题行分隔，
 *    标题行的结构特征稳定（不可点击、高度小、只有一个短文本），据此切成若干个岛。
 *  - RecyclerView 会回收复用子项，所以同时挂「子项挂载」「滚动」「布局变化」三类回调，
 *    每次可见范围变化都重算。
 */
fun applyAddressIslands(feature: ListIslands, host: Any?) {
    if (!feature.isContactsEnabled) return
    val recycler = resolveAddressRecycler(host)
    if (recycler == null) {
        WeLogger.w(TAG, "address RecyclerView not found; contacts islands skipped")
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
    }
}

private fun styleVisibleChildren(feature: ListIslands, recycler: RecyclerView) {
    val preset = feature.preset
    val childCount = recycler.childCount
    if (childCount == 0) return

    if (preset == IslandPreset.NO_LAYOUT) {
        for (index in 0 until childCount) restoreIslandRow(recycler.getChildAt(index))
        return
    }

    // 先标记哪些可见子项是分组标题，再据此切分组边界。
    val headerFlags = ArrayList<Boolean>(childCount)
    for (index in 0 until childCount) {
        headerFlags.add(isSectionHeaderRow(recycler.getChildAt(index)))
    }
    val hasAnyHeader = headerFlags.any { it }

    var groupStart = 0
    for (index in 0 until childCount) {
        if (hasAnyHeader && index > 0 && headerFlags[index]) groupStart = index
        val isFirstInGroup = index == groupStart
        val isLastInGroup = index == childCount - 1 ||
            (hasAnyHeader && headerFlags[index + 1])
        val position = when {
            isFirstInGroup && isLastInGroup -> IslandRowPosition.SINGLE
            isFirstInGroup -> IslandRowPosition.FIRST
            isLastInGroup -> IslandRowPosition.LAST
            else -> IslandRowPosition.MIDDLE
        }
        styleIslandRow(recycler.getChildAt(index), preset, position)
    }
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

private fun singleShortText(root: View): String? {
    var label: String? = null
    var count = 0
    if (root is ViewGroup) {
        for (index in 0 until root.childCount) {
            val childLabel = singleShortText(root.getChildAt(index)) ?: continue
            count++
            label = childLabel
        }
    }
    if (root is TextView) {
        val text = root.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            count++
            label = text
        }
    }
    return if (count == 1) label else null
}

/**
 * 在通讯录页的视图树里找到列表控件。
 *
 * 通讯录的真实列表在内部 Fragment（`MvvmAddressUIFragment`）里，所以顺序是：
 * 从 AddressUI 自身视图树找 RecyclerView -> 退一步查它持有的 Fragment 字段。
 */
private fun resolveAddressRecycler(host: Any?): RecyclerView? {
    val activityView = runCatching {
        host?.reflekt()
            ?.firstMethodOrNull { name = "getWindow"; parameterCount = 0 }
            ?.invoke()
            ?.reflekt()
            ?.firstMethodOrNull { name = "getDecorView"; parameterCount = 0 }
            ?.invoke() as? View
    }.getOrNull()
    activityView?.let { view -> findRecyclerView(view)?.let { return it } }

    // 退化路径：AddressUI 持有 Fragment，从 Fragment 的 View 里找。
    val fragment = runCatching {
        host?.javaClass?.fields
            ?.firstOrNull { field -> field.type.name.contains("Fragment") }
            ?.let { field ->
                field.isAccessible = true
                field.get(host)
            }
    }.getOrNull()

    val fragmentView = runCatching {
        fragment?.reflekt()
            ?.firstMethodOrNull { name = "getView"; parameterCount = 0 }
            ?.invoke() as? View
    }.getOrNull()
    return fragmentView?.let { findRecyclerView(it) }
}

private fun findRecyclerView(root: View): RecyclerView? {
    if (root is RecyclerView) return root
    if (root !is ViewGroup) return null
    for (index in 0 until root.childCount) {
        findRecyclerView(root.getChildAt(index))?.let { return it }
    }
    return null
}
