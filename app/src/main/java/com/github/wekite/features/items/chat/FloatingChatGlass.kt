package com.github.wekite.features.items.chat

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.github.wekite.ui.content.liquid.lens
import com.github.wekite.ui.content.liquid.vibrancy
import com.github.wekite.ui.content.rememberViewBackdrop
import com.github.wekite.ui.utils.InjectedUiTheme
import com.github.wekite.ui.utils.LifecycleOwnerProvider
import com.github.wekite.ui.utils.setLifecycleOwner
import com.github.wekite.utils.WeLogger
import java.util.WeakHashMap
import top.yukonga.miuix.kmp.blur.blur
import top.yukonga.miuix.kmp.blur.drawBackdrop
import top.yukonga.miuix.kmp.blur.highlight.Highlight

/**
 * 给聊天页的悬浮卡片 (悬浮标题栏 / 悬浮输入框) 垫一层液态玻璃。
 *
 * ## 为什么玻璃层是"卡片的子 View"
 *
 * 玻璃层做成卡片自己的**最后一个子 View**, 并把 `translationZ` 设成 -1f:
 *
 * - **几何免同步**: 跟着卡片走, 卡片改 margin / reparent / 随键盘平移都不用我们重算 bounds。
 * - **圆角免裁剪**: 卡片已经 `clipToOutline = true` (见 `FloatingChatHeader.applyCardStyle` /
 *   `FloatingChatFooter.applyDrawingStyle`), 玻璃自动裁成同一个圆角矩形; 输入框那段悬在屏幕
 *   外的面板高度也一并被 outline 裁掉, 不需要自己算。
 * - **层序正确**: ViewGroup 一旦有子 View 带非零 Z 就按 Z 排序绘制, Z = -1 保证玻璃画在卡片
 *   自己的内容 (标题文字 / 输入行) 之下。而 View.draw 先画 background 再 dispatchDraw, 所以
 *   玻璃会盖住微信那张不透明底 —— 这正是要的效果, 同时那张底天然成为**玻璃失效时的兜底色**
 *   ([FloatingChatCardVisuals] 的实色面), 绝不会出现白卡。
 * - **索引不移位**: 追加在末尾, 微信自己子 View 的索引全不变 (不会打破它 `getChildAt(0)`
 *   之类的假设); 压到最底层靠 Z 而不是靠插到 index 0。
 *
 * ## 为什么宿主要自己接管测量与布局
 *
 * 让宿主参与父容器的测量会**破坏卡片自己的高度**: `ChatFooter` / `ActionBarContainer` 都是
 * `FrameLayout` 子类, 高度 `wrap_content` 时 FrameLayout 把每个子 View 的 `measuredHeight`
 * 计入 `maxHeight` —— 子 View 吃满可用高度就会让输入框变成整屏高, 整个聊天页布局崩掉。
 *
 * 于是 [GlassHostLayout.onMeasure] 对外恒报 0×0 (对父容器 `wrap_content` 零贡献), 内部则按
 * `targetWidth/targetHeight` 把子树量到卡片满尺寸。测量报 0 也意味着父容器会把我们**摆到
 * 0×0**, 所以宿主给自己挂了一个 `OnLayoutChangeListener`: 一旦发现自己的 bounds 不是目标值
 * 就立刻 `layout(0, 0, target…)` 纠正回来。
 *
 * 之所以用自监听而不是覆写 `layout`: `ViewGroup.layout` 是 **final**, 覆写不了 (实测编译报
 * `'layout' in 'ViewGroup' is final and cannot be overridden`)。自监听同样不依赖卡片尺寸是否
 * 变化 —— 只要父容器把我们摆回 0×0 就会触发一次纠正, 不会漏帧。纠正后的 `layout` 再次触发
 * 监听时 bounds 已等于目标值, 提前 return, 不会自激。
 *
 * `addView(host)` 不传 LayoutParams 是故意的: 由父容器 `generateDefaultLayoutParams()` 产生
 * 正确类型, 避免把 `FrameLayout.LayoutParams` 塞给 `RelativeLayout` 之类的父容器时在
 * `onMeasure` 里 ClassCastException。参数值本身无所谓 —— 我们对外恒报 0×0。
 *
 * ## 触摸
 *
 * [GlassHostLayout.dispatchTouchEvent] 恒返回 false, 事件继续派发给下一个兄弟 View ——
 * 卡片原本的全部手势 (点标题进详情、点输入框、长按等) 一个都不拦。
 *
 * ## 捕获源
 *
 * `source` 传微信的 `ChattingContent`: 壁纸、消息列表、置顶卡都在它里面, 是两张卡唯一正确的
 * 采样源。玻璃层本身不在 `ChattingContent` 子树内 (标题栏在 ActionBarContainer 里, 输入框是
 * `ChattingContent` 的兄弟), 所以不会自我递归绘制。
 *
 * 抓不到硬件表面 (SurfaceView / TextureView, 如视频通话) 是 `View.draw()` 的固有限制, 那块画空白。
 */
