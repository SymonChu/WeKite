package com.github.wekite.features.items.beautify.islands

import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.github.wekite.features.items.beautify.ListIslands
import com.github.wekite.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "PreferenceIslands"

private val diagClasses = Collections.synchronizedSet(
    Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>()),
)
private val warnClasses = Collections.synchronizedSet(
    Collections.newSetFromMap(WeakHashMap<Class<*>, Boolean>()),
)
private val blankLogged = AtomicBoolean(false)

/**
 * 「发现」「我」以及**微信设置下级页面**的圆角卡片实现。
 *
 * ## 挂钩点
 * 微信的 Preference 列表由 `com.tencent.mm.ui.base.preference.h0`（继承 `BaseAdapter`）
 * 逐条目渲染，**没有 ListView 可找** —— v3.20 走视图树找 ListView，真机日志实测
 * `preference ListView not found` → 整页无效。现在挂 `h0.getView`，每次绑定拿到
 * 「行 View」，再由行向上找到它所属的容器，交给 [ensureContainerStyled] 统一处理。
 *
 * ## 为什么不在这一层分组（v3.24 重写）
 * v3.21 在这一层用「上一条 / 下一条 item 的类名是否含 `Category`」推断自己在块内的
 * 位置，实测两个漏洞：
 * 1. **空白占位行判不出来**：item 是 Preference 对象，没有「界面上有没有东西可看」
 *    的概念。v3.23 截图实测：发现页「朋友圈」下方一张 **81dp 全空**的卡
 *    （y=584..861 内 0 个暗像素）、我页「设置」下方一张约 35dp 的空卡。
 * 2. **块间留白认不出来**：只按 item 相邻关系推，认不出真正的「页面空白」。
 *
 * 现在改成：这一层只负责**发现容器 + 判定这是哪个页面**，分组交给
 * [ensureContainerStyled] 按容器可见子项做，边界 = 「没有可见内容」的子项。
 * 这样同一套代码顺带覆盖了**微信设置的下级菜单**（用户点名的第 5 条）。
 *
 * ## 页面归属（发现/我 vs 微信设置）
 * `h0` 适配器被「发现」「我」和微信设置的多级页面共用，无法从适配器区分，
 * 因此**按行 View 的祖先链类名**判断：祖先里出现 `setting` 归微信设置页，
 * 否则归发现/我。两个开关各自控制，互不干扰。
 */
fun applyPreferenceRow(feature: ListIslands, row: View, adapter: Any?, position: Int) {
    if (!feature.isDiscoverMeEnabled && !feature.isSettingsEnabled) return

    val container = findListContainer(row)
    if (container == null) {
        if (warnClasses.add(row.javaClass)) {
            WeLogger.w(TAG, "preference list container not found from row ${row.javaClass.name}")
        }
        return
    }

    val inSettings = isInsideSettingsPage(row)
    if (inSettings && !feature.isSettingsEnabled) return
    if (!inSettings && !feature.isDiscoverMeEnabled) return

    if (diagClasses.add(container.javaClass)) {
        val count = runCatching { (adapter as? BaseAdapter)?.count }.getOrNull() ?: -1
        WeLogger.i(
            TAG,
            "preference islands active: rows=$count firstPosition=$position " +
                "container=${container.javaClass.name} children=${container.childCount} " +
                "settingsPage=$inSettings",
        )
    }

    ensureContainerStyled(
        feature = feature,
        container = container,
        // 发现/我/设置下级页：不设「章节标题」边界，整屏内容连成一块，
        // 留白行（没有可见内容）自然把块切开。
        groupStart = GroupStart { _, _ -> false },
        excluded = ExcludedRow { _, child ->
            if (hasVisibleContent(child)) {
                false
            } else {
                if (blankLogged.compareAndSet(false, true)) {
                    WeLogger.i(TAG, "blank row left untouched: ${child.javaClass.name}")
                }
                true
            }
        },
    )
}

/**
 * 这一行是否属于**微信设置**体系（而非「发现」「我」tab）。
 *
 * 从行 View 往上走，看祖先容器的类名：微信设置的页面分布在
 * `com.tencent.mm.plugin.setting.*` 下，而「发现」「我」是
 * `com.tencent.mm.ui.FindMoreFriendsUI` / `MoreTabUI`。走到某个容器类名里
 * 出现 `setting` 即判为设置页；遇到已知的 tab 页则判为否。
 */
private fun isInsideSettingsPage(row: View): Boolean {
    var current: View? = row
    var depth = 0
    while (current != null && depth < 24) {
        val name = current.javaClass.name
        if (name.contains("FindMoreFriends", ignoreCase = true) ||
            name.contains("MoreTab", ignoreCase = true)
        ) {
            return false
        }
        if (name.contains("setting", ignoreCase = true) && !name.startsWith("android.")) {
            return true
        }
        current = current.parent as? View
        depth++
    }
    return false
}

/** 供分组逻辑复用：容器当前是否一个子项都没有（还没渲染）。 */
internal fun isEmptyContainer(container: ViewGroup): Boolean = container.childCount == 0
