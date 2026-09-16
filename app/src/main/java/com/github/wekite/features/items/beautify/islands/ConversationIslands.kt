package com.github.wekite.features.items.beautify.islands

import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.github.wekite.features.api.core.WeConversationApi
import com.github.wekite.features.items.beautify.ListIslands
import com.github.wekite.ui.utils.IslandRowPosition
import com.github.wekite.ui.utils.styleIslandRow
import com.github.wekite.utils.WeLogger
import dev.ujhhgtg.reflekt.reflected.ReflectedField
import dev.ujhhgtg.reflekt.reflekt
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "ConversationIslands"

/** 会话模型类 -> 取 field_username 的访问器（同一个类只解析一次）。 */
private val usernameAccessors = ConcurrentHashMap<Class<*>, (Any) -> String?>()
private val usernameFailures = ConcurrentHashMap.newKeySet<Class<*>>()

/**
 * 行 View -> 这一行绑定的会话是否置顶 / 是否未读。
 *
 * 分组依据（「置顶区一块、普通区一块」）与未读高亮都需要**按行**知道会话状态，
 * 而一行被复用时可能换了会话，所以每次 `getView` 都刷新一遍。
 */
private val rowPinned = Collections.synchronizedMap(WeakHashMap<View, Boolean>())
private val rowUnread = Collections.synchronizedMap(WeakHashMap<View, Boolean>())

/** 被单独处理过的「已登录 N 台其他设备」横幅（容器分组时要跳过它）。 */
private val bannerViews = Collections.synchronizedSet(
    Collections.newSetFromMap(WeakHashMap<View, Boolean>()),
)

/**
 * 主页会话列表绑定一行时调用 —— **只做记录**，不再逐行套卡片。
 *
 * v3.21 是「每行自己去问 adapter 上一行/下一行是否置顶」，有两个问题：
 * ①表头（含「已登录 N 台其他设备」横幅）不经过 `getView`，永远拿不到卡片；
 * ②列表回收复用行时，逐行推断的位置可能来自旧行，于是「进聊天再退出后
 *   卡片形状不对」。
 * 现在改成：这里只记「这一行 = 哪个会话、是否置顶、是否未读」，真正的分组由
 * [styleConversationContainer] 按容器当前可见子项统一做。
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
    if (!feature.isConversationEnabled) return

    val position = args.getOrNull(0) as? Int ?: return
    // 置顶状态用于分块；未读状态用于配色。分组关闭时也仍需要（每行各自一张卡）。
    talkerAt(adapter, position)?.let { rowPinned[row] = WeConversationApi.isPinned(it) }
    rowUnread[row] = isUnread(adapter, position)

    findListContainer(row)?.let { styleConversationContainer(feature, it) }
}

/**
 * 「已登录 N 台其他设备」横幅 → 登记为「单独套卡」。
 *
 * ⚠️ 这个横幅不在会话列表的 adapter 里（它是列表的表头，不走 `getView`），
 * 也不能在它自己的 `setVisibility` hook 里套卡 —— 那个 hook 在列表装进
 * activity 之前就跑完了（那一刻 height/width 都是 0）。所以这里只**登记**，
 * 真正的套卡由 [styleConversationContainer] 在跑到容器分组时补做。
 */
fun registerOtherDevicesBanner(banner: View) {
    bannerViews.add(banner)
}

/** 给会话列表容器跑一遍分组（置顶区 / 普通区各一块）。 */
internal fun styleConversationContainer(feature: ListIslands, container: ViewGroup) {
    if (!feature.isConversationEnabled) return

    // 设备横幅（表头）不在列表子项里：每次跑分组时单独给它套卡，
    // 这样在它真正完成布局之后一定会被处理到。
    bannerViews.forEach { banner ->
        if (banner.parent != null) {
            styleIslandRow(
                banner, feature.shape,
                grouped = false, position = IslandRowPosition.SINGLE,
            )
        }
    }

    val listView = asListView(container)
    val first = listView?.firstVisiblePosition ?: 0

    ensureContainerStyled(
        feature = feature,
        container = container,
        groupStart = GroupStart { index, child ->
            // 置顶状态发生变化 ⇒ 新块。第 0 个可见行永远开新块。
            val pinned = pinnedOf(child, listView, first + index)
            val prev = if (index == 0) {
                null
            } else {
                pinnedOf(container.getChildAt(index - 1), listView, first + index - 1)
            }
            index == 0 || (prev != null && pinned != null && prev != pinned)
        },
        excluded = ExcludedRow { _, child ->
            bannerViews.contains(child) || !hasVisibleContent(child)
        },
        unreadOf = { child ->
            feature.isUnreadHighlightEnabled && (rowUnread[child] ?: false)
        },
    )
}

/**
 * 某个可见子项的置顶状态。
 *
 * 优先用 `getView` 时记下的映射（最准）；表头 / 尚未绑定过的行退回按
 * 「ListView 可见位置」换算 adapter position 再查。
 */
private fun pinnedOf(child: View, listView: android.widget.ListView?, visiblePosition: Int): Boolean? {
    rowPinned[child]?.let { return it }
    val adapter = listView?.adapter ?: return null
    val talker = talkerAt(adapter, visiblePosition) ?: return null
    val pinned = WeConversationApi.isPinned(talker)
    rowPinned[child] = pinned
    return pinned
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

/** 该行是否为未读会话。 */
internal fun isUnread(adapter: Any?, position: Int): Boolean {
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
