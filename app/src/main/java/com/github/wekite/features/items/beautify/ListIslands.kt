package com.github.wekite.features.items.beautify

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.wekite.dexkit.abc.IResolveDex
import com.github.wekite.dexkit.dsl.DexMethodDelegate
import com.github.wekite.dexkit.dsl.dexMethod
import com.github.wekite.features.core.ClickableFeature
import com.github.wekite.features.core.Feature
import com.github.wekite.features.items.beautify.islands.applyAddressIslands
import com.github.wekite.features.items.beautify.islands.applyConversationBind
import com.github.wekite.features.items.beautify.islands.applyPreferenceRow
import com.github.wekite.preferences.WePrefs
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.DefaultColumn
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.content.dialogListItemColors
import com.github.wekite.ui.content.dialogRadioButtonColors
import com.github.wekite.ui.content.dialogSwitchColors
import com.github.wekite.ui.utils.IslandShape
import com.github.wekite.ui.utils.ListItem
import com.github.wekite.ui.utils.showComposeDialog
import com.github.wekite.utils.WeLogger

/**
 * 列表圆角岛 —— 把微信列表页的行渲染成圆角卡片 / 圆角「岛」。
 *
 * ⚠️ v3.21 按用户实测反馈重做（v3.20 的发现页/通讯录无效）：
 *  - 发现 + 我：改挂 **Preference 适配器 `ui.base.preference.h0` 的 `getView`**（条目级），
 *    不再去视图树里找 ListView（v3.20 日志实测 `preference ListView not found`）。
 *    分组边界用权威数据判定：`adapter.getItem(position)` 是否为 `PreferenceCategory`。
 *  - 通讯录：改挂 **`MvvmAddressUIFragment.getLayoutView`**，从该 Fragment 自己的根视图向下
 *    找 `WxRecyclerView`（v3.20 挂在外层 `AddressUI.onCreate`，那时列表尚未建立）。
 *  - 分组与卡片样式**解耦**：分组是独立开关，可与任意卡片样式组合
 *    （用户要求「置顶分组岛要与其他几个选项同时可以生效，而不是一个单独的功能」）。
 */
@Feature(
    name = "列表圆角岛",
    categories = ["界面美化"],
    description = "把主页会话列表、通讯录、发现和我页的列表行渲染成圆角卡片；" +
        "置顶与非置顶会话、通讯录分组、我发现页分组都可各自成为一个圆角岛",
)
object ListIslands : ClickableFeature(), IResolveDex {

    private const val TAG = "ListIslands"

    // ---------------------------------------------------------------- 设置项

    /** 卡片样式（圆角 / 内缩 / 底色）。 */
    private var shapeName by WePrefs.prefOption("list_islands_shape", IslandShape.ROUNDED_CARD.name)

    /** 「分组」是独立维度，可与任意样式组合。 */
    private var grouping by WePrefs.prefOption("list_islands_grouping", true)

    /** 未读会话高亮（仅主页生效）。 */
    private var highlightUnread by WePrefs.prefOption("list_islands_highlight_unread", false)

    // 三个页面的开关，默认全开（用户要求「4 个 tab 全做」）
    private var conversationPageEnabled by WePrefs.prefOption("list_islands_page_conversation", true)
    private var contactsPageEnabled by WePrefs.prefOption("list_islands_page_contacts", true)
    private var discoverMePageEnabled by WePrefs.prefOption("list_islands_page_discover_me", true)

    val shape: IslandShape
        get() = IslandShape.entries.firstOrNull { it.name == shapeName } ?: IslandShape.ROUNDED_CARD

    val groupingEnabled: Boolean get() = grouping

    internal val isConversationEnabled: Boolean get() = isEnabled && conversationPageEnabled
    internal val isContactsEnabled: Boolean get() = isEnabled && contactsPageEnabled
    internal val isDiscoverMeEnabled: Boolean get() = isEnabled && discoverMePageEnabled
    internal val isUnreadHighlightEnabled: Boolean get() = highlightUnread

    /** 分组开时行内的原生分隔线会破坏观感，由本功能接管隐藏（仅主页）。 */
    internal val suppressConversationDividers: Boolean get() = grouping

    // ---------------------------------------------------------------- 挂钩点

