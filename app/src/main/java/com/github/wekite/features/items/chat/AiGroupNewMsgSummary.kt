package com.github.wekite.features.items.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.util.TypedValue
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.wekite.features.api.core.WeDatabaseApi
import com.github.wekite.utils.formatEpoch
import com.github.wekite.features.api.core.models.WeMessage
import com.github.wekite.features.api.ui.WeChatNewMsgTipApi
import com.github.wekite.features.core.ClickableFeature
import com.github.wekite.features.core.Feature
import com.github.wekite.preferences.WePrefs
import com.github.wekite.preferences.WePrefs.Companion.prefOption
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.Button
import com.github.wekite.ui.content.DefaultColumn
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.content.animation.WEKITE_BLUE
import com.github.wekite.ui.content.dialogListItemColors
import com.github.wekite.ui.content.dialogSwitchColors
import com.github.wekite.ui.utils.dpToPx
import com.github.wekite.ui.utils.showComposeDialog
import com.github.wekite.utils.WeLogger
import com.github.wekite.utils.android.showToast
import com.github.wekite.utils.strings.isGroupChatWxId
import com.github.wekite.utils.strings.stripWxId
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * 群聊消息分析：在聊天页「N条新消息」胶囊旁挂一个**同尺寸**「AI分析」胶囊
 * （胶囊在上支容器时挂它**下面** 12dp，在下支容器时挂它**上面** 12dp），
 * 点击即把该群消息（有未读 ⇒ 未读全部；无未读 ⇒ 当天）发给用户自填的 OpenAI 兼容接口，生成一份中文报告。
 *
 * 设计要点：
 * - 锚点 = `WeChatNewMsgTipApi` 给出的**当前可见的那一支**胶囊容器；尺寸照抄它的实测宽高 ⇒ 才「同形状」。
 * - ⚠️ **别只挑一支容器**（2026-09-25 定案）：旧实现只把「下支」当锚点，而那支在真机日志里恒
 *   `visibility=8 h=0`（09-24 / 09-25 两份日志全部样本 `tipVisible=true` 出现 0 次）⇒ 挂件位置退化成死常量、
 *   永远固定贴右下角（用户报「位置不正确」）。现在两支都试、谁可见贴谁；都不可见时沿用上次那一侧。
 * - 「只在有『N条新消息』时显示挂件」开关只管**显隐**（亮 = 跟随胶囊；灭 = 群聊页常显）；
 *   **分析范围不再跟开关走** —— 有未读就分析未读，没有才回退当天（旧实现默认模式下胶囊写 33 条、
 *   实际分析的是当天的 18 条文本消息，用户报「判断有误」）。
 * - 接口/Key/模型/请求头全部用户自填，支持各类反代与公益站（自定义 URL、Key 头名与前缀、
 *   User-Agent、任意额外请求头、超时/重试、忽略 TLS 校验、错误原文回显）。
 */
@SuppressLint("DiscouragedApi")
@Feature(
    name = "群聊消息分析",
    categories = ["聊天"],
    description = "聊天页「N条新消息」胶囊旁挂同尺寸「AI分析」胶囊，点击分析该群消息（接口/模型自填，支持各类反代）"
)
object AiGroupNewMsgSummary : ClickableFeature(), WeChatNewMsgTipApi.ITipListener {

    init {
        // 「聊天」分类第一项（分类内默认顺序由 KSP 生成决定，见 BaseFeature.pinnedFirst）
        pinnedFirst = true
    }

    private const val TAG = "AiGroupNewMsgSummary"

    private const val PILL_TEXT = "AI分析"

    /** 挂件与胶囊的间距（视觉上「接着挂」；用户 2026-09-25 明确要 12dp） */
    private const val GAP_DP = 12

    /** 挂件左右留白 */
    private const val PILL_PAD_H_DP = 16

    /**
     * 挂件高度兜底：**只在「胶囊从未被测量过」时**用。
     * 实测（2026-09-25 用户截图逐行扫「近白连通段」）：那枚胶囊 429×130px ≈ **121×37dp**
     * （1dp≈3.54px，由 24dip=85px 反推）⇒ 兜底取 40dp 贴近它。
     * ⚠️ 别再用「按绿色像素」量出来的旧值（112×20dp）：那个掩码只框到绿字/图标，会矮一半。
     */
    private const val PILL_HEIGHT_DP = 40

    /** 「只分析新消息」模式的条数硬上限（用户指定 1000） */
    private const val UNREAD_MAX = 1000

    /** 未读数缓存的 TTL（毫秒） */
    private const val UNREAD_TTL_MS = 1200L

    /** 挂件隐藏后的复查延迟（毫秒） */
    private const val RECHECK_DELAY_MS = 1500L

    // 报告弹窗与屏幕的间距（用户 2026-09-24 指定：左右各 12dp，上留 20mm、下留 15mm）
    private const val DIALOG_SIDE_DP = 12
    private const val DIALOG_TOP_MM = 20f
    private const val DIALOG_BOTTOM_MM = 15f

    /** 报告弹窗整体**上移**的量（用户 2026-09-25：往上移动 30dp，大小不变；同日再追加 12dp ⇒ 42dp） */
    private const val DIALOG_REPORT_LIFT_DP = 42

    /** 内置供应商 id（`custom` = 自己填地址；其余见 PROVIDERS） */
    private var providerId by prefOption("ai_sum_provider", "custom")

    /** true = 只在「N条新消息」提示条出现时才显示挂件；false（默认）= 群聊页常显 */
    private var onlyWhenUnread by prefOption("ai_sum_only_unread", false)

    /** 每批喂给模型的条数 */
    private const val CHUNK_SIZE = 60

    /** 常显模式（没有未读、回退当天范围）的默认条数 = 当天消息的条数上限 */
    private const val DEFAULT_MAX_MSGS = 200

    private const val DEFAULT_SYSTEM_PROMPT =
        "你是微信群聊分析助手。请把下面这批群聊消息总结成一份中文报告：主要话题、讨论要点、" +
                "值得注意的信息（含待办、决定、链接类信息）。条理清晰，不要编造。"

    // ==================== BYOK 配置 ====================

    /** 完整接口地址（优先；留空则用 base + path 拼） */
    private var apiUrl by prefOption("ai_sum_api_url", "")
    private var apiBase by prefOption("ai_sum_api_base", "")
    private var apiPath by prefOption("ai_sum_api_path", "/v1/chat/completions")
    private var apiKey by prefOption("ai_sum_api_key", "")
    private var model by prefOption("ai_sum_model", "")
    private var keyHeader by prefOption("ai_sum_key_header", "Authorization")
    private var keyPrefix by prefOption("ai_sum_key_prefix", "Bearer ")
    private var userAgent by prefOption("ai_sum_user_agent", "")
    private var extraHeaders by prefOption("ai_sum_extra_headers", "")
    private var timeoutSec by prefOption("ai_sum_timeout_sec", 120)
    private var retries by prefOption("ai_sum_retries", 1)
    private var skipTls by prefOption("ai_sum_skip_tls", false)
    private var maxMsgs by prefOption("ai_sum_max_msgs", DEFAULT_MAX_MSGS)
    private var systemPrompt by prefOption("ai_sum_system_prompt", DEFAULT_SYSTEM_PROMPT)

    // ==================== 挂件状态 ====================

    /** ChattingContent -> 我们的胶囊 */
    private val pills = WeakHashMap<View, TextView>()

    /** ChattingContent -> 对应提示条（悬浮底栏高度变化时用它重新定位） */
    private val tipForHost = WeakHashMap<View, View>()

    /** 提示条 -> 原标题条底部外边距（只记一次，只加不覆盖） */
    private val tipBaseBottomMargins = WeakHashMap<View, Int>()

    /** 该会话页上次见到的锚点侧（true = 上支/挂在它下面）：胶囊消失后沿用，挂件不跳来跳去 */
    private val lastTopAnchored = WeakHashMap<View, Boolean>()

    /** 该会话页上次见到的胶囊**实测宽高**（px）：胶囊 gone 时靠它保持「同形状」 */
    private val lastTipSizes = WeakHashMap<View, Pair<Int, Int>>()

    @Volatile
    private var clientCache: Pair<AiParams, OkHttpClient>? = null

    /** 悬浮输入框占用高度变化 → 重新定位（见 FloatingChatFooter.reservedBottomPx） */
    private val zoneListener: (Int) -> Unit = { onZoneChanged() }

    override fun onEnable() {
        WeChatNewMsgTipApi.addListener(this)
        FloatingChatFooter.addZoneListener(zoneListener)
    }

