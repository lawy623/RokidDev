# Rokid 按键与触摸板交互参考

本文档汇总 Rokid 眼镜不同产品代际和开发框架中的实体按键、镜腿触摸板及双指交互能力。

> 重要：Rokid Glass、Rokid Glasses、Rokid AI Glasses Style 的硬件和固件并不完全相同。下文必须区分“官方开发文档给出的应用事件”“官方用户指南描述的系统功能”和“当前设备实测结果”，不要跨型号直接套用键值。

## 1. 能力分层

| 层级 | 官方已确认 | 应用开发时的含义 |
|---|---|---|
| 旧版 Rokid Glass Android APK | TP 点击、滑动、快速滑动、长按、双击，以及 Back、Power、Volume+/Volume- 的 Android 事件 | 可在 `dispatchKeyEvent`、`onKeyDown`、`onKeyUp` 中处理；仍要按固件实测 |
| Rokid Glasses AIUI | 页面支持 `onKeyDown(event)`、`onKeyUp(event)`、`event.code`、`event.preventDefault()`；部分宿主提供 `GlobalHook` | 使用 AIUI 页面事件处理镜腿输入和返回/确认等操作 |
| 新款 Rokid Glasses / Style 系统交互 | 镜腿滑动、单击、双击、长按，功能键、快门键，以及可配置的双指交互快捷方式 | 官方确认系统功能存在，但没有公开第三方 APK/AIUI 收到的固定双指事件映射 |

## 2. 旧版 Glass Android KeyEvent 映射

来源：本地官方文档 `.docs/glass-docs/1-system/index.html`，在线版为 <https://rokid.github.io/glass-docs/>。

| 用户动作 | 官方事件 | 说明 |
|---|---|---|
| TP 单击 | `KEYCODE_DPAD_CENTER` (23) | 确认 |
| TP 右滑 | 连续多个 `KEYCODE_DPAD_RIGHT` (22) | 应用会收到连续键值 |
| TP 左滑 | 连续多个 `KEYCODE_DPAD_LEFT` (21) | 应用会收到连续键值 |
| TP 快速右滑 | 多个 `KEYCODE_DPAD_RIGHT` + 单个 `KEYCODE_DPAD_DOWN` (20) | 可用单次 Down 区分快速滑动 |
| TP 快速左滑 | 多个 `KEYCODE_DPAD_LEFT` + 单个 `KEYCODE_DPAD_UP` (19) | 可用单次 Up 区分快速滑动 |
| TP 长按 | `KEYCODE_TV` (170) | 用户可自定义，可能被系统功能占用 |
| TP 双击 | `KEYCODE_ENTER` (66) | 用户可自定义 |
| Back 单击 | `KEYCODE_BACK` (4) | 返回 |
| Back 长按 | Intent `com.rokid.glass.homekey.longpress` | 有语音助手时通常被占用 |
| Back 双击 | 系统配置：忽略、返回 launcher 或发送 Intent | 见 `persist.rokid.backPanicBehavior` |
| Power | `KEYCODE_POWER` (26) | 电源键；系统可能优先处理 |
| Volume+ | `KEYCODE_VOLUME_UP` (24) | 音量增加 |
| Volume- | `KEYCODE_VOLUME_DOWN` (25) | 音量减少 |

### 固件差异

不要把上表当作所有 Rokid 设备上的绝对映射。现有项目真机已经观察到：主要 TP 确认动作可能产生 `KEYCODE_ENTER` (66)，另一个触摸动作可能产生 `KEYCODE_NOTIFICATION` (83)。开发新应用或更换固件后，应先记录实际事件。

某些按键（特别是 Power、Home/Back 长按、系统 AI 快捷方式）可能先被系统消费，应用不一定能收到。不要为了抢占事件破坏系统的关机、返回或紧急退出能力。

## 3. Android APK 捕获模板

优先在 Activity 的 `dispatchKeyEvent` 记录完整信息，再决定是否消费事件：

