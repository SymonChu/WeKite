package com.github.wekite.features.items.chat

import com.github.wekite.features.core.ApiFeature
import com.github.wekite.features.core.Feature

/**
 * 群聊归拢的异常恢复钩：每次微信主进程启动都会跑一次（不受「群聊归拢」开关影响）。
 *
 * 存在理由：[GroupAggregation] 会改宿主的 `rconversation`（把群聊设成「不显示」+ 插一条入口行）。
 * 一旦出现「功能被关掉但改动没还原」或「入口行被微信清了、群却还被藏着」，用户就彻底看不到群聊了。
 * 这个守护条目在启动时把这两种死局都还原掉。
 */
@Feature(
    name = "群聊归拢守护",
    categories = ["API"],
    description = "群聊归拢的异常恢复：功能已关闭或入口行丢失时自动还原群聊显示"
)
object GroupAggregationGuard : ApiFeature() {

    override fun onEnable() {
        GroupAggregation.recoverIfNeeded()
    }
}