    override fun onDisable() {
        WeChatNewMsgTipApi.removeListener(this)
        FloatingChatFooter.removeZoneListener(zoneListener)
        // 还原我们抬过的下支胶囊（上支的边距归 FloatingChatHeader）
        tipForHost.keys.toList().forEach { runCatching { restoreMovedTips(it) } }
        pills.forEach { (host, pill) ->
            runCatching { (host as? ViewGroup)?.removeView(pill) }
        }
        pills.clear()
        tipForHost.clear()
        tipBaseBottomMargins.clear()
        lastTopAnchored.clear()
        lastTipSizes.clear()
    }

    /** 底栏占用高度变了（进聊天页 / 展开面板 / 关掉悬浮）⇒ 挂件重新贴到卡片上沿。 */
    private fun onZoneChanged() {
        pills.forEach { (host, pill) ->
            if (pill.parent == null) return@forEach
            val tip = tipForHost[host] ?: return@forEach
            runCatching { syncPill(host, tip) }
                .onFailure { WeLogger.e(TAG, "re-sync after bottom zone change failed", it) }
        }
    }

    // ==================== 挂件同步 ====================

    override fun onTipChanged(host: View, tip: View) {
        runCatching { syncPill(host, tip) }
            .onFailure { WeLogger.e(TAG, "syncPill failed", it) }
    }

    /**
     * 让挂件贴着「N条新消息」胶囊（用户 2026-09-25 定的规格）：
     * - 胶囊在**上支**（gravity 含 TOP）⇒ 挂件挂在它**下面** 12dp；
     * - 胶囊在**下支**（gravity 含 BOTTOM）⇒ 挂件挂在它**上面** 12dp（挂它下面会被悬浮输入框吃掉）；
     * - 两支都不可见（常显模式）⇒ 挂件走**常驻位：屏幕右侧垂直居中**（用户 2026-09-25 指定；
     *   早先「沿用上次那一侧」的做法被用户否掉：「常驻时位置不对，就在右侧的中间位置吧」）。
     * ⚠️ 尺寸照抄胶囊**实测**宽高 ⇒ 才「同形状」（`gone` 时的 0 绝不能当尺寸）。
     * ⚠️ 真机实测（2026-09-25）：**上支才是微信在显示的那一枚**（截图实测它贴在消息区上沿 ~24dip、右对齐）；
     * 旧实现只挑下支，而那支在 09-24/09-25 两份日志里恒 `visibility=8 h=0`（`tipVisible=true` 0 次）
     * ⇒ 挂件位置退化成死常量（85+241=326px）、永远固定贴右下角（用户报「位置不正确」）。
     */
    private fun syncPill(host: View, tip: View) {
        val conv = WeChatNewMsgTipApi.convIdOf(host)
        val isGroup = conv.isNotEmpty() && conv.isGroupChatWxId
        val active = WeChatNewMsgTipApi.activeTipOf(host)
        val anchor = active ?: tip
        val topAnchored = WeChatNewMsgTipApi.isTopAnchored(anchor)

        if (!isGroup) {
            pills[host]?.visibility = View.GONE
            restoreMovedTips(host)
            return
        }

        val existing = pills[host]
        val pill = if (existing != null && existing.parent != null) existing
        else createPill(host, anchor) ?: return

        tipForHost[host] = anchor
        val tipVisible = active != null && anchor.height > 0
        if (tipVisible) {
            lastTopAnchored[host] = topAnchored
            lastTipSizes[host] = anchor.width to anchor.height
        }
        // 锚点侧：可见时按它自己的 gravity；不可见时沿用上次那一侧（默认上支）
        val useTop = if (tipVisible) topAnchored else (lastTopAnchored[host] ?: true)
        // 用户 2026-09-25：胶囊不在屏幕上 ⇒ 挂件走常驻位（屏幕右侧垂直居中），不再贴着已消失的位置
        val centered = !tipVisible
        val gap = GAP_DP.dpToPx(anchor.context)

        // 悬浮输入框会压掉页底 ~240px（实测顶掉 241px）⇒ 下支胶囊与挂件一起浮到卡片上沿之上；
        // 上支胶囊的边距归 FloatingChatHeader 的避让逻辑，这里一个字都不碰。
        val zone = if (FloatingChatFooter.isActive) FloatingChatFooter.reservedBottomPx else 0
        val anchorEdge = moveTip(anchor, if (useTop) 0 else zone)

        // 尺寸：胶囊可见 ⇒ 实测宽高；否则上次记住的；再否则高度兜底、宽度 wrap_content
        val remembered = lastTipSizes[host]
        val measuredH = if (tipVisible) anchor.height else remembered?.second ?: 0
        val measuredW = if (tipVisible) anchor.width else remembered?.first ?: 0
        val pillH = measuredH.takeIf { it > 0 } ?: PILL_HEIGHT_DP.dpToPx(anchor.context)
        val pillW = measuredW.takeIf { it > 0 }
        val pillEdge = if (centered) 0 else anchorEdge + pillH + gap

        if (pill.minimumHeight != 0) pill.minimumHeight = 0
        applyPillSkin(pill, pillH)
        placePill(pill, useTop, centered, pillW, pillH, pillEdge)

        // ⚠️ 只有开了「只在有『N条新消息』时显示挂件」才查未读（syncPill 被底栏/提示条事件频繁触发）
        val unread = if (onlyWhenUnread) recentUnread(conv) else 0
        val hasNew = tipVisible || unread > 0
        WeLogger.i(
            TAG,
            "pill placed: side=${if (centered) "center" else if (useTop) "top" else "bottom"} " +
                    "anchorId=0x${Integer.toHexString(anchor.id)} " +
                    "zone=$zone anchorEdge=$anchorEdge gap=$gap tipVisible=$tipVisible " +
                    "tipW=${anchor.width} tipH=${anchor.height} pillW=${pillW ?: -1} pillH=$pillH " +
                    "conv=$conv unread=$unread"
        )

        pill.visibility = if (!onlyWhenUnread || hasNew) View.VISIBLE else View.GONE
        // 隐藏后安排一次有界复查：刚进群那一刻未读数可能还没落到库里
        if (onlyWhenUnread && !hasNew) schedulePillRecheck(host, anchor) else pendingRecheck.remove(host)
        pill.setOnClickListener { onPillClick(host, conv) }
    }

    /**
     * 挪胶囊并返回它当前生效的边距（挂件按它算位置）：
     * 上支 ⇒ 不动（返回它的 `topMargin`，由 `FloatingChatHeader` 维护）；下支 ⇒ 抬 `zone` 并返回 `bottomMargin`。
     */
    private fun moveTip(tip: View, zone: Int): Int {
        val lp = tip.layoutParams as? ViewGroup.MarginLayoutParams ?: return 0
        if (WeChatNewMsgTipApi.isTopAnchored(tip)) return lp.topMargin
        val base = tipBaseBottomMargins.getOrPut(tip) { lp.bottomMargin }
        setMargin(tip, bottom = base + zone)
        return base + zone
    }

    /** 非群聊 / 退出时还原我们抬过的下支（上支的 margin 归 FloatingChatHeader，别碰）。 */
    private fun restoreMovedTips(host: View) {
        for (t in WeChatNewMsgTipApi.candidatesOf(host)) {
            if (WeChatNewMsgTipApi.isTopAnchored(t)) continue
            val base = tipBaseBottomMargins[t] ?: continue
            setMargin(t, bottom = base)
        }
    }

    private fun setMargin(view: View, top: Int? = null, bottom: Int? = null) {
        val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        var changed = false
        if (top != null && lp.topMargin != top) {
            lp.topMargin = top; changed = true
        }
        if (bottom != null && lp.bottomMargin != bottom) {
            lp.bottomMargin = bottom; changed = true
        }
        if (changed) view.layoutParams = lp
    }