    // 主页：会话列表两条历史路径（与本地「隐藏对话列表分割线」同锚点，8.0.77 实测命中）
    private val methodConversationWithCacheGetView by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            name = "getView"
            usingEqStrings(
                "MicroMsg.ConversationWithCacheAdapter",
                "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d",
            )
        }
    }

    private val methodMvvmConversationGetView by dexMethod(allowFailure = true) {
        matcher {
            declaredClass {
                usingEqStrings(
                    "MicroMsg.ConversationAdapter.MvvmConversationAdapter",
                    "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d",
                )
            }
            name = "getView"
        }
    }

    // 发现 + 我：Preference 适配器（条目级，一处挂钩覆盖两页）
    private val methodPreferenceAdapterGetView by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.ui.base.preference.h0"
            name = "getView"
            paramTypes("int", "android.view.View", "android.view.ViewGroup")
        }
    }

    // 通讯录：内部 Fragment 的根视图（真列表 WxRecyclerView 在其中）
    private val methodAddressFragmentLayoutView by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.ui.contact.address.MvvmAddressUIFragment"
            name = "getLayoutView"
        }
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onEnable() {
        var armed = 0

        fun hookConversationGetView(delegate: DexMethodDelegate) {
            if (delegate.isPlaceholder) return
            delegate.hookAfter {
                val row = result as? View ?: return@hookAfter
                applyConversationBind(this@ListIslands, row, thisObject, args)
            }
            armed++
        }

        hookConversationGetView(methodConversationWithCacheGetView)
        hookConversationGetView(methodMvvmConversationGetView)

        if (!methodPreferenceAdapterGetView.isPlaceholder) {
            methodPreferenceAdapterGetView.hookAfter {
                val row = result as? View ?: return@hookAfter
                val position = args.getOrNull(0) as? Int ?: return@hookAfter
                applyPreferenceRow(this@ListIslands, row, thisObject, position)
            }
            armed++
        }

        if (!methodAddressFragmentLayoutView.isPlaceholder) {
            methodAddressFragmentLayoutView.hookAfter {
                applyAddressIslands(this@ListIslands, result as? View)
            }
            armed++
        } else {
            // 明确记录「没挂上」，供真机日志一眼区分「挂钩失效」与「挂上了但分组判断不对」。
            WeLogger.w(TAG, "address fragment hook unavailable (getLayoutView not matched)")
        }

        WeLogger.i(TAG, "list islands armed: $armed hook(s)")
        WeLogger.i(
            TAG,
            "list islands config: shape=${shape.name} grouping=$grouping " +
                "pages[chat=$conversationPageEnabled contacts=$contactsPageEnabled " +
                "discoverMe=$discoverMePageEnabled] unreadHighlight=$highlightUnread",
        )
    }

    override fun onDisable() {
        WeLogger.i(TAG, "list islands disabled; rows fall back to WeChat visuals on next bind")
    }

    // ---------------------------------------------------------------- 设置弹窗

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var shapeInput by remember { mutableStateOf(shape) }
            var groupingInput by remember { mutableStateOf(grouping) }
            var unreadInput by remember { mutableStateOf(highlightUnread) }
            var conversationInput by remember { mutableStateOf(conversationPageEnabled) }
            var contactsInput by remember { mutableStateOf(contactsPageEnabled) }
            var discoverMeInput by remember { mutableStateOf(discoverMePageEnabled) }

            AlertDialogContent(
                title = { Text("列表圆角岛") },
                text = {
                    DefaultColumn {
                        // ── 维度一：分组（与应用范围无关，四个页面共用） ──
                        SwitchRow(
                            title = "分组显示为整块岛",
                            checked = groupingInput,
                            onToggle = {
                                groupingInput = !groupingInput
                                grouping = groupingInput
                            },
                        )
                        // ── 维度二：卡片样式 ──
                        IslandShape.entries.forEach { entry ->
                            ListItem(
                                colors = dialogListItemColors(),
                                modifier = Modifier
                                    .height(48.dp)
                                    .clickable {
                                        shapeInput = entry
                                        shapeName = entry.name
                                    },
                                leadingContent = {
                                    RadioButton(
                                        selected = shapeInput == entry,
                                        onClick = null,
                                        colors = dialogRadioButtonColors(),
                                    )
                                },
                                content = { Text(entry.displayName) },
                            )
                        }
                        SwitchRow(
                            title = "高亮未读会话（仅主页）",
                            checked = unreadInput,
                            onToggle = {
                                unreadInput = !unreadInput
                                highlightUnread = unreadInput
                            },
                        )
                        // ── 应用页面 ──
                        SwitchRow(
                            title = "应用于主页会话列表",
                            checked = conversationInput,
                            onToggle = {
                                conversationInput = !conversationInput
                                conversationPageEnabled = conversationInput
                            },
                        )
                        SwitchRow(
                            title = "应用于通讯录",
                            checked = contactsInput,
                            onToggle = {
                                contactsInput = !contactsInput
                                contactsPageEnabled = contactsInput
                            },
                        )
                        SwitchRow(
                            title = "应用于发现 / 我页",
                            checked = discoverMeInput,
                            onToggle = {
                                discoverMeInput = !discoverMeInput
                                discoverMePageEnabled = discoverMeInput
                            },
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                },
            )
        }
    }

    @androidx.compose.runtime.Composable
    private fun SwitchRow(title: String, checked: Boolean, onToggle: () -> Unit) {
        ListItem(
            colors = dialogListItemColors(),
            modifier = Modifier.height(48.dp).clickable { onToggle() },
            trailingContent = {
                Switch(checked = checked, onCheckedChange = null, colors = dialogSwitchColors())
            },
            content = { Text(title) },
        )
    }

    private val IslandShape.displayName: String
        get() = when (this) {
            IslandShape.ROUNDED_CARD -> "圆角卡片（圆角 14dp）"
            IslandShape.COMPACT -> "紧凑圆角（圆角 10dp）"
            IslandShape.MINIMAL -> "极简（圆角 6dp）"
        }
}
