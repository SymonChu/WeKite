package com.github.wekite.features.items.chat.aichat

import android.app.Activity
import com.github.wekite.features.api.ui.WeContactPrefsScreenApi
import com.github.wekite.features.api.ui.WeContactPrefsScreenApi.IContactInfoProvider
import com.github.wekite.features.api.ui.WeContactPrefsScreenApi.PreferenceItem
import com.github.wekite.features.core.Feature
import com.github.wekite.features.core.SwitchFeature
import com.github.wekite.utils.android.currentWxId
import com.github.wekite.utils.android.showToast

/**
 * 把「每个聊天/群」的 AI 开关放进**群详情 / 联系人聊天详情**页（用户 2026-09-28 要求）。
 *
 * 为什么放这里（用户原话「跟着会话走，会错位」）：
 * 详情页的会话 ID 来自**页面自己的 Intent**（`Contact_User` / `RoomInfo_Id`，见 `Activity.currentWxId`），
 * 是「这一个联系人/这一个群」的固有属性 —— 不存在聊天页切换时的错位窗口。
 *
 * 形态：WeChat 详情页的原生列表条目（标题 + 副标题），**点按切换**，状态写在副标题里。
 * 微信自己的勾选控件（CheckBoxPreference）在 8.0.77 里方法与字段全被混淆
 * （`J` 布尔字段 / `P()Z` / `Q(Z)V` / `V(Z)V`），靠猜 setter 风险高 ⇒ 走仓库已验证的
 * `WeContactPrefsScreenApi` 文字行机制（同机制已有 5 个功能在跑）。
 */
@Feature(name = "AI 助手开关（聊天/群详情）", categories = ["聊天"], description = "在聊天或群详情页单独开启该聊天的 AI 分析与自动回复")
object AiChatPrefsEntry : SwitchFeature(), IContactInfoProvider {

    private const val KEY_ANALYZE = "wekite_ai_analyze"
    private const val KEY_AUTO = "wekite_ai_auto"
    private const val KEY_GROUP_ALL = "wekite_ai_group_all"

    override fun getContactInfoItem(activity: Activity): List<PreferenceItem> {
        val talker = activity.currentWxId?.takeIf { it.isNotBlank() } ?: return emptyList()
        val isGroup = ContextBuilder.isGroupTalker(talker)
        val items = mutableListOf(
            PreferenceItem(
                key = KEY_ANALYZE,
                title = "AI 自动分析并给建议",
                summary = if (AiChatStore.isAnalyzeOn(talker)) "已开启 · 点按关闭" else "已关闭 · 点按开启",
                position = 1,
            ),
            PreferenceItem(
                key = KEY_AUTO,
                title = "AI 全自动回复",
                summary = buildString {
                    append(if (AiChatStore.isAutoReplyOn(talker)) "已开启 · 点按关闭" else "已关闭 · 点按开启")
                    if (!AiChatConfig.autoReplyConsent) append("（会同时打开模块里的全局总闸）")
                },
                position = 2,
            ),
        )
        if (isGroup) {
            items += PreferenceItem(
                key = KEY_GROUP_ALL,
                title = "群里所有消息也处理",
                summary = if (AiChatStore.isGroupAllMessages(talker)) "已开启 · 点按改为只回 @我" else "已关闭 · 点按改为处理所有消息",
                position = 3,
            )
        }
        return items
    }

    override fun onItemClick(activity: Activity, key: String): Boolean {
        val talker = activity.currentWxId?.takeIf { it.isNotBlank() } ?: return true
        when (key) {
            KEY_ANALYZE -> {
                val on = !AiChatStore.isAnalyzeOn(talker)
                AiChatStore.setAnalyzeOn(talker, on)
                if (!on) AnalysisDialog.close()
                showToast(activity, if (on) "已开启自动分析" else "已关闭自动分析")
            }
            KEY_AUTO -> {
                val on = !AiChatStore.isAutoReplyOn(talker)
                if (on && !AiChatConfig.autoReplyConsent) AiChatConfig.autoReplyConsent = true
                AiChatStore.setAutoReplyOn(talker, on)
                showToast(activity, if (on) "已开启全自动回复" else "已关闭全自动回复")
            }
            KEY_GROUP_ALL -> {
                val on = !AiChatStore.isGroupAllMessages(talker)
                AiChatStore.setGroupAllMessages(talker, on)
                showToast(activity, if (on) "群里所有消息都会处理" else "只在被 @ 时处理")
            }
            else -> return false
        }
        // 若该聊天此刻正开着，头部状态立即跟着变
        if (ChatUi.talker == talker) ChatHeaderStatus.refreshText()
        return true
    }

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }
}
