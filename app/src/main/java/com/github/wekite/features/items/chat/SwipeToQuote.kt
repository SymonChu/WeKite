package com.github.wekite.features.items.chat

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import dev.ujhhgtg.reflekt.reflekt
import com.github.wekite.dexkit.abc.IResolveDex
import com.github.wekite.dexkit.dsl.dexClass
import com.github.wekite.dexkit.dsl.dexMethod
import com.github.wekite.features.api.core.WeMessageApi
import com.github.wekite.features.api.core.WeServiceApi
import com.github.wekite.features.api.ui.WeChatMessageViewApi
import com.github.wekite.features.core.Feature
import com.github.wekite.features.core.SwitchFeature
import com.github.wekite.ui.utils.dpToPx
import com.github.wekite.utils.HookParam
import com.github.wekite.utils.WeLogger
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * 在消息上**左滑（向左）快速引用** —— 移植自上游 WeKit `SwipeToQuote`（引入于 `12005138`，
 * 后在上游演化成 SwipeMessageOperations：可换方向/复读/编辑；本 fork 只移植最初的左滑引用，
 * 其余动作不上，等真机验证后再考虑扩展）。
 *
 * ## 结构（全部依赖本 fork 均已具备）
 * - 手势：钩 `ChattingItemContainer.onInterceptTouchEvent`（气泡是可点击子视图，DOWN 被
 *   子视图持有，容器只在拦截后看到 MOVE——必须钩 onIntercept 才能截到横滑）+ 每行 view 的
 *   `OnTouchListener` 包装（空白区路径：容器是不可点击 RelativeLayout，DOWN 直接落到 listener）。
 * - 引用动作：`chattingContext → apiManager → FootComponent api → ChatFooter →
 *   WeMessageApi.setReferringMessage(chatFooter, msgInfo)`（本 fork 已有现成实现，
 *   见 `WeMessageApi.kt`；msgInfo 经 `methodGetMsgInfo` 从 chatHolder 取）。
 *
 * ## 8.0.79(3182) 锚点核验（2026-10-14，字节级扫描，全部「同处一个方法体」唯一命中）
 * - `classChattingItemContainer`: `Lcom/tencent/mm/ui/chatting/viewitems/kh;`（8.0.77 也是唯一）
 * - `methodChatListOnTouch`: `Lcom/tencent/mm/ui/chatting/w8;::onTouch`（只作键盘收起镜像用）
 * - `classChattingUiFootComponent`: `Lcom/tencent/mm/ui/chatting/component/dd;::onNotifyChange`
 * - `methodGetMsgInfo`: `Lcom/tencent/mm/ui/chatting/viewitems/us;::b`
 */
