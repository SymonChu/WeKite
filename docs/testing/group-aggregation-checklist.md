# 群聊归拢 · 真机验证清单

> 用途：装机后按顺序走一遍，每步都有「预期」「日志判据」「失败时查哪里」。
> 代码：`app/src/main/java/com/github/wekite/features/items/chat/GroupAggregation.kt`
> （守护条目同目录 `GroupAggregationGuard.kt`），日志 TAG 统一 `GroupAggregation`。

## 0. 准备

| 项 | 做法 |
|---|---|
| 记录宿主版本 | 设置 → 关于，记下微信版本号与 versionCode（当前实现按 8.0.80/3220、8.0.79 Play 核验） |
| 备份配置 | 设置 → 导出配置（默认 `wekite_prefs_backup.json`）。4.03 及更早版本内部配置文件名是 `wekit_prefs`，跨版本升级需导出→导入 |
| 打开日志 | 设置 → 详细日志保持开启 |
| 功能位置 | 「聊天」分类，默认**关闭** |

## 1. 启动与锚点

**操作**：打开开关 → 杀掉微信 → 重开。

**日志判据**：

```
GroupAggregation: anchor host=<版本> getView=<方法> onItemClick=<方法> setParentRef=<方法> clearParentRef=<方法>
GroupAggregation: hooks getView=true click=true menu=true
```

| 失败形态 | 含义 |
|---|---|
| 某锚点 `<placeholder>` | DexKit 没命中该版本；用 `workspace/probe_stage0.py` 比对字符串 |
| `menu=false` | 长按菜单 API 未挂上（锚 `MicroMsg.ConversationLongClickListener`） |
| `getView=false` / `click=false` | 会话列表后端没命中，先确认宿主版本 |

## 2. 入口条目出现 & 群被收起

**预期**：主页顶部出现「其他群」（或各分组）条目，副标题 `N 个群聊 · M 条未读 · 点此展开`，群聊从列表消失。

**日志判据**：`applied entries=[(其他群, 12)] foldedGroups=12 rootGroups=0`

| 现象 | 最可能原因 |
|---|---|
| 有 `applied` 但没条目 | 宿主没刷新列表 / `rcontact` 昵称行没写进去（看 `insert into rcontact failed`） |
| 条目出现在列表中间 | `flag` 高位没置顶：看 `setPlacedTop unavailable on this build` |
| `foldedGroups=0` | 成员 `parentRef` 没写进去（`apply failed`） |

## 3. 点条目展开

**操作**：点「其他群」。

**预期**：群聊**立刻回到主页列表**；条目副标题变成 `… 已展开，点此收起`；微信不崩、不跳页。

**日志判据**：`toggle folder=other expanded=true`，随后一条 `applied entries=… rootGroups=N`（`rootGroups` 变成该分组成员数）。

**失败形态**：

| 现象 | 原因 |
|---|---|
| 点了没反应 | `onItemClick` 锚点未命中，或 `getItem(position)` 反射取不到会话 |
| 打开了「找不到该聊天」 | 点击没被消费（`result = null` 未生效） |
| 副标题没变 | `group_agg_expanded` 没存进去 / 宿主未刷新 |

## 4. 再点一次收起

**预期**：群重新从主页消失，副标题回到 `… 点此展开`；日志 `toggle folder=other expanded=false`。

## 5. 长按群 →「移入分组」

**操作**：长按任意群 →「移入分组」→「＋ 新建分组…」→ 输入「测试组」。

**预期**：弹出系统列表对话框；确认后多出「测试组」条目，该群从「其他群」移出，主页仍看不到它。

**日志判据**：`applied entries=[(测试组, 1), (其他群, 11)]`

## 6. 长按分组条目 →「重命名分组」

**预期**：弹出系统输入框；改名后条目名立即变化，重启微信仍是新名。

## 7. 新消息不闪回

**操作**：给某个被收起的群发消息，停在主页观察 10 秒。

**预期**：群不出现在主页；未读数计入对应条目。

## 8. 关闭开关 → 完整还原

**预期**：所有分组条目消失，群回到原位（含原置顶状态）；日志 `groups restored`。

## 9. 杀进程重开（自愈）

**预期**：不应出现「群被藏了却没有入口」。守护条目会在启动时打：

```
GroupAggregation: feature disabled but state applied, restoring
GroupAggregation: entry rows missing on startup, restoring groups
```

## 安全回退（按顺序）

1. 关开关：`onDisable` 还原 `parentRef` 并删入口行。
2. 微信里手动恢复：长按群关掉「不显示该聊天」（不依赖模块）。
3. 停用模块：下次启动 `GroupAggregationGuard` 检测「开关已关但状态还在」并还原。

## 记录模板

```
微信版本 / versionCode：
anchor 行：
hooks 行：
applied 行：
toggle 行：
失败步骤编号 + 现象：
```
