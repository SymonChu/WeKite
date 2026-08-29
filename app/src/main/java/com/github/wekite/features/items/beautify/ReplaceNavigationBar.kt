package com.github.wekite.features.items.beautify

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Contacts
import com.composables.icons.materialsymbols.outlined.Explore
import com.composables.icons.materialsymbols.outlined.Home
import com.composables.icons.materialsymbols.outlined.Person
import com.composables.icons.materialsymbols.outlinedfilled.Contacts
import com.composables.icons.materialsymbols.outlinedfilled.Explore
import com.composables.icons.materialsymbols.outlinedfilled.Home
import com.composables.icons.materialsymbols.outlinedfilled.Person
import dev.ujhhgtg.reflekt.firstMethod
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.github.wekite.dexkit.abc.IResolveDex
import com.github.wekite.dexkit.dsl.dexMethod
import com.github.wekite.features.api.ui.WeMainActivityBeautifyApi
import com.github.wekite.features.core.ClickableFeature
import com.github.wekite.features.core.Feature
import com.github.wekite.preferences.WePrefs.Companion.prefOption
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.Button
import com.github.wekite.ui.content.DefaultColumn
import com.github.wekite.ui.content.FloatingBottomBar
import com.github.wekite.ui.content.FloatingBottomBarDefaults
import com.github.wekite.ui.content.FloatingBottomBarItem
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.content.rememberViewBackdrop
import com.github.wekite.ui.content.dialogSliderColors
import com.github.wekite.ui.content.dialogSwitchColors
import com.github.wekite.ui.content.dialogListItemColors
import com.github.wekite.ui.utils.InjectedUiTheme
import com.github.wekite.ui.utils.LifecycleOwnerProvider
import com.github.wekite.ui.utils.setLifecycleOwner
import com.github.wekite.ui.utils.showComposeDialog
import com.github.wekite.ui.utils.theme.SeedResolver
import com.github.wekite.ui.utils.theme.ThemeSettings
import com.github.wekite.utils.WeLogger
import com.github.wekite.utils.reflection.bool
import com.github.wekite.utils.reflection.int
import kotlin.math.roundToInt

@Feature(name = "美化首页底部导航栏", categories = ["界面美化"], description = "将首页底部导航栏替换为 Material Design 或 Backdrop 风格")
object ReplaceNavigationBar : ClickableFeature(), IResolveDex {

    private const val TAG = "ReplaceNavigationBar"

    private data class NavItem(
        val outlined: ImageVector,
        val filled: ImageVector,
        val label: String
    )

    @Stable
    private val TAB_ITEMS = listOf(
        NavItem(MaterialSymbols.Outlined.Home, MaterialSymbols.OutlinedFilled.Home, "主页"),
        NavItem(MaterialSymbols.Outlined.Contacts, MaterialSymbols.OutlinedFilled.Contacts, "通讯录"),
        NavItem(MaterialSymbols.Outlined.Explore, MaterialSymbols.OutlinedFilled.Explore, "发现"),
        NavItem(MaterialSymbols.Outlined.Person, MaterialSymbols.OutlinedFilled.Person, "我")
    )

    private var useFloating by prefOption("nav_bar_use_floating", false)
    private var useBackdrop by prefOption("nav_bar_use_backdrop", false)
    private var animatePageChange by prefOption("nav_bar_animate_page_change", true)
    private var showFinderBadge by prefOption("nav_bar_show_finder_badge", true)
    private var hideLabels by prefOption("nav_bar_hide_labels", false)
    private var blurRadius by prefOption("nav_bar_blur_radius", 8)

    private const val MIN_BLUR_RADIUS = 0
    private const val MAX_BLUR_RADIUS = 40

    // Matches the double-tap threshold WeChat's own tab listener (f8/r8) uses.
    private const val DOUBLE_TAP_WINDOW_MS = 300L

