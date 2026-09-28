package com.github.wekite.features.items.chat.aichat

import android.app.Activity
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.Button
import com.github.wekite.ui.content.DefaultColumn
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.utils.showComposeDialog
import com.github.wekite.utils.WeLogger

/**
 * 「情绪分析 / 建议回复」弹窗。
 *
 * 用户 2026-09-28 定案：**用「群聊消息分析」同款弹窗**（同一套 `AlertDialogContent`：
 * 分析中带旋转流光、出结果带静态描边），**位置贴着聊天输入框上方一点**，不是屏幕居中。
 *
 * 与群聊分析弹窗的区别只有两点：
 * 1. 窗口 `Gravity.BOTTOM` + `y` 偏移 = 输入框实测高度 + 间隙（因此落在输入框正上方）；
 * 2. 内容随分析状态实时刷新（同一个弹窗从「分析中」变到「结果」，不重开窗口）。
 */
object AnalysisDialog {
    private const val TAG = "AiAnalysisDialog"
    private const val SIDE_DP = 12
    private const val GAP_DP = 6

    private val state = mutableStateOf<ChatAiEngine.State?>(null)
    private var showing = false
    private var shownActivity: java.lang.ref.WeakReference<Activity>? = null
    private var shownTalker: String? = null
    private var dismissDialog: (() -> Unit)? = null

    /** 分析开始/更新时调用（引擎线程安全：内部切主线程）。 */
    fun update(talker: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (!showing || shownTalker != talker) return@post
            state.value = ChatAiEngine.stateOf(talker)
            ChatHeaderStatus.refreshText()
        }
    }

    /** 有分析要展示时调用；没有 Activity 就什么都不做（下次消息到达再试）。 */
    fun show(talker: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val activity = ChatUi.activity ?: run {
                WeLogger.i(TAG, "no activity bound, dialog skipped talker=$talker")
                return@post
            }
            if (activity.isFinishing || activity.isDestroyed) return@post
            if (showing && shownTalker == talker) {
                state.value = ChatAiEngine.stateOf(talker)
                return@post
            }
            if (showing) close()
            state.value = ChatAiEngine.stateOf(talker)
            shownTalker = talker
            shownActivity = java.lang.ref.WeakReference(activity)
            showing = true

            showComposeDialog(activity, directlyDismissable = true) {
                dismissDialog = onDismiss
                val dm = activity.resources.displayMetrics
                val density = dm.density
                val side = (SIDE_DP * density).toInt()
                val gap = (GAP_DP * density).toInt()
                val footerH = ChatUi.footerHeightPx().takeIf { it > 0 } ?: (56 * density).toInt()
                // 贴输入框上方：底部对齐 + 向上偏移一个输入框高度
                window.setLayout((dm.widthPixels - side * 2).coerceAtLeast(1), WindowManager.LayoutParams.WRAP_CONTENT)
                window.setGravity(Gravity.BOTTOM)
                window.attributes = window.attributes.apply { y = footerH + gap }
                WeLogger.i(
                    TAG,
                    "dialog window w=${dm.widthPixels - side * 2} bottomOffset=${footerH + gap} (footer=$footerH)"
                )

                val s = state.value
                AlertDialogContent(
                    title = { Text(titleOf(s)) },
                    text = { Body(s, shownTalker.orEmpty()) },
                    rotatingBorder = s is ChatAiEngine.State.Working,
                    confirmButton = { Button({ close() }) { Text("关闭") } },
                )
            }
        }
    }

    fun close() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (!showing) return@post
            showing = false
            shownTalker = null
            dismissDialog?.invoke()
            dismissDialog = null
            state.value = null
        }
    }

    private fun titleOf(s: ChatAiEngine.State?): String = when (s) {
        null -> "AI · 分析中…"
        is ChatAiEngine.State.Working -> "AI · 分析中…"
        is ChatAiEngine.State.Failed -> "AI · ${s.message}"
        is ChatAiEngine.State.Done ->
            "AI · ${s.result.emotionLine}" + if (s.result.autoSent) " · 已自动回复" else ""
    }

    @Composable
    private fun Body(s: ChatAiEngine.State?, talker: String) {
        DefaultColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp), scrollable = true) {
            when (s) {
                null, is ChatAiEngine.State.Working -> {
                    Text("正在分析这条消息的意图与情绪…", style = MaterialTheme.typography.bodyMedium)
                }
                is ChatAiEngine.State.Failed -> {
                    Text(s.message, style = MaterialTheme.typography.bodyMedium)
                    TextButton({ ChatAiEngine.retry(talker) }) { Text("重试") }
                }
                is ChatAiEngine.State.Done -> {
                    val r = s.result
                    if (r.reading.isNotBlank()) {
                        Text("解读（推测）：${r.reading}", style = MaterialTheme.typography.bodyMedium)
                    }
                    r.replies.forEachIndexed { i, reply ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${i + 1}. $reply",
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        val ok = InputBar.fill(reply)
                                        ChatUi.activity?.let {
                                            android.widget.Toast.makeText(
                                                it, if (ok) "已填入输入框" else "填入失败（可长按复制）",
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                        }
                                        if (ok) close()
                                    }
                                    .padding(vertical = 4.dp),
                            )
                            TextButton({ if (InputBar.send(talker, reply)) close() }) { Text("发") }
                        }
                    }
                    if (r.note.isNotBlank()) {
                        Text(r.note, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "点建议 = 填入输入框 · 直接发 = 立刻发送",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
