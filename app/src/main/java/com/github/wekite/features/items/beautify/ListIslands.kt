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
import com.github.wekite.features.items.beautify.islands.applyPreferenceIslands
import com.github.wekite.preferences.WePrefs
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.DefaultColumn
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.content.dialogListItemColors
import com.github.wekite.ui.content.dialogRadioButtonColors
import com.github.wekite.ui.content.dialogSwitchColors
import com.github.wekite.ui.utils.IslandPreset
import com.github.wekite.ui.utils.ListItem
import com.github.wekite.ui.utils.showComposeDialog
import com.github.wekite.utils.WeLogger

/**
 * 列表圆角岛 —— 把微信四个 tab 页的列表行渲染成圆角卡片 / 圆角「岛」。
 *
 * 覆盖页面与各自的挂钩点（微信 8.0.77 离线核验）：
 *  - 主页（会话列表）：`ConversationWithCacheAdapter.getView` / `MvvmConversationAdapter.getView`
 *  - 发现 + 我（Preference 列表）：`FindMoreFriendsUI.onActivityCreated` / `MoreTabUI.onViewCreated`
 *  - 通讯录：`AddressUI.onCreate`
 *
 * 置顶与非置顶的分组岛语义只在主页生效；发现 / 我 / 通讯录按微信自带的分组标题分段。
 */
@Feature(
    name = "列表圆角岛",
    categories = ["界面美化"],
    description = "把主页会话列表、通讯录、发现和我页的列表行渲染成圆角卡片，" +
        "置顶与非置顶会话各自成为一个圆角岛",
)
object ListIslands : ClickableFeature(), IResolveDex {

    private const val TAG = "ListIslands"

    // ---------------------------------------------------------------- 设置项

    /** 全局预设，四个页面共用，保证跨页观感一致。 */
    private var presetName by WePrefs.prefOption(
        "list_islands_preset",
        IslandPreset.PINNED_GROUPED_CARD.name,
    )

    /** 未读会话高亮（仅主页生效）。 */
    private var highlightUnread by WePrefs.prefOption("list_islands_highlight_unread", false)

    private var conversationPageEnabled by WePrefs.prefOption("list_islands_page_conversation", true)
    private var contactsPageEnabled by WePrefs.prefOption("list_islands_page_contacts", false)
    private var discoverMePageEnabled by WePrefs.prefOption("list_islands_page_discover_me", false)

    val preset: IslandPreset
        get() = IslandPreset.entries.firstOrNull { it.name == presetName }
            ?: IslandPreset.PINNED_GROUPED_CARD

    internal val isConversationEnabled: Boolean get() = isEnabled && conversationPageEnabled
    internal val isContactsEnabled: Boolean get() = isEnabled && contactsPageEnabled
    internal val isDiscoverMeEnabled: Boolean get() = isEnabled && discoverMePageEnabled
    internal val isUnreadHighlightEnabled: Boolean get() = highlightUnread

    /** 岛模式下行内的原生分隔线会破坏观感，由本功能接管隐藏。 */
    internal val suppressConversationDividers: Boolean
        get() = preset != IslandPreset.NO_LAYOUT

    // ---------------------------------------------------------------- 挂钩点

    // 会话列表两条历史路径（与本地「隐藏对话列表分割线」同锚点，8.0.77 实测命中）
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