    /**
     * 挂件落位：
     * - 上支胶囊 ⇒ `gravity=TOP|END` + `topMargin`（挂在胶囊下方）；
     * - 下支胶囊 ⇒ `BOTTOM|END` + `bottomMargin`（挂在胶囊上方）；
     * - 常驻（胶囊不在）⇒ `END|CENTER_VERTICAL`（屏幕右侧垂直居中，用户 2026-09-25 指定）。
     * 宽高按胶囊实测值写死（`null` = `WRAP_CONTENT`）⇒ 与胶囊同形状；**右外边距恒 0**
     * ⇒ 右缘贴屏幕边（与原生胶囊「左边圆角、右边直角贴屏幕」的观感一致，见 [applyPillSkin]）。
     */
    private fun placePill(pill: TextView, useTop: Boolean, centered: Boolean, width: Int?, height: Int, edge: Int) {
        val lp = pill.layoutParams as? FrameLayout.LayoutParams
        if (lp == null) {
            // 宿主不是 FrameLayout 系的兜底：只调底边距（保持旧行为）
            setMargin(pill, bottom = edge)
            return
        }
        val gravity = when {
            centered -> (Gravity.END or Gravity.CENTER_VERTICAL)
            useTop -> (Gravity.TOP or Gravity.END)
            else -> (Gravity.BOTTOM or Gravity.END)
        }
        val wantW = width ?: ViewGroup.LayoutParams.WRAP_CONTENT
        val wantTop = if (!centered && useTop) edge else 0
        val wantBottom = if (!centered && !useTop) edge else 0
        var changed = false
        if (lp.gravity != gravity) {
            lp.gravity = gravity; changed = true
        }
        if (lp.width != wantW) {
            lp.width = wantW; changed = true
        }
        if (lp.height != height) {
            lp.height = height; changed = true
        }
        if (lp.topMargin != wantTop) {
            lp.topMargin = wantTop; changed = true
        }
        if (lp.bottomMargin != wantBottom) {
            lp.bottomMargin = wantBottom; changed = true
        }
        // 右缘贴屏幕边：右外边距固定 0（原生胶囊也是顶到屏幕右侧的直角边）
        if (lp.rightMargin != 0) {
            lp.rightMargin = 0; changed = true
        }
        if (changed) pill.layoutParams = lp
    }

