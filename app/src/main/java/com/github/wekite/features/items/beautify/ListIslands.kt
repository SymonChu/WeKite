package com.github.wekite.features.items.beautify

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
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
import com.github.wekite.features.items.beautify.islands.registerOtherDevicesBanner
import com.github.wekite.features.items.beautify.islands.applyPreferenceRow
import com.github.wekite.features.items.beautify.islands.styleConversationContainer
import com.github.wekite.preferences.WePrefs
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.DefaultColumn
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.content.dialogListItemColors
import com.github.wekite.ui.content.dialogSwitchColors
import com.github.wekite.ui.utils.IslandShape
import com.github.wekite.ui.utils.ListItem
import com.github.wekite.ui.utils.showComposeDialog
import com.github.wekite.utils.WeLogger

/**
 * 圆角卡片 —— 把微信列表页的行渲染成圆角卡片 / 圆角「块」。
 *
 * 原名「列表圆角岛」，v3.24 按用户要求改名（内部类名与 pref key 保持不变，
 * 以免丢掉用户已保存的设置）。
 *
 * ⚠️ v3.24 按用户真机截图逐条重做（v3.20~v3.23 四版都没解决的那批问题）：
 *  - **岛内那条细线**：是**我们自己的描边**。GradientDrawable 只能整圈描边，
 *    同一块内相邻两行各画一圈 1dp，接缝处叠成一条 3~4px 深线。改为自绘
 *    「只画外轮廓」（见 `ListIslandStyle.kt` 的 `IslandCardDrawer`）。
 *  - **空白卡片**：微信的留白占位行照样走 getView，被一视同仁套了卡片。
 *    改为「行内没有可见内容 ⇒ 不套卡 + 视为分组边界」（`hasVisibleContent`）。
 *  - **分组逻辑从「按 adapter position 逐行」改为「按容器可见子项统一分组」**
 *    （`IslandListStyler`）：这样才能看见**表头**（「我」页头像区、主页设备横幅），
 *    也修掉 RecyclerView 复用行导致的位置错位。
 */
@Feature(
    name = "圆角卡片",
    categories = ["界面美化"],
    description = "把主页会话列表、通讯录、发现和我页的列表行渲染成圆角卡片；" +
        "置顶与非置顶会话、通讯录分组、我发现页分组都可各自成为一个圆角块",
)
object ListIslands : ClickableFeature(), IResolveDex {

    private const val TAG = "ListIslands"

    // ---------------------------------------------------------------- 设置项

    /** 卡片样式。v3.24 起只剩一档，保留 pref key 以兼容旧值。 */
    private var shapeName by WePrefs.prefOption("list_islands_shape", IslandShape.ROUNDED_CARD.name)

    /** 「分组」是独立维度：开 = 相邻行拼成整块；关 = 每行各自一张卡片。 */
    private var grouping by WePrefs.prefOption("list_islands_grouping", true)

    /** 未读会话高亮（仅主页生效）。 */
    private var highlightUnread by WePrefs.prefOption("list_islands_highlight_unread", false)

    // 三个页面的开关，默认全开（用户要求「4 个 tab 全做」）
    private var conversationPageEnabled by WePrefs.prefOption("list_islands_page_conversation", true)
    private var contactsPageEnabled by WePrefs.prefOption("list_islands_page_contacts", true)
    private var discoverMePageEnabled by WePrefs.prefOption("list_islands_page_discover_me", true)

    /** 微信设置的下级页面（同样是 Preference 列表），用同一个开关。 */
    private var settingsPageEnabled by WePrefs.prefOption("list_islands_page_settings", true)

    val shape: IslandShape
        get() = IslandShape.entries.firstOrNull { it.name == shapeName } ?: IslandShape.ROUNDED_CARD

    val groupingEnabled: Boolean get() = grouping

    internal val isConversationEnabled: Boolean get() = isEnabled && conversationPageEnabled
    internal val isUnreadHighlightEnabled: Boolean get() = highlightUnread
    internal val isContactsEnabled: Boolean get() = isEnabled && contactsPageEnabled
    internal val isDiscoverMeEnabled: Boolean get() = isEnabled && discoverMePageEnabled
    internal val isSettingsEnabled: Boolean get() = isEnabled && settingsPageEnabled

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

