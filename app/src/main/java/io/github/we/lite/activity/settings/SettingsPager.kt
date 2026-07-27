package io.github.we.lite.activity.settings

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import coil3.compose.AsyncImage
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Account_circle
import com.composables.icons.materialsymbols.outlined.Arrow_back
import com.composables.icons.materialsymbols.outlined.Block
import com.composables.icons.materialsymbols.outlined.Brightness_medium
import com.composables.icons.materialsymbols.outlined.Build_circle
import com.composables.icons.materialsymbols.outlined.Close
import com.composables.icons.materialsymbols.outlined.Colorize
import com.composables.icons.materialsymbols.outlined.Contrast
import com.composables.icons.materialsymbols.outlined.Delete_forever
import com.composables.icons.materialsymbols.outlined.Download
import com.composables.icons.materialsymbols.outlined.Frame_bug
import com.composables.icons.materialsymbols.outlined.Label
import com.composables.icons.materialsymbols.outlined.License
import com.composables.icons.materialsymbols.outlined.Notifications
import com.composables.icons.materialsymbols.outlined.Palette
import com.composables.icons.materialsymbols.outlined.Rule_settings
import com.composables.icons.materialsymbols.outlined.Search
import com.composables.icons.materialsymbols.outlined.Style
import com.composables.icons.materialsymbols.outlined.Sync
import com.composables.icons.materialsymbols.outlined.Update
import com.composables.icons.materialsymbols.outlined.Upload
import com.composables.icons.materialsymbols.outlined.Wallpaper
import com.mikepenz.aboutlibraries.Libs
import com.mikepenz.aboutlibraries.entity.Library
import com.tencent.mm.ui.LauncherUI
import io.github.we.lite.BuildConfig
import io.github.we.lite.R
import io.github.we.lite.activity.TransparentActivity
import io.github.we.lite.constants.Preferences
import io.github.we.lite.features.api.core.WeApi
import io.github.we.lite.ui.content.MiuixSmallTitle
import io.github.we.lite.ui.utils.GitHubIcon
import io.github.we.lite.ui.utils.theme.AppColorSpec
import io.github.we.lite.ui.utils.theme.AppPaletteStyle
import io.github.we.lite.ui.utils.theme.AppThemeMode
import io.github.we.lite.ui.utils.theme.ThemeSettings
import io.github.we.lite.utils.HostInfo
import io.github.we.lite.utils.WeLogger
import io.github.we.lite.utils.formatEpoch
import io.github.we.lite.utils.openInSystem
import io.github.we.lite.utils.serialization.DefaultJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.ColorPicker
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

// ---------------------------------------------------------------------------
//  Page 2 — Settings
// ---------------------------------------------------------------------------

