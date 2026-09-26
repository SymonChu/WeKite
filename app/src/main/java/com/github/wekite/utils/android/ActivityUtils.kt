@file:SuppressLint("DiscouragedPrivateApi", "PrivateApi")

package com.github.wekite.utils.android

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityThread
import android.os.IBinder
import android.util.ArrayMap
import android.view.View
import android.view.Window
import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.github.wekite.utils.WeLogger
import java.lang.reflect.Method

private val mActivitiesField = ActivityThread::class.java.getDeclaredField("mActivities").makeAccessible()
private val pausedField = ActivityThread.ActivityClientRecord::class.java.getDeclaredField("paused").makeAccessible()
private val activityField = ActivityThread.ActivityClientRecord::class.java.getDeclaredField("activity").makeAccessible()

fun getTopMostActivity(allowPaused: Boolean = false): Activity? = runCatching {
    val currentActivityThread = ActivityThread.currentActivityThread()

    @Suppress("UNCHECKED_CAST")
    val activities = mActivitiesField.get(currentActivityThread) as ArrayMap<IBinder, ActivityThread.ActivityClientRecord>

    activities.values
        .filter { record -> allowPaused || pausedField.get(record) == false }
        .mapNotNull { record -> activityField.get(record) as? Activity }
        .lastOrNull()
}.getOrElse {
    WeLogger.e("getTopMostActivity", "failed to get top-most activity", it)
    null
}

/**
 * 反查「承载这个视图的窗口属于哪个 Activity」。
 *
 * 判据是 **decorView 同一性**（哪条 Activity 记录的窗口 decor 就是这棵视图树的根），
 * 不用 `view.context`：微信用 `MutableContextWrapper` 的子类复用会话页布局时，
 * `baseContext` 可能仍指向别的 Activity（搜索页/主界面），于是按 context 解析出来的窗口
 * 根本不是屏幕上那个（v3.35 真机日志实测 `ctx` 与 `topMost` 是两个不同窗口）。
 *
 * ⚠️ **不要改用 `view.windowToken` 去查 `mActivities`**：`View.getWindowToken()` 给的是
 * `AttachInfo.mWindowToken`（IWindow 的 Binder），**不等于** ActivityClientRecord 的 token，
 * 实测恒查不到（v3.35 日志里 `tree=null`）。
 *
 * 取不到（未 attach / 视图不挂在任何 Activity 窗口上）时返回 null。
 */
fun findActivityOwningView(view: View): Activity? {
    val decor = view.rootView
    return runCatching {
        val currentActivityThread = ActivityThread.currentActivityThread() ?: return null

        @Suppress("UNCHECKED_CAST")
        val activities = mActivitiesField.get(currentActivityThread) as? ArrayMap<IBinder, ActivityThread.ActivityClientRecord>
            ?: return null

        for (record in activities.values) {
            val activity = activityField.get(record) as? Activity ?: continue
            val window = activity.window ?: continue
            val windowDecor = window.peekDecorViewCompat() ?: continue
            if (windowDecor === decor) return activity
        }
        null
    }.getOrElse {
        WeLogger.e("findActivityOwningView", "failed to resolve owning activity", it)
        null
    }
}

/** `Window.peekDecorView()`（隐藏 API，不触发 installDecor）优先；失败再退到公开的 `getDecorView()`。 */
private val peekDecorViewMethod: Method? = runCatching {
    Window::class.java.getMethod("peekDecorView").apply { isAccessible = true }
}.getOrNull()

private fun Window.peekDecorViewCompat(): View? =
    peekDecorViewMethod?.let { runCatching { it.invoke(this) as? View }.getOrNull() }
        ?: runCatching { decorView }.getOrNull()

val Activity.currentWxId: String?
    get() {
        return intent.getStringExtra("Contact_User")
            ?: intent.getStringExtra("RoomInfo_Id")
            ?: intent.getStringExtra("room_name")
            ?: intent.getStringExtra("Contact_ChatRoomId")
            ?: intent.getStringExtra("Chat_User")
    }
