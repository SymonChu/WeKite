package com.github.wekite.features.items.chat.aichat

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.utils.showComposeDialog
import com.github.wekite.utils.WeLogger
import java.lang.ref.WeakReference

/**
 * 聊天页右上角「AI 状态」显示 + 长按「…」出快捷菜单。
 *
 * - 状态（只显示，不是开关）：灰 `AI`=未开 / 蓝 `AI`=分析已开 / 蓝 `AI…`=分析中 / 蓝 `AI+`=全自动已开
 * - **点状态文字 = 打开助手设置**
 * - **长按标题栏「…」按钮 = 本聊天的快捷菜单**（用户 2026-09-28 指定的入口方式）
 * - 本聊天的开关本体在长按「…」菜单（详情页条目已删，用户 2026-09-29：长按也行）
 *
 * ⚠️ 会话 ID 一律来自 [ChatUi]（事件驱动 + 多源，见其注释）；此处绝不自行取会话。
 */
object ChatHeaderStatus {
    private const val TAG = "AiHeaderStatus"
    private val BLUE = 0xFF1F6FEB.toInt()
    private val GREY = 0xFF9AA3B2.toInt()

    private var label: TextView? = null
    private var title: TextView? = null
    private var oldTitleWidth = Int.MAX_VALUE
    private var oldEllipsize: TextUtils.TruncateAt? = null
    private var boundTalker: String? = null
    private var menuButton: WeakReference<View>? = null
    private val main = Handler(Looper.getMainLooper())
    private var retryPending: Runnable? = null
    /** 「talker|状态文本」签名：限频用（2026-09-30 用户报「经常挂不上」但 v3.61 把挂载成功降 D
     *  ⇒ 成功率无法统计。改为：签名变化才打 I，未变（同聊天反复进出）降 D，不回刷屏）。 */
    private var lastAttachedSig: String? = null

    /** 头部未就绪补挂：300ms×5 次封顶（约 1.5s，覆盖微信分帧装配窗口）。 */
    private const val RETRY_MAX = 5
    private const val RETRY_STEP_MS = 300L