internal object FloatingChatGlass {

    private const val TAG = "FloatingChatGlass"

    /** 液态玻璃叠色透明度, 与悬浮底栏同一配方 (`containerColor.copy(0.4f)`)。 */
    private const val TINT_ALPHA = 0.4f

    /**
     * 毛玻璃叠色透明度。比液态更雾更白: 毛玻璃没有折射/高光/vibrancy 撑质感, 叠色不抬
     * 一点就只剩一团模糊, 和"磨砂白"的经典观感 (iOS frost) 对不上。
     */
    private const val FROSTED_TINT_ALPHA = 0.55f

    /** 浅色模式叠色 (微信浅色标题栏/输入栏底色)。 */
    private val LIGHT_TINT = Color(0xFFF7F7F7)

    /** 深色模式叠色, 与 [FloatingChatCardVisuals] 的兜底面色一致。 */
    private val DARK_TINT = Color(0xFF242424)

    /**
     * blur / lens 走 `RenderEffect` + `RuntimeShader`, API 31 以下拿不到。低版本不挂玻璃,
     * 卡片保持 [FloatingChatCardVisuals] 的实色表面 —— 宁可没玻璃, 也不要空白卡。
     */
    private val isSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    private val attachments = WeakHashMap<View, Attachment>()

    private class Attachment(
        val owner: Any,
        val host: GlassHostLayout,
        val state: GlassState,
        val layoutListener: View.OnLayoutChangeListener,
    )

    /**
     * 玻璃层的活参数。用 snapshot state 持有: 每帧 [apply] 写入新值直接驱动重组, 不必重建
     * ComposeView, 设置里改圆角/半径也能立刻跟上。
     */
    @Stable
    private class GlassState {
        var source by mutableStateOf<View?>(null)
        var cornerRadiusDp by mutableIntStateOf(0)
        var blurRadiusDp by mutableIntStateOf(0)
        var liquid by mutableStateOf(true)
    }

