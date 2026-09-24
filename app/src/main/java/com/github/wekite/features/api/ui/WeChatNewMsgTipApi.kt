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
 * - 聊天页那个由 `HistoryMsgTongueComponent` 渲染，视图容器是 `ChattingContent` 的直接子 View：
 *   - 右下：`layout_gravity = 0x55`(RIGHT|BOTTOM)，默认 `gone`，内含 ImageView + TextView（marginBottom 24dip）
 *   - 右上：`layout_gravity = 0x05`(RIGHT)，默认 `gone`（本服务不管它，`FloatingChatHeader` 另有逻辑）
 * ⇒ 判据 = 「`ChattingContent` 的直接子 + gravity 含 RIGHT + 同时含 ImageView 与 TextView」，
 *   文案特征只用于候选排序（文本可能还没被 `setText`）。
 */
@Feature(
    name = "聊天页新消息提示条服务",
    categories = ["API"],
    description = "定位聊天页右下「N条新消息」提示条并广播其状态"
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
     * [onTipChanged] 在「提示条首次被定位」以及「提示条每次布局变化（含显示/隐藏、尺寸变化）」时回调，
     * 实现方应在回调里同步自己的悬浮挂件（几何 + 可见性）。
     */
    fun interface ITipListener {
        fun onTipChanged(host: View, tip: View)
    }

    private val listeners = CopyOnWriteArrayList<ITipListener>()

    /** ChattingContent -> 右下提示条 */
    private val tips = WeakHashMap<View, View>()

    /** ChattingContent -> 该会话页对应的会话 ID */
    private val tipConvs = WeakHashMap<View, String>()

    /** 已经挂过布局监听的提示条，避免重复挂 */
    private val observed = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    fun addListener(listener: ITipListener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: ITipListener) {
        listeners.remove(listener)
    }

    /** 该会话页对应的会话 ID（群聊为 `xxx@chatroom`）。定位成功前返回空串。 */
    fun convIdOf(host: View): String = tipConvs[host] ?: ""

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
        tips.clear()
        tipConvs.clear()
        observed.clear()
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

        val known = tips[content]?.takeIf { it.parent != null }
        if (known != null) {
            notifyListeners(content, known)
            return
        }

        val tip = findBottomTip(content)
        if (tip == null) {
            retry(footer, conv, attempt, "bottom tip not found")
            return
        }

        tips[content] = tip
        WeLogger.i(TAG, "bottom tip located: ${describe(tip)} | conv=$conv")
        WeLogger.i(TAG, "content children: ${describeChildren(content)}")
        if (observed.add(tip)) {
            tip.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> notifyListeners(content, tip) }
        }
        notifyListeners(content, tip)
    }

    private fun retry(footer: View, conv: String, attempt: Int, why: String) {
        if (attempt < MAX_RESOLVE_RETRY) {
            footer.postDelayed({ resolve(footer, conv, attempt + 1) }, RESOLVE_RETRY_DELAY_MS)
        } else {
            WeLogger.w(TAG, "$why under $CHATTING_CONTENT_CLASS after $attempt tries (conv=$conv)")
        }
    }

    private fun notifyListeners(host: View, tip: View) {
        for (listener in listeners) {
            runCatching { listener.onTipChanged(host, tip) }
        }
    }

    /** 在 `ChattingContent` 的直接子里找右下「N条新消息」提示条。 */
    private fun findBottomTip(content: View): View? {
        val group = content as? ViewGroup ?: return null
        val candidates = (0 until group.childCount)
            .map { group.getChildAt(it) }
            .filter { isPillContainer(it) }
        if (candidates.isEmpty()) return null

        val bottomOnes = candidates.filter { v ->
            (gravityOf(v.layoutParams) and Gravity.BOTTOM) != 0
        }
        val pool = bottomOnes.ifEmpty { candidates }
        return pool.firstOrNull { v -> textOf(v)?.let { TEXT_HINT.containsMatchIn(it) } == true }
            ?: pool.lastOrNull()
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
        return "${v.javaClass.name} gravity=$gravity w=${v.width} h=${v.height} " +
                "visibility=${v.visibility} bottomMargin=${lp?.bottomMargin} children=[$children] " +
                "text=${textOf(v)?.take(24)}"
    }

    private fun describeChildren(content: View): String {
        val group = content as? ViewGroup ?: return "not a ViewGroup"
        return (0 until group.childCount).joinToString(" | ") { i ->
            val child = group.getChildAt(i)
            val lp = child.layoutParams as? ViewGroup.MarginLayoutParams
            "${child.javaClass.simpleName}(g=${gravityOf(child.layoutParams)},vis=${child.visibility},txt=${textOf(child)?.take(12)})"
        }
    }
}
