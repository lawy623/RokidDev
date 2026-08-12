# RokidMusic — Glasses App

Android APK 眼镜端应用。独立运行，不依赖手机。

## 硬件约束

- **显示:** 480x640 px (portrait) @ 240dpi, 单绿 Micro-LED
- **输入:** 触摸板 (TP) — 单击(DPAD_CENTER=23；部分设备配置为 ENTER=66), 长按(TV=170), 滑动(DPAD 方向键)
- **系统:** Android 12 (API 32)
- **边框:** Android 默认焦点框可关闭；Theme.NoTitleBar.Fullscreen 提供黑底全屏
- **光学副像:** 真机镜片顶部可见约 80–100px 的微弱倒影，但 `screencap`
  framebuffer 完全干净，确认不是 Canvas 越界、裁剪或 Surface 残帧。降低窗口
  亮度到 55% 未产生明显改善，因此不保留应用侧亮度限制；顶部 64px 仍作为视觉留白。
- **存储:** ~17 GB 可用

## 项目结构

```
RokidMusic/
├── app/src/main/java/com/rokid/music/
│   ├── MainActivity.kt       # Activity, FLAG_KEEP_SCREEN_ON, 拦截 TV 长按
│   ├── StartScreenView.kt    # 开始页面 Canvas 自绘
│   ├── ScoreServer.kt        # HTTP 服务 (8849), 手机传谱
│   ├── PlayerView.kt         # 播放/滚动/输入状态机
│   ├── model/TabScore.kt     # .tab.json 完整解析模型
│   ├── render/TabRenderer.kt # 眼镜 Canvas 乐谱渲染器
│   └── audio/AudioEngine.kt  # AudioTrack PCM 混音器
├── files/scores/             # 运行时上传目录（应用私有存储）
└── rules/
    ├── glasses-app.md        # 本文档
    ├── rendering.md          # 渲染规则 (web+glasses 通用)
    └── ...
```

## 1. 开始页面 (已完成)

Canvas 自绘的曲谱选择页面。从上到下:

- 顶部 64px 视觉留白
- "Guitar Player" 绿色粗体标题
- 吉他剪影 (从 assets PNG 加载)
- 绿色分割线
- "Select a TAB score for Rokid green display." 提示
- "SCORE" + "N scores" 同行（左右对齐）
- 曲谱选择框 (当前曲名 + 作者 + 上下箭头)
- "● click to expand  ▲▼ swipe to switch  ◉ long-press to enter"
- "Connect to the same WiFi  |  http://IP:8849" / "WiFi not connected" / "Score Manager Error"

**触摸板交互:**

| 状态 | 单击 (23；兼容 66) | 上下滑 | 长按 (170) | Back |
|---|---|---|---|---|
| 收起 | 展开列表 | 不处理 | 进入当前曲 | 退出应用 |
| 展开 | 确认高亮曲并收起 | 每次手势移动一首 (列表自动滚动) | 不处理 | 收起列表 |

**按键兼容:** 官方旧文档将 TP 长按定义为 KEYCODE_TV (170)，但当前实机固件会把长按转换成系统有序广播 `com.android.action.ACTION_AI_START`，并由 AI 助手消费，应用收不到按键。MainActivity 仅在前台时注册高优先级接收器，将该广播路由到当前页面并中止后续分发；退出 RokidMusic 后，系统 AI 长按行为不受影响。当前固件还会在单击、滑动等 TP 操作开始时发送 keyCode 83；它只是触摸前导事件，必须消费但不能触发进入。快速滑动可能同时产生 RIGHT+DOWN 或 LEFT+UP，开始页用短时间去抖保证一次手势只移动一首。

**Canvas 焦点框与页面切换:** 全屏自定义 View 必须保持 `isFocusableInTouchMode = true`
和 `isFocusable = true` 才能接收 TP/DPAD，但 Android 默认会在第一次点击或滑动后
绘制覆盖整个 View 的焦点高亮框，并可能消费第一次输入。两个 Canvas View 都设置
`defaultFocusHighlightEnabled = false`，只关闭框而不关闭焦点能力。Activity 用
`setContentView` 在选择页和播放器之间切换后，要通过 `post { requestFocus() }`
重新把焦点交给选择页；否则从播放器双击返回后，首页的第一次点击/滑动可能没有效果。
这套设置只处理 Android View 默认焦点框，不保证能消除独立的 Rokid 系统 overlay。

APK 不读取或维护内置 `index.json`。曲目列表完全由上传目录中的实际
`.tab.json` 内容产生，标题与作者读取 `metadata.title/artist`。