```kotlin
override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    Log.d(
        "RokidInput",
        "key action=${event.action} keyCode=${event.keyCode} " +
            "scanCode=${event.scanCode} repeat=${event.repeatCount} " +
            "deviceId=${event.deviceId} source=${event.source}"
    )
    return super.dispatchKeyEvent(event)
}
```

同时记录通用运动和触摸事件，以判断当前固件是否把触摸板作为多点触控设备暴露：

```kotlin
override fun onGenericMotionEvent(event: MotionEvent): Boolean {
    Log.d(
        "RokidInput",
        "generic action=${event.actionMasked} pointers=${event.pointerCount} " +
            "deviceId=${event.deviceId} source=${event.source}"
    )
    return super.onGenericMotionEvent(event)
}

override fun onTouchEvent(event: MotionEvent): Boolean {
    Log.d(
        "RokidInput",
        "touch action=${event.actionMasked} pointers=${event.pointerCount} " +
            "deviceId=${event.deviceId} source=${event.source}"
    )
    return true
}
```

注意：触摸板固件可能在输入到达应用之前就把手势转换成 `KeyEvent` 或系统快捷动作。因此，即使硬件支持双指，应用也未必能看到 `MotionEvent.pointerCount == 2`。

## 4. AIUI 页面按键事件

来源：

- `.docs/aiui-docs/0-guide/open-agent-format/page-events.md`
- `.docs/aiui-docs/4-design/interaction.md`

AIUI 页面可声明 `onKeyDown(event)` 和 `onKeyUp(event)`。常见 `event.code` 包括 `Backspace`、`ArrowUp`、`ArrowDown`、`Enter`；部分 Rokid Glasses 宿主还会提供设备专用的 `GlobalHook`。

```javascript
export default {
  onKeyDown(event) {
    console.log('key down:', event.code);
  },

  onKeyUp(event) {
    console.log('key up:', event.code);

    if (event.code === 'GlobalHook') {
      this.setData({ status: 'temple button touched' });
    }
  }
}
```

`GlobalHook` 表示镜腿按键/触摸事件，是 Rokid 设备扩展值，不是标准 Web 键值。其具体物理动作仍应在目标设备上验证。对于存在默认行为的事件，可调用 `event.preventDefault()`；例如接管 `Backspace` 的返回行为。

## 5. 新款设备的实体键与双指交互

Rokid 官方 Academy 的 “Button & Touch Controls” 说明，新款 Rokid Glasses 支持：

- 镜腿触摸板滑动：切换卡片、组件或可选项目
- 单击：选择或确认
- 双击：返回或退出当前视图
- 长按：激活 AI assistant
- Function button：例如连续按三次进入蓝牙配对模式
- Shutter/Capture button：按模式触发拍照或录像相关操作

官方还说明，可在 Hi Rokid App 的自定义交互设置中进入 `Trackpad → Two-finger interaction → Long press`，将双指长按配置为 AI 快捷方式；部分产品页面也描述了双指点击唤醒 AI。

官方来源：

- <https://global.rokid.com/pages/academy>
- <https://global.rokid.com/en-jp/pages/rokid-ai-glasses-style>

### 双指交互的开发边界

当前公开资料只确认“双指交互是系统可配置能力”，尚未说明它会以哪个 Android `KeyEvent`、`MotionEvent`、AIUI `event.code` 或广播传给第三方应用。因此：

1. 不要在通用代码中假定“双指点击 = 某个固定 keyCode”。
2. 系统配置的 AI shortcut 可能在应用之前消费双指动作。
3. 如果应用需要双指手势，应先关闭或调整冲突的系统快捷方式，再抓取实际输入。
4. 将确认后的映射写入具体项目的 `AGENTS.md` / `CLAUDE.md`，并记录设备型号、系统版本和测试日期。
5. 若 `KeyEvent`、`MotionEvent` 和 Linux input 都没有应用可用事件，应视为系统保留能力，并向 Rokid 官方确认是否存在受支持的开发接口。

## 6. 真机验证清单