@Feature(name = "左滑引用消息", categories = ["聊天"], description = "在消息上向左滑动以快速引用")
object SwipeToQuote : SwitchFeature(), IResolveDex,
    WeChatMessageViewApi.ICreateViewListener {

    private const val TAG = "SwipeToQuote"

    // Mutable per-view gesture state. RecyclerView recycles message views, so chattingContext is
    // refreshed on every onBindView (see onCreateView) rather than captured once.
    private class SwipeState(
        val touchSlop: Int,
        val triggerThreshold: Float,
        var chattingContext: Any? = null,
        var startX: Float = 0f,
        var startY: Float = 0f,
        var isDragging: Boolean = false,
        var triggered: Boolean = false,
    )

    // WeakHashMap: entries are removed automatically once the recycled row view is GC'd.
    private val states: MutableMap<View, SwipeState> =
        Collections.synchronizedMap(WeakHashMap())

    private val springInterpolator = OvershootInterpolator(1.3f)

    // com.tencent.mm.ui.chatting.viewitems.ChattingItemContainer (obfuscated: xg / li / kh).
    // This RelativeLayout is the SHARED root of every message item type (each ChattingItem.F()
    // wraps its layout in `new <this>(inflater, layoutRes)`), and it is exactly the itemView
    // handed to onCreateView below. The clickable / long-pressable message bubble is a CHILD of
    // it, so the container only sees MOVE events after it intercepts them — which is why we must
    // hook onInterceptTouchEvent here rather than rely on an OnTouchListener alone. Scoping the
    // hook to this one class (instead of global ViewGroup) keeps the blast radius tiny.
    private val classChattingItemContainer by dexClass {
        searchPackages("com.tencent.mm.ui.chatting.viewitems")
        matcher {
            usingEqStrings(
                "MicroMsg.ChattingItemContainer",
                "warn!!! cacheSize:%s sysSize:%s"
            )
        }
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    override fun onEnable() {
        WeChatMessageViewApi.addListener(this)

        // Steal the horizontal-left drag from the child bubble before it becomes a click / long
        // press. onInterceptTouchEvent is the ONLY place the container can claim a MOVE stream
        // that a clickable child already owns; an OnTouchListener can't intercept. Once we return
        // true here, the tail (MOVE/UP/CANCEL) is delivered to the container itself and handled by
        // the OnTouchListener attached in onCreateView.
        classChattingItemContainer.reflekt()
            .firstMethod { name = "onInterceptTouchEvent" }
            .hookAfter {
                val v = thisObject as? ViewGroup ?: return@hookAfter
                val s = states[v] ?: return@hookAfter
                val event = args[0] as MotionEvent
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        s.startX = event.rawX
                        s.startY = event.rawY
                        s.isDragging = false
                        s.triggered = false
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - s.startX
                        val dy = event.rawY - s.startY
                        if (!s.isDragging && dx < 0 && abs(dx) > s.touchSlop && abs(dx) > abs(dy)) {
                            s.isDragging = true
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        // Once dragging, claim the stream so the tail routes to our OnTouchListener.
                        if (s.isDragging) result = true
                    }
                }
            }
    }

    override fun onDisable() {
        WeChatMessageViewApi.removeListener(this)
        states.clear()
    }

    // ── row binding: register state + attach the swipe listener, keep context fresh ──

    override fun onCreateView(param: HookParam, view: View) {
        val chattingContext = WeChatMessageViewApi.getChattingContextFromParam(param)

        val state = states.getOrPut(view) {
            val ctx = view.context
            SwipeState(
                touchSlop = ViewConfiguration.get(ctx).scaledTouchSlop,
                triggerThreshold = 60.dpToPx(ctx).toFloat(),
            )
        }
        state.chattingContext = chattingContext

        // Re-install our wrapper every bind, delegating to whatever listener is currently attached
        // (unless it is already ours).
        attachSwipeListener(view, state)
    }

    // Marks our wrapper so a re-bind can tell its own listener apart from WeChat's.
    private class SwipeTouchListener(
        val state: SwipeState,
        val delegate: View.OnTouchListener?,
    ) : View.OnTouchListener {
        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val consumed = handleSwipe(v, state, event)
            runCatching { delegate?.onTouch(v, event) }
            return consumed
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachSwipeListener(view: View, state: SwipeState) {
        val current = getAttachedTouchListener(view)
        if (current is SwipeTouchListener) return  // already wrapped for this stream
        view.setOnTouchListener(SwipeTouchListener(state, current))
    }

    // Reads the View's current OnTouchListener out of its ListenerInfo, so we can chain to WeChat's.
    private fun getAttachedTouchListener(view: View): View.OnTouchListener? = runCatching {
        val info = view.reflekt()
            .firstFieldOrNull { name = "mListenerInfo"; superclass() }
            ?.get() ?: return null
        info.reflekt()
            .firstFieldOrNull { name = "mOnTouchListener" }
            ?.get() as? View.OnTouchListener
    }.getOrNull()

    // ── gesture ──────────────────────────────────────────────────────────────
    //
    // This handles TWO entry paths into the same container:
    //   • Bubble (clickable child owns ACTION_DOWN): onInterceptTouchEvent above records the start
    //     and steals the stream once it is a left-drag, so the listener only sees MOVE onward with
    //     isDragging already set. The DOWN branch here never runs.
    //   • Blank area right of the bubble (no clickable descendant under the touch): ACTION_DOWN
    //     falls through to this listener. The container is a non-clickable RelativeLayout, so we must
    //     consume the DOWN (return true) or it receives no further MOVE events. We then detect the
    //     drag here ourselves, since onInterceptTouchEvent is no longer called once the container is
    //     the touch target.
    // requestDisallowInterceptTouchEvent(true) is deferred until a left-drag is confirmed, so a
    // vertical scroll starting on blank space still reaches the RecyclerView.
    private fun handleSwipe(v: View, s: SwipeState, event: MotionEvent): Boolean {
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                s.startX = event.rawX
                s.startY = event.rawY
                s.isDragging = false
                s.triggered = false
                // Claim so the non-clickable container keeps receiving the stream (blank-area path).
                true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!s.isDragging) {
                    val dx = event.rawX - s.startX
                    val dy = event.rawY - s.startY
                    if (dx < 0 && abs(dx) > s.touchSlop && abs(dx) > abs(dy)) {
                        s.isDragging = true
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (s.isDragging) {
                    val dx = event.rawX - s.startX
                    v.translationX = dx.coerceIn(-s.triggerThreshold, 0f)
                    // Haptic tick tracks the live fireable state: buzz when crossing INTO the fire
                    // zone, and re-arm when sliding back out so a re-cross buzzes again.
                    val past = dx <= -s.triggerThreshold
                    if (past && !s.triggered) {
                        v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                    s.triggered = past
                    true
                } else {
                    // Not (yet) a horizontal drag; don't consume, so a vertical scroll can still be
                    // intercepted by the RecyclerView.
                    false
                }
            }

            MotionEvent.ACTION_UP -> {
                val dx = event.rawX - s.startX
                // Decide by the FINAL position, not whether the threshold was ever crossed: sliding
                // past the threshold and then back is an intentional cancel, so it must NOT fire.
                val fire = s.isDragging && dx <= -s.triggerThreshold
                if (s.isDragging) {
                    v.animate()
                        .translationX(0f)
                        .setDuration(250)
                        .setInterpolator(springInterpolator)
                        .start()
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    s.isDragging = false
                    if (fire) s.chattingContext?.let { onSwipeLeft(v, it) }
                    true
                } else {
                    false
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (s.isDragging) {
                    v.animate().translationX(0f).setDuration(150).start()
                    v.parent?.requestDisallowInterceptTouchEvent(false)
                    s.isDragging = false
                }
                false
            }

            else -> false
        }
    }

    // ── quote on swipe ─────────────────────────────────────────────────────────

    private fun onSwipeLeft(originalView: View, chattingContext: Any) {
        val apiMan = chattingContext.reflekt()
            .firstField { type = WeServiceApi.apiManagerClass }
            .get()!!
        val api = WeServiceApi.getApiByClass(apiMan, classChattingUiFootComponent.clazz)
        val chatFooter = api.reflekt()
            .firstField { type = "com.tencent.mm.pluginsdk.ui.chat.ChatFooter" }
            .get()!! as com.tencent.mm.pluginsdk.ui.chat.ChatFooter
        val chatHolder = originalView.tag.reflekt().getField("chatHolder", true)!!
        val msgInfo = methodGetMsgInfo.method.invoke(null, chatHolder, chattingContext)
        WeMessageApi.setReferringMessage(chatFooter, msgInfo)
    }

    private val classChattingUiFootComponent by dexClass {
        searchPackages("com.tencent.mm.ui.chatting.component")
        matcher {
            usingEqStrings(
                "MicroMsg.ChattingUI.FootComponent",
                "onNotifyChange event %s talker %s"
            )
        }
    }
    private val methodGetMsgInfo by dexMethod {
        searchPackages("com.tencent.mm.ui.chatting.viewitems")
        matcher { usingEqStrings("ItemDataTag", "getCurrentMsg2 err") }
    }
}
