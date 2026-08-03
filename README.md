# WeKite

让微信更好用的轻量级增强模块 — 支持 LSPosed 与 Zygisk 双模式

WeKite 是一套针对微信的模块化增强方案：核心功能全部围绕「不打扰、可开关、低开销」设计，并内置 **Monet 动态取色引擎**，让微信界面跟随系统壁纸主题色自然变化。

## ✨ 功能一览

### 💬 聊天增强
| 功能 | 说明 |
|------|------|
| 消息防撤回 | 阻止对方撤回消息，撤回仍可查看 |
| 语音自动转文字 | 收到的语音消息自动转为文字 |
| 输入状态隐藏 | 禁止上传"对方正在输入"状态 |
| 自动发送原图 | 发送图片时默认勾选原图 |
| 自动查看原图 | 点开图片自动加载原图 |
| 群成员身份展示 | 群聊中显示成员身份/角色 |
| 群行为监控 | 监控成员加入/退出/发言等行为 |
| 群员邀请者查询 | 查看成员是被谁拉进群的 |

### 👥 联系人与群组
| 功能 | 说明 |
|------|------|
| 群员历史消息 | 查看群成员的历史发言记录 |
| 圆角头像 | 将头像显示为圆角样式 |
| 单向好友检测 | 检测谁删除了你（单删） |

### 📸 朋友圈
| 功能 | 说明 |
|------|------|
| 朋友圈防撤回 | 好友删除的朋友圈仍可查看 |
| 评论防撤回 | 被删除的评论保留显示 |
| 自动点赞 | 一键/自动给朋友圈点赞 |
| 广告拦截 | 屏蔽朋友圈广告流 |

### 🧧 红包与支付
| 功能 | 说明 |
|------|------|
| 自动抢红包 | 群聊红包自动抢 |
| 自动接收转账 | 转账自动确认收款 |
| 私聊红包领取 | 允许领取私聊红包 |
| 红包页面详情 | 显示红包更多信息 |
| 指纹支付 | 支付/转账启用指纹验证 |
| 余额显示定制 | 自定义余额/转账显示 |
| 历史红包 | 查看历史红包记录 |

### 🎨 Monet 动态取色
| 功能 | 说明 |
|------|------|
| 莫奈引擎 | 微信原生组件（按钮/气泡/开关/光标）跟随主题色 |
| 动态壁纸取色 | Android 12+ 从系统壁纸提取主色 |
| 自定义种子色 | 手动选色，支持明暗双模式 |

Zygisk 版附带原生 Overlay 主题包（适配 Play 版微信 8.0.72）：
- **Monet 基础主题** — 全局取色覆盖
- **现代圆角气泡** — 现代化圆角聊天气泡（默认）
- **经典气泡** — 经典圆润气泡（含红包/转账专属样式）
- **多场景圆角** — 输入栏/消息引用/支付键盘圆角优化
- **纯色底栏** — 纯色底部标签栏

### 🔧 系统与隐私
| 功能 | 说明 |
|------|------|
| 文章广告移除 | 去除公众号文章内广告 |
| 平板模式强制 | 强制启用平板布局 |
| 旧版「我」页卡包 | 恢复旧版「我」界面卡包入口 |
| 省电模式 | 降低后台耗电 |
| 高亮限制 | 禁止屏幕异常高亮 |
| Xposed 检测规避 | 阻止微信检测 Xposed 环境 |
| 热更新禁用 | 禁用微信热更新机制 |
| 扫码限制移除 | 移除二维码扫描限制 |
| 媒体数量限制移除 | 突破发送媒体数量限制 |
| 模块数据保护 | 阻止微信清理模块数据 |
| 模块隐藏 | 隐藏模块应用图标 |
| 扫码记录 | 记录扫码历史 |
| 缓存清理 | 一键清理微信缓存 |
| 设备登录自动批准 | 其他设备登录自动批准 |

### 📱 小程序
| 功能 | 说明 |
|------|------|
| 宿主版本伪装 | 伪装微信版本号 |
| 菜单限制解除 | 解除小程序菜单限制 |
| 开屏广告移除 | 移除小程序开屏广告 |
| 视频广告移除 | 移除小程序视频广告 |

### 🔇 其他
- 屏蔽铃声 · 隐藏消息头像

## 📥 安装

### 方式一：LSPosed（Xposed 模式）

**适用**：已安装 LSPosed 框架的设备

