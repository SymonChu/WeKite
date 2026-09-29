package com.github.wekite.features.api.ui

import android.app.Activity
import android.content.Context
import android.widget.BaseAdapter
import com.tencent.mm.chatroom.ui.ChatroomInfoUI
import com.tencent.mm.plugin.profile.ui.ContactInfoUI
import com.tencent.mm.ui.base.preference.MMPreference
import com.tencent.mm.ui.base.preference.Preference
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.Modifiers
import dev.ujhhgtg.reflekt.utils.isSubclassOf
import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.github.wekite.features.core.ApiFeature
import com.github.wekite.features.core.Feature
import com.github.wekite.utils.WeLogger
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList

@Feature(name = "联系人页面扩展", categories = ["API"])
object WeContactPrefsScreenApi : ApiFeature() {

    interface IContactInfoProvider {
        fun getContactInfoItem(activity: Activity): List<PreferenceItem>
        fun onItemClick(activity: Activity, key: String): Boolean
    }

    data class PreferenceItem(
        val key: String,
        val title: String,
        val summary: String? = null,
        val position: Int = -1
    )

    private const val TAG = "WeContactPrefsScreenApi"

    private val providers = CopyOnWriteArrayList<IContactInfoProvider>()

    /** 每页已插入的 Preference（key → 实例），供 [refresh] 原地更新副标题。 */
    private val storedPrefs = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Activity, MutableMap<String, Any>>()
    )

    fun addProvider(provider: IContactInfoProvider) {
        providers.addIfAbsent(provider)
    }

    fun removeProvider(provider: IContactInfoProvider) {
        providers.remove(provider)
    }

    private lateinit var prefConstructor: Constructor<*>
    private lateinit var prefKeyField: Field
    private lateinit var adapterField: Field
    private lateinit var addPreferenceMethod: Method
    private lateinit var setKeyMethod: Method
    private lateinit var setSummaryMethod: Method
    private lateinit var setTitleMethod: Method

    override fun onEnable() {
        initReflection()
        WeLogger.i(TAG, "prefs screen hook installing for ContactInfoUI / ChatroomInfoUI / ChattingInfoUI")

        // ⚠️ 一律字符串反射查找，禁止 KClass 字面引用：类不在宿主里时 NoClassDefFoundError
        // 会让整个 onEnable 抛异常（v3.50 实测：ChattingInfoUI 引用炸掉群详情/联系人详情两个页的 hook）。
        // 找到才装，找不到记日志跳过。
        val targetClassNames = listOf(
            "com.tencent.mm.plugin.profile.ui.ContactInfoUI",
            "com.tencent.mm.chatroom.ui.ChatroomInfoUI",
            // 单聊「聊天详情」页：用户 2026-09-28 反馈单聊详情页没有 AI 开关（群详情有）。
            // 2026-09-29 真机日志：v3.50 推断的 com.tencent.mm.ui.chatting.ChattingInfoUI 不存在于
            // 用户宿主（NoClassDefFoundError）。真实类名待日志定位，先保留探测。
            "com.tencent.mm.ui.chatting.ChattingInfoUI",
        )
        val loader = ContactInfoUI::class.java.classLoader
        targetClassNames.forEach { name ->
            val clazz = try {
                Class.forName(name, false, loader)
            } catch (_: Throwable) {
                WeLogger.i(TAG, "host class not found, skip: $name")
                null
            } ?: return@forEach
            installPrefsHook(clazz)
        }
    }

    /** 对单个宿主 Activity 类装 initView / onPreferenceTreeClick 两个 hook。 */
    private fun installPrefsHook(clazz: Class<*>) {
        clazz.reflekt().apply {
                firstMethod { name = "initView" }
                    .hookAfter {
                        val hostActivity = thisObject as Activity
                        val adapterInstance = adapterField.get(hostActivity)
                        WeLogger.i(
                            TAG,
                            "initView hooked: ${hostActivity.javaClass.simpleName} providers=${providers.size}"
                        )
                        for (provider in providers) {
                            try {
                                val items = provider.getContactInfoItem(thisObject as Activity)
                                for (item in items) {
                                    val pref = prefConstructor.newInstance(thisObject as Context)
                                    setKeyMethod.invoke(pref, item.key)
                                    setTitleMethod.invoke(pref, item.title)
                                    item.summary?.let { summary -> setSummaryMethod.invoke(pref, summary) }
                                    // h0.add(preference, index) 在 index > 已有条目数时会 IndexOutOfBoundsException
                                    // (群成员资料页 initView 时宿主列表为空)。夹到 [0, count]: 空列表退化为
                                    // 0 号位, 其余情况下插入位置语义不变。
                                    val adapter = adapterInstance as BaseAdapter
                                    val pos = item.position.coerceIn(0, adapter.count)
                                    addPreferenceMethod.invoke(adapter, pref, pos)
                                    storedPrefs.getOrPut(thisObject as Activity) { mutableMapOf() }[item.key] = pref
                                    WeLogger.i(TAG, "item injected key=${item.key} title=${item.title} pos=$pos")
                                }
                            } catch (ex: Exception) {
                                WeLogger.e(
                                    TAG,
                                    "provider ${provider.javaClass.name} threw while providing contact info item",
                                    ex
                                )
                            }
                        }
                    }

                firstMethod {
                    name = "onPreferenceTreeClick"
                }.hookBefore {
                    val preference = args[1] ?: return@hookBefore
                    val key = prefKeyField.get(preference) as? String ?: return@hookBefore
                    for (provider in providers) {
                        try {
                            if (provider.onItemClick(thisObject as Activity, key)) {
                                result = true
                                refresh(thisObject as Activity)   // 状态写副标题 ⇒ 点按后原地刷新
                                return@hookBefore
                            }
                        } catch (ex: Exception) {
                            WeLogger.e(
                                TAG,
                                "provider ${provider.javaClass.name} threw while handling click event",
                                ex
                            )
                        }
                    }
                }
        }
    }

    /** 重新读取各 provider 的内容，更新已插入条目的标题/副标题并刷新列表。 */
    fun refresh(activity: Activity) {
        val prefs = storedPrefs[activity] ?: return
        try {
            for (provider in providers) {
                for (item in provider.getContactInfoItem(activity)) {
                    val pref = prefs[item.key] ?: continue
                    setTitleMethod.invoke(pref, item.title)
                    item.summary?.let { setSummaryMethod.invoke(pref, it) }
                }
            }
            (adapterField.get(activity) as? BaseAdapter)?.notifyDataSetChanged()
        } catch (ex: Exception) {
            WeLogger.e(TAG, "refresh failed", ex)
        }
    }

    private fun initReflection() {
        prefConstructor = Preference::class.reflekt()
            .firstConstructor {
                parameters(Context::class)
            }.self

        prefKeyField = Preference::class.reflekt()
            .firstField {
                type = String::class
                modifiers { !it.contains(Modifiers.FINAL) }
            }.self.makeAccessible()

        adapterField = MMPreference::class.reflekt()
            .firstField {
                modifiers { !it.contains(Modifiers.STATIC) }
                type { it isSubclassOf BaseAdapter::class }
            }.self.makeAccessible()

        addPreferenceMethod = adapterField.type.reflekt()
            .firstMethod {
                modifiers { !it.contains(Modifiers.FINAL) }
                parameters(Preference::class, Int::class)
            }.self

        setKeyMethod = Preference::class.reflekt()
            .firstMethod {
                parameters(String::class)
                returnType = Void.TYPE
            }.self

        val charSeqMethods = Preference::class.reflekt()
            .methods {
                parameters(CharSequence::class)
            }.map { it.self }

        setSummaryMethod = charSeqMethods.getOrElse(0) { error("setSummary method not found") }
        setTitleMethod = charSeqMethods.getOrElse(1) { error("setTitle method not found") }
    }
}
