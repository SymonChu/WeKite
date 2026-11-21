package com.github.wekite.features.items.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.FrameLayout
import android.widget.HeaderViewListAdapter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import com.github.wekite.dexkit.abc.IResolveDex
import com.github.wekite.dexkit.dsl.DexMethodDelegate
import com.github.wekite.dexkit.dsl.dexMethod
import com.github.wekite.features.api.core.WeApi
import com.github.wekite.features.api.core.WeConversationApi
import com.github.wekite.features.api.core.WeDatabaseApi
import com.github.wekite.features.api.core.WeDatabaseListenerApi
import com.github.wekite.features.core.ClickableFeature
import com.github.wekite.features.core.Feature
import com.github.wekite.preferences.WePrefs
import com.github.wekite.preferences.WePrefs.Companion.prefOption
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.utils.showComposeDialog
import com.github.wekite.utils.HostInfo
import com.github.wekite.utils.WeLogger
import com.github.wekite.utils.android.findActivityOwningView
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 群聊归拢 —— 把主页列表里的群聊收进一个「群聊」入口条目，点开进入自建的群聊列表。
 *
 * ## 整体形态（照微X / WechatChatroomHelper 那一类实现）
 * 1. **入口行**：在 `rconversation` 落一条 `username = wekit_groupagg` 的真行（`rcontact` 里配一条
 *    昵称），置顶。用真行而不是「adapter 里 +1 个位置」的好处是：位置天然正确，ListView /
 *    RecyclerView 两条路径、滚动恢复、长按菜单、搜索都不需要做 position 偏移修正——微X 那
 *    760 行 adapter 代码几乎全在补这个偏移。
 * 2. **自绘**：`getView` 钩子里认到这一行就返回自绘视图（图标 + 标题 + 「N 个群聊 · M 条未读」+ 角标）。
 * 3. **隐藏真实群聊**：把根级群聊行的 `parentRef` 改成 `hidden_conv_parent`——这是微信自己的
 *    「不显示该聊天」机制（本仓库 `WeConversationApi.setConversationsVisibility` 已在用），
 *    取消归拢时改回 `''` 即完整还原，不删任何数据。
 * 4. **点开**：`ConversationClickListener.onItemClick` 钩子里认到入口行 → 消费掉事件 + 弹群聊列表。
 *
 * ## 锚点核验（2026-11-21，8.0.80/versionCode 3220 与 8.0.79 Play 双版本字节级扫描）
 * - 会话列表 bind：**全 APK 唯一**命中宿主去重日志
 *   `Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d`，
 *   8.0.80 = `Lcp5/a1;::getView(int,View,ViewGroup)`，8.0.79 = `Liq5/a1;::getView(...)`（混淆名变、
 *   日志串不变 ⇒ 只锚日志串）。
 * - 注意：`ConversationWithCacheAdapter`（`Lcp5/e;`）在 8.0.80 只剩 8 个方法、**没有** getView/getCount，
 *   所以微X 那种「钩 ConversationWithCacheAdapter.superclass.getCount」的做法在新宿主上不成立。
 * - 点击：`com.tencent.mm.ui.conversation.b3::onItemClick(AdapterView,View,int,long)`，锚
 *   `MicroMsg.ConversationClickListener`（79/80 同名，仍只锚日志串）。
 */
@Feature(
    name = "群聊归拢",
    categories = ["聊天"],
    description = "把所有群聊收进主页一个「群聊」条目，点开进入群聊列表"
)
object GroupAggregation : ClickableFeature(), IResolveDex {

    private const val TAG = "GroupAggregation"

    /** 入口行的会话 username。不能带 `@`，否则会被微信当成群/公众号。 */
    private const val ENTRY_USERNAME = "wekit_groupagg"

    /** 微信自己的「不显示该聊天」parentRef 值，用来把真实群聊从主页摘掉。 */
    private const val HIDDEN_PARENT = "hidden_conv_parent"

    private const val DUP_ANCHOR =
        "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d"

    private const val STATS_TTL_MS = 1_500L

    private var entryTitle by prefOption("group_agg_entry_title", "群聊")

    /** 上一次归拢是否落地。用来在异常/被微信清行后自我修复。 */
    private var applied by prefOption("group_agg_applied", false)

    private var statsCache: Triple<Int, Int, Int>? = null
    private var statsAt = 0L

