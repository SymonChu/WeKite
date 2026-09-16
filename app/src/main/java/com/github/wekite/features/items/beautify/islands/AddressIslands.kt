package com.github.wekite.features.items.beautify.islands

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.github.wekite.features.items.beautify.ListIslands
import com.github.wekite.utils.WeLogger

private const val TAG = "AddressIslands"

/**
 * 通讯录（「联系人」tab）的圆角卡片实现。
 *
 * ## 挂钩时机（三次踩坑后定案，均以字节码实测为准）
 * - v3.20 挂外层 Activity `AddressUI.onCreate` —— 那一刻内部 Fragment 的列表根本没建
 * - v3.21 挂 `MvvmAddressUIFragment.getLayoutView()` —— **只 inflate 布局**，列表字段仍为 null
 * - **v3.22 起挂 `MvvmAddressUIFragment.l0(Bundle)`** —— 字节码实测此处才执行
 *   `check-cast -> WxRecyclerView` / `iput-object -> 字段 p` / `setAdapter` /
 *   `setLayoutManager` / `addView`，是整条链上**第一个「列表已存在」**的时点
 *
 * ## 取列表（v3.23 修正）
 * 按**类名**在该 Fragment 的字段里找（字段名混淆，当前叫 `p`），沿父类链回溯；
 * 找不到再退化到视图树里按类名搜。**两条路径都只比类名字符串**：
 * 宿主的 `WxRecyclerView` 由微信 ClassLoader 加载，模块里的
 * `androidx.recyclerview.widget.RecyclerView` 来自模块自己的 ClassLoader，
 * 是两个不同的 Class，`is` / `isAssignableFrom` 恒 false（v3.22 血案）。
 *
 * ## 分组
 * 用户指定与主页一致：新的朋友→服务号一块 / 我的企业一块 / A、B、C 各一块。
 * 实现 = 「行内可见内容中含单个短文本（字母章节标题）」⇒ 开新岛；
 * 「我的企业」行也开新岛（让「企业联系人」跟它同岛）。留白行一律不套卡。
 */
private const val HOST_LIST_CLASS = "com.tencent.mm.view.recyclerview.WxRecyclerView"
private const val RECYCLER_VIEW_CLASS = "androidx.recyclerview.widget.RecyclerView"

/**
 * 从 Fragment 实例上按类名取通讯录列表（含父类链），失败再退化到视图树。
 */
fun applyAddressIslands(feature: ListIslands, fragment: Any?) {
    if (!feature.isContactsEnabled) return
    if (fragment == null) {
        WeLogger.w(TAG, "address fragment is null; contacts islands skipped")
        return
    }

    val list = listOfFragment(fragment)
    if (list == null) {
        WeLogger.w(TAG, "address list field not found on fragment; contacts islands skipped")
        return
    }
    attach(feature, list)
}

private fun listOfFragment(fragment: Any): ViewGroup? {
    var cls: Class<*>? = fragment.javaClass
    var depth = 0
    while (cls != null && depth < 12) {
        for (field in cls.declaredFields) {
            if (!isListType(field.type)) continue
            val value = runCatching {
                field.isAccessible = true
                field.get(fragment)
            }.getOrNull()
            if (value is ViewGroup) {
                WeLogger.i(TAG, "address list field resolved: ${field.name} -> ${field.type.name}")
                return value
            }
        }
        cls = cls.superclass
        depth++
    }

    // 退化路径：字段取不到时，从其根视图里按类名找一次（l0 已 addView，此时应已存在）
    val root = runCatching {
        fragment.javaClass.getMethod("getView").invoke(fragment) as? View
    }.getOrNull()
    return root?.let { findListInTree(it) }
}

/** 按**类名字符串**判断某个类型是不是宿主列表（跨 ClassLoader 安全）。 */
private fun isListType(type: Class<*>): Boolean = isListClass(type)

