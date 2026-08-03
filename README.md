# WeKite

让微信更好用的轻量级增强模块 — 支持 LSPosed 与 Zygisk 双模式

WeKite 是一套针对微信的模块化增强方案：核心功能全部围绕「不打扰、可开关、低开销」设计，并内置 **Monet 动态取色引擎**，让微信界面跟随系统壁纸主题色自然变化。

## ✨ 功能一览

### 💬 聊天增强
- 消息防撤回 · 语音自动转文字 · 输入状态隐藏
- 自动发送原图 · 自动查看原图
- 群成员身份展示 · 群行为监控 · 群员邀请者查询

### 👥 联系人与群组
- 群员历史消息 · 圆角头像 · 单向好友检测

### 📸 朋友圈
- 防撤回 · 评论防撤回 · 自动点赞 · 广告拦截

### 🧧 红包与支付
- 自动抢红包 · 自动收转账 · 私聊红包领取
- 红包详情 · 指纹支付 · 余额显示定制 · 红包记录

### 🎨 Monet 动态取色
- **莫奈引擎**：微信原生组件（按钮/气泡/开关/光标）跟随主题色
- **动态壁纸取色**：Android 12+ 从系统壁纸提取主色
- **自定义种子色**：手动选色，支持明暗双模式
- Zygisk 版附带原生 Overlay 主题包（适配 Play 版微信 8.0.72）：
  - Monet 基础主题 · 气泡 Pro · 经典气泡 · 多场景圆角 · 纯色底栏

### 🔧 系统与隐私
- 文章广告移除 · 平板模式强制 · 旧版「我」页卡包恢复
- 省电模式 · 高亮限制 · Xposed 检测规避
- 热更新禁用 · 扫码限制移除 · 媒体数量限制移除
- 模块数据保护 · 模块隐藏 · 扫码记录
- 缓存清理 · 设备登录自动批准

### 📱 小程序
- 宿主版本伪装 · 菜单限制解除 · 开屏/视频广告移除

### 🔇 其他
- 铃声屏蔽 · 消息头像隐藏

## 📥 安装

### 方式一：LSPosed（Xposed 模式）

1. 安装 [LSPosed](https://github.com/LSPosed/LSPosed) 框架
2. 从 [Releases](https://github.com/SymonChu/WeKite/releases) 下载 APK 并安装
3. LSPosed → 模块 → 启用 WeKite，作用域勾选微信
4. 重启微信

设置入口：桌面 WeKite 图标，或 LSPosed 模块页点击 WeKite。

### 方式二：Zygisk（Magisk / KernelSU）

1. 从 [Releases](https://github.com/SymonChu/WeKite/releases) 下载 `wekit-zygisk-*.zip`
2. Magisk / KernelSU 刷入模块
3. 重启设备
4. 微信 Monet Overlay 自动生效（Play 版 8.0.72）；注入管理在 KernelSU WebUI

## 🎨 莫奈取色使用

1. 打开 WeKite → 主题设置
2. 开启「自定义颜色」
3. 选择「动态壁纸取色」或手动指定种子色
4. 开启「应用到微信本身」
5. 重启微信生效

Zygisk 版刷入后 Overlay 自动安装，无需额外配置。

## 🛠 构建

```bash
git clone https://github.com/SymonChu/WeKite.git --recursive
cd WeKite
./x build --release          # Xposed APK
./x zygisk build --apk-release --release   # Zygisk 模块
```

产物：
- APK → `app/build/outputs/apk/standard/release/`
- Zygisk → `wekit-zygisk/release/*.zip`

### 环境要求
- JDK 21 · Android SDK + NDK · Rust nightly + Android targets

GitHub Actions 自动构建并产出 APK 与 Zygisk 模块。

## 📄 许可

[GPL-3.0 License](LICENSE)

## 🙏 致谢

- [WeKit](https://github.com/Ujhhgtg/WeKit) — 上游项目
- [WechatMonet](https://github.com/SaiOogcn/WechatMonet) — Overlay 主题参考
- [WAuxiliary](https://github.com/HdShare/WAuxiliary_Public) · [QAuxiliary](https://github.com/cinit/QAuxiliary) · [FingerprintPay](https://github.com/eritpchy/FingerprintPay)