    private fun createPill(host: View, tip: View): TextView? {
        val parent = tip.parent as? ViewGroup ?: return null
        val pill = TextView(tip.context).apply {
            text = PILL_TEXT
            isSingleLine = true
            gravity = Gravity.CENTER
        }
        styleFromTip(pill, tip)
        runCatching { parent.addView(pill, cloneParams(tip.layoutParams)) }
            .onFailure {
                WeLogger.e(TAG, "failed to add pill to ${parent.javaClass.name}", it)
                return null
            }
        pills[host] = pill
        pill.addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
            val lp = v.layoutParams as? FrameLayout.LayoutParams
            WeLogger.i(
                TAG,
                "AI pill laid out: ${r - l}x${b - t} visibility=${v.visibility} gravity=${lp?.gravity} " +
                        "topMargin=${lp?.topMargin} margin=${lp?.bottomMargin}"
            )
        }
        WeLogger.i(TAG, "AI pill added: parent=${parent.javaClass.simpleName} host=${host.javaClass.simpleName}")
        return pill
    }

    /** 字号/行距抄提示条（保持等大观感）；颜色与背景由 [applyPillSkin] 决定（用户指定的蓝色梦幻）。 */
    private fun styleFromTip(pill: TextView, tip: View) {
        val src = (tip as? ViewGroup)?.let { group ->
            (0 until group.childCount).map { group.getChildAt(it) }.filterIsInstance<TextView>().firstOrNull()
        }
        if (src != null) {
            // textSize 由 XML 设定，提示条 GONE 时也读得到 ⇒ 可以直接抄
            pill.setTextSize(TypedValue.COMPLEX_UNIT_PX, src.textSize)
            pill.includeFontPadding = src.includeFontPadding
        }
        pill.gravity = Gravity.CENTER
        pill.setTextColor(Color.WHITE)
        pill.setTypeface(pill.typeface, Typeface.BOLD)
        // 左右留白自己给：背景已不是提示条那张 9-patch，不再有背景自带留白
        val padH = PILL_PAD_H_DP.dpToPx(tip.context)
        pill.setPadding(padH, 0, padH, 0)
    }

    /**
     * 蓝色渐变「梦幻」胶囊皮肤：蓝 → 淡紫对角渐变、**左边圆角、右边直角**（用户 2026-09-25：
     * 「应该和『N条新消息』一样，左边是圆角，右边没有圆角、直角贴屏幕」——实测原生胶囊
     * 429×130px ≈ 121×37dp、左缘圆角、右缘是顶到屏幕边（x=1279）的直角）。
     * 圆角半径取高度一半 ⇒ 左半段是标准胶囊弧。
     */
    private fun applyPillSkin(pill: TextView, heightPx: Int) {
        val r = (heightPx.coerceAtLeast(1)) / 2f
        val bg: Drawable = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor("#5B7CFF"), Color.parseColor("#B07CFF"))
        ).apply {
            // cornerRadii 顺序 = 左上/左上、右上/右上、右下/右下、左下/左下
            cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
            setStroke(1.dpToPx(pill.context), Color.parseColor("#59FFFFFF"))
        }
        pill.background = bg
        pill.elevation = 2.dpToPx(pill.context).toFloat()
    }

    private fun cloneParams(src: ViewGroup.LayoutParams?): ViewGroup.MarginLayoutParams = when (src) {
        is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(src)
        is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(src)
        is ViewGroup.MarginLayoutParams -> ViewGroup.MarginLayoutParams(src)
        else -> ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // ==================== 点击 → 分析 ====================

    private fun onPillClick(host: View, conv: String) {
        val activity = host.context.activityOrNull() ?: return
        if (!conv.isGroupChatWxId) {
            showToast(activity, "仅支持群聊")
            return
        }
        val params = currentParams()
        if (!params.isUsable()) {
            showToast(activity, "请先配置接口地址 / API Key / 模型名")
            openSettings(activity)
            return
        }
        runWithProgressDialog(activity, "读取新消息…") { onResult, onStage ->
            onResult(runCatching { analyzeNewMessages(conv, params) { onStage(it) } })
        }
    }

    /**
     * 读取该群「新消息」→ 分块总结 → 汇总成报告。
     *
     * 分析范围（用户 2026-09-25 定稿）：**与「N条新消息」绑定** ——
     * - 该群**有未读** → 分析**全部未读新消息**（上限 [UNREAD_MAX] = 1000 条）＝「分析内容为新消息」；
     * - 没有未读 → 回退分析**当天**的消息（上限 = 设置里的条数，默认 200）。
     * ⚠️ 范围**不再跟「只在有未读时显示挂件」开关走**：那个开关只管挂件显隐。旧实现把两者绑在一起，
     *    结果默认模式下胶囊写着 33 条、实际分析的是当天的 18 条文本消息（用户报「判断有误」）。
     */
    private fun analyzeNewMessages(convId: String, params: AiParams, onStage: (String) -> Unit): String {
        val unread = runCatching { queryUnread(convId) }.getOrDefault(0)
        val raw = if (unread > 0) collectUnread(convId, unread, onStage) else collectToday(convId, onStage)
        val messages = raw.filter { it.type?.isText == true }.reversed()
        if (messages.isEmpty()) {
            return if (unread > 0) "该群当前没有未读的新消息。"
            else "今天这个群还没有可分析的文本消息。"
        }

        val nameCache = HashMap<String, String>()
        val lines = messages.map { msg ->
            val raw = msg.content
            val who = if (msg.isSend != 0) "我" else resolveSender(convId, senderIdOf(raw), nameCache)
            val text = raw.stripWxId().replace('\n', ' ').trim()
                .let { if (it.length > 400) it.take(400) + "…" else it }
            "$who: $text"
        }

        val chunks = lines.chunked(CHUNK_SIZE)
        // 用户 2026-09-24 / 2026-09-25：弹窗里不要批次进度，也不要「共 N 条新消息，分析中…」
        // 这类每次看起来都一样的提示 ⇒ 界面只留「读取…」与「汇总报告…」两句；
        // 条数与批次划分只进日志（排障用，不上界面）。
        WeLogger.i(TAG, "analyze: ${lines.size} messages -> ${chunks.size} chunk(s), CHUNK_SIZE=$CHUNK_SIZE")
        val parts = chunks.mapIndexed { index, chunk ->
            WeLogger.i(TAG, "analyze: chunk ${index + 1}/${chunks.size} ...")
            chatCompletion(params, params.systemPrompt, chunk.joinToString("\n"))
        }
        if (parts.size == 1) return parts.first()

        onStage("汇总报告…")
        val digest = parts.mapIndexed { index, part -> "【第 ${index + 1} 批】\n$part" }.joinToString("\n\n")
        return chatCompletion(
            params,
            "你是微信群聊分析助手。以下是对同一群聊多批新消息的小结，请整合成一份完整的中文分析报告：" +
                    "主要话题、讨论要点、值得注意的信息与结论。条理清晰，不要编造。",
            digest
        )
    }

    /** 该群**全部未读**新消息（上限 [UNREAD_MAX]）；[unread] <= 0 则返回空。 */
    private fun collectUnread(convId: String, unread: Int, onStage: (String) -> Unit): List<WeMessage> {
        val n = unread.coerceIn(0, UNREAD_MAX)
        if (n <= 0) return emptyList()
        onStage("读取该群全部未读新消息（$n 条）…")
        return WeDatabaseApi.getMessages(convId, pageIndex = 1, pageSize = n)
    }

    /**
     * **当天**的消息（本地 00:00 起到现在 = 用户说的「0 点到 24 点」；库里不会有未来时间的消息，
     * 所以只加下界即可）；条数上限 = 设置里的「单次最多分析条数」。
     *
     * ⚠️⚠️ **单位坑（两次踩，务必读）**：本仓对 `message.createTime` 有**两种互斥**的用法 ——
     * 网络层按**秒**（`nowSec = ts/1000`），消息对象/展示层按**毫秒**（`formatEpoch` →
     * `Instant.ofEpochMilli`）。单位搞错的后果极隐蔽：阈值算小了 ⇒ `createTime >= 阈值` **恒真**
     * ⇒ 取到的是「最近 N 条」的**跨天历史**（用户两次实测报回：「感觉判断不准确」「还是按最高条数分析」）。
     * v3.27 第一次修时**判对了单位却用反了方向**（判出「毫秒库」却把阈值除了 1000）。
     * 现在：① [createTimeDivisor] 按「最新一条 vs 现在」判定换算分母；
     * ② 查完再在 Kotlin 里**按换算后的毫秒值过滤一遍**（双保险：即便 SQL 的单位还错，
     * 也绝不会把跨天历史当成「今天」去分析，最坏只会变成 0 条）；
     * ③ 把 div/阈值/条数/被丢弃条数/最新最旧（含可读时间）打进日志 ⇒ 一眼可核。
     */
    private fun collectToday(convId: String, onStage: (String) -> Unit): List<WeMessage> {
        val cap = maxMsgs.coerceIn(1, UNREAD_MAX)
        val div = createTimeDivisor()
        val todayStart = todayStartMillis()
        val since = todayStart / div
        onStage("读取今天的消息（上限 $cap 条）…")
        val fetched = WeDatabaseApi.getMessagesSince(convId, since, cap)
        // 双保险：SQL 之外再按「换算成毫秒后是否属于今天」过滤（SQL 的 >= 可能因单位问题恒真）
        val messages = fetched.filter { toEpochMs(it.createTime, div) >= todayStart }
        val newestMs = messages.firstOrNull()?.let { toEpochMs(it.createTime, div) } ?: 0L
        val oldestMs = messages.lastOrNull()?.let { toEpochMs(it.createTime, div) } ?: 0L
        WeLogger.i(
            TAG,
            "today range: div=$div since=$since n=${messages.size} dropped=${fetched.size - messages.size} " +
                    "newest=${readableTime(newestMs)} oldest=${readableTime(oldestMs)} conv=$convId"
        )
        return messages
    }

    /** 本地当天 00:00 的**毫秒**时间戳。 */
    private fun todayStartMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /**
     * 库里 `message.createTime` → **毫秒** 的换算分母：库是毫秒 ⇒ `1`，库是秒 ⇒ `1000`。
     *
     * 判定不靠魔数、靠「库里最新一条 vs 现在」：直接把最新值当毫秒落在近 30 天内 ⇒ 毫秒库；
     * 把最新值 ×1000 当毫秒才落在近 30 天内 ⇒ 秒库。
     * ⚠️ v3.27 首版曾按「>1e11 即毫秒」判定后**把阈值除以 1000**（方向反了）⇒ 毫秒库下阈值退化成
     * 秒级数值、比较恒真、结果永远是「上限条数」的跨天记录。改单位时**先算一遍真实数字再动手**。
     */
    private fun createTimeDivisor(): Long = runCatching {
        val newest = WeDatabaseApi.rawQuery("SELECT MAX(createTime) FROM message").use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
        val now = System.currentTimeMillis()
        val window = 30L * 24 * 3600 * 1000
        when {
            newest <= 0L -> 1L
            abs(newest - now) < window -> 1L              // 库里就是毫秒
            abs(newest * 1000 - now) < window -> 1000L    // 库里是秒
            else -> if (newest > 100_000_000_000L) 1L else 1000L
        }
    }.getOrDefault(1L)

    /** 把库里单位的时间换算成毫秒（[div] = 1 ⇒ 已是毫秒；1000 ⇒ 是秒）。 */
    private fun toEpochMs(value: Long, div: Long): Long = if (div == 1L) value else value * 1000L

    /** 日志用的可读时间（0 显示为 `-`）。 */
    private fun readableTime(epochMs: Long): String =
        if (epochMs <= 0L) "-" else runCatching { formatEpoch(epochMs, "MM-dd HH:mm:ss") }.getOrDefault("$epochMs")

    /** 该会话的未读数（群聊取 `unReadCount` / `unReadMuteCount` 较大者）。 */
    private fun queryUnread(convId: String): Int = runCatching {
        WeDatabaseApi.rawQuery(
            "SELECT unReadCount, unReadMuteCount FROM rconversation WHERE username = ?",
            arrayOf<Any>(convId)
        ).use { cursor -> if (cursor.moveToFirst()) maxOf(cursor.getInt(0), cursor.getInt(1)) else 0 }
    }.getOrDefault(0)

    // ============ 未读判定的缓存与复查（用户 2026-09-24 报「只在有未读时显示」不出挂件）============

    /** conv -> (查询时刻, 未读数)：syncPill 被底栏/提示条事件频繁触发，别每次都打 SQLite。 */
    private val unreadCache = HashMap<String, Pair<Long, Int>>()

    /** 已排了复查的宿主，避免重复 post。 */
    private val pendingRecheck = WeakHashMap<View, Boolean>()

    /** 该会话真实未读数（带短 TTL 缓存）。 */
    private fun recentUnread(conv: String): Int {
        val now = System.currentTimeMillis()
        unreadCache[conv]?.let { (at, value) -> if (now - at < UNREAD_TTL_MS) return value }
        val value = queryUnread(conv)
        if (unreadCache.size > 32) unreadCache.clear()
        unreadCache[conv] = now to value
        return value
    }

    /** 隐藏挂件后 1.5s 复查一次（先清缓存）：刚进群那一刻未读数可能还没落库。 */
    private fun schedulePillRecheck(host: View, tip: View) {
        if (pendingRecheck[host] == true) return
        pendingRecheck[host] = true
        host.postDelayed({
            pendingRecheck.remove(host)
            // ⚠️ 守卫：这段时间里挂件可能已被移除（功能被关掉 / 页面销毁）——
            //    此时绝不能再去 syncPill，否则会把挂件重新创建出来，看着像「关了没生效」。
            if (pills[host]?.parent == null) return@postDelayed
            unreadCache.remove(WeChatNewMsgTipApi.convIdOf(host))
            runCatching { syncPill(host, tip) }
                .onFailure { WeLogger.e(TAG, "pill recheck failed", it) }
        }, RECHECK_DELAY_MS)
    }

    private val senderPrefixRegex = Regex("""^([^:\n]{1,64}):\n""")

    /** 群消息发送者 wxid 在内容前缀 `wxid:\n` 里（与 `stripWxId` 同一约定）。 */
    private fun senderIdOf(content: String): String =
        senderPrefixRegex.find(content)?.groupValues?.get(1).orEmpty()

    private fun resolveSender(convId: String, senderId: String, cache: HashMap<String, String>): String {
        if (senderId.isEmpty()) return "群成员"
        return cache.getOrPut(senderId) {
            WeDatabaseApi.getGroupMemberDisplayName(convId, senderId).takeIf { it.isNotBlank() } ?: senderId
        }
    }

    // ==================== 内置供应商 ====================

    /**
     * 供应商预设条目。
     *
     * ⚠️ 只预置**稳定且公开**的事实：厂商官方基址、Key 头名/前缀。
     * **不写死模型名** —— 模型名变化极快（同一家半年换两代），写死 = 装到手机上就报
     * 「model not found」。模型走「填完 Key 一键拉取该站 /models 列表」或手动输入。
     */
    private data class Provider(
        val id: String,
        val label: String,
        val base: String,
        val keyHeader: String = "Authorization",
        val keyPrefix: String = "Bearer ",
        val userAgent: String = "",
        val hint: String = ""
    )

    private val BUILTIN_PROVIDERS = listOf(
        Provider(
            "agentrouter", "AgentRouter（公益站）", "https://ps.air-outer.com/v1",
            userAgent = "claude-cli/1.0.60 (external, cli)", hint = "需 claude-cli UA，已预置"
        ),
        Provider(
            "opencode-zen", "opencode zen", "https://opencode.ai/zen/v1",
            userAgent = "claude-cli/1.0.60 (external, cli)", hint = "需 claude-cli UA，已预置"
        ),
        Provider("deepseek", "DeepSeek 深度求索", "https://api.deepseek.com/v1"),
        Provider("moonshot", "月之暗面 Kimi", "https://api.moonshot.cn/v1"),
        Provider("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4"),
        Provider("dashscope", "阿里通义千问（百炼）", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        Provider("hunyuan", "腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1"),
        Provider("siliconflow", "硅基流动 SiliconFlow", "https://api.siliconflow.cn/v1"),
        Provider("qianfan", "百度千帆", "https://qianfan.baidubce.com/v2"),
        Provider("minimax", "MiniMax", "https://api.minimax.chat/v1"),
        Provider("stepfun", "阶跃星辰 StepFun", "https://api.stepfun.com/v1"),
        Provider("openai", "OpenAI", "https://api.openai.com/v1"),
        Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1"),
        Provider("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1")
    )

    /** 按 id 找供应商（内置 + 自定义）；找不到就退回第一个内置（只影响显示，不影响已保存的配置）。 */
    private fun providerOf(id: String): Provider =
        allProviders().firstOrNull { it.id == id } ?: BUILTIN_PROVIDERS.first()

    /** 自定义供应商的网址归一：用户可能直接粘完整接口地址，这里统一存成基址。 */
    private fun normalizeCustomUrl(raw: String): String {
        val u = raw.trim().trimEnd('/')
        return u.removeSuffix("/chat/completions").trimEnd('/')
    }

    /** 自定义供应商 id 前缀（迁移来的旧「自定义」用 `custom`，新建的用 `custom_<时间戳>`）。 */
    private fun isCustomId(id: String) = id.startsWith("custom")

    // ==================== 每个供应商各存一份 Key（用户 2026-09-24 要求）====================
    // 切到别的供应商：它存过 Key 就显示它自己的，没存过就留空；输入即存，不用点保存。

    private fun keyPref(providerId: String) = "ai_sum_key_${providerId.ifEmpty { "custom" }}"

    /** 该供应商已保存的 Key。首次升级把旧的全局 Key 认到当前选中的供应商名下。 */
    private fun keyForProvider(id: String): String {
        val stored = WePrefs.getStringOrDef(keyPref(id), "")
        if (stored.isNotEmpty()) return stored
        val legacy = apiKey
        return if (legacy.isNotEmpty() && id == providerId.ifEmpty { "custom" }) legacy else ""
    }

    private fun rememberKey(id: String, value: String) {
        runCatching { WePrefs.putString(keyPref(id), value) }
            .onFailure { WeLogger.e(TAG, "remember key for $id failed", it) }
    }

    // ==================== 每个供应商各存一份「模型」（用户 2026-09-25 要求）====================
    // 几个供应商来回切换时，各自恢复上次用过的模型（与 Key 同一套机制：输入即存、切换即显示）。

    private fun modelPref(providerId: String) = "ai_sum_model_${providerId.ifEmpty { "custom" }}"

    /** 该供应商上次用过的模型；没存过就是空的（旧全局模型认给「当前选中的供应商」）。 */
    private fun modelForProvider(id: String): String {
        val stored = WePrefs.getStringOrDef(modelPref(id), "")
        if (stored.isNotEmpty()) return stored
        val legacy = model
        return if (legacy.isNotEmpty() && id == providerId.ifEmpty { "custom" }) legacy else ""
    }

    private fun rememberModel(id: String, value: String) {
        runCatching { WePrefs.putString(modelPref(id), value) }
            .onFailure { WeLogger.e(TAG, "remember model for $id failed", it) }
    }

    private fun forgetProvider(id: String) {
        runCatching {
            WePrefs.putString(keyPref(id), "")
            WePrefs.putString(modelPref(id), "")
        }.onFailure { WeLogger.e(TAG, "forget provider $id failed", it) }
    }

    // ==================== 自定义供应商（可加多个，用户 2026-09-25 要求）====================

    private const val PREF_CUSTOM_LIST = "ai_sum_custom_list"

    /** 用户自建的供应商（自建网关 / 反代 / 私有站），JSON：`[{"id","name","url"}]`。 */
    private fun customProviders(): List<Provider> = runCatching {
        val raw = WePrefs.getStringOrDef(PREF_CUSTOM_LIST, "")
        if (raw.isBlank()) return migrateLegacyCustom()
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").trim()
            if (id.isEmpty()) null
            else Provider(
                id = id,
                label = o.optString("name").trim().ifEmpty { "自定义" },
                base = o.optString("url").trim()
            )
        }
    }.getOrElse {
        WeLogger.e(TAG, "parse custom providers failed", it)
        emptyList()
    }

    private fun saveCustomProviders(list: List<Provider>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.label)
                    put("url", p.base)
                }
            )
        }
        runCatching { WePrefs.putString(PREF_CUSTOM_LIST, arr.toString()) }
            .onFailure { WeLogger.e(TAG, "save custom providers failed", it) }
    }

    /**
     * 升级迁移：v3.25 之前「自定义」只有唯一一个，地址存在全局 pref（`ai_sum_api_url`/`ai_sum_api_base`）；
     * 现在自定义是多实例 ⇒ 首次读到空清单时把旧地址落成名为「自定义」的那一条，配置不丢。
     * （写回一次清单即视为已迁移，不会每次开设置都重跑。）
     */
    private fun migrateLegacyCustom(): List<Provider> {
        val legacyUrl = apiUrl.ifBlank { apiBase }
        val list = if (legacyUrl.isBlank()) emptyList()
        else listOf(Provider("custom", "自定义 / 自建网关 / 反代", legacyUrl))
        saveCustomProviders(list)
        WeLogger.i(TAG, "custom providers migrated: ${list.size}")
        return list
    }

    /** 内置 + 自定义（内置在前，自定义在后）。 */
    private fun allProviders(): List<Provider> = BUILTIN_PROVIDERS + customProviders()

    /** 预设基址都自带 `/v1`、`/v4` 之类版本段 ⇒ 拼接口用 `/chat/completions`。 */
    private const val PRESET_PATH = "/chat/completions"

    /** 由基址/完整地址推出该站的模型列表地址（`…/chat/completions` → `…/models`）。 */
    private fun modelListUrl(baseOrEndpoint: String): String {
        val b = baseOrEndpoint.trim().trimEnd('/')
        if (b.isEmpty()) return ""
        val stem = b.removeSuffix("/chat/completions").removeSuffix("/completions").trimEnd('/')
        return if (stem.isEmpty()) "" else "$stem/models"
    }

    /** 拉取该站模型列表（OpenAI 风格 `data[].id`，兼容 `models[].name`）。失败抛异常带状态码与原文。 */
    private fun fetchModels(params: AiParams): List<String> {
        val url = modelListUrl(params.endpoint)
        if (url.isEmpty()) throw IllegalStateException("请先填接口地址")
        val builder = Request.Builder()
            .url(url)
            .get()
            .addHeader("Accept", "application/json")
            .addHeader(params.keyHeader, params.keyPrefix + params.apiKey)
        if (params.userAgent.isNotEmpty()) builder.addHeader("User-Agent", params.userAgent)
        params.extraHeaders.lineSequence()
            .map { it.trim() }
            .filter { it.contains(':') && !it.startsWith("#") }
            .forEach { line ->
                val idx = line.indexOf(':')
                builder.addHeader(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
            }
        clientFor(params).newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} ${response.message}\n${text.take(600)}")
            }
            val root = runCatching { JSONObject(text) }.getOrNull()
                ?: throw IllegalStateException("响应不是 JSON\n${text.take(600)}")
            val ids = LinkedHashSet<String>()
            root.optJSONArray("data")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").ifEmpty { obj.optString("name") }
                    if (id.isNotEmpty()) ids.add(id)
                }
            }
            root.optJSONArray("models")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("name").ifEmpty { obj.optString("id") }
                    if (id.isNotEmpty()) ids.add(id)
                }
            }
            return ids.sorted()
        }
    }

    // ==================== 设置 ====================

    override fun onClick(context: ComponentActivity) = openSettings(context)

    private fun openSettings(context: Context) {
        showComposeDialog(context) {
            // 自定义供应商清单（可加多个，用户 2026-09-25 要求）：本对话框内维护一份，改动即时落盘
            var customs by remember { mutableStateOf(customProviders()) }
            // 选中项：优先用已保存的那个供应商；它已被删掉（或从未选过）就退回第一个可选项
            var providerInput by remember {
                mutableStateOf(
                    providerId.ifEmpty { "custom" }
                        .takeIf { id -> allProviders().any { p -> p.id == id } }
                        ?: allProviders().first().id
                )
            }
            var urlInput by remember { mutableStateOf(apiUrl) }
            // 选中的是自定义供应商时，网址以它自己那份为准（权威来源）
            var baseInput by remember {
                mutableStateOf(customs.firstOrNull { it.id == providerInput }?.base ?: apiBase)
            }
            var pathInput by remember { mutableStateOf(apiPath) }
            // 每个供应商各显示自己那份 Key / 模型（没存过就留空）
            var keyInput by remember { mutableStateOf(keyForProvider(providerInput)) }
            var modelInput by remember { mutableStateOf(modelForProvider(providerInput)) }
            var keyHeaderInput by remember { mutableStateOf(keyHeader) }
            var keyPrefixInput by remember { mutableStateOf(keyPrefix) }
            var uaInput by remember { mutableStateOf(userAgent) }
            var headersInput by remember { mutableStateOf(extraHeaders) }
            var promptInput by remember { mutableStateOf(systemPrompt) }
            var timeoutInput by remember { mutableStateOf(timeoutSec.toString()) }
            var retriesInput by remember { mutableStateOf(retries.toString()) }
            var maxInput by remember { mutableStateOf(maxMsgs.toString()) }
            var tlsInput by remember { mutableStateOf(skipTls) }
            var onlyUnreadInput by remember { mutableStateOf(onlyWhenUnread) }
            var advanced by remember { mutableStateOf(false) }
            var showProviderList by remember { mutableStateOf(false) }
            var showModelList by remember { mutableStateOf(false) }
            var showCustomEditor by remember { mutableStateOf(false) }
            var newCustomName by remember { mutableStateOf("") }
            var newCustomUrl by remember { mutableStateOf("") }
            var modelChoices by remember { mutableStateOf(emptyList<String>()) }
            var testStatus by remember { mutableStateOf("") }

            fun inputsAsParams(forTest: Boolean) = AiParams(
                endpoint = endpointOf(urlInput, baseInput, pathInput),
                apiKey = keyInput.filterNot { it.isWhitespace() },
                model = modelInput.trim(),
                keyHeader = keyHeaderInput.trim().ifEmpty { "Authorization" },
                keyPrefix = keyPrefixInput,
                userAgent = uaInput.trim(),
                extraHeaders = headersInput,
                timeoutSec = timeoutInput.trim().toIntOrNull()?.coerceIn(10, 600) ?: 120,
                retries = if (forTest) 0 else retriesInput.trim().toIntOrNull()?.coerceIn(0, 5) ?: 1,
                skipTls = tlsInput,
                systemPrompt = promptInput.trim().ifEmpty { DEFAULT_SYSTEM_PROMPT }
            )

            /**
             * 选中供应商：只留「网址（仅自定义）+ Key」要填，其余全部自动带好
             * （请求头名 / Key 前缀 / 接口路径 / UA）。自定义也套同一套默认值
             * ⇒ 用户「只填网址和 Key」即可（用户 2026-09-24 指定）。
             * 切换时**各自恢复自己那份 Key 与模型**（用户 2026-09-24 / 2026-09-25 要求）。
             */
            fun applyPreset(p: Provider) {
                providerInput = p.id
                keyInput = keyForProvider(p.id)
                modelInput = modelForProvider(p.id)
                urlInput = ""
                baseInput = p.base
                keyHeaderInput = p.keyHeader
                keyPrefixInput = p.keyPrefix
                uaInput = p.userAgent
                // 预设基址自带 /v1、/v4 之类版本段 ⇒ 路径固定 /chat/completions；
                // 自定义的基址可能没有版本段 ⇒ 路径保持当前值（默认 /v1/chat/completions）。
                if (!isCustomId(p.id)) pathInput = PRESET_PATH
                showProviderList = false
                showCustomEditor = false
                testStatus = ""
            }

            /** 用当前填的 Key 拉该站模型列表，省得用户手抄模型名。 */
            fun loadModels() {
                val params = inputsAsParams(forTest = true)
                if (params.endpoint.isBlank()) {
                    testStatus = "先选一个供应商，或在「高级设置」里填接口地址"
                    return
                }
                if (params.apiKey.isBlank()) {
                    testStatus = "先填 API Key"
                    return
                }
                testStatus = "正在获取模型列表…"
                val activity = context.activityOrNull()
                thread {
                    val result = runCatching { fetchModels(params) }
                    val text = result.fold(
                        onSuccess = { list ->
                            if (list.isEmpty()) "该站没返回模型列表，请手动填模型名" else "已获取 ${list.size} 个模型，请选择"
                        },
                        onFailure = { "获取失败：${it.message?.take(200)}" }
                    )
                    activity?.runOnUiThread {
                        result.onSuccess { list ->
                            modelChoices = list
                            // 用户要求「只填网址和 Key」⇒ 模型为空时自动选第一个，省一次点击
                            if (modelInput.isBlank() && list.isNotEmpty()) {
                                modelInput = list.first()
                                rememberModel(providerInput, list.first())
                            }
                            if (list.isNotEmpty()) showModelList = true
                        }
                        testStatus = text
                    }
                }
            }

            fun testConnection() {
                val params = inputsAsParams(forTest = true)
                if (!params.isUsable()) {
                    testStatus = "先填接口地址、API Key、模型名"
                    return
                }
                testStatus = "测试中…"
                val activity = context.activityOrNull()
                thread {
                    val result = runCatching {
                        chatCompletion(params, "你是连通性测试助手。", "只回复两个字：可用")
                    }
                    val text = result.fold(
                        onSuccess = { "✅ 连接成功：${it.take(60)}" },
                        onFailure = { "❌ 失败：${it.message?.take(300)}" }
                    )
                    if (activity != null) activity.runOnUiThread { testStatus = text } else testStatus = text
                }
            }

            AlertDialogContent(
                title = { Text("群聊消息分析") },
                text = {
                    DefaultColumn(modifier = Modifier.heightIn(max = 420.dp), scrollable = true) {
                        if (showCustomEditor) {
                            // 新建自定义供应商（可加多个，用户 2026-09-25 要求）：只要名称 + 网址
                            OutlinedTextField(
                                value = newCustomName, onValueChange = { newCustomName = it },
                                label = { Text("名称（可留空，如：我的网关）") },
                                singleLine = true, modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = newCustomUrl, onValueChange = { newCustomUrl = it },
                                label = { Text("网址，如 https://host/v1") },
                                singleLine = true, modifier = Modifier.fillMaxWidth()
                            )
                            Row {
                                TextButton({
                                    val url = normalizeCustomUrl(newCustomUrl)
                                    if (url.isEmpty()) {
                                        testStatus = "先填网址"
                                    } else {
                                        val p = Provider(
                                            id = "custom_" + System.currentTimeMillis(),
                                            label = newCustomName.trim().ifEmpty { "自定义 ${customs.size + 1}" },
                                            base = url
                                        )
                                        customs = customs + p
                                        saveCustomProviders(customs)
                                        newCustomName = ""
                                        newCustomUrl = ""
                                        testStatus = ""
                                        applyPreset(p)
                                    }
                                }) { Text("添加") }
                                TextButton({ showCustomEditor = false }) { Text("取消") }
                            }
                            if (testStatus.isNotEmpty()) Text(testStatus)
                        } else if (showProviderList) {
                            BUILTIN_PROVIDERS.forEach { p ->
                                ListItem(
                                    colors = dialogListItemColors(),
                                    modifier = Modifier.clickable { applyPreset(p) },
                                    headlineContent = { Text(p.label) },
                                    supportingContent = { if (p.hint.isNotEmpty()) Text(p.hint) },
                                    trailingContent = { if (p.id == providerInput) Text("✓") }
                                )
                            }
                            customs.forEach { p ->
                                ListItem(
                                    colors = dialogListItemColors(),
                                    modifier = Modifier.clickable { applyPreset(p) },
                                    headlineContent = { Text(p.label) },
                                    supportingContent = { Text(p.base.ifEmpty { "未填网址" }) },
                                    trailingContent = {
                                        Row {
                                            if (p.id == providerInput) Text("✓")
                                            TextButton({
                                                val rest = customs.filterNot { it.id == p.id }
                                                customs = rest
                                                saveCustomProviders(rest)
                                                forgetProvider(p.id)
                                                // 删掉的正好是当前选中的 ⇒ 换到下一个可选项
                                                if (providerInput == p.id) {
                                                    applyPreset(rest.firstOrNull() ?: BUILTIN_PROVIDERS.first())
                                                }
                                            }) { Text("删除") }
                                        }
                                    }
                                )
                            }
                            ListItem(
                                colors = dialogListItemColors(),
                                modifier = Modifier.clickable {
                                    newCustomName = ""
                                    newCustomUrl = ""
                                    testStatus = ""
                                    showCustomEditor = true
                                },
                                headlineContent = { Text("＋ 新建自定义供应商") },
                                supportingContent = { Text("自建网关 / 反代 / 私有站，可以加多个") }
                            )
                        } else if (showModelList) {
                            modelChoices.forEach { m ->
                                ListItem(
                                    colors = dialogListItemColors(),
                                    modifier = Modifier.clickable {
                                        modelInput = m
                                        // 选中的模型也存到该供应商名下（用户 2026-09-25 要求）
                                        rememberModel(providerInput, m)
                                        showModelList = false
                                    },
                                    headlineContent = { Text(m) }
                                )
                            }
                            ListItem(
                                colors = dialogListItemColors(),
                                modifier = Modifier.clickable { showModelList = false },
                                headlineContent = { Text("返回（自己填模型名）") }
                            )
                        } else {
                        // ① 供应商 ② API Key ③ 模型 —— 「只填 Key」的主路径
                        ListItem(
                            colors = dialogListItemColors(),
                            modifier = Modifier.clickable { showProviderList = true },
                            headlineContent = { Text("模型供应商") },
                            supportingContent = { Text(providerOf(providerInput).label) },
                            trailingContent = { Text("›") }
                        )
                        // 自定义供应商：网址就在主路径里改（用户要求「自定义只填网址 + Key」，
                        // 且每个自定义各记自己那份 ⇒ 改完即时写回清单）
                        if (isCustomId(providerInput)) {
                            OutlinedTextField(
                                value = baseInput,
                                onValueChange = { v ->
                                    baseInput = v
                                    val idx = customs.indexOfFirst { it.id == providerInput }
                                    if (idx >= 0) {
                                        val updated = customs.toMutableList()
                                        updated[idx] = updated[idx].copy(base = normalizeCustomUrl(v))
                                        customs = updated
                                        saveCustomProviders(customs)
                                    }
                                },
                                label = { Text("网址，如 https://host/v1") },
                                singleLine = true, modifier = Modifier.fillMaxWidth()
                            )
                        }
                        OutlinedTextField(
                            value = keyInput,
                            onValueChange = {
                                keyInput = it
                                // 输入即存到「当前供应商」名下，不用点保存
                                rememberKey(providerInput, it.filterNot { c -> c.isWhitespace() })
                            },
                            label = { Text("API Key") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = modelInput,
                            onValueChange = {
                                modelInput = it
                                // 模型也按供应商各存一份（用户 2026-09-25 要求）
                                rememberModel(providerInput, it.trim())
                            },
                            label = { Text("模型名") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        Row {
                            TextButton(onClick = { loadModels() }) { Text("获取模型列表") }
                            if (modelChoices.isNotEmpty()) {
                                TextButton(onClick = { showModelList = true }) { Text("选模型") }
                            }
                            TextButton(onClick = { testConnection() }) { Text("测试连接") }
                        }
                        if (testStatus.isNotEmpty()) Text(testStatus)
                        ListItem(
                            colors = dialogListItemColors(),
                            modifier = Modifier.clickable { onlyUnreadInput = !onlyUnreadInput },
                            leadingContent = {
                                Switch(
                                    checked = onlyUnreadInput,
                                    onCheckedChange = { onlyUnreadInput = it },
                                    colors = dialogSwitchColors()
                                )
                            },
                            headlineContent = { Text("只在有「N条新消息」时显示挂件") },
                            supportingContent = {
                                Text(
                                    "关（默认）＝群聊页常显（没有「N条新消息」胶囊时停在屏幕右侧居中）；" +
                                            "开＝有未读才显示。" +
                                            "点挂件时的分析范围：该群有未读 → 分析全部未读（上限 1000 条），" +
                                            "没有未读 → 分析当天消息"
                                )
                            }
                        )
                        ListItem(
                            colors = dialogListItemColors(),
                            modifier = Modifier.clickable { advanced = !advanced },
                            leadingContent = {
                                Switch(
                                    checked = advanced,
                                    onCheckedChange = { advanced = it },
                                    colors = dialogSwitchColors()
                                )
                            },
                            headlineContent = { Text("高级设置") },
                            supportingContent = { Text("地址 / 请求头 / UA / 超时 / 提示词 / TLS") }
                        )
                        if (advanced) {
                        OutlinedTextField(
                            value = urlInput, onValueChange = { urlInput = it },
                            label = { Text("完整接口地址（优先，留空则用下面两项拼）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = baseInput, onValueChange = { baseInput = it },
                            label = { Text("API 基址，如 https://host/v1") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = pathInput, onValueChange = { pathInput = it },
                            label = { Text("接口路径，默认 /v1/chat/completions") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = keyHeaderInput, onValueChange = { keyHeaderInput = it },
                            label = { Text("Key 请求头名，默认 Authorization（有的站要 x-api-key）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = keyPrefixInput, onValueChange = { keyPrefixInput = it },
                            label = { Text("Key 前缀，默认 “Bearer ”（x-api-key 类站点留空）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = uaInput, onValueChange = { uaInput = it },
                            label = { Text("User-Agent（部分公益站按键 UA 放行，可留空）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = headersInput, onValueChange = { headersInput = it },
                            label = { Text("额外请求头，每行一个 key: value") },
                            modifier = Modifier.fillMaxWidth().height(96.dp)
                        )
                        OutlinedTextField(
                            value = promptInput, onValueChange = { promptInput = it },
                            label = { Text("系统提示词（决定报告口径）") },
                            modifier = Modifier.fillMaxWidth().height(120.dp)
                        )
                        OutlinedTextField(
                            value = timeoutInput, onValueChange = { timeoutInput = it },
                            label = { Text("超时秒数（10–600）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = retriesInput, onValueChange = { retriesInput = it },
                            label = { Text("失败重试次数（0–5）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = maxInput, onValueChange = { maxInput = it },
                            label = { Text("单次最多分析条数（1–1000）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        ListItem(
                            colors = dialogListItemColors(),
                            leadingContent = {
                                Switch(
                                    checked = tlsInput,
                                    onCheckedChange = { tlsInput = it },
                                    colors = dialogSwitchColors()
                                )
                            },
                            headlineContent = { Text("忽略 TLS 证书校验（自签反代才需要）") }
                        )
                        }
                        }
                    }
                },
                confirmButton = {
                    Button({
                        providerId = providerInput
                        // 该供应商的 Key / 模型存到它自己名下（另存一份"当前生效"的给运行时用）
                        rememberKey(providerInput, keyInput.filterNot { it.isWhitespace() })
                        rememberModel(providerInput, modelInput.trim())
                        // 自定义供应商：把网址同步回它的清单条目（高级设置里的基址与主路径是同一个值）
                        if (isCustomId(providerInput)) {
                            val idx = customs.indexOfFirst { it.id == providerInput }
                            if (idx >= 0) {
                                val updated = customs.toMutableList()
                                updated[idx] = updated[idx].copy(base = normalizeCustomUrl(baseInput))
                                customs = updated
                                saveCustomProviders(customs)
                            }
                        }
                        apiUrl = urlInput.trim()
                        apiBase = baseInput.trim().trimEnd('/')
                        apiPath = pathInput.trim().ifEmpty { "/v1/chat/completions" }
                        apiKey = keyInput.filterNot { it.isWhitespace() }
                        model = modelInput.trim()
                        keyHeader = keyHeaderInput.trim().ifEmpty { "Authorization" }
                        keyPrefix = keyPrefixInput
                        userAgent = uaInput.trim()
                        extraHeaders = headersInput.trim()
                        systemPrompt = promptInput.trim().ifEmpty { DEFAULT_SYSTEM_PROMPT }
                        timeoutSec = timeoutInput.trim().toIntOrNull()?.coerceIn(10, 600) ?: 120
                        retries = retriesInput.trim().toIntOrNull()?.coerceIn(0, 5) ?: 1
                        maxMsgs = maxInput.trim().toIntOrNull()?.coerceIn(1, 1000) ?: DEFAULT_MAX_MSGS
                        skipTls = tlsInput
                        onlyWhenUnread = onlyUnreadInput
                        clientCache = null
                        showToast(context, "配置已保存")
                        onDismiss()
                    }) { Text("保存") }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } }
            )
        }
    }

    // ==================== 网络层 ====================

    private data class AiParams(
        val endpoint: String,
        val apiKey: String,
        val model: String,
        val keyHeader: String,
        val keyPrefix: String,
        val userAgent: String,
        val extraHeaders: String,
        val timeoutSec: Int,
        val retries: Int,
        val skipTls: Boolean,
        val systemPrompt: String
    ) {
        fun isUsable() = endpoint.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
    }

    /**
     * 完整地址优先；否则 base + path 拼接。
     *
     * ⚠️ **不能无条件拼 `/v1/chat/completions`**：预设基址、以及用户手填的
     * `http://host/v1` 这类**自带版本段**，硬拼会变成 `/v1/v1/chat/completions`（v3.22 的坑）。
     * 规则：基址末尾已是 `/vN` ⇒ 只补 `/chat/completions`；否则按 path（缺省 `/v1/chat/completions`）。
     */
    private fun endpointOf(url: String, base: String, path: String): String {
        val full = url.trim()
        if (full.isNotEmpty()) return full
        val b = base.trim().trimEnd('/')
        if (b.isEmpty()) return ""
        if (b.endsWith("/chat/completions") || b.endsWith("/completions")) return b
        val suffix = if (VERSION_TAIL.containsMatchIn(b)) {
            "/chat/completions"
        } else {
            val p = path.trim().ifEmpty { "/v1/chat/completions" }
            if (p.startsWith("/")) p else "/$p"
        }
        return b + suffix
    }

    /** 基址末尾的版本段（`/v1`、`/v2`、`/v4`、`/api/v3` 的尾段…）。 */
    private val VERSION_TAIL = Regex("""/v\d+$""")

    private fun currentParams() = AiParams(
        endpoint = endpointOf(apiUrl, apiBase, apiPath),
        apiKey = apiKey.filterNot { it.isWhitespace() },
        model = model.trim(),
        keyHeader = keyHeader.ifBlank { "Authorization" },
        keyPrefix = keyPrefix,
        userAgent = userAgent.trim(),
        extraHeaders = extraHeaders,
        timeoutSec = timeoutSec.coerceIn(10, 600),
        retries = retries.coerceIn(0, 5),
        skipTls = skipTls,
        systemPrompt = systemPrompt.ifBlank { DEFAULT_SYSTEM_PROMPT }
    )

    private fun clientFor(params: AiParams): OkHttpClient {
        clientCache?.let { if (it.first == params) return it.second }
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(params.timeoutSec.toLong(), TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .apply {
                if (params.skipTls) {
                    runCatching {
                        val trustAll = object : X509TrustManager {
                            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                        }
                        val sslContext = SSLContext.getInstance("TLS")
                        sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
                        sslSocketFactory(sslContext.socketFactory, trustAll)
                        hostnameVerifier { _, _ -> true }
                    }.onFailure { WeLogger.e(TAG, "skip-tls setup failed", it) }
                }
            }
            .build()
        clientCache = params to client
        return client
    }

    /** OpenAI 兼容 `chat/completions` 调用；失败抛出（含上游状态码与响应原文）。 */
    private fun chatCompletion(params: AiParams, system: String, user: String): String {
        val payload = JSONObject()
            .put("model", params.model)
            .put("stream", false)
            .put("temperature", 0.7)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user))
            )
        val body = payload.toString().toRequestBody("application/json".toMediaType())
        val attempts = params.retries.coerceIn(0, 5) + 1
        var last: Throwable? = null

        for (attempt in 0 until attempts) {
            try {
                val builder = Request.Builder()
                    .url(params.endpoint)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("Accept", "application/json")
                    .addHeader(params.keyHeader, params.keyPrefix + params.apiKey)
                    .post(body)
                if (params.userAgent.isNotEmpty()) builder.addHeader("User-Agent", params.userAgent)
                params.extraHeaders.lineSequence()
                    .map { it.trim() }
                    .filter { it.contains(':') && !it.startsWith("#") }
                    .forEach { line ->
                        val idx = line.indexOf(':')
                        builder.addHeader(line.substring(0, idx).trim(), line.substring(idx + 1).trim())
                    }

                clientFor(params).newCall(builder.build()).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        // 错误原文回显：公益站/反代的 4xx-5xx 往往只有 body 里说得清原因
                        throw IllegalStateException("HTTP ${response.code} ${response.message}\n${text.take(800)}")
                    }
                    val content = runCatching {
                        JSONObject(text).getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                    }.getOrElse {
                        throw IllegalStateException("响应不是 OpenAI 兼容格式（解析 choices[0].message.content 失败）\n${text.take(800)}")
                    }
                    return content.trim()
                }
            } catch (t: Throwable) {
                last = t
                if (attempt < attempts - 1) runCatching { Thread.sleep(800L * (attempt + 1)) }
            }
        }
        throw last ?: IllegalStateException("请求失败")
    }

    // ==================== 结果对话框 ====================

    private fun runWithProgressDialog(
        context: Context,
        title: String,
        task: (onResult: (Result<String>) -> Unit, onStageUpdate: (String) -> Unit) -> Unit
    ) {
        val activity = context.activityOrNull()
        showComposeDialog(context, directlyDismissable = false) {
            var stage by remember { mutableStateOf(title) }
            var finished by remember { mutableStateOf(false) }
            var resultText by remember { mutableStateOf("") }
            var errorText by remember { mutableStateOf("") }

            // 弹窗尺寸**分两段**（用户 2026-09-25 要求）：
            //   分析中 = 保持主题默认的小尺寸 + 屏幕居中（不设任何固定尺寸）；
            //   出报告 / 失败 = 放大成固定盒子（左右 12dp、上 20mm、下 15mm）并**整体上移 30dp**（大小不变）。
            // ⚠️ 必须在 `finished` 声明之后读它 —— 否则窗口几何不会跟随阶段变化。
            LaunchedEffect(finished) {
                if (!finished) {
                    // 分析中弹窗：宽度也按用户要求左右各留 12dp，高度自适应、屏幕居中
                    // （原来不设宽度 = 走系统主题默认，实测可能接近满屏宽）
                    val dm = context.resources.displayMetrics
                    val side = DIALOG_SIDE_DP.dpToPx(context)
                    val w = (dm.widthPixels - side * 2).coerceAtLeast(1)
                    window.setLayout(w, WindowManager.LayoutParams.WRAP_CONTENT)
                    window.setGravity(Gravity.CENTER)
                    WeLogger.i(
                        TAG,
                        "analysis dialog window: ${w}xWRAP (side=$side) screen=${dm.widthPixels}x${dm.heightPixels}"
                    )
                } else {
                    val dm = context.resources.displayMetrics
                    val side = DIALOG_SIDE_DP.dpToPx(context)
                    val top = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, DIALOG_TOP_MM, dm).toInt()
                    val bottom = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, DIALOG_BOTTOM_MM, dm).toInt()
                    val lift = DIALOG_REPORT_LIFT_DP.dpToPx(context)
                    val w = (dm.widthPixels - side * 2).coerceAtLeast(1)
                    val h = (dm.heightPixels - top - bottom).coerceAtLeast(1)
                    window.setLayout(w, h)
                    window.setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                    val lp = window.attributes
                    lp.y = top - lift
                    window.attributes = lp
                    WeLogger.i(
                        TAG,
                        "report dialog window: ${w}x$h (side=$side top=$top bottom=$bottom lift=$lift) " +
                                "screen=${dm.widthPixels}x${dm.heightPixels}"
                    )
                }
            }

            when {
                !finished -> AlertDialogContent(
                    // 用户 2026-09-25：标题改「群聊消息分析中」；进度文字（读取…/汇总报告…）居中显示
                    title = { Text("群聊消息分析中") },
                    text = {
                        Text(
                            text = stage,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    },
                    rotatingBorder = true
                )

                errorText.isNotEmpty() -> AlertDialogContent(
                    bodyScrollable = false,
                    title = { Text("分析失败") },
                    text = {
                        Text(
                            text = errorText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        )
                    },
                    confirmButton = { Button(onDismiss) { Text("关闭") } },
                    fillHeight = true,
                    borderColor = WEKITE_BLUE
                )

                else -> AlertDialogContent(
                    bodyScrollable = false,
                    title = { Text("群聊消息分析") },
                    text = {
                        Text(
                            text = resultText,
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        )
                    },
                    confirmButton = { Button(onDismiss) { Text("关闭") } },
                    dismissButton = {
                        TextButton({
                            copyToClipboard(context, resultText)
                            showToast(context, "已复制")
                        }) { Text("复制") }
                    },
                    fillHeight = true,
                    borderColor = WEKITE_BLUE
                )
            }

            LaunchedEffect(Unit) {
                thread {
                    task(
                        { result ->
                            activity?.runOnUiThread {
                                finished = true
                                result.onSuccess { resultText = it }
                                    .onFailure { errorText = it.message ?: "未知错误" }
                            }
                        },
                        { newStage -> activity?.runOnUiThread { stage = newStage } }
                    )
                }
            }
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("群聊分析", text))
    }

    /** 从 View / 弹窗 context 反查宿主 Activity（与本仓其他聊天页功能同一写法）。 */
    private tailrec fun Context.activityOrNull(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.activityOrNull()
        else -> null
    }
}
