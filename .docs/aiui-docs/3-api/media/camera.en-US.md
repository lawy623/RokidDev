# Camera

Camera capabilities are used to access the device camera in a page and interact with the camera component through page logic.

In AIUI, camera-related capabilities are currently provided mainly through WeChat Mini Program compatible APIs. For pages that need photo capture, preview, or camera interaction, the typical flow is to create a `CameraContext` first and then manage concrete operations through that context.

## Entry

Create a camera context through `wx.createCameraContext()`:

```javascript
const cameraContext = wx.createCameraContext();
```

## Basic Usage

```javascript
export default {
  onReady() {
    this.cameraContext = wx.createCameraContext();
  }
}
```

It is usually recommended to create the camera context only after the page finishes its initial render, so that camera-related views in the page are ready.

## Core APIs

### `wx.createCameraContext()`

- **Return Value**: `CameraContext`
- **Description**: Creates and returns a camera context object used to interact with camera capabilities in the page.

### `CameraContext`

- **Description**: A camera context object.
- **Usage**: Acts as the bridge between page logic and the camera component. Photo capture, preview control, and other camera-related operations are typically built around this object.

## Recommendations

- Initialize the camera in `onReady()` to avoid creating the context too early before page rendering is complete.
- Camera capabilities are usually used together with a camera component in the page, so it is recommended to clearly expose states such as initialization complete, capturing, and operation failed to users.
- If camera interaction is no longer needed after page switching, clean up relevant page state promptly to avoid stale references.
- If your scenario only needs to open the camera and perform a single action, keep the interaction flow simple and reduce page state complexity.

## Example

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

## Continue Reading

- **[Media](/AIUI/api/media)**: Return to the media capability overview.
- **[Recorder](/AIUI/api/media-recorder)**: Learn about recording capabilities and the recorder manager.
- **[Media (media)](/AIUI/api/weixin-compatible-apis-media)**: View the original entry documentation for WeChat Mini Program compatible APIs.