    fun sync(activity: Activity, talker: String?, attempt: Int = 1) {
        if (talker.isNullOrBlank()) { remove(); return }
        boundTalker = talker
        val header = findHeader(activity)
        if (header == null) {
            // 微信聊天页分帧装配：resume/会话事件瞬间原生标题栏可能还没 inflate（真机日志
            // 2026-09-29：19:25–19:33 窗口 10+ 次进聊天全部静默失败=一直看不到徽标）。
            // 找不到宿主时重试而不是放弃——用户停在聊天页时不会再有 resume/会话事件，
            // 放弃=缺到下次切聊天。
            retryAttach(activity, talker, attempt)
            return
        }
        retryPending?.let { main.removeCallbacks(it) }
        retryPending = null
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        installMenuLongPress(header, activity, talker)

        val existing = label
        if (existing?.parent === header && existing.isShown) {
            refreshText()
            return
        }
        removeLabelOnly()

        val tv = TextView(activity).apply {
            textSize = 12f
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(2), dp(6))
            isClickable = true
            setOnClickListener {
                boundTalker?.let { AiChatAssistant.openSettingsDialog(activity, it) }
            }
        }
        label = tv
        header.addView(
            tv,
            FrameLayout.LayoutParams(-2, dp(44), Gravity.END or Gravity.CENTER_VERTICAL).apply {
                // -7dp 右移（累计 -3 → -5 → -7；用户 2026-09-30：再往右 2dp；
                // END 锚定下减 rightMargin = 向右边缘靠）
                rightMargin = (menuSpaceFor(header, density) - dp(7)).coerceAtLeast(0)
            }
        )
        tv.post {
            if (label !== tv || !tv.isAttachedToWindow) return@post
            val t = descendants(header).filterIsInstance<TextView>().firstOrNull {
                it !== tv && it.isShown && it.text.isNotBlank() && it.width > dp(90)
            }
            title = t
            t?.let {
                oldTitleWidth = it.maxWidth
                oldEllipsize = it.ellipsize
                it.maxWidth = (header.width - 2 * (tv.width + dp(56))).coerceAtLeast(dp(60))
                it.ellipsize = TextUtils.TruncateAt.END
            }
        }
        refreshText()
        // 挂载成功的可见性（2026-09-30）：签名（talker|状态文本）变化才打 I，同聊天反复进出降 D
        // ——v3.61 全降 D 导致「挂不上」的发生率/场景无法从日志统计（用户当日反馈）。
        // 状态文本已含 auto/analyzing/on 三态，签名随状态变化自动放开记录。
        val sig = "$talker|" + (label?.text?.toString() ?: "")
        if (sig != lastAttachedSig) {
            lastAttachedSig = sig
            WeLogger.i(TAG, "status attached talker=$talker text=${label?.text}")
        } else {
            WeLogger.d(TAG, "status attached (repeat) talker=$talker")
        }
    }

    /** 头部未就绪的补挂重试。attempt 由 sync 穿透传递；cancelPendingRetry() 清挂起项。 */
    private fun retryAttach(activity: Activity, talker: String, attempt: Int) {
        if (attempt == 1) {
            // W 级：静默失败等于把问题藏起来（19:25 窗口零日志排查半天）。限频：仅首次打。
            WeLogger.w(TAG, "header not ready, retrying talker=$talker act=${activity.javaClass.simpleName}")
        }
        if (attempt >= RETRY_MAX) {
            WeLogger.w(TAG, "header not ready, gave up talker=$talker act=${activity.javaClass.simpleName}")
            return
        }
        retryPending?.let { main.removeCallbacks(it) }
        val r = Runnable {
            retryPending = null
            if (boundTalker != talker) return@Runnable  // 已切走，别把新聊天的徽标挂到旧头部
            sync(activity, talker, attempt + 1)
        }
        retryPending = r
        main.postDelayed(r, RETRY_STEP_MS)
    }

    /** 状态变化时刷新（分析开始/结束、开关改动后调用）。 */
    fun refreshText() {
        val tv = label ?: return
        val talker = boundTalker ?: return
        val analyzing = ChatAiEngine.stateOf(talker) is ChatAiEngine.State.Working
        val auto = AiChatStore.isAutoReplyOn(talker)
        val on = AiChatStore.isAnalyzeOn(talker)
        val (text, color) = when {
            auto -> "AI+" to BLUE
            analyzing -> "AI…" to BLUE
            on -> "AI" to BLUE
            else -> "AI" to GREY
        }
        tv.text = text
        tv.setTextColor(color)
        tv.contentDescription = "AI 聊天助手：$text（点击设置，长按标题栏…出菜单）"
    }

    // ==================== 长按「…」出菜单 ====================

    private fun installMenuLongPress(header: FrameLayout, activity: Activity, talker: String) {
        val btn = menuButtonOf(header) ?: run {
            WeLogger.w(TAG, "menu button not found in header")
            return
        }
        if (menuButton?.get() === btn) return
        btn.isLongClickable = true
        // 注意：View.setOnLongClickListener 不返回旧监听，无法还原 ⇒ 功能关闭时在回调里放行（不消费事件）
        btn.setOnLongClickListener {
            if (!AiChatAssistant.isEnabled) return@setOnLongClickListener false
            showActions(activity, currentTalker() ?: talker)
            true
        }
        menuButton = WeakReference(btn)
        // D 级（2026-09-30 定案）：每次进聊天页必打（与 status attached 同因，727 条/天）
        WeLogger.d(TAG, "menu long-press installed (button=${btn.javaClass.simpleName})")
    }

    private fun showActions(activity: Activity, talker: String) {
        val isGroup = ContextBuilder.isGroupTalker(talker)
        // 圆角卡片菜单：原生 AlertDialog 在微信主题下是直角（2026-09-28 用户要求圆角）。
        // 动作直接绑定条目，不再按下标分发，避免增删条目时错位。
        val items = buildList {
            add(ActionItem("显示 AI 分析卡：${if (AiChatStore.isAnalyzeOn(talker)) "开" else "关（开自动回复则后台静默）"}") {
                val on = !AiChatStore.isAnalyzeOn(talker)
                AiChatStore.setAnalyzeOn(talker, on)
                if (!on) ChatAiEngine.clear(talker)
            })
            add(ActionItem("全自动回复：${if (AiChatStore.isAutoReplyOn(talker)) "开" else "关"}") {
                val on = !AiChatStore.isAutoReplyOn(talker)
                if (on && !AiChatConfig.autoReplyConsent) AiChatConfig.autoReplyConsent = true
                AiChatStore.setAutoReplyOn(talker, on)
            })
            if (isGroup) {
                add(ActionItem("群里所有消息也处理：${if (AiChatStore.isGroupAllMessages(talker)) "开" else "关（只回@我）"}") {
                    AiChatStore.setGroupAllMessages(talker, !AiChatStore.isGroupAllMessages(talker))
                })
            }
            add(ActionItem("本聊天预设（人设/风格）") { AiChatAssistant.openPersonaDialog(activity, talker) })
            add(ActionItem("重新分析最新一条") { ChatAiEngine.retry(talker) })
            add(ActionItem("助手设置") { AiChatAssistant.openSettingsDialog(activity, talker) })
        }
        WeLogger.i(TAG, "actions shown talker=$talker group=$isGroup")
        showComposeDialog(activity) {
            AlertDialogContent(
                title = { Text("AI 聊天助手") },
                text = {
                    Column {
                        items.forEach { item ->
                            TextButton({
                                onDismiss()
                                item.action()
                                refreshText()
                            }) {
                                Text(item.label, modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onDismiss) { Text("关闭") } },
            )
        }
    }

    private data class ActionItem(val label: String, val action: () -> Unit)

    private fun currentTalker(): String? = ChatUi.talker ?: boundTalker

    fun remove() {
        retryPending?.let { main.removeCallbacks(it) }
        retryPending = null
        removeLabelOnly()
        menuButton?.get()?.let { runCatching { it.setOnLongClickListener(null) } }
        menuButton = null
        boundTalker = null
    }

    private fun removeLabelOnly() {
        label?.let { (it.parent as? ViewGroup)?.removeView(it) }
        label = null
        title?.let { it.maxWidth = oldTitleWidth; it.ellipsize = oldEllipsize }
        title = null
    }

    // ==================== 定位辅助 ====================

    /** 标题栏右侧的「…」按钮：可点击、宽度小于标题栏 1/3、位于右半区且最靠右者。 */
    private fun menuButtonOf(header: FrameLayout): View? {
        val headerPos = IntArray(2).also { header.getLocationOnScreen(it) }
        return descendants(header)
            .filter { it.isClickable && it.isShown && it.width in 1..(header.width / 3) }
            .mapNotNull { v ->
                val p = IntArray(2).also { v.getLocationOnScreen(it) }
                val x = p[0] - headerPos[0]
                if (x > header.width * 0.7) v to x else null
            }
            .maxByOrNull { it.second }
            ?.first
    }

    private fun menuSpaceFor(header: FrameLayout, density: Float): Int {
        fun dp(v: Int) = (v * density).toInt()
        val headerPos = IntArray(2).also { header.getLocationOnScreen(it) }
        val menuLeftX = descendants(header)
            .filter { it.isShown && it.isClickable && it.width in 1..(header.width / 3) }
            .mapNotNull { v ->
                val p = IntArray(2).also { v.getLocationOnScreen(it) }
                (p[0] - headerPos[0]).takeIf { it > header.width * 0.7 }
            }.minOrNull()
        return menuLeftX?.let { header.width - it } ?: dp(60)
    }

    private fun findHeader(activity: Activity): FrameLayout? {
        val decor = activity.window?.decorView ?: return null
        return descendants(decor).firstOrNull {
            it.isShown && it.javaClass.name == "androidx.appcompat.widget.ActionBarContainer"
        } as? FrameLayout
    }

    private fun descendants(root: View): List<View> {
        val result = mutableListOf<View>()
        fun walk(view: View, depth: Int) {
            if (depth > 40 || result.size > 4000 || view.visibility != View.VISIBLE) return
            result += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i), depth + 1)
        }
        walk(root, 0)
        return result
    }
}