## 2. 渲染/播放界面 (渲染与基础音频播放已完成)

### 架构

```
app/src/main/java/com/rokid/music/
├── model/
│   └── TabScore.kt          # .tab.json 数据类 + JSON 解析
├── render/
│   └── TabRenderer.kt       # 纯 Canvas 绘制引擎 (无状态)
├── PlayerView.kt             # 交互控制器 (滚动/播放/音量/输入)
└── audio/
    └── AudioEngine.kt        # 低延迟 AudioTrack 播放
```

### TabScore.kt — 数据模型

org.json 解析 `.tab.json` → Kotlin 数据类:

```
TabScore → metadata, defaults(ppq/bpm/tuning),
           tracks, systems, measures[]
  Measure → id, startTick, durationTicks, timeSignature, events[], spanners[]
  Event   → type(note|rest), tick, duration(base/dots/tuplet),
            notes[], articulations[]
  Note    → string(1-6), fret,
            status(normal|tied|artificial-harmonic|ring|dead|ghost|mute)
  Effect  → type, label, kind, to, toEvent
  Spanner → type(bend|slide|vibrato|bend-vibrato|hammer-on|pull-off|
            tie|slur|let-ring|palm-mute|trill), from/to,
            fromEvent/toEvent, label, curve[], width
```

### TabRenderer.kt — Canvas 渲染引擎

从 web `tab_renderer.html` 移植、针对 480×640 单绿显示重新排版的渲染管线:

```
layout(score) → LayoutResult
  ├─ buildSystems(): 1 小节/行 (忽略 JSON system 分组)
  ├─ tickToMeasureX(): tick→像素 映射 (核心时序函数)
  └─ 返回: systems[], measureLayouts, notePositions, eventPositions, totalHeight

drawContent(canvas, layout, scrollY, playheadTick)
  ├─ Pass 1: drawMeasureBase() — 六线谱、TAB、拍号、小节号/线、时值告警
  ├─ Pass 2: drawMeasureEvents() — 品位、休止符、符干、符尾、符杠、连音
  ├─ Pass 3: drawMeasureSpanners() — bend/slide/vibrato/H-P/tie/slur 等
  ├─ Pass 4: drawIncomingSpanners() — 跨行技法的进入半段
  └─ Pass 5: drawPlayhead() — 与音符共用 tick→x 映射的播放竖线
```

**布局常量 (适配 480px 视口):**

| 参数 | 值 | 说明 |
|---|---|---|
| beatWidth | 78px | 每拍像素；四分音符约 78px、八分音符约 39px |
| clefReserve + startPad | 50px | TAB/拍号左侧紧凑预留，避免小节开头空白过大 |
| stringGap | 11px | 压缩后的弦间距 |
| SYSTEM_TOTAL | ~139px | 压缩后的每行总高度（含行间距） |
| GHOST_TOP | 64px | 光波导重影留白 |

**核心公式 `tickToMeasureX()`:** 事件、节奏、技法锚点和播放线共用同一
tick→像素映射。非空小节只把实际事件范围居中，未写成 rest/event 的首尾空白
不占播放时间；`events: []` 的空小节仍完整占用 `durationTicks`。

**当前完整绘制范围:** 1/1–1/64 音符和休止符、附点、任意层级混合符杠、
tuplet、ring/dead/mute/tied/ghost、staccato、tap、harmonic、bend/release、
bend-vibrato、单端/连接 slide、vibrato、H/P 链、tie/slur/trill、let-ring、
P.M.、重复/双/终止小节线，以及上述连线的跨行拆分。

**渲染布局约定:** TAB 的 T/A/B 不是按固定 baseline 摆放，而是按字形真实
`getTextBounds()` 对齐：T 的最高点位于第一、二线之间，B 的最低点位于第五、
六线之间；4/4 同样按上下数字的真实边界跨第二至第五线。音符数字使用浮点字号
9.5px，较长数字、辅助数字和技巧标记使用约 7.5px。音符符干统一约 20px。

**技巧与连线:** H-in/P-out 的缺省端点（`fromFret`/`toFret`）不创建额外的
timed Event；源数字、目标数字、弧线起止点均按数字实际宽度和中心计算，H/P 位于
统一的 technique rail 高度。跨小节 tie 的弧线使用目标数字宽度和浅曲线，避免从
小节左边拉出深 U 形。slide-in/slide-out 的单端斜杠长度约 8px。

