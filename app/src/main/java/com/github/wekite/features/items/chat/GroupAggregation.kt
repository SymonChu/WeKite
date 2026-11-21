package com.github.wekite.features.items.chat

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.EditText
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ujhhgtg.reflekt.reflekt
import dev.ujhhgtg.reflekt.utils.toClass
import com.github.wekite.dexkit.abc.IResolveDex
import com.github.wekite.dexkit.dsl.DexMethodDelegate
import com.github.wekite.dexkit.dsl.dexMethod
import com.github.wekite.features.api.core.WeConversationApi
import com.github.wekite.features.api.core.WeDatabaseApi
import com.github.wekite.features.api.core.WeDatabaseListenerApi
import com.github.wekite.features.api.ui.WeConversationContextMenuApi
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
import com.github.wekite.utils.android.isDarkMode
import org.json.JSONArray
import org.json.JSONObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 群聊归拢 —— 把主页列表里的群聊按「分组」收进若干入口条目，点条目进入微信原生的会话盒子页。
 *
 * ## 形态（v4.05 起：宿主进程里**不再有任何 Compose UI**）
 * - 每个分组 = `rconversation` 里一条真会话行（`username = wekite_groupagg_<id>`）+ `rcontact` 昵称。
 *   这条行同时充当两个角色：主页列表里的入口条目、以及容器页的「父会话」。
 * - 该分组的成员群行 `parentRef = wekite_groupagg_<id>`（微信自己的「不显示该聊天」机制），
 *   于是它们从主页消失、但又归属在这个父会话下。
 * - 点入口条目 → 启动 `com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI`（8.0.80 实测存在），
 *   并把 `Contact_User` 传成这个父会话 id。容器页查询时把 parentRef 换成它，就能列出该分组的群。
 *   **这样二级页完全由微信原生渲染**：不会因为 Compose 组合期异常把微信带崩（v4.04 的闪退就是这样死的）。
 * - 分组长按菜单、重命名、移入分组全部用 framework `AlertDialog`，同样不引入 Compose。
 *
 * ## 锚点核验（2026-11-21，8.0.80/3220 与 8.0.79 Play，字节级扫描）
 * - 列表 bind：宿主去重日志 `Get Item duplicated: positionMaps: …` **全 APK 唯一**命中
 *   `getView(int,View,ViewGroup)`（8.0.80 `Lcp5/a1;`，8.0.79 `Liq5/a1;`）。只锚日志串。
 *   ⚠️ `ConversationWithCacheAdapter` 在 8.0.80 已无 getView/getCount，微X 那套 superclass.getCount 不再成立。
 * - 点击：`com.tencent.mm.ui.conversation.b3::onItemClick`，锚 `MicroMsg.ConversationClickListener`。
 * - 父级查询：`com.tencent.mm.storage.m4::s(int,List,String parentRef,int) -> Cursor`，
 *   锚 `select * from rconversation where ` + ` order by flag desc, conversationTime desc`。
 * - 宿主写 parentRef 走 `m4::Q([String,String,boolean,boolean)` / `M(String)` 的 execSQL
 *   （ContentValues 监听收不到）⇒ 额外挂钩这两个方法做延迟收敛。
 * - 长按菜单（复用本仓库 `WeConversationContextMenuApi`）：`MicroMsg.ConversationLongClickListener`
 *   → 8.0.80 为 `ui/conversation/x3`；talker 字段是 `java.lang.String`（不是 `BString`）。
 * - `rconversation.flag` 低 56 位是 conversationTime、高 8 位是置顶/前移（`m4::<init>` 里可见）。
 */
@Feature(
    name = "群聊归拢",
    categories = ["聊天"],
    description = "把群聊按分组收进主页若干条目，点条目进入原生群聊列表"
)
object GroupAggregation : ClickableFeature(), IResolveDex {

    private const val TAG = "GroupAggregation"

    private const val ENTRY_PREFIX = "wekite_groupagg_"
    private const val LEGACY_ENTRY_PREFIX = "wekit_groupagg_"
    private const val OTHER_ID = "other"

