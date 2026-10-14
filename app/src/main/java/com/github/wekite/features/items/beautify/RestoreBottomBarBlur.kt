package com.github.wekite.features.items.beautify

import android.view.View
import android.view.ViewGroup
import com.github.wekite.features.api.ui.WeMainActivityBeautifyApi
import com.github.wekite.features.core.Feature
import com.github.wekite.features.core.SwitchFeature
import com.github.wekite.utils.WeLogger
import com.github.wekite.utils.reflection.bool
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import java.util.Collections
import java.util.WeakHashMap

/**
 * 恢复首页底栏模糊（8.0.79 丢失的微信原生效果）。
 *
 * 取证定案（references/wechat-8079-adaptation.md 第八节，字节级对比 8.0.77/8.0.79）：
 * - 底栏模糊由 `LauncherUIBottomTabView::onLayout` 调 `FrostedContentView::a(ZIF)` 驱动，
 *   首次 enable 由 `MMActivityController::c` 装配段 `setFrostedEnabled(true)` 完成——两版都在；
 * - 8.0.79 把 `MainUI::o0`(onTabResume) 末尾那条
 *   `findViewById→getRootView→findViewById→getHeight→getTranslationY→FrostedContentView::a` 刷新链
 *   **整体删除**（79 的 y0 里 getTranslationY 0 次）；`FrostedContentView::a` 的全包调用者从 6 处掉到 1 处
 *   （只剩 onLayout）。结果：装配后模糊区域不刷新，实际表现为底栏无模糊。
 *
 * 修法（探测→缺失才补→幂等，参照 ReplaceNavigationBar 的成熟模式）：
 * 1. hook `MainTabUI::doOnCreate`（WeMainActivityBeautifyApi.methodDoOnCreate，8.0.79 验证存活
 *    → classes9 MainTabUI::d()V）after：遍历 decorView 收集 FrostedContentView 实例名单
 *    （此刻仅首页底栏一个，下拉小程序面板尚未创建）；
 * 2. hook `FrostedContentView::a(ZIF)` before：仅当 (a) 实例在首页名单内（v1.31 scope guard 教训：
 *    下拉面板共享同一 activity，只能按实例名单判断，不能按 activity/高度参数），
 *    (b) `getFrostedEnabled()==false`（原生联动缺失）时，先 `setFrostedEnabled(true)` 再放行——
 *    补的是原生缺失的那一下联动，绘制仍走微信自己的 a(ZIF)；
 * 3. 若探测到 enabled 已为 true（微信某版自行恢复联动 / 其它模块已处理），记名退位、零干预，
 *    并打一行 `native blur already active, module idle`。
 *
 * 注意：不能无条件 args[0]=true——「我」页等其它 FrostedContentView 使用者（MoreTabUI 等）
 * 有自己的显隐逻辑，乱置 true 会在它们隐藏时强画模糊。
 */
@Feature(
    name = "恢复底栏模糊",
    categories = ["界面美化"],
    description = "微信 8.0.79 起首页底栏的原生模糊效果失效，开启后由模块补回刷新联动；若宿主版本已自行恢复则本开关空转"
)
object RestoreBottomBarBlur : SwitchFeature() {

    private const val TAG = "RestoreBottomBarBlur"

    /** 首页 FrostedContentView 实例名单（doOnCreate 后收集，弱引用；v1.31 scope guard 同款防线） */
    private val homeFrostedInstances = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /** 已确认「原生联动健在」的实例——记名后跳过干预（幂等退位） */
    private val nativeActiveInstances = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    override fun onEnable() {
        val frostedCls = runCatching { "com.tencent.mm.ui.FrostedContentView".toClass() }
            .getOrElse {
                WeLogger.w(TAG, "FrostedContentView not found in this host, feature idle")
                return
            }

        // ① 收集首页 FrostedContentView 实例（MainTabUI::doOnCreate after，此时底栏已装配）
        WeMainActivityBeautifyApi.methodDoOnCreate.hookAfter {
            val activity = thisObject as? android.app.Activity ?: return@hookAfter
            activity.window?.decorView?.post {
                val n = collectFrosted(activity.window.decorView)
                WeLogger.i(TAG, "home frosted instances collected: $n")
            }
        }

        // ② a(ZIF)：底栏实例 + enabled=false（原生联动缺失）→ 先置 true 再放行
        frostedCls.reflekt().firstMethod {
            name = "a"
            parameters { it[0] == bool }
        }.hookBefore {
            val view = thisObject as? View ?: return@hookBefore
            if (view !in homeFrostedInstances) return@hookBefore
            if (view in nativeActiveInstances) return@hookBefore

            val enabled = runCatching {
                view.reflekt()
                    .firstMethod { name = "getFrostedEnabled" }
                    .invoke() as? Boolean ?: false
            }.getOrDefault(false)

            if (enabled) {
                // 原生已经把模糊打开了（微信某版恢复联动 / 其它模块）——模块退位
                nativeActiveInstances.add(view)
                WeLogger.i(TAG, "native blur already active, module idle")
                return@hookBefore
            }

            runCatching {
                view.reflekt()
                    .firstMethod { name = "setFrostedEnabled" }
                    .invoke(true)
                WeLogger.i(TAG, "blur re-enabled for bottom bar (native linkage missing)")
            }.onFailure {
                WeLogger.w(TAG, "setFrostedEnabled failed: ${it.message}")
            }
        }
        WeLogger.i(TAG, "enabled")
    }

    private fun collectFrosted(v: View): Int {
        var n = 0
        if (v.javaClass.name.contains("FrostedContentView")) {
            homeFrostedInstances.add(v)
            n++
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) n += collectFrosted(v.getChildAt(i))
        }
        return n
    }
}
