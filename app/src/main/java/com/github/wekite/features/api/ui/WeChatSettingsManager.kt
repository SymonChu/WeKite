package com.github.wekite.features.api.ui

import android.app.Activity
import android.content.Context
import androidx.annotation.Keep
import com.android.dx.stock.ProxyBuilder
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.reflekt.utils.toClass
import com.github.wekite.loader.utils.ParcelableFixer
import com.github.wekite.utils.HookHandle
import com.github.wekite.utils.WeLogger
import com.github.wekite.utils.hookAfterDirectly
import com.github.wekite.utils.hookBeforeDirectly
import com.github.wekite.utils.reflection.buildClass
import com.github.wekite.utils.reflection.createProxyBuilder
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

@Keep // keep the names of the marker classes to prevent class name clashing with WeChat's own classes
class WeChatSettingsManager(
    private val classBaseSettingItem: Class<*>,
    private val classBaseSettingSwitchItem: Class<*>,
    private val classSettingLocation: Class<*>,
    private val classSettingItemClassesProvider: Class<*>,
    private val classBaseSettingPrefUI: Class<*>,
    private val classBaseSettingUI: Class<*>,
    private val methodResourceHelperGetStringById: Method,
    private val mGetPageGroupItemClass: String,
    private val mGetLevel: String,
    private val mOnClick: String,
    private val mGetKey: String,
    private val mGetSettingLocation: String,
    private val mGetNameResId: String,
    private val mGetGroupNameResId: String,
    private val mGetSwitchState: String,
    private val mGetSwitchProperty: String
) {
    private val registeredItems = CopyOnWriteArrayList<ItemRegistration>()
    private val stringPool = ConcurrentHashMap<Int, String>()
    private var dynamicResIdCounter = -2000
    private var itemIndexCounter = 0

    private var contextGetStringUnhook: HookHandle? = null
    private var resourcesGetStringUnhook: HookHandle? = null

    // 设置页可以嵌套(设置 → 通用)，superImportUIComponents 会被触发多次。
    // 这里用弱引用集合记录当前存活的设置页，只在第一个进入时挂钩、最后一个销毁时解钩，
    // 避免后进入的页面把前一个的 HookHandle 覆盖掉、把全局 getString Hook 永久留在微信进程里。
    // 用弱引用而不是计数器，是为了让重复进入/未配对的销毁都不会把状态弄坏：
    // 重复添加同一实例是空操作，移除未记录的实例也是空操作，被泄漏的页面会随 GC 自动出集合。
    private val activeSettingsUis: MutableSet<Any> =
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())
    private val settingsUiLock = Any()

    // 依靠 Marker 接口隔离 Proxy 类缓存
    interface M0; interface M1; interface M2; interface M3; interface M4; interface M5; interface M6; interface M7; interface M8; interface M9; interface M10
    interface M11; interface M12; interface M13; interface M14; interface M15; interface M16; interface M17; interface M18; interface M19; interface M20
    interface M21; interface M22; interface M23; interface M24; interface M25; interface M26; interface M27; interface M28; interface M29; interface M30
    private val markers = arrayOf(
        M0::class.java, M1::class.java, M2::class.java, M3::class.java, M4::class.java, M5::class.java, M6::class.java, M7::class.java, M8::class.java, M9::class.java, M10::class.java,
        M11::class.java, M12::class.java, M13::class.java, M14::class.java, M15::class.java, M16::class.java, M17::class.java, M18::class.java, M19::class.java, M20::class.java,
        M21::class.java, M22::class.java, M23::class.java, M24::class.java, M25::class.java, M26::class.java, M27::class.java, M28::class.java, M29::class.java, M30::class.java,
    )

    class SettingItemSpec {
        var key: String = ""
        var title: String = ""
        var groupTitle: String? = null
        var pageClass: Class<*>? = null
        var parentClass: Class<*>? = null
        var childClass: Class<*>? = null
        var level: Int = 1
        var onClick: ((Activity) -> Unit)? = null

        // 开关项专用配置
        var isSwitch: Boolean = false
        var switchState: (() -> Boolean)? = null
        var onSwitchChanged: ((Boolean) -> Unit)? = null
    }

    private class ItemRegistration(val spec: SettingItemSpec, val proxyClass: Class<*>)

    private fun allocateString(value: String): Int {
        val id = dynamicResIdCounter--
        stringPool[id] = value
        return id
    }

    fun createItem(init: SettingItemSpec.() -> Unit): Class<*> {
        val spec = SettingItemSpec().apply(init)
        requireNotNull(spec.pageClass) { "${spec.title} does not have a page class" }
        val titleResId = allocateString(spec.title)
        val groupResId = spec.groupTitle?.let { allocateString(it) } ?: titleResId

        val targetBaseClass = if (spec.isSwitch) classBaseSettingSwitchItem else classBaseSettingItem

        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                mGetPageGroupItemClass -> spec.pageClass
                mGetLevel -> spec.level
                mOnClick -> {
                    if (spec.isSwitch) {
                        ProxyBuilder.callSuper(proxy, method, *args)
                    } else {
                        val activity = args[0] as Activity
                        spec.onClick?.invoke(activity) ?: ProxyBuilder.callSuper(proxy, method, *args)
                    }
                }

                mGetKey -> spec.key
                mGetSettingLocation -> {
                    classSettingLocation.createInstance(spec.pageClass, spec.parentClass)
                }

                mGetNameResId -> titleResId
                mGetGroupNameResId -> if (spec.groupTitle != null) groupResId else null

                // 处理开关独有方法
                mGetSwitchState if spec.isSwitch -> {
                    spec.switchState?.invoke() ?: false
                }

                mGetSwitchProperty if spec.isSwitch -> {
                    val switchHandlerClass = method.returnType
                    createSwitchHandlerProxy(switchHandlerClass, spec)
                }

                else -> ProxyBuilder.callSuper(proxy, method, *args)
            }
        }

        val markerInterface = if (itemIndexCounter < markers.size) markers[itemIndexCounter++] else java.io.Serializable::class.java
        val proxyClass = createProxyBuilder(
            ParcelableFixer.hybridClassLoader,
            targetBaseClass,
            arrayOf("androidx.appcompat.app.AppCompatActivity".toClass()),
            handler,
            arrayOf(markerInterface)
        ).buildClass(handler)

        spec.childClass?.let { childClass ->
            val resolvedPage = spec.pageClass ?: proxyClass
            childClass.reflekt()
                .firstMethod { returnType = classSettingLocation }
                .hookBeforeDirectly {
                    result = classSettingLocation.createInstance(resolvedPage, proxyClass)
                }
        }

        registeredItems.add(ItemRegistration(spec, proxyClass))
        return proxyClass
    }

    private fun createSwitchHandlerProxy(switchHandlerClass: Class<*>, spec: SettingItemSpec): Any {
        val switchClassHandler = InvocationHandler { _, _, args ->
            spec.onSwitchChanged?.invoke(args[0] as Boolean)
        }

        return Proxy.newProxyInstance(switchHandlerClass.classLoader, arrayOf(switchHandlerClass), switchClassHandler)
    }

    @Suppress("UNCHECKED_CAST")
    fun install() {
        classSettingItemClassesProvider.reflekt().firstMethod()
            .hookAfterDirectly {
                val originalMap = result as? Map<Any, Any> ?: return@hookAfterDirectly
                val mutMap = originalMap.toMutableMap()

                val groupedByPage = registeredItems.groupBy { it.spec.pageClass ?: it.proxyClass }
                for ((page, items) in groupedByPage) {
                    val classesToAdd = items.map { it.proxyClass }
                    val existingCollection = mutMap[page] as? Collection<Any>

                    if (existingCollection != null) {
                        val updatedSet = LinkedHashSet(existingCollection)
                        updatedSet.addAll(classesToAdd)
                        mutMap[page] = updatedSet
                    } else {
                        mutMap[page] = LinkedHashSet(classesToAdd)
                    }
                }
                result = mutMap
            }

        classBaseSettingPrefUI.reflekt()
            .firstMethod { name = "superImportUIComponents" }
            .hookAfterDirectly {
                val currentUi = thisObject!!
                if (!isSupportedSettingsUi(currentUi)) return@hookAfterDirectly

                @Suppress("UNCHECKED_CAST")
                val layoutComponentSet = args[0] as? HashSet<Class<*>> ?: return@hookAfterDirectly

                for (item in registeredItems) {
                    layoutComponentSet.add(item.proxyClass)
                }

                onSettingsUiEntered(currentUi)
            }

        classBaseSettingUI.reflekt()
            .firstMethod { name = "onDestroy" }
            .hookAfterDirectly {
                val currentUi = thisObject!!
                if (!isSupportedSettingsUi(currentUi)) return@hookAfterDirectly

                onSettingsUiDestroyed(currentUi)
            }
    }

    private fun isSupportedSettingsUi(ui: Any): Boolean {
        val name = ui.javaClass.name
        return name.endsWith("MainSettingsUI") || name.endsWith("CommonSettingsUI")
    }

    // 进入设置页时**幂等**确保 getString Hook 在位，而不依赖 activeSettingsUis 的数量。
    // 该集合是弱引用集(GC 决定清理时机)，且进入/销毁分别挂在 BaseSettingPrefUI / BaseSettingUI 上，
    // 只触发一边就会漂移成「集合非空但 Hook 已拆」——此时假 resId 取不到字符串，
    // 表现为设置项还在、点击正常、唯独标题空白(须重装微信才恢复)。集合只用于决定何时拆钩。
    private fun onSettingsUiEntered(ui: Any) {
        synchronized(settingsUiLock) {
            activeSettingsUis.add(ui)

            if (contextGetStringUnhook != null && resourcesGetStringUnhook != null) return

            WeLogger.d(
                TAG, "installing getString hooks (activeUis=${activeSettingsUis.size}, " +
                        "ctx=${contextGetStringUnhook != null}, res=${resourcesGetStringUnhook != null})"
            )

            if (contextGetStringUnhook == null) {
                contextGetStringUnhook = Context::class.reflekt()
                    .firstMethod { name = "getString"; parameters(Int::class) }
                    .hookBeforeDirectly {
                        stringPool[args[0] as Int]?.let { result = it }
                    }
            }

            if (resourcesGetStringUnhook == null) {
                resourcesGetStringUnhook = methodResourceHelperGetStringById.hookBeforeDirectly {
                    stringPool[args[1] as Int]?.let { result = it }
                }
            }
        }
    }

    // 最后一个设置页销毁时才解钩
    private fun onSettingsUiDestroyed(ui: Any) {
        synchronized(settingsUiLock) {
            activeSettingsUis.remove(ui)
            if (activeSettingsUis.isNotEmpty()) return

            WeLogger.d(TAG, "last settings ui destroyed, uninstalling getString hooks")
            contextGetStringUnhook?.unhook(); contextGetStringUnhook = null
            resourcesGetStringUnhook?.unhook(); resourcesGetStringUnhook = null
        }
    }

    private companion object {
        const val TAG = "WeChatSettingsManager"
    }
}
