package com.github.wekite.features.api.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import dev.ujhhgtg.reflekt.reflekt
import com.github.wekite.features.core.ApiFeature
import com.github.wekite.features.core.Feature
import com.github.wekite.ui.utils.allViews
import com.github.wekite.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 聊天页「N条新消息」提示条（微信内部叫「舌头」tongue）定位服务。
 *
 * 背景（2026-09-24 离线逆向 8.0.77(3141) 实测，详见技能
 * `wekite-slim/references/wechat-resource-layout-analysis.md`）：
 * - 「N条新消息」这句文案在 8.0.77 里有 **11 份字符串资源**，分属游戏群聊/视频号/状态/MV/卡包等页面
 *   （卡包那份 `res/aa/qk.xml` 是蓝字 31.6dip 胶囊，最容易被误认）⇒ **按文案 grep 必挂错对象**。
 * - 聊天页那个由 `HistoryMsgTongueComponent` 渲染，视图容器是 `ChattingContent` 的直接子 View，**有两支**：
 *   - **上支**：`layout_gravity = 0x05`(RIGHT，纵向默认 top)，默认 `gone`，`marginTop 24dip`，WeImageView + TextView
 *   - **下支**：`layout_gravity = 0x55`(RIGHT|BOTTOM)，默认 `gone`，`marginBottom 24dip`，ImageView + TextView
 *   两支共用同一张 9-patch 背景（selector）。**别只挑一支**：2026-09-25 定案 ——
 *   旧实现只把下支当锚点，而那支在真机日志里 **恒 `visibility=8 h=0`（两份日志全部样本 0 次可见）**，
 *   导致挂件永远固定贴右下角、与胶囊毫无关系。现在**两支都定位、都监听，谁可见谁就是锚点**。
 *
 * 本服务只负责「定位 + 广播」；几何、避让、尺寸由监听方决定。
 */
@Feature(
    name = "聊天页新消息提示条服务",
    categories = ["API"],
    description = "定位聊天页「N条新消息」胶囊（上/下两支）并广播其状态与真实几何"
)
object WeChatNewMsgTipApi : ApiFeature() {

    private const val TAG = "WeChatNewMsgTipApi"

    /** 会话页内容区（消息列表与悬浮提示条的宿主）。微信未混淆，AXML 里就是这个类名。 */
    private const val CHATTING_CONTENT_CLASS = "com.tencent.mm.pluginsdk.ui.chat.ChattingContent"

    private const val MAX_RESOLVE_RETRY = 6
    private const val RESOLVE_RETRY_DELAY_MS = 300L

    /** 文案特征：`3条新消息` / `999+条新消息` / `有人@我`。只用于候选排序，不作硬门槛。 */
    private val TEXT_HINT = Regex("""(条新消息|有人@我|@我)""")

    /**
     * 提示条状态监听器。
     *
     * [onTipChanged] 在「候选首次被定位」以及「任一候选每次布局变化（含显示/隐藏、尺寸变化）」时回调。
     * 回调里的 `tip` 参数 = **当前可见的那一支**（都不可见时给首选的**上支**，仅作 LayoutParams 来源）；
     * 需要区分时用 [activeTipOf] / [candidatesOf] / [isTopAnchored]。
     */
    fun interface ITipListener {
        fun onTipChanged(host: View, tip: View)
    }

    private val listeners = CopyOnWriteArrayList<ITipListener>()

    /** ChattingContent -> 候选容器（上支在前、下支在后） */
    private val tipCandidates = WeakHashMap<View, List<View>>()

    /** ChattingContent -> 该会话页对应的会话 ID */
    private val tipConvs = WeakHashMap<View, String>()

    /** 已经挂过布局监听的候选，避免重复挂 */
    private val observed = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /** 上一次的「可见」态，用于只在显示/隐藏跳变时打日志（不刷屏） */
    private val visibleStates = WeakHashMap<View, Boolean>()

    fun addListener(listener: ITipListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: ITipListener) {
        listeners.remove(listener)
    }

    /** 该会话页对应的会话 ID（群聊为 `xxx@chatroom`）。定位成功前返回空串。 */
    fun convIdOf(host: View): String = tipConvs[host] ?: ""

    /** 该会话页的候选容器（上支、下支；未定位到则为空列表）。 */
    fun candidatesOf(host: View): List<View> = tipCandidates[host] ?: emptyList()

    /** 当前**可见**的那一支（都不可见返回 null）—— 挂件应当贴它。 */
    fun activeTipOf(host: View): View? = candidatesOf(host).firstOrNull { it.isShown && it.height > 0 }

    /** 该容器是否按「距内容区顶部」定位（上支 = gravity 含 TOP；下支含 BOTTOM）。 */
    fun isTopAnchored(v: View): Boolean = (gravityOf(v.layoutParams) and Gravity.TOP) != 0

    override fun onEnable() {
        // setUserName(convId) 是聊天页绑定会话的稳定入口（宿主类编译期可见，与 WeCurrentConversationApi 同源）。
        ChatFooter::class.reflekt()
            .firstMethod {
                name = "setUserName"
                parameterCount = 1
            }
            .hookAfter {
                val footer = thisObject as? View ?: return@hookAfter
                val conv = args[0] as? String ?: return@hookAfter
                if (conv.isEmpty()) return@hookAfter
                resolve(footer, conv, attempt = 0)
            }
    }

    override fun onDisable() {
        listeners.clear()
        tipCandidates.clear()
        tipConvs.clear()
        observed.clear()
        visibleStates.clear()
    }

    // ==================== 定位 ====================