    // 发现页 / 我页：Preference 列表的宿主页面
    private val methodFindMoreOnActivityCreated by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.ui.FindMoreFriendsUI"
            name = "onActivityCreated"
        }
    }

    private val methodMoreTabOnViewCreated by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.ui.MoreTabUI"
            name = "onViewCreated"
        }
    }

    // 通讯录页
    private val methodAddressOnCreate by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.ui.contact.AddressUI"
            name = "onCreate"
        }
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onEnable() {
        var armed = 0

        val conversationHook: (DexMethodDelegate) -> Unit = { delegate ->
            if (!delegate.isPlaceholder) {
                delegate.hookAfter {
                    val row = result as? View ?: return@hookAfter
                    applyConversationBind(this@ListIslands, row, thisObject, args)
                }
                armed++
            }
        }
        conversationHook(methodConversationWithCacheGetView)
        conversationHook(methodMvvmConversationGetView)

        if (!methodFindMoreOnActivityCreated.isPlaceholder) {
            methodFindMoreOnActivityCreated.hookAfter {
                applyPreferenceIslands(
                    this@ListIslands,
                    thisObject,
                    args.getOrNull(0) as? View,
                )
            }
            armed++
        }

        if (!methodMoreTabOnViewCreated.isPlaceholder) {
            methodMoreTabOnViewCreated.hookAfter {
                applyPreferenceIslands(
                    this@ListIslands,
                    thisObject,
                    args.getOrNull(0) as? View,
                )
            }
            armed++
        }

        if (!methodAddressOnCreate.isPlaceholder) {
            methodAddressOnCreate.hookAfter {
                applyAddressIslands(this@ListIslands, thisObject)
            }
            armed++
        }

        WeLogger.i(TAG, "list islands armed: $armed hook(s)")
    }

    override fun onDisable() {
        WeLogger.i(TAG, "list islands disabled; rows fall back to WeChat visuals on next bind")
    }

    // ---------------------------------------------------------------- 设置弹窗

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var presetInput by remember { mutableStateOf(preset) }
            var unreadInput by remember { mutableStateOf(highlightUnread) }
            var conversationInput by remember { mutableStateOf(conversationPageEnabled) }
            var contactsInput by remember { mutableStateOf(contactsPageEnabled) }
            var discoverMeInput by remember { mutableStateOf(discoverMePageEnabled) }

            AlertDialogContent(
                title = { Text("列表圆角岛") },
                text = {
                    DefaultColumn {
                        IslandPreset.entries.forEach { entry ->
                            ListItem(
                                colors = dialogListItemColors(),
                                modifier = Modifier
                                    .height(48.dp)
                                    .clickable {
                                        presetInput = entry
                                        presetName = entry.name
                                    },
                                leadingContent = {
                                    RadioButton(
                                        selected = presetInput == entry,
                                        onClick = null,
                                        colors = dialogRadioButtonColors(),
                                    )
                                },
                                content = { Text(entry.displayName) },
                            )
                        }
                        ListItem(
                            colors = dialogListItemColors(),
                            modifier = Modifier.height(48.dp).clickable {
                                unreadInput = !unreadInput
                                highlightUnread = unreadInput
                            },
                            trailingContent = {
                                Switch(
                                    checked = unreadInput,
                                    onCheckedChange = null,
                                    colors = dialogSwitchColors(),
                                )
                            },
                            content = { Text("高亮未读会话（仅主页）") },
                        )
                        ListItem(
                            colors = dialogListItemColors(),
                            modifier = Modifier.height(48.dp).clickable {
                                conversationInput = !conversationInput
                                conversationPageEnabled = conversationInput
                            },
                            trailingContent = {
                                Switch(
                                    checked = conversationInput,
                                    onCheckedChange = null,
                                    colors = dialogSwitchColors(),
                                )
                            },
                            content = { Text("应用于主页会话列表") },
                        )
                        ListItem(
                            colors = dialogListItemColors(),
                            modifier = Modifier.height(48.dp).clickable {
                                contactsInput = !contactsInput
                                contactsPageEnabled = contactsInput
                            },
                            trailingContent = {
                                Switch(
                                    checked = contactsInput,
                                    onCheckedChange = null,
                                    colors = dialogSwitchColors(),
                                )
                            },
                            content = { Text("应用于通讯录") },
                        )
                        ListItem(
                            colors = dialogListItemColors(),
                            modifier = Modifier.height(48.dp).clickable {
                                discoverMeInput = !discoverMeInput
                                discoverMePageEnabled = discoverMeInput
                            },
                            trailingContent = {
                                Switch(
                                    checked = discoverMeInput,
                                    onCheckedChange = null,
                                    colors = dialogSwitchColors(),
                                )
                            },
                            content = { Text("应用于发现 / 我页") },
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = onDismiss) { Text("关闭") }
                },
            )
        }
    }

    private val IslandPreset.displayName: String
        get() = when (this) {
            IslandPreset.NO_LAYOUT -> "不使用（恢复微信原样）"
            IslandPreset.COMFORT_CARD -> "舒适卡片"
            IslandPreset.PINNED_GROUPED_CARD -> "置顶分组岛"
            IslandPreset.COMPACT_ROUNDED -> "紧凑圆角"
            IslandPreset.MINIMAL_LIST -> "极简列表"
        }
}
