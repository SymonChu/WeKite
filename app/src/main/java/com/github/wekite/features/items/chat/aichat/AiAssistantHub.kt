package com.github.wekite.features.items.chat.aichat

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.wekite.features.core.ClickableFeature
import com.github.wekite.features.core.Feature
import com.github.wekite.features.items.chat.AiGroupNewMsgSummary
import com.github.wekite.preferences.WePrefs
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.Button
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.utils.showComposeDialog

/**
 * AI 助手（合并入口）。
 *
 * 2026-11-15 用户要求把「AI 聊天助手」与「群聊消息分析」合并成一个功能菜单：
 * - 功能列表只保留本条目；两个子系统各自保留原偏好键（老用户配置不丢）
 * - 弹窗内两个 Tab，各自独立开关（只开分析、不开自动回复是有效组合）
 *
 * 两个子系统不再带 @Feature（不进 FeaturesProvider）⇒ 生命周期由本入口转发：
 * 启用时调用各自的 startup()（内部按自己的偏好决定是否真的 enable）。
 */
@Feature(
    name = "AI 助手",
    categories = ["聊天"],
    description = "AI 聊天助手 + 群聊消息分析：一个菜单、两个独立开关"
)
object AiAssistantHub : ClickableFeature() {

    init {
        pinnedFirst = true
    }

    /**
     * 老用户兼容：任一子系统之前开过 ⇒ 合并入口默认开。
     *
     * 否则升级后「AI 助手」这个新键默认 false，用户原先开着的功能会被静默关掉。
     */
    override val defaultEnabled: Boolean
        get() = WePrefs.getBoolOrDef(AiChatAssistant.name, AiChatAssistant.defaultEnabled) ||
                WePrefs.getBoolOrDef(AiGroupNewMsgSummary.name, AiGroupNewMsgSummary.defaultEnabled)

    override fun onEnable() {
        AiChatAssistant.startup()
        AiGroupNewMsgSummary.startup()
    }

    override fun onDisable() {
        // 只关运行时，保留偏好：重新打开入口时按用户原设置恢复
        AiChatAssistant.isEnabled = false
        AiGroupNewMsgSummary.isEnabled = false
    }

    override fun onClick(context: ComponentActivity) = showHub(context)

    private fun showHub(activity: Activity) {
        showComposeDialog(activity) {
            var tab by remember { mutableIntStateOf(0) }
            var assistantOn by remember { mutableStateOf(AiChatAssistant.isEnabled) }
            var summaryOn by remember { mutableStateOf(AiGroupNewMsgSummary.isEnabled) }
            AlertDialogContent(
                title = { Text("AI 助手") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        TabRow(selectedTabIndex = tab) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("聊天助手") })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("群聊分析") })
                        }
                        Spacer(Modifier.height(12.dp))
                        when (tab) {
                            0 -> {
                                SwitchRow("启用聊天助手", assistantOn) { v ->
                                    assistantOn = v
                                    AiChatAssistant.applyToggle(v)
                                }
                                Text(
                                    "新消息自动分析并给出建议回复，可全自动回复。开关与设置独立于群聊分析。",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                                TextButton(onClick = { AiChatAssistant.openSettingsDialog(activity) }) {
                                    Text("打开聊天助手设置")
                                }
                            }

                            else -> {
                                SwitchRow("启用群聊分析", summaryOn) { v ->
                                    summaryOn = v
                                    AiGroupNewMsgSummary.applyToggle(v)
                                }
                                Text(
                                    "聊天页「N条新消息」胶囊旁的「AI分析」：先出本地统计，再出 AI 报告。" +
                                            "开关与设置独立于聊天助手。",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                                TextButton(onClick = { AiGroupNewMsgSummary.openSettingsDialog(activity) }) {
                                    Text("打开分析设置")
                                }
                            }
                        }
                    }
                },
                confirmButton = { Button(onDismiss) { Text("关闭") } },
            )
        }
    }

    @Composable
    private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, Modifier.weight(1f))
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}
