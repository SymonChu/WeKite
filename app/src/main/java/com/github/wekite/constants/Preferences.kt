package com.github.wekite.constants

import com.github.wekite.preferences.WePrefs.Companion.prefOption

object Preferences {

    const val VERBOSE_LOG = "verbose_log"
    const val NO_DEX_RESOLVE = "no_dex_resolve"
    const val SHOW_STARTUP_TOAST = "toast_startup"

    // 已移除的两个设置项 (设置界面入口已删除, 行为固化为原默认值):
    //   "reset_dex_on_hot_upd"  → 恒为 false (宿主热更新时不重置 DEX 缓存), 见 WeLauncher.init
    //   "match_generic_wxid"    → 恒为 true  (允许非标准微信 ID), 见 MessageTextUtils.stripWxId
    // 旧 MMKV 里残留的键值不再被读取, 无害。

    // Settings UI theming
    const val THEME_MODE = "settings_theme_mode"
    const val THEME_CUSTOM_COLOR = "settings_theme_custom_color"
    const val THEME_DYNAMIC_WALLPAPER = "settings_theme_dynamic_wallpaper"
    const val THEME_PALETTE_STYLE = "settings_theme_palette_style"
    const val THEME_COLOR_SPEC = "settings_theme_color_spec"
    const val THEME_SEED_COLOR = "settings_theme_seed_color"
    const val THEME_APPLY_TO_WECHAT = "settings_theme_apply_to_wechat"

    var verboseLog by prefOption(VERBOSE_LOG, false)
    var noDexResolve by prefOption(NO_DEX_RESOLVE, false)
    var showStartupToast by prefOption(SHOW_STARTUP_TOAST, false)

    // use this when Google fucked up itself again
//    var useActivityInsteadOfDialog: Boolean
//        get() = false
//        set(value) { WePrefs.putBool(USE_ACTIVITY_INSTEAD_OF_DIALOG, value) }
}