    private val getItemMethods = WeakHashMap<Class<*>, Method>()
    private val usernameFields = WeakHashMap<Class<*>, Field>()

    private val lastBindLog = AtomicLong()

    /** 增量归拢的节流：新入群/新会话会自动被收进入口行，但不必每次 bind 都写库。 */
    private val lastApply = AtomicLong()
    private const val REAPPLY_INTERVAL_MS = 60_000L
    @Volatile
    private var applying = false

    /** 还原过程中要让宿主的 parentRef 写入通过，否则刚还原的行会被监听器立刻藏回去。 */
    @Volatile
    private var restoring = false

    // ---------------------------------------------------------------- dex 锚点

    private val methodConversationGetView by dexMethod(allowFailure = true) {
        matcher {
            name = "getView"
            paramTypes("int", "android.view.View", "android.view.ViewGroup")
            returnType = "android.view.View"
            usingEqStrings(DUP_ANCHOR)
        }
    }

    private val methodConversationOnItemClick by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.ui.conversation")
        matcher {
            name = "onItemClick"
            paramTypes("android.widget.AdapterView", "android.view.View", "int", "long")
            usingEqStrings("MicroMsg.ConversationClickListener")
        }
    }

    /**
     * 宿主写会话行时会把 `parentRef` 抹掉（收到新消息就重写整行）——在这里直接改回
     * [HIDDEN_PARENT]，比「等下一次 60 秒节流再补一遍」快，群不会先闪回主页列表。
     */
    private val conversationUpdateListener =
        WeDatabaseListenerApi.IUpdateListener { table, values, _where, whereArgs, _conflict ->
            if (table != "rconversation") return@IUpdateListener
            if (!isEnabled || restoring) return@IUpdateListener
            val talker = values.getAsString("username") ?: whereArgs?.firstOrNull()
            if (talker == null || !talker.endsWith("@chatroom")) return@IUpdateListener
            if (!values.containsKey("parentRef")) return@IUpdateListener
            val parent = values.getAsString("parentRef")
            if (parent.isNullOrEmpty()) {
                values.put("parentRef", HIDDEN_PARENT)
            }
        }

    // ---------------------------------------------------------------- 生命周期

    override fun onEnable() {
        WeLogger.i(
            TAG,
            "anchor host=${HostInfo.versionName} getView=${descriptorOf(methodConversationGetView)} " +
                "onItemClick=${descriptorOf(methodConversationOnItemClick)}"
        )
        hookBind()
        hookClick()
        WeDatabaseListenerApi.addListener(conversationUpdateListener)
        runCatching { applyAggregation() }
            .onFailure { WeLogger.e(TAG, "apply aggregation failed", it) }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(conversationUpdateListener)
        runCatching { restoreAll() }
            .onFailure { WeLogger.e(TAG, "restore failed", it) }
    }

    /** 设置页里点开：手动刷新 / 查看群聊列表。 */
    override fun onClick(context: ComponentActivity) {
        showGroupListDialog(context)
    }

    private fun descriptorOf(target: DexMethodDelegate): String =
        if (target.isPlaceholder) "<placeholder>" else runCatching { target.method.toString() }
            .getOrElse { "<resolve-failed>" }

    // ---------------------------------------------------------------- 钩子

    private fun hookBind() = hookBindInternal(methodConversationGetView)

    private fun hookBindInternal(target: DexMethodDelegate) {
        if (target.isPlaceholder) return
        target.hookAfter {
            val position = args.getOrNull(0) as? Int ?: return@hookAfter
            val adapter = thisObject ?: return@hookAfter
            val username = usernameAt(adapter, position) ?: return@hookAfter
            if (username != ENTRY_USERNAME) return@hookAfter
            val now = System.currentTimeMillis()
            val last = lastBindLog.get()
            if (now - last > 5_000) {
                lastBindLog.set(now)
                WeLogger.d(TAG, "entry row bound, pos=$position count=${countOf(adapter)}")
            }
            result = buildEntryView()
            maybeReapply()
        }
    }

    private fun hookClick() {
        val target = methodConversationOnItemClick
        if (target.isPlaceholder) return
        target.hookBefore {
            val view = args.getOrNull(1) as? View ?: return@hookBefore
            val position = args.getOrNull(2) as? Int ?: return@hookBefore
            val parent = args.getOrNull(0) as? AdapterView<*> ?: return@hookBefore
            val listAdapter = parent.adapter ?: return@hookBefore
            val header = if (listAdapter is HeaderViewListAdapter) {
                runCatching {
                    listAdapter.javaClass.getMethod("getHeaderViewsCount")
                        .invoke(listAdapter) as Int
                }.getOrDefault(0)
            } else {
                0
            }
            val adapter = if (listAdapter is HeaderViewListAdapter) listAdapter.wrappedAdapter
            else listAdapter
            val username = usernameAt(adapter, position - header) ?: return@hookBefore
            if (username != ENTRY_USERNAME) return@hookBefore
            // 必须吃掉这次点击：否则微信会拿这行去 startChatting，打开一个不存在的会话。
            result = null
            val activity = findActivityOwningView(view) ?: return@hookBefore
            WeLogger.d(TAG, "entry row clicked")
            showGroupListDialog(activity)
        }
    }

    // ---------------------------------------------------------------- 列表结构读取

    private fun usernameAt(adapter: Any, position: Int): String? {
        if (position < 0) return null
        val item = runCatching {
            getItemMethod(adapter.javaClass).invoke(adapter, position)
        }.getOrNull() ?: return null
        return usernameOf(item)
    }

    private fun getItemMethod(cls: Class<*>): Method = getItemMethods.getOrPut(cls) {
        cls.methods.first {
            it.name == "getItem" && it.parameterCount == 1 &&
                it.parameterTypes[0] == Int::class.javaPrimitiveType
        }
    }

    private fun usernameOf(conversation: Any): String? {
        val cls = conversation.javaClass
        var field = usernameFields[cls]
        if (field == null) {
            field = runCatching {
                conversation.reflekt()
                    .firstFieldOrNull { name = "field_username"; superclass() }
                    ?.self
                    ?.also { it.isAccessible = true }
            }.getOrNull() ?: return null
            usernameFields[cls] = field
        }
        return runCatching { field.get(conversation) as? String }.getOrNull()
    }

    private fun countOf(adapter: Any): Int? = runCatching {
        adapter.javaClass.methods.first { it.name == "getCount" && it.parameterCount == 0 }
            .invoke(adapter) as Int
    }.getOrNull()

    // ---------------------------------------------------------------- 归拢 / 还原

    private fun applyAggregation() {
        if (applying) return
        if (!WeDatabaseApi.isReady) {
            WeLogger.w(TAG, "database not ready, skip")
            return
        }
        applying = true
        try {
            if (!ensureEntryRow()) {
                WeLogger.w(TAG, "entry row unavailable")
                // 入口行都建不出来时绝不能把群聊藏起来——否则用户没有任何入口能看到群。
                if (applied) restoreAll()
                return
            }
            WeDatabaseApi.execStatement(
                "UPDATE rconversation SET parentRef = ? " +
                    "WHERE username LIKE '%@chatroom' AND (parentRef IS NULL OR parentRef = '')",
                arrayOf(HIDDEN_PARENT)
            )
            WeDatabaseApi.execStatement(
                "UPDATE rconversation SET conversationTime = ? WHERE username = ?",
                arrayOf(System.currentTimeMillis(), ENTRY_USERNAME)
            )
            WeConversationApi.reloadConversations()
            applied = true
            lastApply.set(System.currentTimeMillis())
            statsCache = null
            WeLogger.i(TAG, "groups folded; stats=${stats()}")
        } finally {
            applying = false
        }
    }

    private fun maybeReapply() {
        if (applying) return
        val now = System.currentTimeMillis()
        if (now - lastApply.get() < REAPPLY_INTERVAL_MS) return
        lastApply.set(now)
        runCatching { applyAggregation() }
            .onFailure { WeLogger.e(TAG, "incremental apply failed", it) }
    }

    /**
     * 异常自愈，由 [GroupAggregationGuard] 在每次微信启动时调用：
     * - 功能已关闭但上次留下的改动还在 → 还原（用户关掉开关后不该继续藏群）
     * - 入口行被微信清掉 → 还原，避免「群被藏了又没有入口」的死局
     */
    internal fun recoverIfNeeded() {
        if (!applied) return
        // 用偏好值而不是 isEnabled:守护条目可能比本功能的 startup 先跑，那时 isEnabled 还是 false。
        if (!WePrefs.getBoolOrDef(name, defaultEnabled)) {
            WeLogger.i(TAG, "feature disabled but state applied, restoring")
            runCatching { restoreAll() }
            return
        }
        // 数据库还没就绪时什么都不做:此时 restore 写不进去,却会把 applied 标记清掉,状态就再也对不上了。
        if (!WeDatabaseApi.isReady) return
        val rowExists = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT 1 AS ok FROM rconversation WHERE username = ? LIMIT 1",
                arrayOf(ENTRY_USERNAME)
            ).isNotEmpty()
        }.getOrDefault(false)
        if (!rowExists) {
            WeLogger.w(TAG, "entry row missing on startup, restoring groups")
            runCatching { restoreAll() }
        }
    }

    private fun restoreAll() {
        if (!WeDatabaseApi.isReady) return
        applied = false
        restoring = true
        try {
            WeDatabaseApi.execStatement(
                "UPDATE rconversation SET parentRef = '' WHERE parentRef = ?",
                arrayOf(HIDDEN_PARENT)
            )
            WeDatabaseApi.execStatement(
                "DELETE FROM rconversation WHERE username = ?", arrayOf(ENTRY_USERNAME)
            )
            WeDatabaseApi.execStatement(
                "DELETE FROM rcontact WHERE username = ?", arrayOf(ENTRY_USERNAME)
            )
        } finally {
            restoring = false
        }
        WeConversationApi.reloadConversations()
        WeLogger.i(TAG, "groups restored")
    }

    /** 插入/刷新入口行；返回入口行是否可用。 */
    private fun ensureEntryRow(): Boolean {
        val okContact = insertOrReplace(
            "rcontact",
            linkedMapOf("username" to ENTRY_USERNAME, "nickname" to entryTitle)
        )
        val now = System.currentTimeMillis()
        val okConversation = insertOrReplace(
            "rconversation",
            linkedMapOf(
                "username" to ENTRY_USERNAME,
                "unReadCount" to "0",
                "unReadMuteCount" to "0",
                "atCount" to "0",
                "status" to "0",
                "isSend" to "0",
                "conversationTime" to now.toString(),
                "content" to "",
                "msgType" to "",
                "digest" to "",
                "digestUser" to "",
                "attrflag" to "0",
                "editingMsg" to "",
                "parentRef" to ""
            )
        )
        if (!okContact || !okConversation) return false
        runCatching { WeConversationApi.setPinned(ENTRY_USERNAME, true) }
        return true
    }

    /**
     * 按宿主真实表结构裁剪后再写入：不同版本 `rconversation` 列集合会变，硬写全部列会整条失败。
     * 只写「表里确实存在」的列，其余交给 SQLite 默认值。
     */
    private fun insertOrReplace(table: String, values: Map<String, String>): Boolean {
        return runCatching {
            val columns = tableColumns(table)
            if (columns.isEmpty()) {
                WeLogger.w(TAG, "no columns for $table")
                return false
            }
            val usable = values.filterKeys { it in columns }
            if (usable.isEmpty()) return false
            val names = usable.keys.joinToString(", ")
            val placeholders = usable.keys.joinToString(", ") { "?" }
            WeDatabaseApi.execStatement(
                "INSERT OR REPLACE INTO $table ($names) VALUES ($placeholders)",
                usable.values.toTypedArray()
            )
            true
        }.onFailure { WeLogger.e(TAG, "insert into $table failed", it) }.getOrDefault(false)
    }

    private fun tableColumns(table: String): Set<String> = runCatching {
        val out = linkedSetOf<String>()
        WeDatabaseApi.rawQuery("PRAGMA table_info($table)").use { cursor ->
            val nameIdx = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIdx >= 0) out += cursor.getString(nameIdx)
            }
        }
        out
    }.getOrDefault(emptySet())

    // ---------------------------------------------------------------- 统计 / 群列表

    /** (群数量, 未读数, 免打扰未读数) */
    private fun stats(): Triple<Int, Int, Int> {
        val now = System.currentTimeMillis()
        statsCache?.let { if (now - statsAt < STATS_TTL_MS) return it }
        val value = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT COUNT(*) AS c, " +
                    "IFNULL(SUM(unReadCount), 0) AS u, " +
                    "IFNULL(SUM(unReadMuteCount), 0) AS m " +
                    "FROM rconversation WHERE username LIKE '%@chatroom' " +
                    "AND (parentRef IS NULL OR parentRef = '' OR parentRef = '$HIDDEN_PARENT')"
            ).firstOrNull()?.let { row ->
                val c = (row["c"] as? Long)?.toInt() ?: 0
                val u = (row["u"] as? Long)?.toInt() ?: 0
                val m = (row["m"] as? Long)?.toInt() ?: 0
                Triple(c, u, m)
            }
        }.getOrNull() ?: Triple(0, 0, 0)
        statsCache = value
        statsAt = now
        return value
    }

    private data class GroupEntry(
        val wxId: String,
        val name: String,
        val unread: Int,
        val time: Long
    )

    private fun loadGroups(): List<GroupEntry> = runCatching {
        WeDatabaseApi.executeQuery(
            "SELECT c.username AS username, c.unReadCount AS u, c.conversationTime AS t, " +
                "IFNULL(r.nickname, '') AS nick " +
                "FROM rconversation c LEFT JOIN rcontact r ON r.username = c.username " +
                "WHERE c.username LIKE '%@chatroom' " +
                "AND (c.parentRef IS NULL OR c.parentRef = '' OR c.parentRef = '$HIDDEN_PARENT') " +
                "ORDER BY c.unReadCount DESC, c.conversationTime DESC"
        ).map { row ->
            val wxId = row["username"] as? String ?: ""
            val nick = (row["nick"] as? String).orEmpty()
            GroupEntry(
                wxId = wxId,
                name = nick.ifEmpty { wxId },
                unread = (row["u"] as? Long)?.toInt() ?: 0,
                time = (row["t"] as? Long) ?: 0L
            )
        }.filter { it.wxId.isNotEmpty() }
    }.getOrDefault(emptyList())

    // ---------------------------------------------------------------- 入口行自绘

    @SuppressLint("SetTextI18n")
    private fun buildEntryView(): View {
        val context = HostInfo.application
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val (count, unread, muteUnread) = stats()
        val badge = if (unread > 0) unread else muteUnread
        val muted = unread == 0 && muteUnread > 0

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        // 图标：圆形底 + 「群」字（不依赖宿主资源，避免跨版本 drawable 失效）
        val iconBox = FrameLayout(context)
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF07C160.toInt())
        }
        iconBox.background = circle
        iconBox.addView(TextView(context).apply {
            text = "群"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })
        row.addView(iconBox, LinearLayout.LayoutParams(dp(48), dp(48)))

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        texts.addView(TextView(context).apply {
            text = entryTitle
            textSize = 16f
            setTextColor(0xFF111111.toInt())
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        texts.addView(TextView(context).apply {
            text = buildString {
                append(count).append(" 个群聊")
                if (unread + muteUnread > 0) append(" · ").append(unread + muteUnread).append(" 条未读")
            }
            textSize = 13f
            setTextColor(0xFF888888.toInt())
            maxLines = 1
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (badge > 0) {
            val badgeView = TextView(context).apply {
                text = if (badge > 99) "99+" else badge.toString()
                textSize = 11f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(9).toFloat()
                    setColor(if (muted) 0xFFB0B0B0.toInt() else 0xFFFA5151.toInt())
                }
                minWidth = dp(18)
                setPadding(dp(5), 0, dp(5), 0)
            }
            row.addView(badgeView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(18)
            ))
        }
        return row
    }

    // ---------------------------------------------------------------- 群聊列表弹窗

    private fun showGroupListDialog(activity: Activity) {
        val groups = loadGroups()
        showComposeDialog(activity) {
            AlertDialogContent(
                title = { Text(entryTitle) },
                text = {
                    Box(Modifier.heightIn(max = 420.dp)) {
                        LazyColumn(Modifier.fillMaxWidth()) {
                            items(groups, key = { it.wxId }) { group ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            dialog.dismiss()
                                            runCatching {
                                                WeApi.openContact(
                                                    activity, group.wxId,
                                                    WeApi.OpenContactDestination.CONVERSATION
                                                )
                                            }.onFailure {
                                                WeLogger.e(TAG, "open chat failed: ${group.wxId}", it)
                                            }
                                        }
                                        .padding(horizontal = 4.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = group.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontWeight = if (group.unread > 0) FontWeight.SemiBold
                                        else FontWeight.Normal,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (group.unread > 0) {
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .padding(start = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = if (group.unread > 99) "99+" else group.unread.toString(),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dialog.dismiss() }) { Text("关闭") }
                }
            )
        }
    }
}
