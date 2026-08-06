# RokidDev

AR 眼镜应用开发 monorepo，面向 [Rokid Glass](https://rokid.github.io/glass-docs/) 平台。每个子目录是一个独立 Android 应用。

## 项目结构

```
RokidDev/
├── README.md           ← 本文件
├── CLAUDE.md           ← 共享 Rokid 开发知识（硬件交互、构建管线、安全约束）
├── .docs/              ← 离线开发文档（Glass SDK、AIUI 框架、社区参考索引）
├── RokidGame/          ← 头部控制的 Flappy Bird 游戏
├── RokidMusic/         ← 电吉他六线谱阅读器 & 播放器
└── RokidTerm/          ← 远程 Claude Code 终端客户端（SSH + tmux + 服务端 ASR）
```

> `RokidLocalAsr/`（过时的眼镜端 ASR 测试应用）仅保留在本地磁盘，不纳入版本管理。

## 子项目一览

| 项目 | 说明 | 输入方式 | 状态 |
|---|---|---|---|
| **[RokidGame](./RokidGame/)** | Flappy Bird 克隆 — 点头控制小鸟飞越水管 | 头部 IMU（点头）+ 触摸板 | ✅ 可玩 |
| **[RokidMusic](./RokidMusic/)** | 电吉他 Tab 谱阅读/播放/编辑，支持手机 WiFi 传谱 | 触摸板 + 手机浏览器 | ✅ 核心功能完成 |
| **[RokidTerm](./RokidTerm/)** | 远程 Claude Code 终端：SSH 直连 + tmux 会话恢复 + 本地 composer + 服务端 ASR（SenseVoice） | 触摸板 + COIDEA 键盘 + INMO Ring4 | ✅ 日常可用（语音输入已验证） |

## 开发前置条件

- macOS + [Android Studio](https://developer.android.com/studio)
- Rokid Glass 设备（USB 连接，开启开发者选项 & USB 调试）
- Java 17（随 Android Studio 安装）

### 环境变量

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export ANDROID_HOME="$HOME/Library/Android/sdk"
```

每个子项目有独立的 `dev.sh` 脚本封装了上述变量及常用命令。

## 构建 & 部署

```bash
# 以 RokidGame 为例
cd RokidGame
./dev.sh run     # 构建 → 安装 → 启动
./dev.sh build   # 仅构建 APK
./dev.sh install # 仅安装到眼镜
./dev.sh log     # 查看实时崩溃日志
```

## Rokid Glass 开发要点

- 眼镜显示屏为 **480×640 竖屏，240dpi，绿色单色** AR 叠加
- 输入为**触摸板**（非触摸屏）：点击 = 确认，滑动 = 导航
- `adb screencap` 截屏结果像素完美，拖影为光学现象非软件问题
- 摄像头对准需通过 `RokidSystem.getAlignmentRect()` 计算显示区域映射
- 全屏自定义 View 需设置 `defaultFocusHighlightEnabled = false` 抑制框架焦点框

更多参考 [开发者文档](https://rokid.github.io/glass-docs/) 或本仓库 `.docs/` 目录。