    /** 微信自己的会话盒子页：渲染某个 parentRef 下的会话列表。8.0.80 实测存在（未混淆）。 */
    private const val CONTAINER_UI = "com.tencent.mm.ui.conversation.ConvBoxServiceConversationUI"

    private const val DUP_ANCHOR =
        "Get Item duplicated: positionMaps: %s username [%s, %d] Map: %s datas: %d"

    private const val SNAPSHOT_TTL_MS = 1_500L
    private const val REAPPLY_INTERVAL_MS = 60_000L
    private const val SETTLE_DELAY_MS = 900L
    private const val MAX_DB_RETRIES = 10
    private const val DB_RETRY_DELAY_MS = 3_000L

    /**
     * rconversation.flag 的编码：低 56 位 conversationTime、高 8 位置顶/前移。
     * 入口行必须自己把时间写进低 56 位，否则排不进列表、也顶不住（微X「置顶取消不掉」的坑）。
     */
    private const val FLAG_TIME_MASK = 0x00FFFFFFFFFFFFFFL
    private const val FLAG_HIGH_MASK = FLAG_TIME_MASK.inv()

    private const val MENU_ID_MOVE = 0x7E4B0001
    private const val MENU_ID_RENAME = 0x7E4B0002


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
        val time: Long
    )

    private data class EntryState(
        val username: String,
        val folderId: String,
        val name: String,
        val members: List<String>,
        val unread: Int
    )

    @Volatile
    private var entryCache: List<EntryState>? = null

    @Volatile
    private var entryCacheAt = 0L

    @Volatile
    private var applying = false

    /** 还原过程中要让宿主的 parentRef 写入通过，否则刚还原的行会被监听器立刻藏回去。 */
    @Volatile
    private var restoring = false

    /** 当前打开的容器页对应的分组 id，由容器页 onCreate/onDestroy 维护。 */
    @Volatile
    private var activeFolderId: String? = null

    private val getItemMethods = WeakHashMap<Class<*>, Method>()
    private val usernameFields = WeakHashMap<Class<*>, Field>()
    private val lastBindLog = AtomicLong()
    private val lastApply = AtomicLong()
    private val dbRetries = AtomicInteger()

    /** 写库 + 重算都放这条单线程上，避免在列表 bind（UI 线程）里做数据库工作。 */
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wekite-group-agg").apply { isDaemon = true }
    }

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

    /** 容器页按 parentRef 列会话：`select * from rconversation where … order by flag desc, conversationTime desc`。 */
    private val methodConversationQueryByParent by dexMethod(allowFailure = true) {
        searchPackages("com.tencent.mm.storage")
        matcher {
            usingStrings(
                "select * from rconversation where ",
                " order by flag desc, conversationTime desc"
            )
            paramTypes("int", "java.util.List", "java.lang.String", "int")
        }
    }

    /**
     * 宿主改 parentRef 的真实通道（8.0.80 实测）：`storage/m4::Q(...)` 拼
     * `Update rconversation set parentRef = '…'` 走 **execSQL**，`ContentValues` 监听收不到。
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

    /** 宿主写会话行（ContentValues 路径）时的兜底：把被抹掉的 parentRef 改回来。 */
    private val conversationUpdateListener =
        WeDatabaseListenerApi.IUpdateListener { table, values, _where, whereArgs, _conflict ->
            if (table != "rconversation") return@IUpdateListener
            if (!isEnabled || restoring) return@IUpdateListener
            val talker = values.getAsString("username") ?: whereArgs?.firstOrNull()
            if (talker == null || !talker.endsWith("@chatroom")) return@IUpdateListener
            if (!values.containsKey("parentRef")) return@IUpdateListener
            val parent = values.getAsString("parentRef")
            if (parent.isNullOrEmpty()) {
                // 只用快照，不在数据库回调里再查库（会重入同一条连接）。
                val folder = entryCache
                    ?.firstOrNull { state -> state.members.contains(talker) }
                    ?.username ?: return@IUpdateListener
                values.put("parentRef", folder)
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
                    showRenameDialog(context.activity, context.talker.removePrefix(ENTRY_PREFIX))
                }
            )
        )
    }

    // ---------------------------------------------------------------- 生命周期

    override fun onEnable() {
        WeLogger.i(
            TAG,
            "anchor host=${HostInfo.versionName} getView=${describe(methodConversationGetView)} " +
                "onItemClick=${describe(methodConversationOnItemClick)} " +
                "queryByParent=${describe(methodConversationQueryByParent)} " +
                "setParentRef=${describe(methodStorageSetParentRef)} " +
                "clearParentRef=${describe(methodStorageClearParentRef)}"
        )
        hookBind()
        hookClick()
        hookParentRefWrites()
        hookContainerPage()
        WeDatabaseListenerApi.addListener(conversationUpdateListener)
        val menuBound = runCatching { WeConversationContextMenuApi.addProvider(menuProvider) }
            .onFailure { WeLogger.w(TAG, "context menu provider unavailable", it) }
            .isSuccess
        WeLogger.i(
            TAG,
            "hooks getView=${!methodConversationGetView.isPlaceholder} " +
                "click=${!methodConversationOnItemClick.isPlaceholder} " +
                "queryByParent=${!methodConversationQueryByParent.isPlaceholder} " +
                "menu=$menuBound"
        )
        runCatching { applyAggregation() }
            .onFailure { WeLogger.e(TAG, "apply aggregation failed", it) }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(conversationUpdateListener)
        runCatching { WeConversationContextMenuApi.removeProvider(menuProvider) }
        runCatching { restoreAll() }
            .onFailure { WeLogger.e(TAG, "restore failed", it) }
    }

    /** 设置页里点开：列出全部分组（这里是我们自己的 Activity 进程，Compose 安全）。 */
    override fun onClick(context: ComponentActivity) {
        showFoldersOverview(context)
    }

    private fun describe(target: DexMethodDelegate): String =
        if (target.isPlaceholder) "<placeholder>"
        else runCatching { target.method.toString() }.getOrElse { "<resolve-failed>" }

    // ---------------------------------------------------------------- 钩子

    private fun hookBind() {
        val target = methodConversationGetView
        if (target.isPlaceholder) return
        target.hookAfter {
            // 任意一行 bind 都是一次「主页列表活着」的信号：兜住「首次启用时数据库还没就绪」
            // 与「宿主把 parentRef 改回去」两种情况（内部 60 秒节流，不会刷库）。
            maybeReapply()
            val position = args.getOrNull(0) as? Int ?: return@hookAfter
            val adapter = thisObject ?: return@hookAfter
            val username = usernameAt(adapter, position) ?: return@hookAfter
            if (!username.startsWith(ENTRY_PREFIX)) return@hookAfter
            val state = entries().firstOrNull { it.username == username }
            val now = System.currentTimeMillis()
            if (now - lastBindLog.get() > 5_000) {
                lastBindLog.set(now)
                WeLogger.d(TAG, "entry bound: $username")
            }
            result = buildEntryView(username, state)
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
            openFolder(activity, username)
        }
    }

    /** 容器页打开/关闭时维护 [activeFolderId]，只在这期间替换父级查询的 parentRef。 */
    private fun hookContainerPage() {
        runCatching {
            val cls = CONTAINER_UI.toClass()
            cls.reflekt().firstMethod { name = "onCreate"; parameters(android.os.Bundle::class) }.hookBefore {
                activeFolderId = (thisObject as? Activity)?.intent?.getStringExtra("Contact_User")
                WeLogger.i(TAG, "container opened, folder=$activeFolderId")
            }
            cls.reflekt().firstMethod { name = "onDestroy" }.hookAfter {
                activeFolderId = null
            }
            if (!methodConversationQueryByParent.isPlaceholder) {
                methodConversationQueryByParent.hookBefore {
                    val folder = activeFolderId ?: return@hookBefore
                    if (args.getOrNull(2) is String) args[2] = folder
                }
            }
        }.onFailure { WeLogger.w(TAG, "container page hook failed", it) }
    }

    private fun hookParentRefWrites() {
        listOf(methodStorageSetParentRef, methodStorageClearParentRef).forEach { target ->
            if (target.isPlaceholder) return@forEach
            target.hookAfter { scheduleReapply() }
        }
    }

    private val settleHandler by lazy { Handler(Looper.getMainLooper()) }
    private val settleTask = Runnable { reapplyNow() }

    private fun scheduleReapply() {
        if (!isEnabled || applying || restoring) return
        settleHandler.removeCallbacks(settleTask)
        settleHandler.postDelayed(settleTask, SETTLE_DELAY_MS)
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
            val target = folders.firstOrNull { it.id == folderId } ?: return
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

    private fun deleteFolder(folderId: String) {
        val folders = loadFolders()
        if (!folders.removeAll { it.id == folderId }) return
        saveFolders(folders)
        reapplyNow()
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
                unread = rows.sumOf { it.unread }
            )
        }

        val rest = groups.filter { it.wxId !in assigned }
        if (rest.isNotEmpty()) {
            out += EntryState(
                username = ENTRY_PREFIX + OTHER_ID,
                folderId = OTHER_ID,
                name = otherName,
                members = rest.map { it.wxId },
                unread = rest.sumOf { it.unread }
            )
        }
        entryCache = out
        entryCacheAt = now
        return out
    }

    private fun groupsInDb(): List<GroupRow> = runCatching {
        WeDatabaseApi.executeQuery(
            "SELECT c.username AS username, c.unReadCount AS u, c.conversationTime AS t, " +
                "IFNULL(r.nickname, '') AS nick " +
                "FROM rconversation c LEFT JOIN rcontact r ON r.username = c.username " +
                "WHERE c.username LIKE '%@chatroom' " +
                "AND (c.parentRef IS NULL OR c.parentRef = '' OR c.parentRef LIKE '$ENTRY_PREFIX%') " +
                "ORDER BY c.unReadCount DESC, c.conversationTime DESC"
        ).mapNotNull { row ->
            val wxId = row["username"] as? String ?: return@mapNotNull null
            if (wxId.isEmpty()) return@mapNotNull null
            val nick = (row["nick"] as? String).orEmpty()
            GroupRow(
                wxId = wxId,
                name = nick.ifEmpty { wxId },
                unread = (row["u"] as? Long)?.toInt() ?: 0,
                time = (row["t"] as? Long) ?: 0L
            )
        }
    }.getOrDefault(emptyList())

    private fun reapplyNow() {
        runCatching {
            worker.execute {
                runCatching { applyAggregation() }
                    .onFailure { WeLogger.e(TAG, "reapply failed", it) }
            }
        }.onFailure { WeLogger.e(TAG, "schedule reapply failed", it) }
    }

    private fun applyAggregation() {
        if (applying) return
        if (!WeDatabaseApi.isReady) {
            val attempt = dbRetries.incrementAndGet()
            if (attempt <= MAX_DB_RETRIES) {
                WeLogger.w(TAG, "database not ready, retry #$attempt in ${DB_RETRY_DELAY_MS}ms")
                settleHandler.postDelayed(settleTask, DB_RETRY_DELAY_MS)
            } else {
                WeLogger.w(TAG, "database still not ready after $MAX_DB_RETRIES retries")
            }
            return
        }
        dbRetries.set(0)
        applying = true
        try {
            invalidateSnapshot()
            val desired = entries()
            if (desired.isEmpty()) {
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

            // 每个成员群挂到自己分组的父会话下（不是统一的隐藏值），容器页才能按 parentRef 列出来。
            val mapping = desired.flatMap { state -> state.members.map { it to state.username } }
            WeDatabaseApi.transaction {
                WeDatabaseApi.execStatement(
                    "UPDATE rconversation SET parentRef = '' WHERE parentRef LIKE ?",
                    arrayOf("$ENTRY_PREFIX%")
                )
                mapping.forEach { (talker, entry) ->
                    WeDatabaseApi.execStatement(
                        "UPDATE rconversation SET parentRef = ? WHERE username = ?",
                        arrayOf(entry, talker)
                    )
                }
            }
            // 入口行之间按分组顺序排：第 1 个分组拿最新时间，依次后退；flag 低 56 位同步写时间。
            desired.forEachIndexed { index, state ->
                val time = System.currentTimeMillis() - index * 60_000L
                WeDatabaseApi.execStatement(
                    "UPDATE rconversation SET conversationTime = ?, flag = (flag & ?) | ? " +
                        "WHERE username = ?",
                    arrayOf(time, FLAG_HIGH_MASK, time and FLAG_TIME_MASK, state.username)
                )
            }
            WeConversationApi.reloadConversations()
            applied = true
            lastApply.set(System.currentTimeMillis())
            WeLogger.i(
                TAG,
                "applied entries=${desired.map { it.name to it.members.size }} " +
                    "foldedGroups=${countFoldedGroups()} rootGroups=${countRootGroups()}"
            )
        } catch (ex: Throwable) {
            WeLogger.e(TAG, "apply failed", ex)
        } finally {
            applying = false
        }
    }

    private fun countFoldedGroups(): Int = runCatching {
        WeDatabaseApi.rawQuery(
            "SELECT COUNT(*) FROM rconversation WHERE parentRef LIKE ?", arrayOf("$ENTRY_PREFIX%")
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }.getOrDefault(-1)

    private fun countRootGroups(): Int = runCatching {
        WeDatabaseApi.rawQuery(
            "SELECT COUNT(*) FROM rconversation WHERE username LIKE '%@chatroom' " +
                "AND (parentRef IS NULL OR parentRef = '')"
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }.getOrDefault(-1)

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
                "UPDATE rconversation SET parentRef = '' WHERE parentRef LIKE ?",
                arrayOf("$ENTRY_PREFIX%")
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
                "UPDATE rconversation SET parentRef = '' WHERE parentRef LIKE ?",
                arrayOf("$LEGACY_ENTRY_PREFIX%")
            )
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

    // ---------------------------------------------------------------- 入口行自绘（横向多头像）

    @SuppressLint("SetTextI18n")
    private fun buildEntryView(username: String, state: EntryState?): View {
        val context = HostInfo.application
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val dark = context.isDarkMode
        val titleColor = if (dark) 0xFFEDEDED.toInt() else 0xFF111111.toInt()
        val subtitleColor = if (dark) 0xFF8C8C8C.toInt() else 0xFF888888.toInt()

        val name = state?.name ?: run {
            val folderId = username.removePrefix(ENTRY_PREFIX)
            if (folderId == OTHER_ID) otherName
            else loadFolders().firstOrNull { it.id == folderId }?.name ?: "分组"
        }
        val count = state?.members?.size ?: 0
        val unread = state?.unread ?: 0

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }

        // 单个圆形图标（首字）+ 名字 + 未读角标；暂不做横向多头像。
        row.addView(avatar(context, name.take(1), 0, density), LinearLayout.LayoutParams(dp(48), dp(48)))

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        texts.addView(TextView(context).apply {
            text = name
            textSize = 16f
            setTextColor(titleColor)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        texts.addView(TextView(context).apply {
            text = buildString {
                append(count).append(" 个群聊")
                if (unread > 0) append(" · ").append(unread).append(" 条未读")
            }
            textSize = 13f
            setTextColor(subtitleColor)
            maxLines = 1
        })
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (unread > 0) {
            row.addView(TextView(context).apply {
                text = if (unread > 99) "99+" else unread.toString()
                textSize = 11f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(9).toFloat()
                    setColor(0xFFFA5151.toInt())
                }
                minWidth = dp(18)
                setPadding(dp(5), 0, dp(5), 0)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(18)))
        }
        return row
    }

    private fun avatar(
        context: android.content.Context,
        letter: String,
        index: Int,
        density: Float
    ): TextView {
        val palette = intArrayOf(0xFF07C160.toInt(), 0xFF576B95.toInt(), 0xFFFA9D3B.toInt(), 0xFF7F6BFF.toInt())
        val stroke = (2 * density).toInt()
        return TextView(context).apply {
            text = letter
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette[index % palette.size])
                setStroke(stroke, 0xFFFFFFFF.toInt())
            }
        }
    }

    // ---------------------------------------------------------------- 宿主进程里的弹窗（framework，不用 Compose）

    private fun openFolder(activity: Activity, entryUsername: String) {
        activeFolderId = entryUsername
        runCatching {
            activity.startActivity(Intent().apply {
                setClassName(HostInfo.packageName, CONTAINER_UI)
                putExtra("Contact_User", entryUsername)
                putExtra("Contact_ChatRoomId", entryUsername)
                putExtra("room_name", entryUsername)
            })
        }.onFailure {
            activeFolderId = null
            WeLogger.e(TAG, "open container failed: $entryUsername", it)
        }
    }

    /** 群 →「移入分组」。用 framework AlertDialog 的 items 列表，避免 Compose 在宿主进程里崩。 */
    private fun showMoveDialog(activity: Activity, talker: String) {
        val folders = loadFolders()
        val labels = folders.map { it.name } +
            listOf("＋ 新建分组…", "移出分组（回到「$otherName」）")
        runCatching {
            AlertDialog.Builder(activity)
                .setTitle("移入分组")
                .setItems(labels.toTypedArray()) { _, which ->
                    when {
                        which < folders.size -> moveGroupTo(talker, folders[which].id)
                        which == folders.size -> promptNewFolder(activity, talker)
                        else -> moveGroupTo(talker, OTHER_ID)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }.onFailure { WeLogger.e(TAG, "show move dialog failed", it) }
    }

    private fun promptNewFolder(activity: Activity, talker: String) {
        val input = EditText(activity).apply { hint = "分组名称" }
        runCatching {
            AlertDialog.Builder(activity)
                .setTitle("新建分组")
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    val name = input.text?.toString()?.trim().orEmpty()
                    createFolder(name, talker)
                    reapplyNow()
                }
                .setNegativeButton("取消", null)
                .show()
        }.onFailure { WeLogger.e(TAG, "show new folder dialog failed", it) }
    }

    private fun showRenameDialog(activity: Activity, folderId: String) {
        val current = if (folderId == OTHER_ID) otherName
        else loadFolders().firstOrNull { it.id == folderId }?.name ?: return
        val input = EditText(activity).apply {
            setText(current)
            setSelection(current.length)
        }
        runCatching {
            AlertDialog.Builder(activity)
                .setTitle("重命名分组")
                .setView(input)
                .setPositiveButton("确定") { _, _ ->
                    val name = input.text?.toString()?.trim().orEmpty()
                    if (name.isNotEmpty()) {
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
                .setNegativeButton("取消", null)
                .show()
        }.onFailure { WeLogger.e(TAG, "show rename dialog failed", it) }
    }

    /** 设置页里的分组一览：这里是我们自己的 Activity 进程，用 Compose 弹窗是安全的。 */
    private fun showFoldersOverview(activity: ComponentActivity) {
        val states = entries()
        showComposeDialog(activity) {
            val list = remember { states }
            AlertDialogContent(
                title = { Text("群聊归拢") },
                text = {
                    if (list.isEmpty()) {
                        Text("当前没有群聊可归拢。", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Box(Modifier.heightIn(max = 420.dp)) {
                            LazyColumn(Modifier.fillMaxWidth()) {
                                items(list, key = { it.username }) { state ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                dialog.dismiss()
                                                deleteFolderIfNotOther(state.folderId)
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

    /** 设置页里点某个分组＝删除它（成员回落到「其他群」）。「其他群」不可删。 */
    private fun deleteFolderIfNotOther(folderId: String) {
        if (folderId == OTHER_ID) return
        deleteFolder(folderId)
    }
}