    // 「已登录 N 台其他设备」横幅：复用「隐藏其他设备横幅」已验证命中的锚点，
    // 但它设在 setVisibility 上——我们改成 hookAfter 拿 thisObject（横幅 View 实例）。
    private val methodOtherOnlineBannerSetVisibility by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.ui.conversation.banner")
        matcher {
            paramTypes("int")
            returnType = "void"
            usingEqStrings(
                "com/tencent/mm/ui/conversation/banner/OtherOnlineBanner",
                "setVisibility",
            )
        }
    }

    // 发现 + 我 + 微信设置下级页：Preference 适配器（条目级，一处挂钩覆盖多页）
    private val methodPreferenceAdapterGetView by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.ui.base.preference.h0"
            name = "getView"
            paramTypes("int", "android.view.View", "android.view.ViewGroup")
        }
    }

    // 通讯录：内部 Fragment 初始化列表的方法。
    // ⚠️ 不能挂 getLayoutView() —— 它只返回 inflate 出来的布局，那一刻列表字段仍是 null
    //（真机日志实证 `address RecyclerView not found under fragment root`）。
    // 字节码实测 `l0(Bundle)` 才执行 findViewById->WxRecyclerView 赋值 + setAdapter +
    // setLayoutManager + addView，是整条链上第一个「列表已存在」的时点。
    private val methodAddressFragmentInitList by dexMethod(allowFailure = true) {
        matcher {
            declaredClass = "com.tencent.mm.ui.contact.address.MvvmAddressUIFragment"
            name = "l0"
            paramTypes("android.os.Bundle")
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

        // ⚠️ 这个 hook 在**列表装进 activity 之前**就跑了（那一刻横幅 height/width 都是 0），
        // 所以这里只登记「这是横幅」，真正的套卡由会话容器跑分组时补做
        // （见 ConversationIslands 的 bannerViews：容器会跳过它、由本模块单独套卡）。
        if (!methodOtherOnlineBannerSetVisibility.isPlaceholder) {
            methodOtherOnlineBannerSetVisibility.hookAfter {
                val banner = thisObject as? View ?: return@hookAfter
                registerOtherDevicesBanner(banner)
            }
            armed++
        }

        if (!methodPreferenceAdapterGetView.isPlaceholder) {
            methodPreferenceAdapterGetView.hookAfter {
                val row = result as? View ?: return@hookAfter
                val position = args.getOrNull(0) as? Int ?: return@hookAfter
                applyPreferenceRow(this@ListIslands, row, thisObject, position)
            }
            armed++
        }

        if (!methodAddressFragmentInitList.isPlaceholder) {
            methodAddressFragmentInitList.hookAfter {
                // thisObject 是 Fragment 实例：按类型直接读它那个 WxRecyclerView 字段，
                // 比在视图树里搜索可靠（v3.20/v3.21 两次失败同源于时序与层级假设）。
                applyAddressIslands(this@ListIslands, thisObject)
            }
            armed++
        } else {
            // 明确记录「没挂上」，供真机日志一眼区分「挂钩失效」与「挂上了但分组判断不对」。
            WeLogger.w(TAG, "address fragment hook unavailable (l0(Bundle) not matched)")
        }

        WeLogger.i(TAG, "list islands armed: $armed hook(s)")
        WeLogger.i(
            TAG,
            "list islands config: shape=${shape.name} grouping=$grouping " +
                "pages[chat=$conversationPageEnabled contacts=$contactsPageEnabled " +
                "discoverMe=$discoverMePageEnabled settings=$settingsPageEnabled] " +
                "unreadHighlight=$highlightUnread",
        )
    }

    override fun onDisable() {
        WeLogger.i(TAG, "list islands disabled; rows fall back to WeChat visuals on next bind")
    }

    // ---------------------------------------------------------------- 设置弹窗

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var groupingInput by remember { mutableStateOf(grouping) }
            var unreadInput by remember { mutableStateOf(highlightUnread) }
            var conversationInput by remember { mutableStateOf(conversationPageEnabled) }
            var contactsInput by remember { mutableStateOf(contactsPageEnabled) }
            var discoverMeInput by remember { mutableStateOf(discoverMePageEnabled) }
            var settingsInput by remember { mutableStateOf(settingsPageEnabled) }

            AlertDialogContent(
                title = { Text("圆角卡片") },
                text = {
                    DefaultColumn {
                        // ── 维度一：分组（与应用范围无关，四个页面共用） ──
                        SwitchRow(
                            title = "相邻行显示为整块卡片",
                            checked = groupingInput,
                            onToggle = {
                                groupingInput = !groupingInput
                                grouping = groupingInput
                            },
                        )
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
                        SwitchRow(
                            title = "应用于微信设置的下级菜单",
                            checked = settingsInput,
                            onToggle = {
                                settingsInput = !settingsInput
                                settingsPageEnabled = settingsInput
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
}
