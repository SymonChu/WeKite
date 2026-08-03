# WeKite

适用于微信的 Xposed / Zygisk 模块 — 精简实用版

基于 [WeKit](https://github.com/Ujhhgtg/WeKit) 精简而来，**仅保留常用核心功能**，砍掉了 AI 智能体、脚本引擎等非必要模块，体积更小、更专注。内置 **莫奈取色引擎**，让微信跟随系统壁纸主题色。

## ✨ 功能

### 💬 聊天
- 防撤回 · 自动语音转文字 · 禁止上传输入状态
- 自动启用发送原图 · 自动查看原图
- 显示群成员身份 · 群成员行为监控 · 查看群员邀请者

### 👥 联系人与群组
- 查看群员历史消息 · 圆角头像 · 检测单向删除好友

### 📸 朋友圈
- 朋友圈防撤回 · 评论防撤回 · 自动点赞 · 拦截朋友圈广告

### 🧧 红包与支付
- 自动抢红包 · 自动接收转账 · 允许领取私聊红包
- 红包页面详情 · 指纹支付 · 修改余额/转账显示 · 历史红包

### 🎨 莫奈取色（Monet）
- **莫奈引擎**：微信原生组件（按钮/气泡/开关/光标）跟随主题色
- **动态壁纸取色**：从系统壁纸提取主题色应用到微信（Android 12+）
- **自定义颜色**：手动选择种子色，支持浅色/深色模式
- Zygisk 版内置 **WeChat Monet overlays**（Play 版微信 8.0.72）：
  - Monet 基础主题 · 气泡 Pro · 经典气泡 · 多场景圆角 · 纯色底栏

### 🔧 系统与隐私
- 去除文章广告 · 强制平板模式 · 恢复旧版「我」界面卡包
- 省电模式 · 禁止屏幕高亮度 · 禁止微信检测 Xposed
- 禁用微信热更新 · 移除二维码扫描限制 · 移除媒体发送数量限制
- 阻止微信清理模块数据 · 隐藏模块应用 · 二维码扫描记录
- 清理缓存垃圾 · 自动批准设备登录

### 📱 小程序
- 伪装宿主版本 · 去除菜单限制 · 移除开屏广告 · 移除视频广告

### 🔇 其他
- 屏蔽铃声 · 隐藏消息头像

## 📥 安装

### 方式一：Xposed（LSPosed）

1. 手机需安装 [LSPosed](https://github.com/LSPosed/LSPosed) 或其它 Xposed 框架
2. 下载 APK 从 [Releases](https://github.com/SymonChu/WeKite/releases)
3. 在 LSPosed 中启用 WeKite，作用域勾选微信
4. 重启微信
5. 设置入口：桌面 WeKite 图标，或 LSPosed 模块列表点击 WeKite

### 方式二：Zygisk（Magisk / KernelSU）

1. 下载 Zygisk 模块 zip（`wekit-zygisk-*.zip`）从 [Releases](https://github.com/SymonChu/WeKite/releases)
2. 在 Magisk / KernelSU 中刷入模块
3. 重启设备
4. 模块内置莫奈 overlay（Play 版微信 8.0.72 自动生效）+ 注入管理（KernelSU WebUI）

## 🎨 莫奈取色使用

1. 打开 WeKite 设置 → 主题
2. 开启「自定义颜色」
3. 开启「动态壁纸取色」（跟随壁纸）或手动选种子色
4. 开启「将自定义配色应用到微信本身」
5. 重启微信生效

Zygisk 版：刷入后莫奈 overlay 自动安装（需 Play 版微信 8.0.72，versionCode 3083/3084），重启后微信自动取色。

## 🛠 构建

```bash
git clone https://github.com/SymonChu/WeKite.git --recursive
cd WeKite
./x build            # Xposed APK（debug）
./x build --release  # Xposed APK（release，R8 压缩）
./x zygisk build --apk-release --release  # Zygisk 模块 zip
```

- APK 输出：`app/build/outputs/apk/standard/release/`
- Zygisk 模块：`wekit-zygisk/release/*.zip`

### 构建要求
- JDK 21
- Android SDK + NDK
- Rust toolchain (nightly) + Android targets

### CI 构建
GitHub Actions 自动构建，产物：
- `wekite-apk`：Xposed 版 APK（Release 压缩）
- `wekite-zygisk`：Zygisk 模块 zip（含莫奈 overlays）

## 📄 许可

[GPL-3.0 License](LICENSE)

## 🙏 致谢

- [WeKit](https://github.com/Ujhhgtg/WeKit) — 上游项目
- [WeChatMonet Pro](https://github.com/SaiOogcn/WechatMonet) — 莫奈 overlay 参考
- [WAuxiliary](https://github.com/HdShare/WAuxiliary_Public)
- [NewMiko](https://github.com/dartcv/NewMiko)
- [QAuxiliary](https://github.com/cinit/QAuxiliary)
- [FingerprintPay](https://github.com/eritpchy/FingerprintPay)
- [FunBox](https://github.com/Ujhhgtg/funbox_deobf)
- [I-Am-Pad](https://github.com/Houvven/I-Am-Pad)
