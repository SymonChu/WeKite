package com.github.wekite.features.items.contacts

import android.app.Activity
import android.content.Context
import android.os.Build
import com.github.wekite.features.api.core.WeContactLabelApi
import com.github.wekite.features.api.core.WeDatabaseApi
import com.github.wekite.features.api.ui.WeContactPrefsScreenApi
import com.github.wekite.features.api.ui.WeContactPrefsScreenApi.IContactInfoProvider
import com.github.wekite.features.api.ui.WeContactPrefsScreenApi.PreferenceItem
import com.github.wekite.features.api.ui.WeCurrentConversationApi
import com.github.wekite.features.core.Feature
import com.github.wekite.features.core.SwitchFeature
import com.github.wekite.utils.WeLogger
import com.github.wekite.utils.android.copyToClipboard
import com.github.wekite.utils.android.currentWxId
import com.github.wekite.utils.android.showToast
import com.github.wekite.utils.strings.isGroupChatWxId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 导出群成员 —— 在群资料页添加「导出群成员」入口，一键复制全量成员信息到剪贴板。
 *
 * 数据全部来自 WeDatabaseApi 现成查询（零新增 DEX 锚点）：
 * - `getGroupMembers(groupId)`：chatroom.memberlist（`;` 分隔的 wxId 列表）
 *   联查 rcontact → wxId / 昵称 / 备注 / 微信号（customWxId）/ 头像等；
 * - `getGroupMemberDisplayName(groupId, wxId)`：chatroom.roomdata protobuf → 群昵称；
 * - `getGroupMemberInviter(groupId, wxId)`：roomdata protobuf → 进群邀请人 wxId（可能缺）。
 *
 * 导出格式（TSV，制表符分隔，可直接粘进 Excel / 表格软件）：
 *   群昵称  昵称  备注  微信号  wxId  邀请人wxId  成员类型
 * 第一行为表头，第二行是汇总（群名 / 总人数 / 导出时间），随后每成员一行。
 * 邀请人缺失时写 `—`；非好友成员在 rcontact 里查不到时昵称列写 wxId 本身。
 *
 * 大群注意：剪贴板无大小硬限制，但数百行文本时部分输入法/剪贴板管理器可能截断——
 * 导出后模块会用 Toast 报「已复制 N 条」，N 与预期不符时按成员数检查。
 */
@Feature(
    name = "导出群成员",
    categories = ["联系人与群组"],
    description = "在群资料页添加入口，一键复制全部成员（群昵称/昵称/备注/微信号/邀请人）到剪贴板，TSV 格式可直接粘贴到表格软件"
)
object ExportGroupMembers : SwitchFeature(), IContactInfoProvider {

    private const val TAG = "ExportGroupMembers"
    private const val PREF_KEY = "export_group_members"

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }

    // ── IContactInfoProvider ──────────────────────────────────────────────────

    /** 仅群聊的资料页显示（普通联系人 / 群成员个人页不显示） */
    override fun getContactInfoItem(activity: Activity): List<PreferenceItem> {
        val groupId = WeCurrentConversationApi.value
        if (!groupId.isGroupChatWxId) return emptyList()
        return listOf(
            PreferenceItem(
                key = PREF_KEY,
                title = "导出群成员",
                summary = "复制全部成员信息（TSV）到剪贴板",
                position = 60,
            )
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != PREF_KEY) return false
        val groupId = WeCurrentConversationApi.value
        if (!groupId.isGroupChatWxId) return true

        // 数据库查询可能较慢（数百成员 × 多次 protobuf 解码），丢后台线程
        CoroutineScope(Dispatchers.IO).launch {
            val text = runCatching { buildExportText(groupId) }
                .onFailure { WeLogger.e(TAG, "export failed; groupId=$groupId", it) }
                .getOrNull()
            withContext(Dispatchers.Main) {
                if (text == null) {
                    showToast(activity, "导出失败，见模块日志")
                } else {
                    copyToClipboard(activity, text)
                    val lines = text.count { it == '\n' } - 2 // 表头+汇总行
                    showToast(activity, "已复制 $lines 个成员")
                }
            }
        }
        return true
    }

    // ── 导出内容组装 ──────────────────────────────────────────────────────────

    private fun buildExportText(groupId: String): String {
        val members = WeDatabaseApi.getGroupMembers(groupId)
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val rows = ArrayList<List<String>>(members.size)
        for (m in members) {
            val displayName = runCatching {
                WeDatabaseApi.getGroupMemberDisplayName(groupId, m.wxId)
            }.getOrDefault("")
            val inviter = runCatching {
                WeDatabaseApi.getGroupMemberInviter(groupId, m.wxId)
            }.getOrDefault("")

            rows.add(
                listOf(
                    displayName.ifBlank { "—" },
                    m.nickname.ifBlank { "—" },
                    m.remarkName.ifBlank { "—" },
                    m.customWxId.ifBlank { "—" },
                    m.wxId,
                    inviter.ifBlank { "—" },
                )
            )
        }

        // 排序：有群昵称的在前（通常=核心/管理），随后按昵称的拼音
        val sorted = rows.sortedWith(
            compareByDescending<List<String>> { it[0] != "—" }.thenBy { it[1] }
        )

        val header = "群昵称\t昵称\t备注\t微信号\twxId\t邀请人"
        val summary = "成员数: ${sorted.size}    导出时间: $now    群ID: $groupId"
        val body = sorted.joinToString("\n") { r -> r.joinToString("\t") }

        return "$header\n$summary\n$body\n"
    }
}
