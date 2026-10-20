package com.github.wekite.features.items.contacts

object CustomLocalFriendAvatars {
    val isActive: Boolean get() = false

    /**
     * 自定义头像的缓存版本号。通知进化用它判断头像缓存是否需要刷新;
     * 本仓未启用自定义头像, 恒返回空串 = 不参与缓存键。
     */
    fun notificationAvatarCacheKey(username: String): String = ""

    fun onRoundAvatarConfigChanged() {}
}
