package com.github.wekite.features.items.contacts

import android.app.Activity
import android.content.Intent
import com.tencent.mm.chatroom.ui.SelectedMemberChattingRecordUI
import com.github.wekite.features.api.ui.WeContactPrefsScreenApi
import com.github.wekite.features.api.ui.WeCurrentConversationApi
import com.github.wekite.features.core.Feature
import com.github.wekite.features.core.SwitchFeature
import com.github.wekite.utils.android.currentWxId
import com.github.wekite.utils.strings.isGroupChatWxId

@Feature(
    name = "查看群成员消息历史",
    categories = ["联系人与群组"],
    description = "在联系人与群组详情页面添加入口, 可查看任意群成员的全部历史消息"
)
object DisplayGroupMemberMessages : SwitchFeature(), WeContactPrefsScreenApi.IContactInfoProvider {

    private const val PREF_KEY = "member_msg"

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }

    override fun getContactInfoItem(activity: Activity): List<WeContactPrefsScreenApi.PreferenceItem> {
        if (!WeCurrentConversationApi.value.isGroupChatWxId) return emptyList()
        if (activity.currentWxId!!.isGroupChatWxId) return emptyList()

        return listOf(
            WeContactPrefsScreenApi.PreferenceItem(
                key = PREF_KEY,
                title = "查看群消息历史",
                position = 1
            )
        )
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        if (key != PREF_KEY) return false

        val groupId = WeCurrentConversationApi.value
        val memberId = activity.currentWxId ?: return true

        activity.startActivity(Intent(activity, SelectedMemberChattingRecordUI::class.java).apply {
            putExtra("RoomInfo_Id", groupId)
            putExtra("room_member", memberId)
            putExtra("title", "查看群成员消息历史")
        })

        return true
    }
}
