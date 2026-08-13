# WeKite

让微✉️更好用的轻量级增强模块 — 支持 LSPosed 与 Zygisk 双模式

> 🙏 **致谢（原始上游 [WeKit](https://github.com/cwuom/WeKit)）**：本项目基于上游 WeKit 二次开发，感谢原作者的开源贡献。
>
> 🤖 **本项目由 AI 编写**。
>
> ✅ **编译状态：CI 构建成功（最近一次：2026-08-10）**
>
> - **LSPosed 版本**：APK 已签名，可安装测试（见下方安装说明）
> - **Zygisk 版本**：模块包可用，支持 Magisk / KernelSU（见下方安装说明）

WeKite 是一套针对微✉️的模块化增强方案：核心功能全部围绕「不打扰、可开关、低开销」设计。

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
| 群成员实名尾字 | 通过转账接口获取并显示群成员的实名尾字 |
| 自动缓存图片 | 收到图片自动从 CDN 缓存原图到本地 |
| 自动缓存文件 | 收到文件自动触发下载缓存到本地 |
| 半屏相册选择器 | 聊天相册选择器/预览/搜索改为半屏卡片显示 |
| 隐藏对话列表分割线 | 隐藏主页对话列表里对话间的分割线 |

### 🎨 界面美化
| 功能 | 说明 |
|------|------|
| 莫奈引擎 | 动态取色，将自定义配色应用到微✉️原生组件 |
| 自定义配色 | 个性化界面颜色方案 |
| 底部导航栏美化 | 自定义首页底部导航样式（支持 Telegram 风格悬浮底栏） |
| 圆角头像 | 将头像显示为圆角样式 |
| 隐藏其他设备横幅 | 隐藏主页顶部其他设备登录横幅 |

### 📞 音视频通话
| 功能 | 说明 |
|------|------|
| 屏蔽铃声 | 屏蔽音视频通话的呼出/呼入铃声 |
| 虚拟视频通话 | 在微✉️视频通话相机预览中播放本地视频或网络直播流 |

### 👥 联系人与群组
| 功能 | 说明 |
|------|------|
| 群员历史消息 | 查看群成员的历史发言记录 |
| 单向好友检测 | 检测谁删除了你（单删） |
| 显示微✉️ ID | 在联系人与群组详情页面显示微✉️ ID（点击复制） |
| 隐藏联系人 | 隐藏指定的联系人（对话列表/通讯录/搜索/朋友圈/通知/通话等 22 处，支持定时显示） |

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
| 历史红包 | 查看历史红包记录 |

### 🔧 系统与隐私
| 功能 | 说明 |
|------|------|
| 文章广告移除 | 去除公众号文章内广告 |
| 平板模式强制 | 强制启用平板布局 |
| 旧版「我」页卡包 | 恢复旧版「我」界面卡包入口 |
| 省电模式 | 降低后台耗电 |
| 高亮限制 | 禁止屏幕异常高亮 |
| Xposed 检测规避 | 阻止微✉️检测 Xposed 环境 |
| 热更新禁用 | 禁用微✉️热更新机制 |
| 扫码限制移除 | 移除二维码扫描限制 |
| 模块数据保护 | 阻止微✉️清理模块数据 |
| 模块隐藏 | 隐藏模块应用图标 |
| 扫码记录 | 记录扫码历史 |
| 缓存清理 | 一键清理微✉️缓存 |
| 设备登录自动批准 | 其他设备登录自动批准 |

### 📱 小程序
| 功能 | 说明 |
|------|------|
| 宿主版本伪装 | 伪装微✉️版本号 |
| 菜单限制解除 | 解除小程序菜单限制 |
| 开屏广告移除 | 移除小程序开屏广告 |
| 视频广告移除 | 移除小程序视频广告 |
| 跳过启动页面 | 跳过小程序启动页面（实验性） |

### 📰 公众号
| 功能 | 说明 |
|------|------|
| 公众号去广告 | 清除公众号信息流中的广告（订阅号信息流/推荐流/聚合页） |

## 📥 安装

> ✅ **LSPosed 版本已可安装使用**（从 [Releases](https://github.com/SymonChu/WeKite/releases) 下载）。

### 方式一：LSPosed（Xposed 模式）

**适用**：已安装 LSPosed 框架的设备

1. 安装 [LSPosed](https://github.com/LSPosed/LSPosed) 框架（Android 8.0+）
2. 从 [Releases](https://github.com/SymonChu/WeKite/releases) 下载 APK 并安装
3. 打开 LSPosed → 模块 → 启用 WeKite
4. 勾选作用域：**微✉️（com.tencent.mm）**
5. 完全退出微✉️并重新打开

**设置入口**：桌面 WeKite 图标，或 LSPosed 模块页点击 WeKite。

**注意事项**：
- 支持微✉️ 8.0.65 ~ 8.0.76
- 需在 LSPosed 中启用模块并勾选作用域，否则功能不生效
- 部分功能修改微✉️界面，重启微✉️后生效

### 方式二：Zygisk（Magisk / KernelSU）

**适用**：已 Root 且启用 Zygisk 的设备（Magisk 或 KernelSU）

1. 从 [Releases](https://github.com/SymonChu/WeKite/releases) 下载 `WeKite-*-release.zip`（Zygisk 模块包）
2. 在 Magisk / KernelSU 中刷入模块
3. 重启设备
4. 打开 Root 管理器中的 WeKite 模块 WebUI，为微✉️打开注入开关

**特点**：
- 无需 Xposed 框架，Zygisk 原生注入
- KernelSU WebUI 管理注入目标

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
| Zygisk 模块 | `wekite-zygisk/release/WeKite-*.zip` |

## ❓ 常见问题

**Q: 安装后找不到设置入口？**
A: LSPosed 模式需在 LSPosed 中启用模块并勾选微✉️作用域；Zygisk 模式入口在 Root 管理器的模块 WebUI。两者入口不同，请确认你用的框架。

**Q: 支持国内版微✉️吗？**
A: 核心功能（Xposed 模式）支持 8.0.65~8.0.76 全部版本。

## 📄 许可

[GPL-3.0 License](LICENSE)

## 🙏 致谢

- [WeKit](https://github.com/Ujhhgtg/WeKit) — 上游项目
- [WeKit](https://github.com/cwuom/WeKit) — WeKit 原始上游
- [WechatMonet](https://github.com/SaiOogcn/WechatMonet) — Overlay 主题参考
- [WAuxiliary](https://github.com/HdShare/WAuxiliary_Public) · [QAuxiliary](https://github.com/cinit/QAuxiliary) · [FingerprintPay](https://github.com/eritpchy/FingerprintPay)