### 设备侧输入能力

```bash
adb devices -l
adb shell getevent -lp
adb shell dumpsys input
```

### 应用与系统日志

先安装包含上述 `RokidInput` 日志的测试 APK，再运行：

```bash
adb logcat | rg 'RokidInput|InputReader|InputDispatcher|WindowManager'
```

逐项测试并记录结果：

- 单指：点击、左右滑动、快速滑动、长按、双击
- 双指：点击、左右滑动、上下滑动、长按、双击
- 实体键：Function、Shutter/Capture、Power、Volume+、Volume-、Back
- 分别测试前台应用、系统主页，以及开启/关闭系统双指快捷方式的情况

建议记录表：

| 设备/固件 | 物理动作 | App `KeyEvent` | App `MotionEvent` | `getevent` | 是否被系统消费 |
|---|---|---|---|---|---|
| 待填写 | 双指长按 | 待测 | 待测 | 待测 | 待测 |

## 7. 交互设计建议

- 保留单击确认、滑动导航、双击/Back 返回等符合系统习惯的路径。
- 长按和双指手势可能与 AI assistant 冲突，应用必须提供不依赖它们的备用操作。
- 对连续滑动键值做节流或按时间/累计距离处理，不要让一次滑动跨越过多项目。
- 对长按处理 `repeatCount`，避免长按期间重复触发动作。
- 为实体键和触摸手势提供即时视觉或音效反馈。
- 全屏 Canvas View 需要焦点时保留 `isFocusable`，但可用 `defaultFocusHighlightEnabled = false` 关闭 Android 默认绿色焦点框。

## 8. 实测：长按与快门在系统广播层拦截（2026-08-05，RokidTerminal 设备实测）

当前固件（输入设备 `ROKID,PSOC-TP-R`）上，TP 长按和快门键**不会**以 KeyEvent 送达前台应用，
框架层（`PhoneWindowManager`）在派发前消费它们并转为有序广播：

| 物理动作 | getevent 原始码 | 系统有序广播 | 系统行为 |
|---|---|---|---|
| TP 单击 | `KEY_DASHBOARD`(204) + `KEY_ENTER`(28) | — | 应用收到 `KEYCODE_NOTIFICATION`(83) / `KEYCODE_ENTER`(66) |
| TP 长按 | `KEY_PROG1`(148) 按住 >1s | `com.android.action.ACTION_AI_START` | 系统 AI 助手（`launchRokidAI`） |
| 快门键 | `KEY_MENU`(139)（event0/qpnp_pon） | `com.android.action.ACTION_SPRITE_BUTTON_UP` | assistserver 按 `settings_interaction_shortPressFun`（默认 `picture`）拍照 |

广播特征（AMS 记录实测）：

- `FLAG_RECEIVER_REGISTERED_ONLY`（0x10）——**只投递给动态注册的接收器**，Manifest 静态接收器永远不会触发；
- `requiredPermission=…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`——接收者需持有该系统专属权限；
- 发送方栈：`PhoneWindowManager.launchRokidAI` / `sendFunctionKeyAction` / `onSpriteFuncKeyPress`。

应用层拦截方法（已在 RokidMusic 与 RokidTerminal 双项目验证有效）：

- **动态注册**高优先级接收器，前台才生效（`onStart` 注册 / `onStop` 注销）：
  ```kotlin
  val filter = IntentFilter(ACTION_AI_START).apply { priority = 1000 }
  registerReceiver(receiver, filter)
  ```
- `onReceive` 中先转发语义动作，再 `if (isOrderedBroadcast) abortBroadcast()` 阻止系统接收器
  （助手/相机）执行；
- 应用退出前台后广播放行，系统行为恢复；
- 静态（Manifest）接收器对这类广播无效，不要用 `android:priority` 方案。

验证记录（2026-08-05）：拦截后 logcat 出现 `system key intercepted: long-press/shutter`，
系统助手与相机零启动；长按在输入框内触发发送、快门触发删除。
