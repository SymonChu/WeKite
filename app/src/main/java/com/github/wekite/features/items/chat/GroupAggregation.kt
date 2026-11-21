package com.github.wekite.features.items.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
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
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.github.wekite.features.api.ui.WeConversationContextMenuApi
import com.github.wekite.features.core.ClickableFeature
import com.github.wekite.features.core.Feature
import com.github.wekite.preferences.WePrefs
import com.github.wekite.preferences.WePrefs.Companion.prefOption
import com.github.wekite.ui.content.AlertDialogContent
import com.github.wekite.ui.content.DefaultColumn
import com.github.wekite.ui.content.TextButton
import com.github.wekite.ui.utils.showComposeDialog
import com.github.wekite.utils.HostInfo
import com.github.wekite.utils.WeLogger
import com.github.wekite.utils.android.findActivityOwningView
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 群聊归拢 —— 把主页列表里的群聊按「分组」收进若干入口条目，点开进入该分组的群聊列表。
 *
 * ## 形态
 * - 每个分组 = `rconversation` 里一条自己的真会话行（`username = wekite_groupagg_<id>`）+ `rcontact` 昵称。
 *   用真行而不是「往 adapter 里多插一个位置」，是为了不碰 `position → 会话` 的偏移修正
 *   （ListView / RecyclerView 两条路径、滚动恢复、长按菜单、搜索都不用改）。
 * - 未分组的群自动进「其他群」兜底条目，所以主页列表里**只剩好友会话 + 若干分组条目**。
 * - 真实群聊行的摘除用微信自己的「不显示该聊天」机制（`parentRef = hidden_conv_parent`），不删数据。
 * - 分组归属在主页长按群 →「移入分组」里设置；长按分组条目可改名。
 *
 * ## 锚点核验（2026-11-21，8.0.80/3220 与 8.0.79 Play 双版本字节级扫描）
 * - 列表 bind：宿主去重日志 `Get Item duplicated: positionMaps: …` **全 APK 唯一**命中
 *   `getView(int,View,ViewGroup)`（8.0.80 `Lcp5/a1;`，8.0.79 `Liq5/a1;`）。只锚日志串，不写死混淆名。
 *   ⚠️ `ConversationWithCacheAdapter` 在 8.0.80 已无 getView/getCount，微X 那套 superclass.getCount 不再成立。
 * - 点击：`com.tencent.mm.ui.conversation.b3::onItemClick`，锚 `MicroMsg.ConversationClickListener`。
 * - 长按菜单（复用本仓库 `WeConversationContextMenuApi`）：`MicroMsg.ConversationLongClickListener`
 *   + `onCreateContextMenu, contact is null` → 8.0.80 为 `ui/conversation/x3`；
 *   选中回调锚 `yuanbao_set_top` → `ui/conversation/v3::onMMMenuItemSelected`。
 *
 * ## 8.0.80 额外实测（决定下面三处实现细节）
 * - `ui/conversation/x3` 的 talker 字段是 `java.lang.String`（不是老版本的 `BString`），
 *   所以 `WeConversationContextMenuApi.resolveContext` 必须两种类型都认，否则长按菜单静默失效。
 * - 宿主改 parentRef 走 `storage/m4::Q([String,String,boolean,boolean)` / `M(String)` 的 **execSQL**，
 *   `ContentValues` 更新监听收不到 ⇒ 额外挂钩这两个方法做延迟收敛。
 * - 列表查询本身按 parentRef 过滤（`m4::s` 的 `where parentRef = '<arg>'`、`m4::C` 的
 *   `parentRef is null or parentRef = '' or …`），所以 `hidden_conv_parent` 单独就够藏群，
 *   不需要再改列表 SQL。
 */
@Feature(
    name = "群聊归拢",
    categories = ["聊天"],
    description = "把群聊按分组收进主页若干条目，点开进入该分组的群聊列表"
)
object GroupAggregation : ClickableFeature(), IResolveDex {

