package com.github.wekite.features.items.beautify

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
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

            val navigateToTab = { index: Int -> methodOnTabClick.invoke(tabsAdapter, index) }

            val viewParent = viewPager.parent as ViewGroup

            // 查找微信原底栏 (LauncherUIBottomTabView)。不用 getChildAt(1) 硬编码:
            // 微信在切换主 tab 时会重建底栏实例 (removeView 旧实例 + addView 新实例),
            // 固定引用会失效, 导致 removeAllViews/GONE 操作的是已脱离视图树的旧实例,
            // 新实例依旧显示 → 通讯录/发现/我页面残留原底栏。
            fun findBottomTabView(): ViewGroup? {
                for (i in 0 until viewParent.childCount) {
                    val child = viewParent.getChildAt(i)
                    if (child != null && child.javaClass.name.contains("LauncherUIBottomTabView")) {
                        return child as? ViewGroup
                    }
                }
                return null
            }

            val isBottomTabView: (Any?) -> Boolean = { obj ->
                obj != null && obj.javaClass.name.contains("LauncherUIBottomTabView")
            }

            // 强制隐藏微信原底栏。微信在切换主 tab 时会重新 setVisibility(VISIBLE)
            // 并重绘自绘内容, 导致通讯录/发现/我页面残留原底栏; 这里在每次 tab
            // 切换时再清一次 (类名查找, 覆盖重建实例), 并多层 hook 防止微信恢复显示。
            val forceHideBottomTab = {
                val bottomTab = findBottomTabView()
                if (bottomTab != null) {
                    bottomTab.removeAllViews()
                    bottomTab.visibility = View.GONE
                }
            }

            // 防线 1: hook View.setVisibility, 用类名而非实例引用匹配, 覆盖微信
            // 重建底栏实例的情况 (v1.0 用 thisObject === 固定实例, 重建后失效)。
            View::class.java.getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType)
                .hookBefore {
                    if (useFloating && isBottomTabView(thisObject)) {
                        args[0] = View.GONE
                    }
                }

            // 防线 2: LauncherUIBottomTabView 若重写了 setVisibility 且不调 super,
            // 防线 1 拦不到, 这里直接 hook 子类方法兜底 (未重写则 getDeclaredMethod
            // 抛 NoSuchMethodException, runCatching 静默跳过)。
            runCatching {
                "com.tencent.mm.ui.LauncherUIBottomTabView".toClass()
                    .getDeclaredMethod("setVisibility", Int::class.javaPrimitiveType)
            }.onSuccess { method ->
                method.hookBefore {
                    if (useFloating) args[0] = View.GONE
                }
            }

            // 防线 3: 微信重建底栏时走 addView(新实例), 新实例默认 VISIBLE 且不触发
            // setVisibility, 在 addView 后立即隐藏。
            //
            // v1.1 只 hook 了 addView(View, LayoutParams) 双参重载——这是致命的:
            // Android 的公开重载中, 单参 addView(View) 转调 addView(View, int),
            // 索引版 addView(View, int) 转调 addView(View, int, LayoutParams),
            // 双参 addView(View, LayoutParams) 也转调三参版, 全部汇聚到
            // addView(View, int, LayoutParams) 与私有的 addViewInner。微信切换主
            // tab 重建底栏若走单参/索引版重载, 双参 hook 根本不会被触发。
            // 因此改为 hook 三参汇聚点 + 更底层的 addViewInner, 双保险。
            val hideRebuiltBottomTab = { view: Any? ->
                if (useFloating && isBottomTabView(view)) {
                    WeLogger.i(TAG, "bottom tab re-added (addView/addViewInner), hiding")
                    (view as? ViewGroup)?.let {
                        it.removeAllViews()
                        it.visibility = View.GONE
                    }
                }
            }
            // 三参 addView — 单参/索引参/双参重载的公共汇聚点 (API 1+)
            runCatching {
                ViewGroup::class.java.getMethod(
                    "addView", View::class.java, Int::class.javaPrimitiveType,
                    ViewGroup.LayoutParams::class.java
                )
            }.onSuccess { method ->
                method.hookAfter { hideRebuiltBottomTab(args[0]) }
            }
            // addViewInner — 所有 addView 的最终入口 (API 28+ 私有方法)
            runCatching {
                ViewGroup::class.java.getDeclaredMethod(
                    "addViewInner", View::class.java, Int::class.javaPrimitiveType,
                    ViewGroup.LayoutParams::class.java, Boolean::class.javaPrimitiveType
                )
            }.onSuccess { method ->
                method.hookAfter { hideRebuiltBottomTab(args[0]) }
            }
            // 保留双参 hook: 极少数 ROM 重写双参版时单走它
            runCatching {
                ViewGroup::class.java.getMethod(
                    "addView", View::class.java, ViewGroup.LayoutParams::class.java
                )
            }.onSuccess { method ->
                method.hookAfter { hideRebuiltBottomTab(args[0]) }
            }

            // 防线 4: 微信若把底栏 bringToFront 到悬浮条上层 (z-order 恢复),
            // 拦截并重新隐藏。底栏 GONE 后 bringToFront 不影响显示, 此处防御
            // 微信「先恢复可见性再提层」的组合操作。
            runCatching {
                View::class.java.getMethod("bringToFront")
            }.onSuccess { method ->
                method.hookAfter {
                    if (useFloating && isBottomTabView(thisObject)) {
                        WeLogger.i(TAG, "bottom tab brought to front, hiding again")
                        (thisObject as? ViewGroup)?.let {
                            it.removeAllViews()
                            it.visibility = View.GONE
                        }
                    }
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
                    navigateToTab(index)
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
                    // 微信切换主 tab 时会恢复原底栏显示, 这里再次强制隐藏
                    if (useFloating) forceHideBottomTab()
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
                                    onSelected = { navigateToTab(it) },
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

                // 防线 5: 轮询兜底。以上防线都依赖「类名含 LauncherUIBottomTabView」,
                // 若微信改版 / Play 版混淆导致类名变化, 防线 1/3/4 全部静默失效
                // (防线 2 的 toClass 抛异常被 runCatching 吞掉)。这里每 200ms 扫描
                // viewParent 的子视图, 用形态特征 (底部对齐 + 宽度接近父宽 + 高度在
                // 微信底栏量级) 识别底栏, 不依赖任何类名, 持续强制隐藏。遍历对象只
                // 有一个 FrameLayout 的 4~6 个子视图, 开销可忽略; activity 销毁后
                // viewParent detached, 轮询自动停止。
                val metrics = activity.resources.displayMetrics
                val minBarHeightPx = (30f * metrics.density).toInt()
                val maxBarHeightPx = (110f * metrics.density).toInt()
                val isBottomBarLike: (View) -> Boolean = { v ->
                    val name = v.javaClass.name
                    if (name.contains("LauncherUIBottomTabView") || name.contains("BottomTab")) {
                        true
                    } else {
                        val bottomAligned = (v.layoutParams as? FrameLayout.LayoutParams)
                            ?.let { lp -> (lp.gravity and Gravity.BOTTOM) != 0 } ?: false
                        // height 上限排除占满高度的 mViewPager, 宽度下限排除小挂件
                        bottomAligned &&
                            v.height in minBarHeightPx..maxBarHeightPx &&
                            v.width >= viewParent.width / 2
                    }
                }
                val handler = Handler(Looper.getMainLooper())
                val hideRunnable = object : Runnable {
                    override fun run() {
                        if (!viewParent.isAttachedToWindow) return
                        var hiddenAny = false
                        for (i in 0 until viewParent.childCount) {
                            val child = viewParent.getChildAt(i) ?: continue
                            if (child === composeView) continue
                            if (isBottomBarLike(child)) {
                                hiddenAny = true
                                if (child.visibility != View.GONE) child.visibility = View.GONE
                                if (child is ViewGroup) child.removeAllViews()
                            }
                        }
                        if (hiddenAny) WeLogger.v(TAG, "polling: kept bottom tab hidden")
                        handler.postDelayed(this, 200L)
                    }
                }
                handler.post(hideRunnable)
                WeLogger.i(TAG, "floating bottom bar defenses armed (5 lines)")
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
        // Scope guard: FrostedContentView is also used by other pages (e.g. the
        // frosted card backgrounds on the "我" page). Disabling the frost on
        // every instance made those card backgrounds render broken/misaligned,
        // so only suppress the instance owned by the home tab activity.
        "com.tencent.mm.ui.FrostedContentView".toClass().firstMethod {
            parameters { it[0] == bool && it[1] == int }
        }.hookBefore {
            if (useFloating) {
                val view = thisObject as? View
                if (view != null) {
                    var ctx = view.context
                    while (ctx is android.content.ContextWrapper) {
                        ctx = ctx.baseContext
                    }
                    val className = ctx.javaClass.name
                    if (className.contains("LauncherUI") || className.contains("MainUI")) {
                        args[0] = false
                    }
                }
            }
        }
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
            var showFinderBadgeInput by remember { mutableStateOf(showFinderBadge) }
            var hideLabelsInput by remember { mutableStateOf(hideLabels) }
            var blurRadiusInput by remember { mutableFloatStateOf(blurRadius.toFloat()) }

            AlertDialogContent(
                title = { Text("美化首页底部导航栏") },
                text = {
                    DefaultColumn {
                        ListItem(
                            trailingContent = {
                                Switch(
                                    useFloatingInput,
                                    { useFloatingInput = it })
                            },
                            headlineContent = { Text("使用悬浮底栏") },
                        )
                        ListItem(
                            trailingContent = {
                                Switch(
                                    useBackdropInput,
                                    { useBackdropInput = it })
                            },
                            supportingContent = { Text("需启用「使用悬浮底栏」") },
                            headlineContent = { Text("启用液态玻璃效果") },
                        )
                        if (useBackdropInput) {
                            ListItem(
                                supportingContent = {
                                    Slider(
                                        value = blurRadiusInput,
                                        onValueChange = { blurRadiusInput = it },
                                        valueRange = MIN_BLUR_RADIUS.toFloat()..MAX_BLUR_RADIUS.toFloat(),
                                        steps = MAX_BLUR_RADIUS - MIN_BLUR_RADIUS - 1
                                    )
                                },
                                headlineContent = {
                                    val r = blurRadiusInput.roundToInt()
                                    Text(if (r <= 0) "模糊半径: 关闭 (完全透明)" else "模糊半径: $r")
                                },
                            )
                        }
                        ListItem(
                            trailingContent = {
                                Switch(
                                    hideLabelsInput,
                                    { hideLabelsInput = it })
                            },
                            supportingContent = { Text("需启用「使用悬浮底栏」") },
                            headlineContent = { Text("隐藏标签文本") },
                        )
                        ListItem(
                            modifier = Modifier,
                            leadingContent = null,
                            trailingContent = {
                                Switch(
                                    showFinderBadgeInput,
                                    { showFinderBadgeInput = it })
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
