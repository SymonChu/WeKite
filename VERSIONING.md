# Versioning

WeKite 使用语义化版本号 (`major.minor`) 作为对外版本标识, 配合 git 派生的构建号。

## Module Version

`app/build.gradle.kts` 在构建时计算:

| Field         | Source                                                                      | Example |
|---------------|-----------------------------------------------------------------------------|---------|
| `versionCode` | `git rev-list --count HEAD` — total number of commits in the current branch | `925`   |
| `versionName` | 语义化版本号, 手动维护                                                       | `1.0`   |

- `versionCode` 随每次 commit 单调递增, 是更新检查 (AppUpdater) 的比较依据。
- `versionName` 是面向用户的语义化版本 (`1.0`, `1.1`, ...), 手工 bump。
- Release tag 使用 `v<versionName>` (如 `v1.0`), 与 versionCode 解耦。

The APK also embeds these in `BuildConfig`:

- `BuildConfig.COMMIT_HASH` — short commit hash
- `BuildConfig.TAG` — always `"WeKite"`
- `BuildConfig.BUILD_TIMESTAMP` — `System.currentTimeMillis()` at build time

## Release Model

- 每次 push 到 `master` 触发 CI, 构建并签名 APK + Zygisk 模块 ZIP。
- GitHub Release 由人工 (或脚本) 发布: tag `v<versionName>`, 附 3 个资产:
  - `app-standard-release.apk` — universal APK (含双 ABI native 库)
  - `WeKite-<versionCode>-<versionName>-release.zip` — Zygisk 模块包
  - `update.json` — `{"versionCode": <N>, "versionName": "<x.y>"}`, 供 AppUpdater 精确比较版本

### update.json

发布 Release 时随资产上传, 内容示例:

```json
{
  "versionCode": 925,
  "versionName": "1.0"
}
```

AppUpdater 优先读取该资产; 缺失时回退解析 Zygisk ZIP 资产名 (`WeKite-<N>-...-release.zip`) 或 tag (`v<N>`)。
