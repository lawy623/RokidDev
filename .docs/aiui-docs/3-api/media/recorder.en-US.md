# Recorder

Recording capabilities are used to capture the user's voice or environmental sound and manage the recording process in page logic.

In AIUI, recording-related capabilities are currently provided mainly through WeChat Mini Program compatible APIs. The recording entry uses a globally unique recorder manager, which is suitable for scenarios such as voice input, voice messages, and audio collection.

## Entry

Get the recorder manager through `wx.getRecorderManager()`:

```javascript
const recorderManager = wx.getRecorderManager();
```

## Basic Usage

```javascript
export default {
  onLoad() {
    this.recorderManager = wx.getRecorderManager();
  }
}
```

The recorder manager is a globally unique object. It is usually recommended to obtain it during page initialization and manage recording state centrally in page logic.

## Core APIs

### `wx.getRecorderManager()`

- **Return Value**: `RecorderManager`
- **Description**: Gets the globally unique recorder manager object.

### `RecorderManager`

- **Description**: A recorder manager object.
- **Usage**: Responsible for recording lifecycle management. Starting, stopping, and handling recording states are usually centered around this object.

## Recommendations

- Recording usually involves obvious state transitions such as idle, recording, recording finished, and recording failed, so it is recommended to reflect these states directly in the UI.
- Since `RecorderManager` is globally unique, avoid maintaining separate recording manager instances across multiple pages or logic branches.
- When the page is hidden, the recording flow exits, or the business process ends, end the current recording-related state promptly to avoid confusing follow-up interactions.
- If your goal is only to capture a short voice clip, prefer a simple loop of start recording -> stop recording -> process result.

## Example

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

## Continue Reading

- **[Media](/AIUI/api/media)**: Return to the media capability overview.
- **[Camera](/AIUI/api/media-camera)**: Learn about camera capabilities and the camera context.
- **[Media (media)](/AIUI/api/weixin-compatible-apis-media)**: View the original entry documentation for WeChat Mini Program compatible APIs.
