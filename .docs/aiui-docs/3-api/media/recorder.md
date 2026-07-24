# 录音

录音能力用于采集用户语音或环境声音，并在页面逻辑中管理录音过程。

在 AIUI 中，录音相关能力目前主要通过微信小程序兼容接口提供。录音入口采用全局唯一的录音管理器形式，适合用于语音输入、语音留言、语音采集等场景。

## 入口

通过 `wx.getRecorderManager()` 获取录音管理器：

```javascript
const recorderManager = wx.getRecorderManager();
```

## 基本用法

```javascript
export default {
  onLoad() {
    this.recorderManager = wx.getRecorderManager();
  }
}
```

录音管理器是全局唯一对象，通常建议在页面初始化时获取，并在页面逻辑中统一管理录音状态。

## 核心接口

### `wx.getRecorderManager()`

- **返回值**：`RecorderManager`
- **说明**：获取全局唯一的录音管理器对象。

### `RecorderManager`

- **说明**：录音管理器对象。
- **用途**：负责录音生命周期管理。开始录音、停止录音以及录音过程中的状态处理，通常都围绕该对象展开。

## 使用建议

- 录音能力通常具有明显的状态切换，例如“待机”“录音中”“结束录音”“录音失败”，建议把这些状态直接反馈到界面上。
- 由于 `RecorderManager` 是全局唯一对象，不建议在多个页面或多个逻辑分支中重复维护不同的录音管理实例。
- 页面隐藏、退出录音流程或业务结束时，及时结束当前录音相关状态，避免后续交互混乱。
- 如果你的目标只是采集一段短语音，优先把交互收敛为“开始录音 -> 停止录音 -> 处理结果”的简单闭环。

## 示例

```javascript
export default {
  data: {
    recording: false
  },

  onLoad() {
    this.recorderManager = wx.getRecorderManager();
  },

  startRecording() {
    this.setData({ recording: true });
  },

  stopRecording() {
    this.setData({ recording: false });
  }
}
```

## 继续阅读

- **[多媒体](/AIUI/api/media)**：返回多媒体能力总览。
- **[相机](/AIUI/api/media-camera)**：查看相机能力与相机上下文。
- **[多媒体 (media)](/AIUI/api/weixin-compatible-apis-media)**：查看微信小程序兼容接口中的原始入口说明。
