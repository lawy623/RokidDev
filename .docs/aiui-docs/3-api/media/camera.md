# 相机

相机能力用于在页面中接入设备摄像头，并通过页面逻辑与相机组件进行交互。

在 AIUI 中，相机相关能力目前主要通过微信小程序兼容接口提供。对于需要拍照、预览或与摄像头交互的页面，通常会先创建一个 `CameraContext`，再通过该上下文管理具体操作。

## 入口

通过 `wx.createCameraContext()` 创建相机上下文：

```javascript
const cameraContext = wx.createCameraContext();
```

## 基本用法

```javascript
export default {
  onReady() {
    this.cameraContext = wx.createCameraContext();
  }
}
```

通常建议在页面初次渲染完成后再创建相机上下文，这样可以确保页面中的相机相关视图已经准备就绪。

## 核心接口

### `wx.createCameraContext()`

- **返回值**：`CameraContext`
- **说明**：创建并返回一个相机上下文对象，用于和页面中的相机能力交互。

### `CameraContext`

- **说明**：相机上下文对象。
- **用途**：作为页面逻辑与相机组件之间的桥梁，后续拍照、控制预览或其他相机相关操作通常都基于该对象完成。

## 使用建议

- 把相机初始化放在 `onReady()` 之后，避免在页面尚未完成渲染时过早创建上下文。
- 相机能力通常与页面中的相机组件配合使用，建议把“初始化完成”“正在拍摄”“操作失败”等状态明确展示给用户。
- 如果页面切换后不再需要相机交互，及时清理相关页面状态，避免残留无效引用。
- 当你的场景只需要“打开相机并执行一次操作”时，优先保持交互流程简洁，减少页面状态复杂度。

## 示例

```javascript
export default {
  data: {
    cameraReady: false
  },

  onReady() {
    this.cameraContext = wx.createCameraContext();
    this.setData({ cameraReady: true });
  }
}
```

## 继续阅读

- **[多媒体](/AIUI/api/media)**：返回多媒体能力总览。
- **[录音](/AIUI/api/media-recorder)**：查看录音能力与录音管理器。
- **[多媒体 (media)](/AIUI/api/weixin-compatible-apis-media)**：查看微信小程序兼容接口中的原始入口说明。
