# WeLite

适用于微信的 Xposed 模块 — 精简实用版

## 📖 介绍

WeLite 是基于 [WeKit](https://github.com/Ujhhgtg/WeKit) 精简而来的微信 Xposed 模块，**仅保留常用核心功能**，砍掉了 AI 智能体、脚本引擎、界面美化等非必要模块，体积更小、更专注。

## ✨ 功能

### 💬 聊天
- 防撤回
- 自动语音转文字
- 禁止上传输入状态
- 自动启用发送原图
- 自动查看原图
- 显示群成员身份（群主/管理员/成员）
- 群成员行为监控
- 查看群员邀请者

### 👥 联系人与群组
- 查看群员历史消息
- 圆角头像
- 检测单向删除好友

### 📸 朋友圈
- 朋友圈防撤回
- 评论防撤回
- 自动点赞
- 拦截朋友圈广告

### 🧧 红包与支付
- 自动抢红包
- 自动接收转账
- 允许领取私聊红包
- 红包页面详情
- 指纹支付
- 修改余额/转账显示
- 历史红包

### 🔧 系统与隐私
- 去除文章广告
- 强制平板模式
- 恢复旧版「我」界面卡包
- 省电模式
- 禁止屏幕高亮度
- 禁止微信检测 Xposed
- 禁用微信热更新
- 移除二维码扫描限制
- 移除媒体发送数量限制
- 阻止微信清理模块数据
- 隐藏模块应用
- 二维码扫描记录
- 清理缓存垃圾
- 自动批准设备登录

### 📱 小程序
- 伪装宿主版本
- 去除菜单限制
- 移除开屏广告
- 移除视频广告

### 🔇 其他
- 屏蔽铃声
- 隐藏消息头像

## 📥 安装

1. 确保手机已安装 [LSPosed](https://github.com/LSPosed/LSPosed) 或其它 Xposed 框架
2. 下载最新 APK 从 [Releases](https://github.com/SymonChu/WeKit/releases)
3. 在 Xposed 模块中启用 WeLite
4. 作用域勾选微信
5. 重启微信

## 🛠 构建

```bash
git clone https://github.com/SymonChu/WeKit.git
cd WeKit
./x build
```

APK 输出在 `app/build/outputs/apk/standard/debug/`

### 构建要求
- JDK 21
- Android SDK
- Android NDK
- Rust toolchain + Android targets

## 📄 许可

[GPL-3.0 License](LICENSE)

基于 [WeKit](https://github.com/Ujhhgtg/WeKit) 修改，感谢原作者 [Ujhhgtg](https://github.com/Ujhhgtg) 的杰出工作。

## 🙏 致谢

- [WeKit](https://github.com/Ujhhgtg/WeKit) — 上游项目
- [WAuxiliary](https://github.com/HdShare/WAuxiliary_Public)
- [NewMiko](https://github.com/dartcv/NewMiko)
- [QAuxiliary](https://github.com/cinit/QAuxiliary)
- [FingerprintPay](https://github.com/eritpchy/FingerprintPay)
- [FunBox](https://github.com/Ujhhgtg/funbox_deobf)
- [I-Am-Pad](https://github.com/Houvven/I-Am-Pad)