    private const val TAG = "GroupAggregation"

    private const val ENTRY_PREFIX = "wekite_groupagg_"

    /**
     * 早期构建用过的前缀（品牌拼写修正前）。这两个前缀共存的唯一后果是列表里多一条打不开的
     * 僵尸条目，所以每次 apply / restore 都顺手清掉，不做迁移。
     */
    private const val LEGACY_ENTRY_PREFIX = "wekit_groupagg_"
    private const val OTHER_ID = "other"

    private const val HIDDEN_PARENT = "hidden_conv_parent"

    private const val DUP_ANCHOR =
        "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d"

    private const val SNAPSHOT_TTL_MS = 1_500L
    private const val REAPPLY_INTERVAL_MS = 60_000L

    /** 宿主打回 parentRef 后，延迟这么久再收敛一次（避开宿主本次写入的同一批 SQL）。 */
    private const val SETTLE_DELAY_MS = 900L

    /**
     * rconversation.flag 的编码（8.0.80 实测：`storage/m4::<init>` 里做 `update rconversation set
     * flag = conversationTime…`，即低 56 位存 conversationTime、高 8 位是置顶/前移位）。
     * 入口行必须自己把时间写进低 56 位，否则排不进列表、也顶不住（微X 那种「置顶取消不掉」的坑）。
     */
    private const val FLAG_TIME_MASK = 0x00FFFFFFFFFFFFFFL
    private const val FLAG_HIGH_MASK = FLAG_TIME_MASK.inv()

    private const val MENU_ID_MOVE = 0x4B01
    private const val MENU_ID_RENAME = 0x4B02

    // ---------------------------------------------------------------- 偏好

    /** 分组定义：`[{"id":"...","name":"工作","members":["xxx@chatroom"]}]`。未分组的群进「其他群」。 */
    private var foldersJson by prefOption("group_agg_folders", "[]")

    private var otherName by prefOption("group_agg_other_name", "其他群")

    private var applied by prefOption("group_agg_applied", false)

    // ---------------------------------------------------------------- 运行时状态

    private data class Folder(val id: String, var name: String, val members: LinkedHashSet<String>)

    private data class GroupRow(
        val wxId: String,
        val name: String,
        val unread: Int,
        val muteUnread: Int,
        val time: Long
    )

    private data class EntryState(
        val username: String,
        val folderId: String,
        val name: String,
        val members: List<String>,
        val unread: Int,
        val muteUnread: Int
    )

    @Volatile
    private var entryCache: List<EntryState>? = null
    private var entryCacheAt = 0L

    @Volatile
    private var applying = false

    /** 还原过程中要让宿主的 parentRef 写入通过，否则刚还原的行会被监听器立刻藏回去。 */
    @Volatile
    private var restoring = false

    private val getItemMethods = WeakHashMap<Class<*>, Method>()
    private val usernameFields = WeakHashMap<Class<*>, Field>()
    private val lastBindLog = AtomicLong()
    private val lastApply = AtomicLong()

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

    /** 宿主写会话行时会把 `parentRef` 抹掉（收到新消息就重写整行）——当场改回去，群不会闪回主页。 */
    private val conversationUpdateListener =
        WeDatabaseListenerApi.IUpdateListener { table, values, _where, whereArgs, _conflict ->
            if (table != "rconversation") return@IUpdateListener
            if (!isEnabled || restoring) return@IUpdateListener
            val talker = values.getAsString("username") ?: whereArgs?.firstOrNull()
            if (talker == null || !talker.endsWith("@chatroom")) return@IUpdateListener
            if (!values.containsKey("parentRef")) return@IUpdateListener
            val parent = values.getAsString("parentRef")
            if (parent.isNullOrEmpty()) values.put("parentRef", HIDDEN_PARENT)
        }