    override fun onEnable() {
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            val activity = thisObject!!.reflekt()
                .firstField {
                    type = "com.tencent.mm.ui.MMFragmentActivity"
                }
                .get()!! as Activity
            val viewPager = thisObject!!.reflekt()
                .firstField {
                    name = "mViewPager"
                }
                .get()!! as ViewGroup
            val tabsAdapter = thisObject!!.reflekt()
                .firstField {
                    name = "mTabsAdapter"
                }
                .get()!!
            val methodOnTabClick = tabsAdapter.reflekt()
                .firstMethod {
                    name = "onTabClick"
                }.self

            // 页面切换动画: 微信切 tab 时 WxViewPager.setCurrentItem 第二个参数是 smoothScroll,
            // 微信恒传 false (点击直接跳转), 翻成 true 即滑动切换。guard 只在用户点击我们
            // 底栏标签(即 navigateToTabAnimated)时生效, 避免首次布局/状态恢复路径也被翻成动画。
            val animatePageChangeEnabled = animatePageChange
            val programmaticTabChange = ThreadLocal.withInitial { false }
            val navigateToTabAnimated = { index: Int ->
                if (animatePageChangeEnabled) programmaticTabChange.set(true)
                try {
                    methodOnTabClick.invoke(tabsAdapter, index)
                } finally {
                    programmaticTabChange.remove()
                }
            }
            if (animatePageChangeEnabled) {
                "com.tencent.mm.ui.mogic.WxViewPager".toClass().reflekt().apply {
                    listOf("setCurrentItem", "setCurrentItemNotify").forEach { methodName ->
                        firstMethod {
                            name = methodName
                            parameters(int, bool)
                        }.hookBefore(priority = 100) {
                            if (programmaticTabChange.get() != true) return@hookBefore
                            args[1] = true
                        }
                    }
                }
            }

            val viewParent = viewPager.parent as ViewGroup

            // 查找微信原底栏: 与上游 wekit 一致, 底栏是 viewParent 的第 1 个子视图
            // (LauncherUIBottomTabView), 直接取该索引; 若布局改版导致索引取错
            // (非 ViewGroup / 越界), 回退按类名遍历查找。
            fun findBottomTabView(): ViewGroup? {
                val first = viewParent.getChildAt(1)
                if (first is ViewGroup) return first
                for (i in 0 until viewParent.childCount) {
                    val child = viewParent.getChildAt(i)
                    if (child != null && child.javaClass.name.contains("LauncherUIBottomTabView")) {
                        return child as? ViewGroup
                    }
                }
                return null
            }

            // 强制隐藏微信原底栏 (上游 wekit 实现): removeAllViews + GONE。
            val forceHideBottomTab = {
                val bottomTab = findBottomTabView()
                if (bottomTab != null) {
                    bottomTab.removeAllViews()
                    bottomTab.visibility = View.GONE
                }
            }

            // WeChat's original bottom tab (LauncherUIBottomTabView) is kept alive — we only
            // clear its children below — so its own OnClickListener (an `f8`/`r8` instance)
            // survives with its double-tap state machine and the LiveData event it fires.
            // Double-tapping the Chat tab makes that listener fire WeChat's "scroll to next
            // unread conversation" event, which MainUI already observes. We capture the
            // listener and replay two rapid clicks to reproduce that behaviour, so we don't
            // have to resolve the fully-obfuscated event class ourselves.
            val bottomTabClickListener = runCatching {
                findBottomTabView()?.reflekt()
                    ?.firstField { type = View.OnClickListener::class }
                    ?.get() as? View.OnClickListener
            }.getOrNull()
            val doubleTapProbeView = View(activity).apply { tag = 0 }

            var lastHomeTapUptime = 0L
            val onTabClicked = { index: Int ->
                if (index == 0 && bottomTabClickListener != null &&
                    SystemClock.uptimeMillis() - lastHomeTapUptime <= DOUBLE_TAP_WINDOW_MS
                ) {
                    // Second tap on the Chat tab within the double-tap window: drive WeChat's
                    // own listener twice so its internal timing check trips and fires the
                    // scroll-to-next-unread event.
                    bottomTabClickListener.onClick(doubleTapProbeView)
                    bottomTabClickListener.onClick(doubleTapProbeView)
                    lastHomeTapUptime = SystemClock.uptimeMillis()
                } else {
                    navigateToTabAnimated(index)
                    lastHomeTapUptime = if (index == 0) SystemClock.uptimeMillis() else 0L
                }
            }

            val lifecycleOwner = LifecycleOwnerProvider.lifecycleOwner
            findBottomTabView()?.setLifecycleOwner(lifecycleOwner)

            val selectedPageIndexState = mutableIntStateOf(0)
            val scrollOffsetState = mutableFloatStateOf(0f)
            // Settled page index: only advances once the pager comes to rest on a page
            // (positionOffset == 0). The floating bar highlights from this so the tab
            // change happens *after* the content stops in both directions. The raw
            // `position` above flips to the target the instant a backward swipe starts,
            // which would move the pill early; the NavigationBar branch still needs that
            // raw value for its scroll-driven color cross-fade.
            val settledPageIndexState = mutableIntStateOf(0)
            // Target page as soon as it's decided: immediately on a tab tap, and at the
            // half-way crossing during a finger swipe. Drives the discrete spring so a tap
            // still bulges + slides the pill instead of teleporting.
            val targetPageIndexState = mutableIntStateOf(0)
            // True only while the pager is being moved by a finger (SCROLL_STATE_DRAGGING),
            // through to the follow-on settle. A tab tap smooth-scrolls (SETTLING) without
            // ever passing through DRAGGING, so it stays false and takes the spring path.
            val isSwipingState = mutableStateOf(false)
            var pageDidDrag = false

            tabsAdapter.reflekt()
                .firstMethod { name = "onPageScrolled" }
                .hookBefore {
                    val position = args[0] as Int
                    val positionOffset = args[1] as Float

                    selectedPageIndexState.intValue = position
                    scrollOffsetState.floatValue = positionOffset
                    if (positionOffset == 0f) {
                        settledPageIndexState.intValue = position
                    }
                }

            tabsAdapter.reflekt()
                .firstMethod { name = "onPageSelected" }
                .hookBefore {
                    targetPageIndexState.intValue = args[0] as Int
                }

            tabsAdapter.reflekt()
                .firstMethod { name = "onPageScrollStateChanged" }
                .hookBefore {
                    when (args[0] as Int) {
                        1 -> { // DRAGGING: finger is moving the pager
                            pageDidDrag = true
                            isSwipingState.value = true
                        }

                        2 -> { // SETTLING: keep tracking only if this settle came from a drag
                            isSwipingState.value = pageDidDrag
                        }

                        else -> { // IDLE
                            isSwipingState.value = false
                            pageDidDrag = false
                        }
                    }
                }

            val useFloating = useFloating
            val useBackdrop = useBackdrop
            val showFinderBadge = showFinderBadge
            val hideLabels = hideLabels
            val blurRadius = blurRadius

            val composeView = ComposeView(activity).apply {
                setLifecycleOwner(lifecycleOwner)

                setContent {
                    InjectedUiTheme {
                        val view = LocalView.current

                        // Long-press "发现" tab to jump straight into the improved timeline.
                        val openImproveSnsTimeline = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            activity.startActivity(
                                Intent().setClassName(
                                    "com.tencent.mm",
                                    "com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI"
                                )
                            )
                        }

                        var selectedIndex by selectedPageIndexState
                        val settledIndex by settledPageIndexState
                        val targetIndex by targetPageIndexState
                        val unreadCount by unreadCountState
                        val finderUnreadCount by finderUnreadCountState
                        val showFinderDot by showFinderDotState
                        val contactUnreadCount by contactUnreadCountState

                        val isDark = isSystemInDarkTheme()
                        val backgroundColor = if (isDark) Color(0xFF191919) else Color(0xFFF7F7F7)
                        // 强调色与模块设置页保持一致: 自定义颜色开启时跟随用户主题
                        // (同 SeedResolver 逻辑), 否则用 miuix 默认蓝 (light 0xFF3482FF /
                        // dark 0xFF277AF7)。不能直接用 MaterialTheme.colorScheme.primary —
                        // 注入 UI 的 InjectedUiTheme 默认是微信绿, 与模块设置页的强调色不一致。
                        val activeColor = if (ThemeSettings.customColor) {
                            SeedResolver.materialScheme(SeedResolver.customSeed(activity, isDark), isDark).primary
                        } else {
                            if (isDark) Color(0xFF277AF7) else Color(0xFF3482FF)
                        }
                        val inactiveColor = if (isDark) Color(0xFF999999) else Color(0xFF181818)

                        if (!useFloating) {
                            val offset by scrollOffsetState
                            NavigationBar(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp),
                                containerColor = backgroundColor
                            ) {
                                TAB_ITEMS.forEachIndexed { index, item ->
                                    val isSelected = index == selectedIndex
                                    val isNext = index == selectedIndex + 1

                                    val tint = when {
                                        isSelected -> lerpColor(
                                            activeColor,
                                            inactiveColor,
                                            offset
                                        )

                                        isNext -> lerpColor(
                                            inactiveColor,
                                            activeColor,
                                            offset
                                        )

                                        else -> inactiveColor
                                    }

                                    val showFilled = if (offset < 0.5f) isSelected else isNext

                                    NavigationBarItem(
                                        selected = isSelected && offset < 0.5f,
                                        onClick = {
                                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                            onTabClicked(index)
                                        },
                                        modifier = if (index == 2) Modifier.onLongPress(openImproveSnsTimeline) else Modifier,
                                        icon = {
                                            BadgedBox(
                                                badge = {
                                                    if (index == 0 && unreadCount > 0) {
                                                        Badge(containerColor = Color(0xFFFF3B30)) {
                                                            Text(
                                                                if (unreadCount <= 99) unreadCount.toString() else "99+",
                                                                color = Color.White, fontSize = 10.sp
                                                            )
                                                        }
                                                    } else if (index == 1 && contactUnreadCount > 0) {
                                                        Badge(containerColor = Color(0xFFFF3B30)) {
                                                            Text(
                                                                if (contactUnreadCount <= 99) contactUnreadCount.toString() else "99+",
                                                                color = Color.White, fontSize = 10.sp
                                                            )
                                                        }
                                                    } else if (index == 2 && showFinderBadge) {
                                                        if (finderUnreadCount > 0) {
                                                            Badge(containerColor = Color(0xFFFF3B30)) {
                                                                Text(
                                                                    if (finderUnreadCount <= 99) finderUnreadCount.toString() else "99+",
                                                                    color = Color.White, fontSize = 10.sp
                                                                )
                                                            }
                                                        } else if (showFinderDot) {
                                                            Badge(containerColor = Color(0xFFFF3B30))
                                                        }
                                                    }
                                                }
                                            ) {
                                                Crossfade(
                                                    targetState = showFilled,
                                                    animationSpec = tween(200),
                                                    label = "navIcon"
                                                ) { filled ->
                                                    Icon(
                                                        imageVector = if (filled) item.filled else item.outlined,
                                                        contentDescription = item.label,
                                                        tint = tint
                                                    )
                                                }
                                            }
                                        },
                                        label = null,
                                        alwaysShowLabel = false,
                                        colors = NavigationBarItemDefaults.colors(
                                            indicatorColor = activeColor.copy(alpha = 0.15f),
                                            selectedIconColor = activeColor,
                                            unselectedIconColor = inactiveColor,
                                            selectedTextColor = activeColor,
                                            unselectedTextColor = inactiveColor
                                        )
                                    )
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                FloatingBottomBar(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = {},
                                        )
                                        .padding(
                                            bottom = 12.dp + WindowInsets.navigationBars.asPaddingValues()
                                                .calculateBottomPadding()
                                        ),
                                    // Telegram 风格: 横跨大部分屏幕宽度、更矮的悬浮胶囊
                                    fullWidth = true,
                                    // Spring target: on a tap this is the tapped tab, so the
                                    // pill bulges and slides across. During a swipe the gate
                                    // below hands position control to `progress` instead.
                                    selectedIndex = { targetIndex },
                                    // Drive the indicator from the pager's live fractional
                                    // scroll position so the pill tracks the content 1:1 in
                                    // both directions, like the non-floating bar's crossfade.
                                    progress = { selectedIndex + scrollOffsetState.floatValue },
                                    isTracking = { isSwipingState.value },
                                    onSelected = { navigateToTabAnimated(it) },
                                    // In glass mode the pill covers the selected tab and eats
                                    // the tap before the item's onClick can run, so tapping /
                                    // double-tapping the current tab (e.g. Home) would do
                                    // nothing. Route that tap through the same haptic + tab
                                    // handler the items use, restoring double-tap-to-next-unread.
                                    onTabReselected = { index ->
                                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                        onTabClicked(index)
                                    },
                                    // Long-pressing the "发现" tab while it is already selected:
                                    // the pill sits on top and eats the event, so the item's
                                    // onLongPress modifier never fires — forward it here instead.
                                    onTabReselectedLongPress = { index ->
                                        if (index == 2) openImproveSnsTimeline()
                                    },
                                    // Sample WeChat's real content (native ViewPager) into the
                                    // glass. rememberLayerBackdrop would only capture Compose
                                    // pixels, of which there are none behind this overlay bar.
                                    backdrop = rememberViewBackdrop(viewPager),
                                    tabsCount = TAB_ITEMS.size,
                                    isBlurEnabled = useBackdrop,
                                    blurRadius = blurRadius.dp,
                                    colors = FloatingBottomBarDefaults.colors(
                                        containerColor = backgroundColor,
                                        indicatorColor = activeColor,
                                        contentColor = inactiveColor,
                                        activeContentColor = activeColor
                                    )
                                ) {
                                    TAB_ITEMS.forEachIndexed { index, item ->
                                        val isSelected = index == settledIndex

                                        FloatingBottomBarItem(
                                            onClick = {
                                                view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                                onTabClicked(index)
                                            },
                                            modifier = Modifier
                                                .then(if (index == 2) Modifier.onLongPress(openImproveSnsTimeline) else Modifier)
                                                .defaultMinSize(minWidth = 76.dp)
                                        ) {
                                            BadgedBox(
                                                badge = {
                                                    if (index == 0 && unreadCount > 0) {
                                                        Badge(containerColor = Color(0xFFFF3B30)) {
                                                            Text(
                                                                if (unreadCount <= 99) unreadCount.toString() else "99+",
                                                                color = Color.White, fontSize = 10.sp
                                                            )
                                                        }
                                                    } else if (index == 1 && contactUnreadCount > 0) {
                                                        Badge(containerColor = Color(0xFFFF3B30)) {
                                                            Text(
                                                                if (contactUnreadCount <= 99) contactUnreadCount.toString() else "99+",
                                                                color = Color.White, fontSize = 10.sp
                                                            )
                                                        }
                                                    } else if (index == 2 && showFinderBadge) {
                                                        if (finderUnreadCount > 0) {
                                                            Badge(containerColor = Color(0xFFFF3B30)) {
                                                                Text(
                                                                    if (finderUnreadCount <= 99) finderUnreadCount.toString() else "99+",
                                                                    color = Color.White, fontSize = 10.sp
                                                                )
                                                            }
                                                        } else if (showFinderDot) {
                                                            Badge(containerColor = Color(0xFFFF3B30))
                                                        }
                                                    }
                                                }
                                            ) {
                                                Crossfade(
                                                    targetState = isSelected,
                                                    animationSpec = tween(200),
                                                    label = "navIconFloating"
                                                ) { selected ->
                                                    Icon(
                                                        imageVector = if (selected) item.filled else item.outlined,
                                                        contentDescription = item.label
                                                    )
                                                }
                                            }
                                            if (!hideLabels) {
                                                Text(
                                                    text = item.label,
                                                    fontSize = 11.sp,
                                                    lineHeight = 14.sp,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Visible
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (useFloating) {
                // In floating mode, hide the original tab bar container so that WeChat's
                // FrostedContentView reads its height as 0 and doesn't draw a frosted grey
                // overlay behind it. Instead, attach the ComposeView directly to the parent
                // FrameLayout as an overlay on top of the content.
                forceHideBottomTab()

                // The pill scales up (press bulge ~1.39x plus velocity overshoot) via a
                // graphicsLayer, so it draws beyond the ComposeView's WRAP_CONTENT bounds.
                // The bottom overdraw lands in the padding/inset gap, but the top overdraw
                // extends above the ComposeView and would be clipped by the Android view
                // hierarchy. Disable child/padding clipping on the parent so it renders.
                viewParent.clipChildren = false
                viewParent.clipToPadding = false
                composeView.clipChildren = false
                composeView.clipToPadding = false

                viewParent.addView(
                    composeView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        Gravity.BOTTOM
                    )
                )

                // 主页下拉小程序面板时, 悬浮底栏跟随下滑隐藏。
                // 算法移植自真机验证可用的第三方实现「低栏美化 1.0.22」(dev.floatbar):
                // 不猜微信意图 (v1.44 事件派失败), 也不做绝对几何判断 (v1.39/v1.42 误伤),
                // 而是 200ms 轮询读取**下拉容器 (类名含 pulldown) 子树的相对基线变化**。
                installPullDownHideWatcher(activity, composeView) {
                    selectedPageIndexState.intValue
                }
            } else {
                val bottomTab = findBottomTabView()
                if (bottomTab != null) {
                    bottomTab.removeAllViews()
                    bottomTab.addView(composeView)
                } else {
                    viewParent.addView(
                        composeView,
                        FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.WRAP_CONTENT,
                            Gravity.BOTTOM
                        )
                    )
                }
            }

          // Suppress FrostedContentView's bottom blur overlay in floating mode.
          //
          // In WeChat 8.0.69, MainUI.q0() (onResume) calls:
          //   frostedContentView.a(true, tabBar.getHeight())
          // synchronously during doOnCreate — before our hookAfter fires and
          // sets the tab bar to GONE. By that point bottomBlurAreaHeight is
          // already set to the real measured height. Worse, a() has a <= 0
          // fallback: if height is 0 it computes dimen.b2*density + nav_bar_height,
          // producing the short frosted-glass strip you see below our bar.
          // Hooking a() and forcing its first arg (frostedEnabled) to false is the
          // only reliable fix regardless of call timing.
          //
          // v1.31 scope guard — 两条防线, 零误伤:
          //   FrostedContentView 也被其他页面使用 (我页卡片背景、主页下拉小程序
          //   面板背景)。v1.3 用 ctx === activity 兜底命中首页毛玻璃条, 但下拉
          //   面板与主页共享同一 activity 实例, 其毛玻璃背景被一并禁用 → 面板
          //   黑屏 (禁用后先显示缓存帧、渲染停更后露出黑底, 即"过段时间变黑")。
          //   v1.4 改用高度参数过滤, 但 Play 版首页毛玻璃条真实高度参数不在预期
          //   区间 → 首页毛玻璃条残留 (双底栏) 且面板动画中间态仍可能被误伤。
          //   弃用 activity/高度参数判断, 改为:
          //   防线1 视图树归属: 只抑制挂在主页内容容器 (viewParent) 树内的实例;
          //         下拉面板是覆盖层, 绝不在 MainTabUI 内容容器树内 → 永不误伤。
          //   防线2 实例名单: doOnCreate 后遍历主页视图树收集 FrostedContentView
          //         实例 (此刻面板尚未创建, 名单只含首页毛玻璃条), a() 调用时实例
          //         在名单内即抑制; 覆盖毛玻璃条挂在内容容器树外的布局差异。
          val homeFrostedInstances = java.util.Collections.newSetFromMap(
              java.util.WeakHashMap<View, Boolean>()
          )
          activity.window.decorView.post {
              fun collectFrosted(v: View) {
                  if (v.javaClass.name.contains("FrostedContentView")) {
                      homeFrostedInstances.add(v)
                  }
                  if (v is ViewGroup) {
                      for (i in 0 until v.childCount) collectFrosted(v.getChildAt(i))
                  }
              }
              collectFrosted(activity.window.decorView)
              WeLogger.i(TAG, "home frosted instances collected: ${homeFrostedInstances.size}")
          }

          "com.tencent.mm.ui.FrostedContentView".toClass().firstMethod {
              parameters { it[0] == bool && it[1] == int }
          }.hookBefore {
              // 实时读开关 (不捕获 doOnCreate 时刻的旧值)
              if (ReplaceNavigationBar.useFloating) {
                  val view = thisObject as? View
                  if (view != null) {
                      var parent: android.view.ViewParent? = view.parent
                      var inHomeContainerTree = false
                      while (parent != null) {
                          if (parent === viewParent) {
                              inHomeContainerTree = true
                              break
                          }
                          parent = parent.parent
                      }
                      if (inHomeContainerTree || view in homeFrostedInstances) {
                          args[0] = false
                      }
                  }
              }
          }
        }

        methodUpdateTabUnread.hookBefore {
            val count = args[0] as Int
            unreadCountState.intValue = count
            result = null
        }

        methodUpdateFriendTabUnread.hookBefore {
            val count = args[0] as Int
            finderUnreadCountState.intValue = count
            result = null
        }

        methodShowFriendPoint.hookBefore {
            val show = args[0] as Boolean
            showFinderDotState.value = show
            result = null
        }

        methodUpdateContactTabUnread.hookBefore {
            val count = args[0] as Int
            contactUnreadCountState.intValue = count
            result = null
        }

    }
    /**
     * 悬浮底栏跟随主页下拉小程序面板隐藏。
     *
     * 算法移植自真机验证可用的第三方模块「低栏美化 1.0.22」(`dev.floatbar`)。要点:
     *  - **锚点**: 递归查找类名 (lowercase) 含 `pulldown` 的 ViewGroup —— 这才是下拉面板的
     *    真实挂载点 (v1.52 扫 decorView 直接子视图找 translationY 因此没有效果)。
     *  - **判据 (相对基线, 不做绝对几何判断)**:
     *      1. 面板位移: 面板子树中高度 ≥ 1/4 屏高的 View, 其 `getLocationOnScreen()[1]`
     *         比历史最小值 (= 收起态) 下移超 8% 屏高 → 面板已被拉下。
     *      2. 兜底: 面板容器 `scrollY > 50dp` (弹性滚动实现的面板)。
     *  - **基线取历史最小 top** 而非一次性快照: 即使首次发现容器时面板恰好已展开也不会
     *    把展开态错当基线, 收起一次即自校正。
     *  - **恢复兜底天然存在**: 200ms 轮询读真值, 面板消失/收起必然被下一轮看到 →
     *    不存在 v1.44 那种事件丢失卡死导致底栏永久消失的可能。
     *  - **失败安全**: 找不到面板容器就永不隐藏 (退化成当前行为), 绝不误隐藏。
     */
    private fun installPullDownHideWatcher(
        activity: Activity,
        dock: View,
        currentTabIndex: () -> Int
    ) {
        val metrics = activity.resources.displayMetrics
        val screenH = metrics.heightPixels
        if (screenH <= 0) return
        val density = metrics.density
        val scrollThresholdPx = (50f * density).toInt()
        // 位移阈值 30% 屏高(真机 screenH=2800 → 840px)。真机日志实测面板完全展开时
        // shift 达 1300~1800px, 故 840 不是「快滑不生效」的原因, 保持不变。
        val shiftThresholdPx = screenH * 30 / 100
        val handler = android.os.Handler(android.os.Looper.getMainLooper())

        var pullDownRef: java.lang.ref.WeakReference<ViewGroup>? = null
        // 每个候选 View 独立维护: 基线 top(收起时的最高位置) / 采样时高度 / 本轮 top / 连续越界次数。
        // v1.95 只有 minTop(全局一张表), 面板内可复用 View 被换到更靠下位置时会带着旧基线,
        // top-base 一上来就越界 → 轻轻一拉就隐藏; 开启圆角头像后头像 Hook 加剧重排, 复现率大幅上升。
        // v1.97: 防复用污染改由「基线 top < 0 的候选才参与判定」承担(真机 panelLike=1/9),
        // 不再用「位移跳变」做重排信号 —— 那会把快速下拉误判为重排并重设基线, 导致快滑不生效。
        val baseTop = java.util.WeakHashMap<View, Int>()
        val baseHeight = java.util.WeakHashMap<View, Int>()
        val curTop = java.util.WeakHashMap<View, Int>()
        val overCount = java.util.WeakHashMap<View, Int>()
        var loggedFound = false
        var loggedMissing = false
        var loggedNoPanelLike = false
        var pollCount = 0
        var hidden = false

        fun findPullDown(v: View, depth: Int): ViewGroup? {
            if (depth > 12 || v !is ViewGroup) return null
            if (v.javaClass.name.lowercase().contains("pulldown") && v.childCount > 0) return v
            for (i in 0 until v.childCount) {
                findPullDown(v.getChildAt(i), depth + 1)?.let { return it }
            }
            return null
        }

        fun collectCandidates(v: View, depth: Int, out: MutableList<View>) {
            if (depth > 4) return
            if (v.visibility == View.VISIBLE && v.isShown && v.height >= screenH / 4) out.add(v)
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) collectCandidates(v.getChildAt(i), depth + 1, out)
            }
        }

        fun applyHidden(hide: Boolean) {
            if (hide == hidden) return
            hidden = hide
            dock.animate().cancel()
            if (hide) {
                val h = if (dock.height > 0) dock.height else (96f * density).toInt()
                val dy = h + 24f * density
                dock.animate()
                    .translationY(dy)
                    .alpha(0f)
                    .setDuration(260)
                    .withEndAction { if (hidden) dock.visibility = View.INVISIBLE }
                    .start()
            } else {
                dock.visibility = View.VISIBLE
                dock.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(260)
                    .start()
            }
            WeLogger.i(TAG, "floating dock ${if (hide) "hidden" else "restored"} (pull-down watcher)")
        }

        val poll = object : Runnable {
            override fun run() {
                if (activity.isFinishing || activity.isDestroyed) return
                runCatching {
                    pollCount++
                    var panel = pullDownRef?.get()
                    if (panel == null || !panel.isAttachedToWindow) {
                        panel = findPullDown(activity.window.decorView, 0)
                        pullDownRef = panel?.let { java.lang.ref.WeakReference(it) }
                        if (panel != null && !loggedFound) {
                            loggedFound = true
                            WeLogger.i(
                                TAG,
                                "pull-down container found: ${panel.javaClass.name} children=${panel.childCount}"
                            )
                        }
                    }

                    // 非首页 tab / 未找到面板容器 → 永不隐藏
                    if (panel == null || currentTabIndex() != 0) {
                        // 诊断: 15s 后仍找不到面板容器时打一次窗口顶层子视图类名, 便于真机排查
                        if (panel == null && !loggedMissing && pollCount >= 75) {
                            loggedMissing = true
                            val root = activity.window.decorView as? ViewGroup
                            val names = buildString {
                                if (root != null) {
                                    for (i in 0 until root.childCount) {
                                        append(root.getChildAt(i)?.javaClass?.simpleName ?: "null")
                                        append(' ')
                                    }
                                }
                            }
                            WeLogger.i(TAG, "pull-down container NOT found; decor children: $names")
                        }
                        applyHidden(false)
                        return@runCatching
                    }

                    var shouldHide = panel.scrollY > scrollThresholdPx

                    if (!shouldHide) {
                        val candidates = ArrayList<View>(8)
                        collectCandidates(panel, 0, candidates)
                        val loc = IntArray(2)

                        // 第一遍: 采样 + 维护每个候选的基线。
                        // 只在「首次见到 / 高度变化(重排或 View 复用) / 出现更高位置」时重设基线。
                        // ⚠️ v1.96 曾额外把「位移跳变 > 1/4 屏高」当作重排信号重设基线 —— 那正是
                        // 「快速下拉不生效」的根因: 快速下拉单次采样位移轻易超过 1/4 屏高, 基线被
                        // 重设到已下拉的位置使 shift 归零, 面板随后停在展开位不再移动, 本次手势
                        // 再也无法越界。防 View 复用污染已由第二遍的 panelLike 过滤承担, 故移除。
                        for (c in candidates) {
                            c.getLocationOnScreen(loc)
                            val top = loc[1]
                            val h = c.height
                            curTop[c] = top
                            val base = baseTop[c]
                            if (base == null || baseHeight[c] != h || top < base) {
                                baseTop[c] = top
                                baseHeight[c] = h
                                overCount[c] = 0
                            }
                        }

                        // 第二遍: 只用「收起时位于视口上方」(基线 top < 0) 的候选判定 —— 这是下拉面板的
                        // 定义特征。主页会话列表基线 top >= 0 被排除, 于是「圆角头像」Hook 每次加载头像
                        // 引起的列表重排/View 复用不会污染判定(真机实测 panelLike=1/9, 过滤器有效)。
                        // 一个都没有时不做几何判定(宁可不隐藏, 也不拿会话列表冒误触发风险)。
                        val panelLike = candidates.filter { (baseTop[it] ?: 0) < 0 }
                        if (panelLike.isEmpty() && !loggedNoPanelLike) {
                            loggedNoPanelLike = true
                            WeLogger.i(
                                TAG,
                                "no panel-like candidate (all baselines >= 0); geometry judge idle, candidates=${candidates.size}"
                            )
                        }

                        for (c in panelLike) {
                            val top = curTop[c] ?: continue
                            val base = baseTop[c] ?: continue
                            if (top - base <= shiftThresholdPx) {
                                overCount[c] = 0
                                continue
                            }
                            // 连续 2 次采样越界(轮询 120ms → ≈240ms)才认, 过滤单帧抖动。
                            // v1.96 要求 3 次 @200ms = 600ms, 快速手势根本凑不够帧数。
                            val n = (overCount[c] ?: 0) + 1
                            overCount[c] = n
                            if (n >= 2) {
                                shouldHide = true
                                // 诊断: 记录触发时真实几何, 手感不对时据此调阈值(别再靠猜)
                                WeLogger.i(
                                    TAG,
                                    "pull-down hide trigger: top=$top base=$base shift=${top - base} thr=$shiftThresholdPx screenH=$screenH panelLike=${panelLike.size}/${candidates.size}"
                                )
                                break
                            }
                        }
                    }

                    applyHidden(shouldHide)
                }
                handler.postDelayed(this, 120)
            }
        }
        handler.postDelayed(poll, 600)
    }

    private val unreadCountState = mutableIntStateOf(0)
    private val finderUnreadCountState = mutableIntStateOf(0)
    private val showFinderDotState = mutableStateOf(false)
    private val contactUnreadCountState = mutableIntStateOf(0)

    /**
     * Non-consuming long-press modifier. Fires [block] when the pointer is held down long enough,
     * but does **not** consume the down/up events, so the item's own tap ripple and onClick still work.
     */
    private fun Modifier.onLongPress(block: () -> Unit): Modifier = pointerInput(block) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            block()
        }
    }

    private fun lerpColor(start: Color, stop: Color, fraction: Float): Color {
        val f = fraction.coerceIn(0f, 1f)
        return Color(
            red = start.red + (stop.red - start.red) * f,
            green = start.green + (stop.green - start.green) * f,
            blue = start.blue + (stop.blue - start.blue) * f,
            alpha = start.alpha + (stop.alpha - start.alpha) * f
        )
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var useFloatingInput by remember { mutableStateOf(useFloating) }
            var useBackdropInput by remember { mutableStateOf(useBackdrop) }
            var animatePageChangeInput by remember { mutableStateOf(animatePageChange) }
            var showFinderBadgeInput by remember { mutableStateOf(showFinderBadge) }
            var hideLabelsInput by remember { mutableStateOf(hideLabels) }
            var blurRadiusInput by remember { mutableFloatStateOf(blurRadius.toFloat()) }

            AlertDialogContent(
                title = { Text("美化首页底部导航栏") },
                text = {
                    DefaultColumn {
                        ListItem(
                            colors = dialogListItemColors(),
                            trailingContent = {
                                Switch(
                                    animatePageChangeInput,
                                    { animatePageChangeInput = it }, colors = dialogSwitchColors())
                            },
                            supportingContent = { Text("点击标签时滑动切换页面, 而非直接跳转") },
                            headlineContent = { Text("启用页面切换动画") },
                        )
                        ListItem(
                            colors = dialogListItemColors(),
                        modifier = Modifier.height(48.dp),
                        trailingContent = {
                                Switch(
                                    useFloatingInput,
                                    { useFloatingInput = it }, colors = dialogSwitchColors())
                            },
                            headlineContent = { Text("使用悬浮底栏") },
                        )
                        ListItem(
                            colors = dialogListItemColors(),
                        trailingContent = {
                                Switch(
                                    useBackdropInput,
                                    { useBackdropInput = it }, colors = dialogSwitchColors())
                            },
                            supportingContent = { Text("需启用「使用悬浮底栏」") },
                            headlineContent = { Text("启用液态玻璃效果") },
                        )
                        if (useBackdropInput) {
                            ListItem(
                                colors = dialogListItemColors(),
                            supportingContent = {
                                    Slider(
                                        value = blurRadiusInput,
                                        onValueChange = { blurRadiusInput = it },
                                        valueRange = MIN_BLUR_RADIUS.toFloat()..MAX_BLUR_RADIUS.toFloat(),
                                        steps = MAX_BLUR_RADIUS - MIN_BLUR_RADIUS - 1
                                    , colors = dialogSliderColors())
                                },
                                headlineContent = {
                                    val r = blurRadiusInput.roundToInt()
                                    Text(if (r <= 0) "模糊半径: 关闭 (完全透明)" else "模糊半径: $r")
                                },
                            )
                        }
                        ListItem(
                            colors = dialogListItemColors(),
                        trailingContent = {
                                Switch(
                                    hideLabelsInput,
                                    { hideLabelsInput = it }, colors = dialogSwitchColors())
                            },
                            supportingContent = { Text("需启用「使用悬浮底栏」") },
                            headlineContent = { Text("隐藏标签文本") },
                        )
                        ListItem(
                            colors = dialogListItemColors(),
                            leadingContent = null,
                            trailingContent = {
                                Switch(
                                    showFinderBadgeInput,
                                    { showFinderBadgeInput = it }, colors = dialogSwitchColors())
                            },
                            supportingContent = { Text("包含朋友圈新通知数量等") },
                            headlineContent = { Text("显示「发现」标签角标") },
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        useFloating = useFloatingInput
                        useBackdrop = useBackdropInput
                        animatePageChange = animatePageChangeInput
                        hideLabels = hideLabelsInput
                        showFinderBadge = showFinderBadgeInput
                        blurRadius = blurRadiusInput.roundToInt()
                        onDismiss()
                    }) { Text("确定") }
                }
            )
        }
    }

    private val methodUpdateTabUnread by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("MicroMsg.LauncherUITabView", "updateMainTabUnread %d")
        }
    }

    private val methodUpdateFriendTabUnread by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("[updateFriendTabUnread] unread : ")
        }
    }

    private val methodShowFriendPoint by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("[showFriendPoint] show : ")
        }
    }

    private val methodUpdateContactTabUnread by dexMethod {
        matcher {
            declaredClass = "com.tencent.mm.ui.LauncherUIBottomTabView"
            usingEqStrings("[updateContactTabUnread] unread : ")
        }
    }
}
