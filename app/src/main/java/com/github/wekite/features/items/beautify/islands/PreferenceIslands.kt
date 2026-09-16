package com.github.wekite.features.items.beautify.islands

import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import com.github.wekite.features.items.beautify.ListIslands
import com.github.wekite.ui.utils.IslandPreset
import com.github.wekite.ui.utils.IslandRowPosition
import com.github.wekite.ui.utils.restoreIslandRow
import com.github.wekite.ui.utils.styleIslandRow
import com.github.wekite.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
import java.util.Collections
import java.util.WeakHashMap

private const val TAG = "PreferenceIslands"

/** 已安装监听器的列表，避免重复叠加。 */
private val installedLists = Collections.synchronizedSet(
    Collections.newSetFromMap(WeakHashMap<ListView, Boolean>()),
)

/**
 * 「发现」与「我」页的圆角岛实现。
 *
 * 这两页共用微信同一套 Preference 列表机制（宿主页面都继承
 * `AbstractTabChildPreference` -> `MMPreferenceFragment`，列表控件是 `android.widget.ListView`），
 * 所以一个实现同时覆盖两页。
 *
 * 分组规则（用户指定「按微信自己的分组标题」）：把「分组标题行」当作岛的起点，
 * 标题行 + 其后的条目构成一个岛，直到下一个标题行。
 */
fun applyPreferenceIslands(feature: ListIslands, host: Any?, rootHint: View?) {
    if (!feature.isDiscoverMeEnabled) return
    val list = resolvePreferenceList(host, rootHint)
    if (list == null) {
        WeLogger.w(TAG, "preference ListView not found; discover/me page islands skipped")
        return
    }
    styleList(feature, list)
    if (installedLists.add(list)) {
        // 条目可能异步补齐（红点 / 插件项后到），布局变化时重算。
        list.viewTreeObserver.addOnGlobalLayoutListener {
            styleList(feature, list)
        }
    }
}

private fun styleList(feature: ListIslands, list: ListView) {
    val preset = feature.preset
    val childCount = list.childCount
    if (childCount == 0) return

    if (preset == IslandPreset.NO_LAYOUT) {
        for (index in 0 until childCount) restoreIslandRow(list.getChildAt(index))
        return
    }

    // 先标记哪些可见行是分组标题，再据此切分组边界。
    val headerFlags = ArrayList<Boolean>(childCount)
    for (index in 0 until childCount) {
        headerFlags.add(isCategoryHeaderRow(list.getChildAt(index)))
    }
    val hasAnyHeader = headerFlags.any { it }

    var groupStart = 0
    for (index in 0 until childCount) {
        // 除第一个分组外，每个标题行开启一个新分组。
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
        styleIslandRow(list.getChildAt(index), preset, position)
    }
}

/**
 * 判定一行是否为「分组标题」。
 *
 * 结构性判据（不依赖具体文案）：不可点击、只有一个文本控件、高度明显小于普通条目。
 */
private fun isCategoryHeaderRow(row: View): Boolean {
    if (row.isClickable) return false
    val density = row.resources.displayMetrics.density
    val maxTitleHeightPx = (48f * density).toInt()
    if (row.height <= 0 || row.height > maxTitleHeightPx) return false
    return countNonBlankTexts(row) == 1
}

private fun countNonBlankTexts(root: View): Int {
    var count = 0
    if (root is ViewGroup) {
        for (index in 0 until root.childCount) count += countNonBlankTexts(root.getChildAt(index))
    }
    if (root is TextView && root.text?.isNotBlank() == true) count++
    return count
}

/**
 * 定位这两页的 Preference 列表控件。
 *
 * 顺序：挂钩点给的 View 里找 -> 宿主 Fragment 自己的 View 里找 -> 宿主所在 Activity 的视图树里找。
 */
private fun resolvePreferenceList(host: Any?, rootHint: View?): ListView? {
    rootHint?.let { hint -> findListView(hint)?.let { return it } }

    val hostView = runCatching {
        host?.reflekt()?.firstMethodOrNull { name = "getView"; parameterCount = 0 }?.invoke() as? View
    }.getOrNull()
    hostView?.let { view -> findListView(view)?.let { return it } }

    val activity = runCatching {
        host?.reflekt()?.firstMethodOrNull { name = "getActivity"; parameterCount = 0 }?.invoke()
    }.getOrNull()
    val decor = runCatching {
        activity?.reflekt()
            ?.firstMethodOrNull { name = "getWindow"; parameterCount = 0 }
            ?.invoke()
            ?.reflekt()
            ?.firstMethodOrNull { name = "getDecorView"; parameterCount = 0 }
            ?.invoke() as? View
    }.getOrNull()
    return decor?.let { findListView(it) }
}

private fun findListView(root: View): ListView? {
    if (root is ListView) return root
    if (root !is ViewGroup) return null
    for (index in 0 until root.childCount) {
        findListView(root.getChildAt(index))?.let { return it }
    }
    return null
}