@Composable
fun SettingsPager(onOpenLicense: () -> Unit) {
    val context = LocalComponentActivity.current

    var showClearConfirm by remember { mutableStateOf(false) }

    ClearConfigDialog(show = showClearConfirm, onDismiss = { showClearConfirm = false })

    MiuixListScaffold(title = "设置") {
        // Account info card — shown at top of Settings tab.
        item {
            Spacer(Modifier.height(12.dp))
            ProfileCard()
        }

        // 界面
        item {
            MiuixSmallTitle(text = "界面", modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                ThemeSection()
            }
        }

        // 调试
        item {
            MiuixSmallTitle(text = "调试", modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefSwitch(
                    key = Preferences.VERBOSE_LOG,
                    title = "详细日志",
                    summary = "输出高频日志 (这可能会暴露你的隐私信息）",
                    icon = MaterialSymbols.Outlined.Frame_bug,
                )
                PrefSwitch(
                    key = Preferences.SHOW_STARTUP_TOAST,
                    title = "显示加载完成 Toast",
                    summary = "全部功能加载完成后显示 Toast 提示",
                    icon = MaterialSymbols.Outlined.Notifications,
                )
                PrefSwitch(
                    key = Preferences.MATCH_GENERIC_WXID_EXP,
                    title = "清理消息内容微信 ID 前缀时允许非标准 ID",
                    summary = "允许处理不带 'wxid_' 前缀的微信 ID, 可能导致误伤消息原始内容 (实验性)",
                    icon = MaterialSymbols.Outlined.Rule_settings,
                    default = true,
                )
            }
        }

        // 兼容
        item {
            MiuixSmallTitle(text = "兼容", modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefSwitch(
                    key = Preferences.NO_DEX_RESOLVE,
                    title = "禁用版本适配",
                    summary = "不弹出 DEX 查找对话框，未适配功能将不会被加载",
                    icon = MaterialSymbols.Outlined.Block,
                )
                PrefArrow(
                    title = "重置适配信息",
                    summary = "清除 DEX 缓存, 等待下次启动时重新适配",
                    icon = MaterialSymbols.Outlined.Build_circle,
                    onClick = { /** ResetDexCache removed in WeLite */ },
                )
                PrefSwitch(
                    key = Preferences.RESET_DEX_ON_HOT_UPDATE,
                    title = "宿主热更新时重新适配",
                    summary = "宿主热更新时是否重置 DEX 缓存, 可能导致频繁重新适配 (实验性)",
                    icon = MaterialSymbols.Outlined.Auto_delete,
                )
            }
        }

        // 配置
        item {
            MiuixSmallTitle(text = "配置", modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefArrow(
                    title = "导出配置",
                    summary = "将模块配置导出为 JSON",
                    icon = MaterialSymbols.Outlined.Upload,
                    onClick = { exportConfig(context) },
                )
                PrefArrow(
                    title = "导入配置",
                    summary = "从 JSON 导入模块配置; JSON 中的配置将会与现有配置合并, 覆盖所有已存在的配置",
                    icon = MaterialSymbols.Outlined.Download,
                    onClick = { importConfig(context) },
                )
                PrefArrow(
                    title = "清除配置",
                    summary = "清除全部模块配置 (警告: 此操作不可逆!)",
                    icon = MaterialSymbols.Outlined.Delete_forever,
                    onClick = { showClearConfirm = true },
                )
            }
        }

        // 更新
        item {
            MiuixSmallTitle(text = "更新", modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefArrow(
                    title = "检查更新",
                    summary = "立即检查模块是否有新版本并自动下载",
                    icon = MaterialSymbols.Outlined.Update,
                    onClick = {
                        checkForUpdate(
                            onAvailable = { /* updateInfo = it */ },
                            onError = { /* updateError = it */ },
                        )
                    },
                )
            }
        }

        // 关于
        item {
            MiuixSmallTitle(text = "关于", modifier = Modifier.padding(top = 12.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                PrefArrow(title = "版本", summary = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", icon = MaterialSymbols.Outlined.Label)
                PrefArrow(title = "构建提交时间", summary = formatEpoch(BuildConfig.BUILD_TIMESTAMP, true), icon = MaterialSymbols.Outlined.Build_circle)
                PrefArrow(
                    title = "开放源代码许可",
                    summary = "本项目使用的开放源代码库许可",
                    icon = MaterialSymbols.Outlined.License,
                    onClick = onOpenLicense,
                )
                PrefArrow(
                    title = "GitHub",
                    summary = "SymonChu/WeLite",
                    icon = GitHubIcon,
                    onClick = { "https://github.com/SymonChu/WeLite".toUri().openInSystem(context, true) })
            }
        }

        item { Spacer(Modifier.height(CONTENT_BOTTOM_INSET)) }
    }
}

// ---------------------------------------------------------------------------
//  Profile card — account info at the top of the Settings tab
// ---------------------------------------------------------------------------

@Composable
private fun ProfileCard() {
    val wxId = remember { WeApi.selfWxId }

    // WeChat identity — loaded once from the local DB; doesn't change mid-session.
    data class WechatIdentity(val nickname: String, val avatarUrl: String)

    val identity by produceState(WechatIdentity("", "")) {
        withContext(Dispatchers.IO) {
            val db = io.github.we.lite.features.api.core.WeDatabaseApi
            val nickname = if (db.isReady) {
                db.getSelfProfileField(io.github.we.lite.features.api.core.models.SelfProfileField.NAME, "")
                    ?.toString().orEmpty()
            } else ""
            val avatarUrl = if (db.isReady && wxId.isNotEmpty()) db.getAvatarUrl(wxId) else ""
            value = WechatIdentity(nickname, avatarUrl)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (identity.avatarUrl.isNotEmpty()) {
                AsyncImage(
                    model = identity.avatarUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape),
                )
            } else {
                AvatarPlaceholder()
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = identity.nickname.ifEmpty { wxId.ifEmpty { "—" } },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MiuixTheme.colorScheme.onSurface,
                )
                if (wxId.isNotEmpty()) {
                    Text(
                        text = wxId,
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AvatarPlaceholder() {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = MaterialSymbols.Outlined.Account_circle,
            contentDescription = null,
            modifier = Modifier.size(34.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** A miuix dropdown bound to an enum's entries. */
@Composable
private fun <T> EnumDropdown(
    title: String,
    entries: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelected: (T) -> Unit,
    summary: String? = null,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    WindowDropdownPreference(
        title = title,
        summary = summary,
        items = entries.map(labelOf),
        selectedIndex = entries.indexOf(selected).coerceAtLeast(0),
        enabled = enabled,
        startAction = icon?.let { { PrefIcon(it) } },
        onSelectedIndexChange = { onSelected(entries[it]) },
    )
}

@Composable
private fun ThemeSection() {
    EnumDropdown(
        title = "主题模式",
        entries = AppThemeMode.entries,
        selected = ThemeSettings.themeMode,
        labelOf = { it.displayName },
        onSelected = { ThemeSettings.updateThemeMode(it) },
        icon = MaterialSymbols.Outlined.Brightness_medium,
    )

    var customColor by remember { mutableStateOf(ThemeSettings.customColor) }
    SwitchPreference(
        title = "自定义颜色",
        summary = "使用调色板样式生成配色, 而非 Miuix 默认蓝",
        startAction = { PrefIcon(MaterialSymbols.Outlined.Palette) },
        checked = customColor,
        onCheckedChange = {
            customColor = it
            ThemeSettings.updateCustomColor(it)
        },
    )

    var showColorPicker by remember { mutableStateOf(false) }
    SeedColorPickerDialog(show = showColorPicker, onDismiss = { showColorPicker = false })

    AnimatedVisibility(visible = customColor) {
        Column {
            var dynamicWallpaper by remember { mutableStateOf(ThemeSettings.dynamicWallpaper) }
            SwitchPreference(
                title = "动态壁纸取色",
                summary = "使用系统壁纸的强调色作为种子\n需系统 Android SDK >= 31",
                startAction = { PrefIcon(MaterialSymbols.Outlined.Wallpaper) },
                checked = dynamicWallpaper,
                onCheckedChange = {
                    dynamicWallpaper = it
                    ThemeSettings.updateDynamicWallpaper(it)
                },
            )
            AnimatedVisibility(visible = !dynamicWallpaper) {
                BasicComponent(
                    title = "种子颜色",
                    summary = "点击选择配色的种子颜色",
                    startAction = { PrefIcon(MaterialSymbols.Outlined.Colorize) },
                    onClick = { showColorPicker = true },
                    endActions = {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(ThemeSettings.seedColor)),
                        )
                    },
                )
            }
            EnumDropdown(
                title = "调色板样式",
                entries = AppPaletteStyle.entries,
                selected = ThemeSettings.paletteStyle,
                labelOf = { it.displayName },
                onSelected = {
                    ThemeSettings.updatePaletteStyle(it)
                    if (!it.supportsSpec2025 && ThemeSettings.colorSpec == AppColorSpec.SPEC_2025) {
                        ThemeSettings.updateColorSpec(AppColorSpec.SPEC_2021)
                    }
                },
                icon = MaterialSymbols.Outlined.Style,
            )
            val spec2025Supported = ThemeSettings.paletteStyle.supportsSpec2025
            EnumDropdown(
                title = "颜色规格",
                entries = if (spec2025Supported) AppColorSpec.entries else listOf(AppColorSpec.SPEC_2021),
                selected = ThemeSettings.effectiveColorSpec,
                labelOf = { it.displayName },
                onSelected = { ThemeSettings.updateColorSpec(it) },
                enabled = spec2025Supported,
                summary = if (!spec2025Supported) "当前调色板样式仅支持 Material 3 (2021)" else null,
                icon = MaterialSymbols.Outlined.Contrast,
            )

            var applyToWechat by remember { mutableStateOf(ThemeSettings.applyToWechat) }
            SwitchPreference(
                title = "同时对微信生效",
                summary = "将对模块设置的颜色同时应用于微信内部 (会影响微信渲染性能)",
                startAction = { PrefIcon(MaterialSymbols.Outlined.Sync) },
                checked = applyToWechat,
                onCheckedChange = {
                    applyToWechat = it
                    ThemeSettings.updateApplyToWechat(it)
                },
            )
        }
    }
}
