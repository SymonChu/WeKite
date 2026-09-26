@file:SuppressLint("DiscouragedPrivateApi", "PrivateApi")

package com.github.wekite.utils.android

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityThread
import android.os.IBinder
import android.util.ArrayMap
import android.view.View
import dev.ujhhgtg.reflekt.utils.makeAccessible
import com.github.wekite.utils.WeLogger

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
 * 判据是**窗口 token 归属**（视图树），不用 `view.context`：微信用 `MutableContextWrapper`
 * 的子类复用会话页布局时，`baseContext` 可能仍指向别的 Activity（搜索页/主界面），
 * 于是按 context 解析出来的窗口根本不是屏幕上那个。
 *
 * 未 attach（`windowToken == null`）或 token 不在 `mActivities` 里时返回 null。
 */
fun findActivityOwningView(view: View): Activity? {
    val token = view.windowToken ?: return null
    return runCatching {
        val currentActivityThread = ActivityThread.currentActivityThread() ?: return null

        @Suppress("UNCHECKED_CAST")
        val activities = mActivitiesField.get(currentActivityThread) as? ArrayMap<IBinder, ActivityThread.ActivityClientRecord>
            ?: return null

        val record = activities[token] ?: return null
        activityField.get(record) as? Activity
    }.getOrElse {
        WeLogger.e("findActivityOwningView", "failed to resolve owning activity", it)
        null
    }
}

val Activity.currentWxId: String?
    get() {
        return intent.getStringExtra("Contact_User")
            ?: intent.getStringExtra("RoomInfo_Id")
            ?: intent.getStringExtra("room_name")
            ?: intent.getStringExtra("Contact_ChatRoomId")
            ?: intent.getStringExtra("Chat_User")
    }