    private fun resolve(footer: View, conv: String, attempt: Int) {
        val root = footer.rootView ?: return
        val content = root.allViews.firstOrNull { it.javaClass.name == CHATTING_CONTENT_CLASS }
        if (content == null) {
            retry(footer, conv, attempt, "ChattingContent not found")
            return
        }
        tipConvs[content] = conv

        val known = tipCandidates[content]
        if (known != null && known.isNotEmpty() && known.all { it.parent != null }) {
            notifyListeners(content)
            return
        }

        val tips = findTips(content)
        if (tips.isEmpty()) {
            retry(footer, conv, attempt, "tip candidates not found")
            return
        }

        tipCandidates[content] = tips
        // ⭐ 一次性 dump：把两支候选的 id / gravity / 边距 / 初始可见性全打出来 ——
        // 「哪一支才是微信真正在显示的那枚」靠这条日志钉死，不用反复装机试。
        WeLogger.i(TAG, "tip candidates: " + tips.joinToString(" || ") { describe(it) })
        WeLogger.i(TAG, "content children: ${describeChildren(content)}")
        for (tip in tips) {
            if (observed.add(tip)) {
                tip.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                    logVisibilityChange(tip)
                    notifyListeners(content)
                }
                logVisibilityChange(tip)
            }
        }
        notifyListeners(content)
    }

    private fun retry(footer: View, conv: String, attempt: Int, why: String) {
        if (attempt < MAX_RESOLVE_RETRY) {
            footer.postDelayed({ resolve(footer, conv, attempt + 1) }, RESOLVE_RETRY_DELAY_MS)
        } else {
            WeLogger.w(TAG, "$why under $CHATTING_CONTENT_CLASS after $attempt tries (conv=$conv)")
        }
    }

    private fun notifyListeners(host: View) {
        val list = candidatesOf(host)
        if (list.isEmpty()) return
        // 可见的那支优先；都不可见时给上支（挂件在下支可见时会自己改贴下支）
        val tip = activeTipOf(host) ?: list.first()
        for (listener in listeners) {
            runCatching { listener.onTipChanged(host, tip) }
        }
    }

    /** 只在「可见 ⇄ 不可见」跳变时打一条带 id 的日志（挂件排障靠它）。 */
    private fun logVisibilityChange(tip: View) {
        val visible = tip.isShown && tip.height > 0
        if (visibleStates[tip] == visible) return
        visibleStates[tip] = visible
        WeLogger.i(TAG, "tip ${if (visible) "shown" else "hidden"}: ${describe(tip)}")
    }

    /**
     * 找出 `ChattingContent` 直接子里的两支「N条新消息」胶囊容器，**上支在前、下支在后**。
     * ⚠️ 不上来就只挑一支 —— 真机上哪一支会出现由微信自己决定（见类注释）。
     */
    private fun findTips(content: View): List<View> {
        val group = content as? ViewGroup ?: return emptyList()
        val candidates = (0 until group.childCount)
            .map { group.getChildAt(it) }
            .filter { isPillContainer(it) }
        if (candidates.isEmpty()) return emptyList()
        return candidates.sortedWith(
            compareByDescending<View> { textOf(it)?.let { t -> TEXT_HINT.containsMatchIn(t) } == true }
                .thenByDescending { if (isTopAnchored(it)) 1 else 0 }
        )
    }

    private fun isPillContainer(v: View): Boolean {
        if (v !is ViewGroup) return false
        if (v.childCount !in 1..3) return false
        if (v.layoutParams !is ViewGroup.MarginLayoutParams) return false
        if ((gravityOf(v.layoutParams) and (Gravity.RIGHT or Gravity.END)) == 0) return false
        var hasImage = false
        var hasText = false
        for (i in 0 until v.childCount) {
            when (val child = v.getChildAt(i)) {
                is TextView -> hasText = true
                is ImageView -> hasImage = true
                else -> if (child.javaClass.name.contains("ImageView")) hasImage = true
            }
        }
        return hasText && hasImage
    }

    private fun textOf(v: View): String? {
        if (v is TextView) return v.text?.toString()
        if (v !is ViewGroup) return null
        for (i in 0 until v.childCount) {
            (v.getChildAt(i) as? TextView)?.let { return it.text?.toString() }
        }
        return null
    }

    /**
     * `layout_gravity` 只存在于 FrameLayout/LinearLayout 的 LayoutParams 上
     * （`ViewGroup.MarginLayoutParams` 没有这个字段）—— 取错会直接编译不过。
     */
    private fun gravityOf(lp: ViewGroup.LayoutParams?): Int = when (lp) {
        is FrameLayout.LayoutParams -> lp.gravity
        is LinearLayout.LayoutParams -> lp.gravity
        else -> 0
    }

    private fun describe(v: View): String {
        val lp = v.layoutParams as? ViewGroup.MarginLayoutParams
        val gravity = gravityOf(v.layoutParams)
        val children = (v as? ViewGroup)?.let { g ->
            (0 until g.childCount).joinToString("+") { g.getChildAt(it).javaClass.simpleName }
        } ?: ""
        return "${v.javaClass.name} id=0x${Integer.toHexString(v.id)} gravity=$gravity " +
                "w=${v.width} h=${v.height} x=${v.left} y=${v.top} visibility=${v.visibility} " +
                "topMargin=${lp?.topMargin} bottomMargin=${lp?.bottomMargin} children=[$children] " +
                "text=${textOf(v)?.take(24)}"
    }

    private fun describeChildren(content: View): String {
        val group = content as? ViewGroup ?: return "not a ViewGroup"
        return (0 until group.childCount).joinToString(" | ") { i ->
            val child = group.getChildAt(i)
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
            "${child.javaClass.simpleName}(g=${gravityOf(child.layoutParams)},vis=${child.visibility}," +
                    "id=0x${Integer.toHexString(child.id)},tm=${lp?.topMargin},bm=${lp?.bottomMargin})"
        }
    }
}