    /**
     * 宿主改 parentRef 的真实通道（8.0.80 实测）：`storage/m4::Q([String,String,boolean,boolean)`
     * 拼 `Update rconversation set parentRef = '…'` 走 **execSQL**，`ContentValues` 监听收不到。
     * 这两条一挂钩，宿主把群从「不显示」改回去后我们会很快再收敛一次，群不会停留在主页。
     */
    private val methodStorageSetParentRef by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingStrings("Update rconversation set parentRef = '")
            paramCount = 4
        }
    }

    /** `storage/m4::M(String)`：`Update rconversation set parentRef = '', unReadCount = 0`。 */
    private val methodStorageClearParentRef by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingStrings("Update rconversation set parentRef = '', unReadCount = 0")
            paramCount = 1
        }
    }

    /** 主页长按菜单：群 →「移入分组」；分组条目 →「重命名分组」。 */
    private val menuProvider = object : WeConversationContextMenuApi.IMenuItemsProvider {
        override fun getMenuItems(): List<WeConversationContextMenuApi.MenuItem> = listOf(
            WeConversationContextMenuApi.MenuItem(
                id = MENU_ID_MOVE,
                text = "移入分组",
                drawable = ColorDrawable(Color.TRANSPARENT),
                shouldShow = { context, _ -> context.talker.endsWith("@chatroom") },
                onClick = { context -> showMoveDialog(context.activity, context.talker) }
            ),
            WeConversationContextMenuApi.MenuItem(
                id = MENU_ID_RENAME,
                text = "重命名分组",
                drawable = ColorDrawable(Color.TRANSPARENT),
                shouldShow = { context, _ -> context.talker.startsWith(ENTRY_PREFIX) },
                onClick = { context ->
                    val folderId = context.talker.removePrefix(ENTRY_PREFIX)
                    showRenameDialog(context.activity, folderId)
                }
            )
        )
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onEnable() {
        WeLogger.i(
            TAG,
            "anchor host=${HostInfo.versionName} getView=${describe(methodConversationGetView)} " +
                "onItemClick=${describe(methodConversationOnItemClick)}"
        )
        hookBind()
        hookClick()
        hookParentRefWrites()
        WeDatabaseListenerApi.addListener(conversationUpdateListener)
        runCatching { WeConversationContextMenuApi.addProvider(menuProvider) }
            .onFailure { WeLogger.w(TAG, "context menu provider unavailable", it) }
        runCatching { applyAggregation() }
            .onFailure { WeLogger.e(TAG, "apply aggregation failed", it) }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(conversationUpdateListener)
        runCatching { WeConversationContextMenuApi.removeProvider(menuProvider) }
        runCatching { restoreAll() }
            .onFailure { WeLogger.e(TAG, "restore failed", it) }
    }

    /** 设置页里点开：查看全部分组。 */
    override fun onClick(context: ComponentActivity) {
        showAllEntriesDialog(context)
    }

    private fun describe(target: DexMethodDelegate): String =
        if (target.isPlaceholder) "<placeholder>"
        else runCatching { target.method.toString() }.getOrElse { "<resolve-failed>" }

    // ---------------------------------------------------------------- 钩子

    private fun hookBind() {
        val target = methodConversationGetView
        if (target.isPlaceholder) return
        target.hookAfter {
            val position = args.getOrNull(0) as? Int ?: return@hookAfter
            val adapter = thisObject ?: return@hookAfter
            val username = usernameAt(adapter, position) ?: return@hookAfter
            if (!username.startsWith(ENTRY_PREFIX)) return@hookAfter
            val state = entries().firstOrNull { it.username == username }
            if (state == null && !username.startsWith(ENTRY_PREFIX)) return@hookAfter
            val now = System.currentTimeMillis()
            if (now - lastBindLog.get() > 5_000) {
                lastBindLog.set(now)
                WeLogger.d(TAG, "entry bound: $username")
            }
            result = buildEntryView(username, state)
            maybeReapply()
        }
    }

    private fun hookParentRefWrites() {
        listOf(methodStorageSetParentRef, methodStorageClearParentRef).forEach { target ->
            if (target.isPlaceholder) return@forEach
            target.hookAfter { scheduleReapply() }
        }
    }

    private val settleHandler by lazy { Handler(Looper.getMainLooper()) }
    private val settleTask = Runnable { reapplyNow() }

    /** 宿主刚把 parentRef 改回去时，等它这一批写库结束再收敛，避免和它互相踩。 */
    private fun scheduleReapply() {
        if (!isEnabled || applying || restoring) return
        settleHandler.removeCallbacks(settleTask)
        settleHandler.postDelayed(settleTask, SETTLE_DELAY_MS)
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
                    listAdapter.javaClass.getMethod("getHeaderViewsCount").invoke(listAdapter) as Int
                }.getOrDefault(0)
            } else {
                0
            }
            val adapter = if (listAdapter is HeaderViewListAdapter) listAdapter.wrappedAdapter
            else listAdapter
            val username = usernameAt(adapter, position - header) ?: return@hookBefore
            if (!username.startsWith(ENTRY_PREFIX)) return@hookBefore
            // 必须吃掉这次点击：否则微信会拿这行去 startChatting，打开一个不存在的会话。
            result = null
            val activity = findActivityOwningView(view) ?: return@hookBefore
            showEntryDialog(activity, username)
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

    // ---------------------------------------------------------------- 分组配置

    private fun loadFolders(): MutableList<Folder> {
        val out = mutableListOf<Folder>()
        runCatching {
            val array = JSONArray(foldersJson)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isEmpty()) continue
                val members = linkedSetOf<String>()
                val memberArray = obj.optJSONArray("members") ?: JSONArray()
                for (j in 0 until memberArray.length()) {
                    val member = memberArray.optString(j)
                    if (member.isNotEmpty()) members += member
                }
                out += Folder(id, obj.optString("name").ifEmpty { "分组" }, members)
            }
        }.onFailure { WeLogger.e(TAG, "parse folders failed", it) }
        return out
    }

    private fun saveFolders(folders: List<Folder>) {
        val array = JSONArray()
        folders.forEach { folder ->
            array.put(
                JSONObject().apply {
                    put("id", folder.id)
                    put("name", folder.name)
                    put("members", JSONArray(folder.members.toList()))
                }
            )
        }
        foldersJson = array.toString()
        invalidateSnapshot()
    }

    private fun moveGroupTo(talker: String, folderId: String) {
        val folders = loadFolders()
        folders.forEach { it.members.remove(talker) }
        if (folderId != OTHER_ID) {
            val target = folders.firstOrNull { it.id == folderId }
                ?: return
            target.members += talker
        }
        saveFolders(folders)
        reapplyNow()
    }

    private fun createFolder(name: String, talker: String?): Folder {
        val folders = loadFolders()
        val folder = Folder(
            id = System.currentTimeMillis().toString(36),
            name = name.ifEmpty { "分组" },
            members = linkedSetOf()
        )
        if (talker != null) folder.members += talker
        folders += folder
        saveFolders(folders)
        return folder
    }

    // ---------------------------------------------------------------- 归拢 / 还原

    private fun invalidateSnapshot() {
        entryCache = null
    }

    /** 当前应该有哪几个入口条目（含「其他群」兜底）。 */
    private fun entries(): List<EntryState> {
        val cached = entryCache
        val now = System.currentTimeMillis()
        if (cached != null && now - entryCacheAt < SNAPSHOT_TTL_MS) return cached
        val groups = groupsInDb()
        val byId = groups.associateBy { it.wxId }
        val folders = loadFolders()
        val assigned = HashSet<String>()
        val out = mutableListOf<EntryState>()

        folders.forEach { folder ->
            val members = folder.members.filter { byId.containsKey(it) }
            assigned += members
            if (members.isEmpty()) return@forEach
            val rows = members.mapNotNull { byId[it] }
            out += EntryState(
                username = ENTRY_PREFIX + folder.id,
                folderId = folder.id,
                name = folder.name,
                members = members,
                unread = rows.sumOf { it.unread },
                // 群消息的未读全部记在 unReadCount（群聊行不填 unReadMuteCount，8.0.80 实测 +
                // WCX 同款结论），所以这里不再做「免打扰未读」拆分，灰点逻辑一并去掉。
                muteUnread = 0
            )
        }

        val rest = groups.filter { it.wxId !in assigned }
        if (rest.isNotEmpty()) {
            out += EntryState(
                username = ENTRY_PREFIX + OTHER_ID,
                folderId = OTHER_ID,
                name = otherName,
                members = rest.map { it.wxId },
                unread = rest.sumOf { it.unread },
                muteUnread = 0
            )
        }
        entryCache = out
        entryCacheAt = now
        return out
    }

    private fun groupsInDb(): List<GroupRow> = runCatching {
        WeDatabaseApi.executeQuery(
            "SELECT c.username AS username, c.unReadCount AS u, c.unReadMuteCount AS m, " +
                "c.conversationTime AS t, IFNULL(r.nickname, '') AS nick " +
                "FROM rconversation c LEFT JOIN rcontact r ON r.username = c.username " +
                "WHERE c.username LIKE '%@chatroom' " +
                "AND (c.parentRef IS NULL OR c.parentRef = '' OR c.parentRef = '$HIDDEN_PARENT') " +
                "ORDER BY c.unReadCount DESC, c.conversationTime DESC"
        ).mapNotNull { row ->
            val wxId = row["username"] as? String ?: return@mapNotNull null
            if (wxId.isEmpty()) return@mapNotNull null
            val nick = (row["nick"] as? String).orEmpty()
            GroupRow(
                wxId = wxId,
                name = nick.ifEmpty { wxId },
                unread = (row["u"] as? Long)?.toInt() ?: 0,
                muteUnread = (row["m"] as? Long)?.toInt() ?: 0,
                time = (row["t"] as? Long) ?: 0L
            )
        }
    }.getOrDefault(emptyList())

    private fun reapplyNow() {
        runCatching { applyAggregation() }
            .onFailure { WeLogger.e(TAG, "reapply failed", it) }
    }

    private fun applyAggregation() {
        if (applying) return
        if (!WeDatabaseApi.isReady) {
            WeLogger.w(TAG, "database not ready, skip")
            return
        }
        applying = true
        try {
            invalidateSnapshot()
            val desired = entries()
            if (desired.isEmpty()) {
                // 没有群聊时不需要任何入口行，也不能把已有改动留在那儿。
                if (applied) restoreAll()
                return
            }
            var entryFailed = false
            desired.forEach { state ->
                if (!ensureEntryRow(state)) entryFailed = true
            }
            if (entryFailed) {
                WeLogger.w(TAG, "entry row unavailable, keeping conversations untouched")
                if (applied) restoreAll()
                return
            }
            // 清掉已不存在的分组条目（分组被删空 / 改名后 id 不变，不会误删）
            val keep = desired.map { it.username }
            val placeholders = keep.joinToString(", ") { "?" }
            WeDatabaseApi.execStatement(
                "DELETE FROM rconversation WHERE username LIKE ? AND username NOT IN ($placeholders)",
                arrayOf("$ENTRY_PREFIX%", *keep.toTypedArray())
            )
            WeDatabaseApi.execStatement(
                "DELETE FROM rcontact WHERE username LIKE ? AND username NOT IN ($placeholders)",
                arrayOf("$ENTRY_PREFIX%", *keep.toTypedArray())
            )
            purgeLegacyRows()
            // 入口行之间按分组顺序排：第 1 个分组拿最新时间，依次后退；flag 低 56 位同步写时间，
            // 高位（置顶）保持宿主/用户设置过的值不动。
            desired.forEachIndexed { index, state ->
                val time = System.currentTimeMillis() - index * 60_000L
                WeDatabaseApi.execStatement(
                    "UPDATE rconversation SET conversationTime = ?, flag = (flag & ?) | ? " +
                        "WHERE username = ?",
                    arrayOf(time, FLAG_HIGH_MASK, time and FLAG_TIME_MASK, state.username)
                )
            }
            WeDatabaseApi.execStatement(
                "UPDATE rconversation SET parentRef = ? " +
                    "WHERE username LIKE '%@chatroom' AND (parentRef IS NULL OR parentRef = '')",
                arrayOf(HIDDEN_PARENT)
            )
            WeConversationApi.reloadConversations()
            applied = true
            lastApply.set(System.currentTimeMillis())
            WeLogger.i(TAG, "entries=${desired.map { it.username to it.members.size }}")
        } finally {
            applying = false
        }
    }

    private fun maybeReapply() {
        if (applying) return
        val now = System.currentTimeMillis()
        if (now - lastApply.get() < REAPPLY_INTERVAL_MS) return
        lastApply.set(now)
        reapplyNow()
    }

    /**
     * 异常自愈，由 [GroupAggregationGuard] 在每次微信启动时调用：
     * - 功能已关闭但上次留下的改动还在 → 还原
     * - 入口行被微信清掉 → 还原，避免「群被藏了又没有入口」的死局
     */
    internal fun recoverIfNeeded() {
        if (!applied) return
        // 用偏好值而不是 isEnabled:守护条目可能比本功能的 startup 先跑。
        if (!WePrefs.getBoolOrDef(name, defaultEnabled)) {
            WeLogger.i(TAG, "feature disabled but state applied, restoring")
            runCatching { restoreAll() }
            return
        }
        if (!WeDatabaseApi.isReady) return
        val rowExists = runCatching {
            WeDatabaseApi.executeQuery(
                "SELECT 1 AS ok FROM rconversation WHERE username LIKE ? LIMIT 1",
                arrayOf("$ENTRY_PREFIX%")
            ).isNotEmpty()
        }.getOrDefault(false)
        if (!rowExists) {
            WeLogger.w(TAG, "entry rows missing on startup, restoring groups")
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
                "DELETE FROM rconversation WHERE username LIKE ?", arrayOf("$ENTRY_PREFIX%")
            )
            WeDatabaseApi.execStatement(
                "DELETE FROM rcontact WHERE username LIKE ?", arrayOf("$ENTRY_PREFIX%")
            )
            purgeLegacyRows()
        } finally {
            restoring = false
        }
        invalidateSnapshot()
        WeConversationApi.reloadConversations()
        WeLogger.i(TAG, "groups restored")
    }

    /** 清掉品牌拼写修正前（`wekit_` 前缀）留下的入口行，避免列表里多一条打不开的僵尸条目。 */
    private fun purgeLegacyRows() {
        runCatching {
            WeDatabaseApi.execStatement(
                "DELETE FROM rconversation WHERE username LIKE ?", arrayOf("$LEGACY_ENTRY_PREFIX%")
            )
            WeDatabaseApi.execStatement(
                "DELETE FROM rcontact WHERE username LIKE ?", arrayOf("$LEGACY_ENTRY_PREFIX%")
            )
        }.onFailure { WeLogger.w(TAG, "purge legacy entry rows failed", it) }
    }

    private fun ensureEntryRow(state: EntryState): Boolean {
        val contactOk = insertOrReplace(
            "rcontact",
            linkedMapOf("username" to state.username, "nickname" to state.name)
        )
        val now = System.currentTimeMillis()
        val conversationOk = insertOrReplace(
            "rconversation",
            linkedMapOf(
                "username" to state.username,
                "unReadCount" to "0",
                "unReadMuteCount" to "0",
                "atCount" to "0",
                "status" to "0",
                "isSend" to "0",
                "conversationTime" to now.toString(),
                // 低 56 位必须是时间、高位保留原有的置顶状态，否则这一行排不进列表或置顶失控。
                "flag" to entryFlag(state.username, now).toString(),
                "content" to "",
                "msgType" to "",
                "digest" to "",
                "digestUser" to "",
                "attrflag" to "0",
                "editingMsg" to "",
                "parentRef" to ""
            )
        )
        if (!contactOk || !conversationOk) return false
        runCatching { WeConversationApi.setPinned(state.username, true) }
        return true
    }

    /** 入口行的 flag：沿用现有行的高位（置顶/前移），低 56 位写本次时间。 */
    private fun entryFlag(username: String, time: Long): Long {
        val liveHigh = runCatching {
            WeDatabaseApi.rawQuery(
                "SELECT flag FROM rconversation WHERE username = ? LIMIT 1", arrayOf(username)
            ).use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
            }
        }.getOrNull()?.and(FLAG_HIGH_MASK) ?: 0L
        return liveHigh or (time and FLAG_TIME_MASK)
    }

    /**
     * 按宿主真实表结构裁剪后再写入：不同版本 `rconversation` 列集合会变，硬写全部列会整条失败。
     */
    private fun insertOrReplace(table: String, values: Map<String, String>): Boolean = runCatching {
        val columns = tableColumns(table)
        if (columns.isEmpty()) return false
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

    // ---------------------------------------------------------------- 入口行自绘

    @SuppressLint("SetTextI18n")
    private fun buildEntryView(username: String, state: EntryState?): View {
        val context = HostInfo.application
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val name = state?.name ?: run {
            val folderId = username.removePrefix(ENTRY_PREFIX)
            if (folderId == OTHER_ID) otherName
            else loadFolders().firstOrNull { it.id == folderId }?.name ?: "分组"
        }
        val count = state?.members?.size ?: 0
        val unread = state?.unread ?: 0
        val muteUnread = state?.muteUnread ?: 0
        val badge = if (unread > 0) unread else muteUnread
        val muted = unread == 0 && muteUnread > 0

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        val iconBox = FrameLayout(context)
        iconBox.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(0xFF07C160.toInt())
        }
        iconBox.addView(TextView(context).apply {
            text = name.take(1)
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
            text = name
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
            row.addView(
                badgeView,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(18))
            )
        }
        return row
    }

    // ---------------------------------------------------------------- 弹窗

    /** 所有分组一览（点功能行时打开）。 */
    private fun showAllEntriesDialog(activity: Activity) {
        val states = entries()
        showComposeDialog(activity) {
            AlertDialogContent(
                title = { Text("群聊归拢") },
                text = {
                    if (states.isEmpty()) {
                        Text("当前没有群聊可归拢。", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Box(Modifier.heightIn(max = 420.dp)) {
                            LazyColumn(Modifier.fillMaxWidth()) {
                                items(states, key = { it.username }) { state ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                dialog.dismiss()
                                                showEntryDialog(activity, state.username)
                                            }
                                            .padding(horizontal = 4.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = state.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "${state.members.size} 个群",
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onClick = { dialog.dismiss() }) { Text("关闭") } }
            )
        }
    }

    /** 某个分组的群列表。 */
    private fun showEntryDialog(activity: Activity, entryUsername: String) {
        val state = entries().firstOrNull { it.username == entryUsername }
        val groups = groupsInDb()
        val byId = groups.associateBy { it.wxId }
        val rows = state?.members?.mapNotNull { byId[it] } ?: emptyList()
        val title = state?.name ?: "分组"
        showComposeDialog(activity) {
            AlertDialogContent(
                title = { Text(title) },
                text = {
                    Box(Modifier.heightIn(max = 420.dp)) {
                        LazyColumn(Modifier.fillMaxWidth()) {
                            items(rows, key = { it.wxId }) { group ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            dialog.dismiss()
                                            openChat(activity, group.wxId)
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
                                            modifier = Modifier.size(24.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = if (group.unread > 99) "99+"
                                                else group.unread.toString(),
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
                    Row {
                        TextButton(onClick = {
                            dialog.dismiss()
                            showRenameDialog(activity, entryUsername.removePrefix(ENTRY_PREFIX))
                        }) { Text("重命名") }
                        if (entryUsername.removePrefix(ENTRY_PREFIX) != OTHER_ID) {
                            TextButton(onClick = {
                                dialog.dismiss()
                                deleteFolder(entryUsername.removePrefix(ENTRY_PREFIX))
                            }) { Text("删除分组") }
                        }
                        TextButton(onClick = { dialog.dismiss() }) { Text("关闭") }
                    }
                }
            )
        }
    }

    /** 群 →「移入分组」。 */
    private fun showMoveDialog(activity: Activity, talker: String) {
        val folders = loadFolders()
        showComposeDialog(activity) {
            AlertDialogContent(
                title = { Text("移入分组") },
                text = {
                    Box(Modifier.heightIn(max = 420.dp)) {
                        LazyColumn(Modifier.fillMaxWidth()) {
                            items(folders, key = { it.id }) { folder ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            dialog.dismiss()
                                            moveGroupTo(talker, folder.id)
                                        }
                                        .padding(horizontal = 4.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = folder.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (talker in folder.members) {
                                        Text("已在此", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            dialog.dismiss()
                                            showTextInputDialog(
                                                activity, "新建分组", "",
                                                onConfirm = { createdName, _ ->
                                                    val folder = createFolder(createdName, talker)
                                                    reapplyNow()
                                                    WeLogger.i(TAG, "folder created: ${folder.name}")
                                                }
                                            )
                                        }
                                        .padding(horizontal = 4.dp, vertical = 10.dp)
                                ) { Text("＋ 新建分组…", style = MaterialTheme.typography.bodyMedium) }
                            }
                            item {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            dialog.dismiss()
                                            moveGroupTo(talker, OTHER_ID)
                                        }
                                        .padding(horizontal = 4.dp, vertical = 10.dp)
                                ) { Text("移出分组（回到「$otherName」）", style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    }
                },
                dismissButton = { TextButton(onClick = { dialog.dismiss() }) { Text("取消") } }
            )
        }
    }

    /** 删除分组：成员不删，自然回落到「其他群」。 */
    private fun deleteFolder(folderId: String) {
        val folders = loadFolders()
        val removed = folders.removeAll { it.id == folderId }
        if (!removed) return
        saveFolders(folders)
        reapplyNow()
    }

    private fun showRenameDialog(activity: Activity, folderId: String) {
        val current = if (folderId == OTHER_ID) otherName else
            loadFolders().firstOrNull { it.id == folderId }?.name ?: return
        showTextInputDialog(activity, "重命名分组", current) { name, _ ->
            if (name.isEmpty()) return@showTextInputDialog
            if (folderId == OTHER_ID) {
                otherName = name
            } else {
                val folders = loadFolders()
                folders.firstOrNull { it.id == folderId }?.name = name
                saveFolders(folders)
            }
            invalidateSnapshot()
            reapplyNow()
        }
    }

    private fun showTextInputDialog(
        activity: Activity,
        title: String,
        initial: String,
        onConfirm: (String, Activity) -> Unit
    ) {
        showComposeDialog(activity) {
            var text by remember { mutableStateOf(initial) }
            AlertDialogContent(
                title = { Text(title) },
                text = {
                    DefaultColumn {
                        TextField(
                            value = text,
                            onValueChange = { text = it },
                            singleLine = true,
                            label = { Text("名称") }
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        val value = text.trim()
                        dialog.dismiss()
                        onConfirm(value, activity)
                    }) { Text("确定") }
                },
                dismissButton = { TextButton(onClick = { dialog.dismiss() }) { Text("取消") } }
            )
        }
    }

    private fun openChat(activity: Activity, wxId: String) {
        runCatching {
            WeApi.openContact(activity, wxId, WeApi.OpenContactDestination.CONVERSATION)
        }.onFailure { WeLogger.e(TAG, "open chat failed: $wxId", it) }
    }
}
