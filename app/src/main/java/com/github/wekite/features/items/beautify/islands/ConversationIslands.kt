package com.github.wekite.features.items.beautify.islands

import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import com.github.wekite.features.api.core.WeConversationApi
import com.github.wekite.features.items.beautify.ListIslands
import com.github.wekite.ui.utils.IslandRowPosition
import com.github.wekite.ui.utils.restoreIslandRow
import com.github.wekite.ui.utils.styleIslandRow
import com.github.wekite.utils.WeLogger
import dev.ujhhgtg.reflekt.reflected.ReflectedField
import dev.ujhhgtg.reflekt.reflekt
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ConversationIslands"

/** 会话模型类 -> 取 field_username 的访问器（同一个类只解析一次）。 */
private val usernameAccessors = ConcurrentHashMap<Class<*>, (Any) -> String?>()
private val usernameFailures = ConcurrentHashMap.newKeySet<Class<*>>()

/**
 * 主页会话列表绑定一行时调用。
 *
 * 分组维度开启时，按「置顶 / 非置顶」把相邻行拼成两个岛：
 * 顶部置顶区一个岛、下面普通区一个岛，这是用户最初点名的效果。
 *
 * @param adapter `getView` 的 thisObject（会话适配器）
 * @param args    `getView` 的参数，args[0] 是 position
 */
fun applyConversationBind(
    feature: ListIslands,
    row: View,
    adapter: Any?,
    args: Array<Any?>,
) {
    if (!feature.isConversationEnabled) {
        restoreIslandRow(row)
        return
    }

    val shape = feature.shape
    val grouped = feature.groupingEnabled

    val position = args.getOrNull(0) as? Int ?: return
    val talker = talkerAt(adapter, position)
    // 拿不到会话标识时无法判断置顶边界，这一行退化为独立卡片（不静默出错但也不误拼）。
    val canGroup = grouped && talker != null

    val pinned = if (canGroup) WeConversationApi.isPinned(talker!!) else false
    val previousPinned = if (canGroup) {
        talkerAt(adapter, position - 1)?.let { WeConversationApi.isPinned(it) }
    } else {
        null
    }
    val nextPinned = if (canGroup) {
        talkerAt(adapter, position + 1)?.let { WeConversationApi.isPinned(it) }
    } else {
        null
    }

    val rowPosition = if (!canGroup) {
        IslandRowPosition.SINGLE
    } else {
        when {
            previousPinned != pinned && nextPinned != pinned -> IslandRowPosition.SINGLE
            previousPinned != pinned -> IslandRowPosition.FIRST
            nextPinned != pinned -> IslandRowPosition.LAST
            else -> IslandRowPosition.MIDDLE
        }
    }

    val unread = feature.isUnreadHighlightEnabled && isUnread(adapter, position)

    // 分组开时隐藏行内原生分隔线（含置顶区与普通区的交界处）。
    if (feature.suppressConversationDividers) hideInlineDividers(row)

    styleIslandRow(row, shape, canGroup, rowPosition, unread)
}

/**
 * 从适配器取第 [position] 行的会话标识（talker）。
 *
 * 主页 ListView 可能被 `HeaderViewListAdapter` 包装（头部有折叠横幅、「N 条新消息」等），
 * 这时真实适配器的 position 与列表 position 有偏移，按头部数量校正。
 */
internal fun talkerAt(adapter: Any?, position: Int): String? {
    if (adapter == null || position < 0) return null
    val base = unwrapAdapter(adapter) as? BaseAdapter ?: return null
    val raw = position - headerCount(adapter)
    if (raw < 0 || raw >= base.count) return null
    val item = runCatching { base.getItem(raw) }.getOrNull() ?: return null
    return usernameOf(item)
}

private fun unwrapAdapter(adapter: Any): Any = runCatching {
    adapter.reflekt()
        .firstMethodOrNull { name = "getWrappedAdapter"; parameterCount = 0 }
        ?.invoke()
}.getOrNull() ?: adapter

private fun headerCount(adapter: Any): Int = runCatching {
    adapter.reflekt()
        .firstMethodOrNull { name = "getHeadersCount"; parameterCount = 0 }
        ?.invoke() as? Int
}.getOrNull() ?: 0

private fun usernameOf(conversation: Any): String? {
    val modelClass = conversation.javaClass
    val accessor = usernameAccessors.computeIfAbsent(modelClass) { cls ->
        val field = runCatching {
            cls.reflekt().firstFieldOrNull { name = "field_username"; superclass() }
        }.getOrNull()
        if (field == null) {
            if (usernameFailures.add(cls)) {
                WeLogger.w(
                    TAG,
                    "conversation model ${cls.name} has no field_username; " +
                        "pinned grouping falls back to single rows",
                )
            }
            { _: Any -> null }
        } else {
            // Class<*> 上取到的字段是带 out 投影的泛型，直接调用 get 不被允许，显式强转。
            @Suppress("UNCHECKED_CAST")
            val typed = field as ReflectedField<Any>
            { instance: Any -> runCatching { typed.get(instance) as? String }.getOrNull() }
        }
    }
    return accessor(conversation)
}

private fun isUnread(adapter: Any?, position: Int): Boolean {
    if (adapter == null || position < 0) return false
    val base = unwrapAdapter(adapter) as? BaseAdapter ?: return false
    val raw = position - headerCount(adapter)
    if (raw < 0 || raw >= base.count) return false
    val item = runCatching { base.getItem(raw) }.getOrNull() ?: return false
    val field = runCatching {
        item.javaClass.reflekt().firstFieldOrNull { name = "field_unReadCount"; superclass() }
    }.getOrNull() ?: return false
    val count = runCatching { field.get(item) as? Number }.getOrNull()?.toInt() ?: return false
    return count > 0
}

/**
 * 隐藏行内原生分隔线。
 *
 * 用形态特征（细长、非 ViewGroup、宽度接近列表宽）而不是固定子索引 ——
 * 本地既有实现证明该判据对微信布局改版免疫。
 */
private fun hideInlineDividers(row: View) {
    val listWidth = (row.parent as? ListView)?.width ?: row.rootView.width
    if (listWidth <= 0) return
    val maxHeight = (6f * row.resources.displayMetrics.density).toInt()
    collectDividers(row, row, maxHeight, listWidth).forEach { it.visibility = View.GONE }
}

private fun collectDividers(
    root: View,
    current: View,
    maxHeightPx: Int,
    listWidthPx: Int,
): List<View> {
    val found = ArrayList<View>()
    if (current is ViewGroup) {
        for (index in 0 until current.childCount) {
            found += collectDividers(root, current.getChildAt(index), maxHeightPx, listWidthPx)
        }
    }
    if (current !== root &&
        current !is ViewGroup &&
        current.visibility != View.GONE &&
        current.height in 1..maxHeightPx &&
        current.width >= listWidthPx * 2 / 3
    ) {
        found += current
    }
    return found
}
