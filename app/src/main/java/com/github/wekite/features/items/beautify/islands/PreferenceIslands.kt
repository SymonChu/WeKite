package com.github.wekite.features.items.beautify.islands

import android.view.View
import android.widget.BaseAdapter
import com.github.wekite.features.items.beautify.ListIslands
import com.github.wekite.ui.utils.IslandRowPosition
import com.github.wekite.ui.utils.restoreIslandRow
import com.github.wekite.ui.utils.styleIslandRow
import com.github.wekite.utils.WeLogger
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "PreferenceIslands"

/** 一次性诊断开关（避免每行都打日志刷屏）。 */
private val diagnosed = AtomicBoolean(false)

/**
 * 「发现」与「我」页的圆角岛实现。
 *
 * ⚠️ v3.21 重写。v3.20 走「先在视图树里找 ListView」的路线，真机日志实测
 * `preference ListView not found` → 发现页完全无效。
 * 离线核验 8.0.77 后确认：微信的 Preference 列表由
 * `com.tencent.mm.ui.base.preference.h0`（继承 `BaseAdapter`）逐条目渲染，
 * 根本不需要那个 ListView。
 *
 * 现在挂 `h0.getView`，每次绑定直接拿到「行 View + position」。
 *
 * ## 分组判据（v3.21 修正的关键点）
 * 微信偏好页用**多种** Category 类区分区块：`PreferenceCategory` /
 * `PreferenceSmallCategory`（小组间距）/ `PreferenceTitleCategory` / `PreferenceFooterCategory`
 * 等。v3.20 只认 `PreferenceCategory`，而「我」页**根本不引用**它
 * （离线实测 `MoreTabUI` 零命中）→ 我页切不出块。
 *
 * 现改为：**凡 item 类名含 `Category` 的行都视为分组边界，且该行不套卡片**
 * —— 这类行本身就是区块之间的间距/小标题，保持透明，让留白自然形成块与块之间的缝隙，
 * 从而复现微信自己的分块结构（「我」页即用户说的那几大块）。
 *
 * 发现页与「我」页共用同一个适配器类，一处挂钩同时覆盖两页。
 */
fun applyPreferenceRow(feature: ListIslands, row: View, adapter: Any?, position: Int) {
    if (!feature.isDiscoverMeEnabled) {
        restoreIslandRow(row)
        return
    }

    // 一次性诊断：让真机日志能判断「发现/我页到底处理了多少行」，而不用靠肉眼猜。
    // 只在首次调用时打印，避免刷屏。
    if (diagnosed.compareAndSet(false, true)) {
        val count = runCatching { (adapter as? BaseAdapter)?.count }.getOrNull() ?: -1
        WeLogger.i(
            TAG,
            "preference islands active: rows=$count firstPosition=$position " +
                "isCategory=${isCategoryAt(adapter, position)}",
        )
    }

    val shape = feature.shape
    val grouped = feature.groupingEnabled

    // 分组边界行（任意 *Category）本身不套卡片，让它保持原样的间距作用。
    if (grouped && isCategoryAt(adapter, position)) {
        styleIslandRow(row, shape, grouped = false, position = IslandRowPosition.SINGLE)
        // 间距行不应有自己的卡片外观，直接还原成原样最稳。
        restoreIslandRow(row)
        return
    }

    val rowPosition = if (!grouped) {
        IslandRowPosition.SINGLE
    } else {
        // 上一个边界（或列表开头）之后的第一个内容行 = 块首；下一个边界之前 = 块尾。
        // 「甲」方案：头像区（AccountInfoPreference，不带 Category 标记）若被 Category
        // 行夹在中间，就会自然成为 SINGLE —— 即它自己也成一张圆角卡片，
        // 于是「我」页稳定分成 4 块（头像 / 服务 / 收藏那组 / 设置）。
        val prevIsBoundary = position == 0 || isCategoryAt(adapter, position - 1)
        val nextIsBoundary = isCategoryAt(adapter, position + 1)
        when {
            prevIsBoundary && nextIsBoundary -> IslandRowPosition.SINGLE
            prevIsBoundary -> IslandRowPosition.FIRST
            nextIsBoundary -> IslandRowPosition.LAST
            else -> IslandRowPosition.MIDDLE
        }
    }

    styleIslandRow(row, shape, grouped, rowPosition)
}

/** 该 position 是否为分组边界行（任意 `*Category` 类型）。 */
private fun isCategoryAt(adapter: Any?, position: Int): Boolean {
    if (adapter == null || position < 0) return false
    val base = adapter as? BaseAdapter ?: return false
    if (position >= base.count) return true
    val item = runCatching { base.getItem(position) }.getOrNull() ?: return false
    return item.javaClass.name.contains("Category")
}