1. 安装 [LSPosed](https://github.com/LSPosed/LSPosed) 框架（Android 8.0+）
2. 从 [Releases](https://github.com/SymonChu/WeKite/releases) 下载 APK 并安装
3. 打开 LSPosed → 模块 → 启用 WeKite
4. 勾选作用域：**微信（com.tencent.mm）**
5. 完全退出微信并重新打开

**设置入口**：桌面 WeKite 图标，或 LSPosed 模块页点击 WeKite。

**注意事项**：
- 支持微信 8.0.65 ~ 8.0.76
- 需在 LSPosed 中启用模块并勾选作用域，否则功能不生效
- 部分功能修改微信界面，重启微信后生效

### 方式二：Zygisk（Magisk / KernelSU）

**适用**：已 Root 且启用 Zygisk 的设备（Magisk 或 KernelSU）

1. 从 [Releases](https://github.com/SymonChu/WeKite/releases) 下载 `wekit-zygisk-*.zip`
2. 在 Magisk / KernelSU 中刷入模块
3. 重启设备
4. 打开 Root 管理器中的 WeKite 模块 WebUI，为微信打开注入开关

**特点**：
- 无需 Xposed 框架，Zygisk 原生注入
- 内置 Monet Overlay 主题（Play 版微信 8.0.72 自动生效）
- KernelSU WebUI 管理注入目标

## 🎨 莫奈取色使用

### Xposed 模式（LSPosed）

1. 打开 WeKite → 主题设置
2. 开启「自定义颜色」
3. 选择「动态壁纸取色」或手动指定种子色
4. 开启「应用到微信本身」
5. 重启微信生效

### Zygisk 模式（Overlay 主题）

**气泡样式切换**：

1. 在 Magisk / KernelSU 中打开 WeKite 模块的「执行」（Action）
2. 按提示操作：
   - 音量 **+** = 切换气泡样式（现代圆角 ↔ 经典气泡）
   - 音量 **-** = 保持当前样式，重启微信
3. 切换后重启微信生效

**默认主题**：现代圆角气泡 + Monet 基础主题 + 多场景圆角 + 纯色底栏

**版本要求**：Overlay 主题适配 **Play 版微信 8.0.72**（versionCode 3083/3084），其他版本会自动跳过覆盖，不影响模块其他功能。

## 🛠 构建

### 环境要求

| 工具 | 版本 |
|------|------|
| JDK | 21 |
| Android SDK | 含 NDK |
| Rust | nightly + Android targets |

### 构建命令

```bash
# 克隆（含子模块）
git clone https://github.com/SymonChu/WeKite.git --recursive
cd WeKite

# 构建 Xposed APK（release）
./x build --release

# 构建 Zygisk 模块
./x zygisk build --apk-release --release
```

### 产物位置

| 产物 | 路径 |
|------|------|
| Xposed APK | `app/build/outputs/apk/standard/release/` |
| Zygisk 模块 | `wekit-zygisk/release/*.zip` |

### CI 自动构建

GitHub Actions 在每次推送后自动构建，产物：

| Artifact | 说明 |
|----------|------|
| `wekite-apk` | Xposed 版 APK（R8 压缩） |
| `wekite-zygisk` | Zygisk 模块 zip（含 Monet overlays） |

## 📂 项目结构

```
app/                          Android 模块主工程
├── src/main/java/            主代码
│   ├── features/             功能实现（按类别分目录）
│   │   └── items/            具体功能项
│   │       └── beautify/     界面美化（MonetEngine 莫奈引擎）
│   ├── loader/               Xposed/Zygisk 入口
│   ├── activity/             模块自身 UI（设置界面）
│   └── utils/                工具类
├── src/standard/             standard flavor（libxposed 入口）
└── embedded/monet/           Monet overlay 素材
wekit-zygisk/                 Zygisk 模块工程
├── template/                 模块模板（安装脚本 + overlay APK）
└── native/                   Zygisk 原生库
libs/                         子模块（reflekt / bsh / stubs）
xtask/                        Rust 构建编排
```

## ❓ 常见问题

**Q: 安装后找不到设置入口？**
A: LSPosed 模式需在 LSPosed 中启用模块并勾选微信作用域；Zygisk 模式入口在 Root 管理器的模块 WebUI。两者入口不同，请确认你用的框架。

**Q: 莫奈取色不生效？**
A: Xposed 模式需开启「自定义颜色」+「应用到微信」并重启微信；Zygisk 模式需 Play 版微信 8.0.72。

**Q: 气泡样式能换吗？**
A: 可以。Zygisk 模式在模块「执行」菜单用音量键切换现代圆角/经典气泡。

**Q: 支持国内版微信吗？**
A: 核心功能（Xposed 模式）支持 8.0.65~8.0.76 全部版本；Monet Overlay 主题目前仅适配 Play 版 8.0.72。

## 📄 许可

[GPL-3.0 License](LICENSE)

## 🙏 致谢

- [WeKit](https://github.com/Ujhhgtg/WeKit) — 上游项目
- [WechatMonet](https://github.com/SaiOogcn/WechatMonet) — Overlay 主题参考
- [WAuxiliary](https://github.com/HdShare/WAuxiliary_Public) · [QAuxiliary](https://github.com/cinit/QAuxiliary) · [FingerprintPay](https://github.com/eritpchy/FingerprintPay)