**固定标题栏:** PlayerView 顶部标题、歌手和横线属于独立 HUD，不参与 `scrollY`
平移；谱面 Canvas 使用横线下方的 clip 区域，滚动和播放头不能覆盖标题栏。右侧
显示按状态变化的英文操作提示：浏览时 `◉ long-press to play  ◀▶ swipe`，播放时
`● short-click pause  ◀▶ volume`，暂停时同时显示
`◉ long-press play from top  ● short-click continue  ◀▶ swipe`。标题会按提示的实际宽度截断，
避免暂停态较长的说明与歌曲信息重叠。

### PlayerView.kt — 交互控制器

状态机:
```
BROWSING → (长按倒计时3-2-1) → PLAYING ⇄ (单击) ⇄ PAUSED
   ↑                                    │
   └──── 双击 ENTER / Back ─────────────┘
```

按键映射:

| 操作 | BROWSING | PLAYING | PAUSED |
|---|---|---|---|
| ◀▶ 滑动 | 滚页 | 调音量 (0-100%) | 滚页 |
| 单击 DPAD_CENTER（兼容 ENTER） | — | 暂停 | 回竖线位置继续 |
| 长按 TV (170) | 倒计时→播放 | 倒计时重新开始 | 倒计时重新开始 |
| 双击 ENTER | 返回选曲页 | 返回选曲页 | 返回选曲页 |
| Back | 返回选曲页 | 返回选曲页 | 返回选曲页 |

- 倒计时: 全屏 3→2→1 (各1秒)
- 播放中左右滑调节系统媒体音量, 右上角音量条；退出播放器恢复进入前的系统音量
- 竖线一旦进入过播放模式就始终显示
- 播放使用与 web 规则一致的扁平时间线；省略的首尾静默被跳过，显式休止符与
  完全空小节保留
- 自动滚动把播放行保持在视口上部约 35% 的位置
- 播放自动滚动使用目标位置 + 14% 阻尼跟随，不在小节切换时瞬移；手动滑动仍立即响应
- 方向事件使用 280ms 手势去重，避免快速滑动的组合键造成双滚动/双调音量

**双击返回:** 播放器中的 ENTER/DPAD_CENTER 双击由 PlayerView 识别并调用
`MainActivity.closePlayer()` 返回选择页。MainActivity 还会显式路由播放器期间的
ENTER、DPAD_CENTER 和 BACK，避免 Rokid 固件把第二次点击交给系统而直接结束 App。

### 音频

`AudioEngine.kt` 使用单独线程和低延迟 `AudioTrack` 输出 44.1kHz 单声道 PCM，
采用 128 帧短块。每个事件进入一个共享混音器声部，避免多个音符同时写 AudioTrack
产生竞争；混音器按块加锁，避免音频线程抖动导致 underrun/click。音色是轻量
吉他拨弦合成（基频 + 二/三次谐波 + 短拾音噪声 + attack/decay 包络）。

音高来自调弦字符串（如 `E4`）+ capo + 品位；技法调制包括 bend 曲线、连接
slide/slide-in、vibrato、泛音（上移八度）、ring 延音和 mute 短包络。音量遵循
Rokid 系统的 `STREAM_MUSIC` 设置；播放界面显示系统音量比例，眼镜 TP 滑动直接
调整系统媒体音量，避免应用内增益与系统音量脱节。进入播放器时保存原始系统
音量，退出播放器时恢复，音量调整只在 RokidMusic 会话内临时生效。

### 完成状态与可选增强

眼镜端核心功能已完成：选曲、上传/删除、Canvas 谱面渲染、TP 交互、播放、
临时系统音量调整、平滑跟随滚动、跨小节技法和返回选曲页均已在真机验证。

以下不属于发布阻塞项，仅作为未来可选增强：

- 更真实的采样/箱体音色；当前低延迟 PCM 合成已满足跟谱和节奏确认。
- 语音控制或更多型号/固件兼容验证。
- 若未来 AR 录屏再次稳定复现卡顿，可缓存静态谱面 Bitmap、拆分播放头动态层，
  或将视觉刷新降到 30fps。最近一次真机录屏未复现明显卡顿，因此暂不引入复杂优化。
- 光学副像需要向 Rokid 官方确认面板/光波导支持；应用侧 framebuffer 与裁剪均正常。

## 3. 手机/电脑同步 (已完成)

HTTP Score Manager (端口 8849), 详见文档。

## 构建 & 部署

```bash
cd RokidMusic && ./dev.sh run     # build + install + launch
adb forward tcp:8849 tcp:8849     # USB 转发 (WiFi 不可用时)
./dev.sh log                      # 看崩溃日志
```