private fun isListClass(type: Class<*>): Boolean {
    var c: Class<*>? = type
    var depth = 0
    while (c != null && depth < 12) {
        val name = c.name
        if (name == HOST_LIST_CLASS || name == RECYCLER_VIEW_CLASS) return true
        if (name.startsWith("android.view.") || name.startsWith("android.widget.")) return false
        if (name == "java.lang.Object") return false
        c = c.superclass
        depth++
    }
    return false
}

private fun findListInTree(root: View): ViewGroup? {
    if (isListClass(root.javaClass)) return root as? ViewGroup
    if (root !is ViewGroup) return null
    for (index in 0 until root.childCount) {
        findListInTree(root.getChildAt(index))?.let { return it }
    }
    return null
}

private fun attach(feature: ListIslands, list: ViewGroup) {
    ensureContainerStyled(
        feature = feature,
        container = list,
        groupStart = GroupStart { _, child -> ownGroupKeyOf(child) != null },
        excluded = blankContentRow,
    )
    WeLogger.i(TAG, "address islands attached (children=${list.childCount})")
}

/**
 * 这一行**自己**是否声明了「我是一个新分组的开头」；返回 null 表示「跟着上一行」。
 *
 * 规则（对应用户指定，与主页一致）：
 *  - 字母分组标题行（A / B / C …）→ 开新岛，于是每个字母自成一块
 *  - 含「我的企业」的行 → 开新岛（让「企业联系人」跟它同岛）
 *  - 其余行 → null，继承上一行所属的岛
 *
 * ⚠️ 用**字母标题**而不是「凡分组标题都开新岛」：通讯录里还有
 * 「我的企业及企业联系人」这类中文标题行，它开岛后「企业联系人」自然也同岛，
 * 不需要额外特判；而顶部那批固定入口（新的朋友 / 群聊 / 标签 …）没有标题行，
 * 于是自然连成一块 —— 与用户要的三段一致。
 */
private fun ownGroupKeyOf(row: View): String? {
    if (isSectionHeaderRow(row)) {
        val label = singleShortText(row)?.trim().orEmpty()
        if (label.isNotEmpty()) {
            // 单个字母/字符的标题（A~Z、#）按字母分组；更长的标题按标题文本分组
            return if (label.length == 1) "alpha:$label" else "section:$label"
        }
    }
    val text = collectTexts(row).joinToString(" ")
    if (text.contains("我的企业")) return "mine"
    return null
}

/**
 * 判定一个子项是否是通讯录的分组标题行。
 *
 * 结构性判据（不依赖文案与混淆类名）：不可点击、高度明显小于普通联系人行、
 * 只有一个短文本。
 *
 * ⚠️ 这三个条件缺一不可：普通联系人行同样「只有一个非空文本」（就是姓名），
 * 只靠文本数量会把**每一个联系人**都判成章节标题，于是每行各自成一个岛。
 */
private fun isSectionHeaderRow(row: View): Boolean {
    if (row.isClickable) return false
    val density = row.resources.displayMetrics.density
    val maxHeaderHeightPx = (40f * density).toInt()
    if (row.height <= 0 || row.height > maxHeaderHeightPx) return false
    val label = singleShortText(row) ?: return false
    return label.length <= 6
}

/** 行内只有唯一一个非空文本时返回它（分组标题的特征），否则 null。 */
private fun singleShortText(root: View): String? {
    val texts = collectTexts(root)
    return if (texts.size == 1) texts.first() else null
}

private fun collectTexts(root: View): List<String> {
    val out = ArrayList<String>()
    collectTextsInto(root, out)
    return out
}

private fun collectTextsInto(root: View, out: MutableList<String>) {
    if (root is ViewGroup) {
        if (root.visibility != View.VISIBLE) return
        for (index in 0 until root.childCount) collectTextsInto(root.getChildAt(index), out)
    }
    if (root is TextView && root.visibility == View.VISIBLE) {
        val text = root.text?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) out += text
    }
}
