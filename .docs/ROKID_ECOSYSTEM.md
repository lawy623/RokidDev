# Rokid 眼镜开发生态 — 技术选型参考

本文档总结 Rokid 眼镜的所有开发路径及其适用场景，供开发决策时参考。

## 基础架构

```
Rokid 眼镜硬件 (480×640 单绿 Micro-LED, 触摸板, 摄像头, 麦克风)
  └── YodaOS (基于 AOSP Android 12 的 AI 眼镜操作系统)
        ├── Android 应用层 (APK)
        ├── AIUI 小程序容器 (QuickJS 运行时)
        ├── CXR-S (眼镜端蓝牙接收服务)
        └── 系统应用 (Launcher, 语音助手"乐奇", HTTP 服务器等)

手机端:
  ├── CXR-M SDK → 蓝牙 + Wi-Fi Direct → 全功能遥控 + UI推送
  └── CXR-L SDK → Hi Rokid App → 轻量AI输出 + APK安装
```

## 开发路径对比

| | AIUI 小程序 | Android APK | CXR-M 手机桥接 | CXR-L | 裸机开发 |
|---|---|---|---|---|---|
| **目标设备** | 眼镜独立运行 | 眼镜独立运行 | 手机→眼镜 | 手机→Hi Rokid→眼镜 | 眼镜硬件 |
| **开发语言** | JavaScript | Kotlin/Java | Kotlin/Java | Kotlin/Java | C/C++ (NDK) / Rust |
| **UI 能力** | .ink SFC + Canvas | Android View/Canvas | JSON→系统控件 (LinearLayout/TextView/ImageView) | 仅文本 | 完全自绘 |
| **Canvas 自绘** | ✅ 支持 | ✅ 支持 | ❌ 不支持 | ❌ 不支持 | ✅ (底层) |
| **边框问题** | ✅ 无 (系统容器) | ✅ 可关闭 Android 默认焦点框 | ✅ 无 (系统渲染) | N/A | 取决于实现 |
| **是否需要手机** | 否 | 否 | 是 | 是 (Hi Rokid) | 否 |
| **文件传输** | USB MTP / CXR-M | USB MTP / ADB | CXR-M sendStream/fileSync | 有限 | USB |
| **摄像头** | takePhoto JS API | Camera2 API | CXR-M 拍照 API | 有限 | Camera HAL |
| **语音** | 唤醒词 + ASR JS API | AudioRecord API | CXR-M ASR/TTS | AI 输出 | 底层音频 |
| **打包格式** | .aix | .apk | .apk (手机端) | .apk (手机端) | 系统镜像 |
| **官方文档** | js.rokid.com/AIUI | developer.android.com | custom.rokid.com CXR-M 文档 | custom.rokid.com CXR-L 文档 | 无官方 |
| **生态成熟度** | 新 (2026) | 成熟 | 成熟 | 较新 | 社区驱动 |
| **代表项目** | Cube Copilot | Flappy Bird, RokidMusic | GlassesReader | Rokid-APKs | ar_drivers (Rust) |

## 各方案详解

### 1. AIUI (JS 小程序) — 推荐用于眼镜独立应用

**在线文档:** https://js.rokid.com/AIUI/guide/quickstart?lang=zh-CN
**GitHub:** https://github.com/jsar-project/AIUI

YodaOS 内置的 QuickJS 小程序容器。类似微信小程序，但跑在眼镜上。自带单绿 monochrome 设计系统。

```bash
npm create @yodaos-pkg/aiui-agent my-agent
```

优点:
- 无边框问题 (系统容器渲染)
- Canvas API 支持自绘 (适合六线谱等复杂图形)
- 自带单绿设计规范 (480×640, 四种绿色透明度)
- .aix 热更新, 不需 ADB 安装
- 可用 CXR-M 辅助传数据

不足:
- QuickJS 性能低于 Android (无 JIT)
- 生态较新, 文档仍在完善
- 文本 500 字符限制 (CXR 自定义页)

### 2. Android APK — 当前使用方案

**在线文档:** https://rokid.github.io/glass-docs/ (本地: `.docs/glass-docs/`)
**本地 SDK 文档:** `.docs/glass-docs/2-sdk/5-ui-sdk/`

标准 Android 应用, 直接安装在眼镜上。Canvas 自绘适合复杂 UI。

已知限制:
- 480×640 (portrait) @ 240dpi
- 密集高亮内容可能在镜片顶部形成约 80–100px 的微弱光学副像；`adb screencap`
  framebuffer 干净，应用侧亮度限制也未明显改善，需向 Rokid 官方确认面板/光波导方案
- 全屏自绘 View 的大绿框通常是 Android 默认 DPAD 焦点高亮，可用
  `defaultFocusHighlightEnabled = false` 关闭，同时保留 focus 和 TP 按键能力
- 可引入 `com.rokid.glass:ui:1.5.4` (jcenter 已关闭, Rakid Maven 仓库 `https://maven.rokid.com/repository/maven-public/` 上无此 SDK, 可能需要联系官方获取)

