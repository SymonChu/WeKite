# 群聊归拢 · 真机验证清单

> 用途：装机后按顺序走一遍，每步都有「预期」「日志判据」「失败时查哪里」。
> 代码位置：`app/src/main/java/com/github/wekite/features/items/chat/GroupAggregation.kt`
> （守护条目：同目录 `GroupAggregationGuard.kt`），日志 TAG 全部是 `GroupAggregation`。

## 0. 准备

| 项 | 做法 |
|---|---|
| 记录宿主版本 | 设置 → 关于，记下微信版本号与 versionCode（当前实现按 8.0.80/3220、8.0.79 Play 核验） |
| 备份配置 | 设置 → 导出配置（默认文件名 `wekite_prefs_backup.json`）。本版起内部配置文件名从 `wekit_prefs` 改名 `wekite_prefs`，跨版本升级必须导出→导入 |
| 打开日志 | 设置 → 详细日志（应保持开启，排查时需要 `hooks`/`applied` 行） |
| 记住入口 | 本功能在「聊天」分类，默认**关闭** |

## 1. 启动与锚点

**操作**：打开「群聊归拢」开关 → 杀掉微信 → 重新打开微信 → 停在会话列表。

**预期**：日志出现两行；列表暂时还没有变化（首次 apply 在开关打开时已执行，若开关是在本轮之前就打开，则启动即生效）。

**日志判据**：

```
GroupAggregation: anchor host=<版本> getView=<方法签名> onItemClick=<方法签名> setParentRef=<…> clearParentRef=<…>
GroupAggregation: hooks getView=true click=true menu=true
```

| 失败形态 | 含义 | 查哪里 |
|---|---|---|
| 某锚点显示 `<placeholder>` | DexKit 没命中该版本 | 用 8.0.x 的 APK 重跑 `workspace/probe_stage0.py` / `probe_wx8080_adapter.py` 比对字符串 |
| `menu=false` | `WeConversationContextMenuApi` 挂了 | 该 API 的 `methodOnCreateMenu` 锚点 `MicroMsg.ConversationLongClickListener` |
| `getView=false` 或 `click=false` | 会话列表两套后端都没命中 | 先确认宿主版本；`ConversationWithCacheAdapter` 在 8.0.78+ 已不是 adapter |

## 2. 入口行是否出现

**预期**：会话列表顶部出现「其他群」条目（若已有分组则出现各分组条目），副标题是 `N 个群聊 · M 条未读`，右侧红色角标。

**日志判据**：

```
GroupAggregation: applied entries=[(其他群, 12)] hiddenGroups=12 rootGroups=0
GroupAggregation: entry bound: wekite_groupagg_other      （D 级，节流 5 秒一条）
```

| 现象 | 最可能原因 |
|---|---|
| 没有条目，`applied … hiddenGroups=0` | 数据库没就绪或写入被拒；查同一行的 `rootGroups` |
| 没有条目，但有 `applied` | 宿主没刷新列表 → 看 `WeConversationApi.reloadConversations()` 是否被主线程调度 |
| 条目出现但显示的是 `wekite_groupagg_other` 原文 | `rcontact` 昵称行没写进去（`insertOrReplace` 失败会打 `insert into rcontact failed`） |
| 条目在列表中间而不是顶部 | `flag` 高位没被置顶：`setPinned` 调用失败会打 `setPlacedTop unavailable on this build` |

## 3. 群聊是否真的从主页消失

**预期**：主页只剩好友会话 + 分组条目；原来置顶的群也不再出现。

**判据**：`applied … hiddenGroups=N rootGroups=0`（`rootGroups` 应为 0，非 0 说明有群被宿主改回了根级）。

## 4. 点条目进分组列表

**操作**：点「其他群」条目。

**预期**：弹出群列表（未读优先），点任意群能进入该群聊天；弹窗底部有「重命名」「关闭」。

**失败形态**：

| 现象 | 原因 |
|---|---|
| 点击无反应 | `onItemClick` 锚点未命中，或 `getItem(position)` 反射取不到会话（`usernameAt` 返回 null） |
| 打开了「找不到该聊天」 | 点击没被消费（`result = null` 未生效）→ 查 `entry row clicked` 日志是否出现 |
| 弹窗空白 | `loadGroups()` 查不到行：确认群行的 `parentRef` 是 `hidden_conv_parent` |

## 5. 长按群 →「移入分组」

**操作**：主页长按任意群 → 菜单里应出现「移入分组」→ 选「＋ 新建分组…」→ 输入「测试组」。

**预期**：菜单项出现；确认后列表多出「测试组」条目，该群不再出现在「其他群」，且主页依然看不到它。

**日志判据**：`applied entries=[(测试组, 1), (其他群, 11)]`。

**失败形态**：菜单里根本没有「移入分组」→ 这是本次修过的点（`ui/conversation/x3` 的 talker 字段是 `java.lang.String` 不是 `BString`）；若仍不行，检查 `WeConversationContextMenuApi.resolveContext` 是否取到非空 talker。

## 6. 长按分组条目 →「重命名分组」

**操作**：长按「测试组」条目 → 重命名 → 输入「测试组2」。

**预期**：条目名立即变化；`rcontact.nickname` 同步（重启微信后仍是新名）。

## 7. 删除分组

**操作**：点「测试组2」条目 → 弹窗底部「删除分组」。

**预期**：该条目消失，成员回到「其他群」；群依然不在主页。

## 8. 新消息不闪回

**操作**：用另一台设备/小号给某个被藏起来的群发消息，停在主页观察 10 秒。

**预期**：群不会出现在主页；「其他群」条目的未读数增加。

**日志判据**：`applied …`（重新收敛）出现的频率 ≤ 1 次/分钟；不应出现刷屏。

## 9. 关闭开关 → 完整还原

**操作**：关掉「群聊归拢」。

**预期**：所有分组条目消失，群回到主页原位（含原来的置顶状态）；日志 `groups restored`。

## 10. 杀进程重开（自愈）

**操作**：关掉开关后重启微信；再打开开关、重启微信。

**预期**：两种情况下都不应出现「群被藏了但没有任何入口」。守护条目会在启动时打：

```
GroupAggregation: feature disabled but state applied, restoring        （开关关了但改动还在）
GroupAggregation: entry rows missing on startup, restoring groups      （入口行被微信清掉）
```

## 安全回退（三档，按顺序）

1. **关开关**：`onDisable` 会把 `parentRef` 改回空串并删入口行。
2. **在微信里手动恢复**：长按群 →「不显示该聊天」开关关掉（微信原生行为，不依赖模块）。
3. **停用模块**：下次启动时 `GroupAggregationGuard` 会检测「开关已关但状态还在」并还原；若模块整体停用导致守护也没跑，需重新启用模块一次让它自愈。

## 记录模板（回填给开发）

```
微信版本 / versionCode：
锚点行：anchor …（贴原文）
hooks 行：hooks getView=… click=… menu=…
applied 行：applied …（首次 / 移入分组后 各一条）
失败步骤编号：
现象（截图或描述）：
相关日志片段（grep GroupAggregation）：
```