    /**
     * 幂等地把玻璃层同步到 [card]: 该挂就挂、该更新参数就更新、该摘就摘。可以每帧调用。
     *
     * @param owner 调用方特性对象, [detachAll] 按它清理, 两个特性互不干扰。
     * @param card 悬浮卡片本体 (ActionBarContainer / ChatFooter)。
     * @param source 采样源 (`ChattingContent`), 为 null 时不挂玻璃。
     * @param enabled 特性设置里的玻璃开关 (毛玻璃或液态都算开)。
     * @param liquid true = 液态玻璃 (模糊 + 折射 + 高光 + vibrancy);
     *               false = 毛玻璃 (只模糊 + 更浓的叠色, iOS 磨砂白观感)。
     */
    fun apply(
        owner: Any,
        card: View,
        source: View?,
        cornerRadiusDp: Int,
        blurRadiusDp: Int,
        enabled: Boolean,
        liquid: Boolean = true,
    ) {
        if (!enabled || !isSupported || source == null || card !is ViewGroup) {
            detach(card)
            return
        }

        val existing = attachments[card]
        if (existing != null && existing.host.parent === card) {
            existing.state.source = source
            existing.state.cornerRadiusDp = cornerRadiusDp
            existing.state.blurRadiusDp = blurRadiusDp
            existing.state.liquid = liquid
            existing.host.stretchTo(card.width, card.height)
            return
        }
        // parent 变了 (卡片被微信回收重建) 说明旧宿主已失效, 先彻底摘掉再重挂
        if (existing != null) detach(card)

        val state = GlassState().apply {
            this.source = source
            this.cornerRadiusDp = cornerRadiusDp
            this.blurRadiusDp = blurRadiusDp
            this.liquid = liquid
        }
        val host = createHost(card.context, state) ?: return
        // 不传 LayoutParams: 让父容器生成自己认得的类型 (见类文档)
        card.addView(host)
        val layoutListener = View.OnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            host.stretchTo(v.width, v.height)
        }
        card.addOnLayoutChangeListener(layoutListener)
        host.stretchTo(card.width, card.height)
        attachments[card] = Attachment(owner, host, state, layoutListener)
        WeLogger.d(
            TAG,
            "attached glass to ${card.javaClass.simpleName}: " +
                "corner=${cornerRadiusDp}dp blur=${blurRadiusDp}dp size=${card.width}x${card.height}"
        )
    }

    fun detach(card: View) {
        val attachment = attachments.remove(card) ?: return
        card.removeOnLayoutChangeListener(attachment.layoutListener)
        val host = attachment.host
        (host.parent as? ViewGroup)?.removeView(host)
        // 断开对微信视图的强引用; ComposeView 脱离窗口后 ViewBackdrop 的 pre-draw 监听随之释放
        attachment.state.source = null
        WeLogger.d(TAG, "detached glass from ${card.javaClass.simpleName}")
    }

    /** 特性关闭时清掉自己挂的全部玻璃层, 不动另一个特性的。 */
    fun detachAll(owner: Any) {
        attachments.entries.toList()
            .filter { it.value.owner === owner }
            .forEach { detach(it.key) }
    }

    private fun createHost(context: Context, state: GlassState): GlassHostLayout? {
        return runCatching {
            val composeView = ComposeView(context).apply {
                setLifecycleOwner(LifecycleOwnerProvider.lifecycleOwner)
                setContent {
                    InjectedUiTheme {
                        GlassSurface(state)
                    }
                }
            }
            GlassHostLayout(context).apply {
                addView(
                    composeView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                )
            }
        }.getOrElse {
            WeLogger.e(TAG, "failed to create glass layer, falling back to solid card", it)
            null
        }
    }

    @Composable
    private fun GlassSurface(state: GlassState) {
        val source = state.source ?: return
        val cornerRadiusDp = state.cornerRadiusDp
        val blurRadiusDp = state.blurRadiusDp
        val liquid = state.liquid
        val isInDark = isSystemInDarkTheme()
        val shape = remember(cornerRadiusDp) { RoundedCornerShape(cornerRadiusDp.dp) }
        // 半径 0 = 完全透明: 叠色、模糊、折射、高光全去掉, 微信内容原样透出。
        // 与悬浮底栏的 `isGlassTransparent` 同一语义。
        val isTransparent = blurRadiusDp <= 0
        val tint = if (isInDark) DARK_TINT else LIGHT_TINT
        val tintAlpha = if (liquid) TINT_ALPHA else FROSTED_TINT_ALPHA
        val backdrop = rememberViewBackdrop(source)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { shape },
                    effects = {
                        if (!isTransparent) {
                            blur(blurRadiusDp.dp.toPx(), blurRadiusDp.dp.toPx())
                            if (liquid) {
                                // 液态专属: 饱和度提升 + 边缘折射。毛玻璃只要模糊,
                                // 折射边缘会把"磨砂白"变成"透镜", 观感就错了。
                                vibrancy()
                                lens(
                                    refractionHeight = 24.dp.toPx(),
                                    refractionAmount = 24.dp.toPx(),
                                )
                            }
                        }
                    },
                    highlight = {
                        when {
                            isTransparent -> null
                            liquid -> Highlight.Default.copy(alpha = 0.75f)
                            // 毛玻璃要哑光, 只留一条极淡的顶边高光划出轮廓
                            else -> Highlight.Default.copy(alpha = 0.15f)
                        }
                    },
                    onDrawSurface = {
                        if (!isTransparent) drawRect(tint.copy(alpha = tintAlpha))
                    },
                )
        )
    }

    /**
     * 对父容器测量零影响、对触摸完全透明的玻璃宿主。详见 [FloatingChatGlass] 类文档。
     */
    private class GlassHostLayout(context: Context) : FrameLayout(context) {

        private var targetWidth = 0
        private var targetHeight = 0

        init {
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            translationZ = -1f
            // 父容器按 measured size (0×0) 摆我们之后自我纠正回目标尺寸。
            // ViewGroup.layout 是 final 覆写不了, 只能这样兜。
            addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
                if (targetWidth <= 0 || targetHeight <= 0) return@addOnLayoutChangeListener
                if (right - left == targetWidth && bottom - top == targetHeight &&
                    left == 0 && top == 0
                ) {
                    return@addOnLayoutChangeListener
                }
                layout(0, 0, targetWidth, targetHeight)
            }
        }

        /**
         * 记下卡片的当前尺寸并立刻量+摆一次, 不等下一轮父容器布局, 免得中间空一帧。
         * 尺寸没变就 no-op。
         */
        fun stretchTo(width: Int, height: Int) {
            if (width <= 0 || height <= 0) return
            if (targetWidth == width && targetHeight == height &&
                this.width == width && this.height == height
            ) {
                return
            }
            targetWidth = width
            targetHeight = height
            measureChildrenToTarget()
            layout(0, 0, width, height)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            // 忽略父容器给的规格: 子树按目标尺寸量, 对外恒报 0×0
            measureChildrenToTarget()
            setMeasuredDimension(0, 0)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val width = right - left
            val height = bottom - top
            for (i in 0 until childCount) {
                getChildAt(i).layout(0, 0, width, height)
            }
        }

        private fun measureChildrenToTarget() {
            if (targetWidth <= 0 || targetHeight <= 0) return
            val widthSpec = MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY)
            val heightSpec = MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
            for (i in 0 until childCount) {
                getChildAt(i).measure(widthSpec, heightSpec)
            }
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean = false

        override fun onTouchEvent(event: MotionEvent): Boolean = false

        override fun hasOverlappingRendering(): Boolean = false
    }
}