### 3. CXR-M (手机端全功能 SDK) — 手机遥控眼镜

**在线文档:** https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/0ae2153f5e32433e9f7ce43a6b5c922a.html
**Maven:** `com.rokid.cxr:client-m` @ `https://maven.rokid.com/repository/maven-public/`

手机通过蓝牙 (BLE) + Wi-Fi Direct 连接眼镜, 全功能遥控:

| 模块 | 功能 |
|---|---|
| 设备连接 | 蓝牙扫描、配对、状态监听 |
| 自定义页面 | JSON→LinearLayout/RelativeLayout+TextView/ImageView, 眼镜系统渲染 |
| 设备控制 | 亮度(0-15)、音量(0-15)、音效、熄屏/关机、重启/关机 |
| 拍照 | 打开相机、拍照 (WebP回传)、分辨率/质量控制 |
| 录像 | 参数设置、启动/停止 |
| 录音 | PCM/Opus 音频流 |
| AI 场景 | ASR 识别、TTS 播报、AI 事件监听 |
| 数据传输 | sendStream、媒体文件同步 |
| 媒体同步 | 眼镜 ↔ 手机文件传输 |

依赖: `com.rokid.cxr:client-m:1.0.1-20250812.080117-2`

### 4. CXR-L (手机端轻量 SDK) — 简化方案

**在线文档:** https://custom.rokid.com/prod/rokid_web/84feb39f8ef141b0ad0326f902ab881f/pc/cn/3b63d21420e645e3affca478b39e4a13.html

CXR-M 的轻量替代, 通过 Hi Rokid App 桥接 (不直接连眼镜):

- 通过 Hi Rokid App 蓝牙连接眼镜
- 手机 Wi-Fi 加入眼镜热点
- 主要用于 APK 安装 + 轻量 AI 输出
- 不需 CXR-M 认证凭据, 用 Hi Rokid 授权
- 比 CXR-M 更稳定 (不依赖 Wi-Fi Direct)

适合: 简单 APK 安装、轻量 AI 语音/视觉交互

### 5. 裸机开发 — 无官方支持

**在线文档:** https://custom.rokid.com/prod/rokid_web/ff28c865a9634876be98cbc293588460/pc/cn/index.html

无官方 C/C++ SDK。可通过 Android NDK (C++) 间接开发, 或使用社区 Rust crate `ar_drivers` 通过 USB/HID 协议直接操作硬件。仅适合底层系统开发。

## 选型建议

| 场景 | 推荐方案 |
|---|---|
| 眼镜独立应用, 需要复杂自绘 UI (如六线谱) | **AIUI** (Canvas API, 无边框) |
| 眼镜独立应用, 已有 Android 代码 | **Android APK** (成熟, 有边框) |
| 手机 AI 处理 + 眼镜显示结果 | **CXR-M** |
| 快速装 APK 或轻量 AI 输出 | **CXR-L** |
| 手机→眼镜传乐谱文件 | **CXR-M sendStream** 或 **USB MTP** |
| 眼镜→手机传乐谱 | **USB MTP** 或 **CXR-M 媒体同步** |
| 混合: AIUI播放 + 手机传谱 | **AIUI + CXR-M** |

## 本地文档索引

- **AIUI 官方文档:** `.docs/aiui-docs/` (290 文件, 中英双语)
  - `0-guide/` — 快速开始、框架概念、运行时、配置、打包发布、调试、性能
  - `1-framework/` — 项目结构、WXML 模板、WXSS 样式
  - `2-components/` — 全部组件 (view, text, image, canvas, button, swiper, input...)
  - `3-api/` — 全部 API (Canvas 2D, AI(ASR/TTS/LLM), 设备传感器, 蓝牙, 相机, 音频, 网络, 存储)
  - `4-design/` — 视觉与交互设计规范
  - `5-tools/` — CLI、Craft、调试工具
- **旧版 Glass SDK:** `.docs/glass-docs/` (Android 开发)
- **GlassesReader SDK 文档:** `.docs/cxr-m/` 等目录（CXR SDK 文档离线页）

## 关键 URL

- AIUI 快速开始: https://js.rokid.com/AIUI/guide/quickstart?lang=zh-CN
- AIUI GitHub: https://github.com/jsar-project/AIUI
- CXR-L 文档: https://custom.rokid.com/prod/rokid_web/84feb39f8ef141b0ad0326f902ab881f/pc/cn/3b63d21420e645e3affca478b39e4a13.html
- CXR-M 文档: https://custom.rokid.com/prod/rokid_web/57e35cd3ae294d16b1b8fc8dcbb1b7c7/pc/cn/0ae2153f5e32433e9f7ce43a6b5c922a.html
- 裸机开发: https://custom.rokid.com/prod/rokid_web/ff28c865a9634876be98cbc293588460/pc/cn/index.html
- Rokid Maven: https://maven.rokid.com/repository/maven-public/
- 旧版 Glass 文档: https://rokid.github.io/glass-docs/ (本地: `.docs/glass-docs/`)
- Rokid 社区项目: https://github.com/Anezium/awesome-rokid
