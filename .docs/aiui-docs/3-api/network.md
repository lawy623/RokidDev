# 网络

AIUI 的网络能力主要分成三类：一次性请求响应、服务端持续推送、以及双向实时通信。大多数业务接口和 AI 服务调用都可以先从 `HTTPS` 开始；如果服务端需要持续向前端推送增量结果，可以继续使用 `SSE`；只有在确实需要双向实时长连接时，再考虑 `WebSocket`。

在 AI 眼镜场景下，网络能力还需要兼顾续航与功耗。为了降低设备侧直接联网带来的能耗开销，AIUI 在 Rokid 眼镜上会通过蓝牙连接到手机端 App，并借助这条链路承载 HTTPS 请求的传输。对开发者来说，请求方式保持一致，但在理解网络表现、弱网行为和真机调试结果时，需要意识到这类请求实际经过了设备与手机之间的蓝牙链路。

## 如何选择

- **HTTPS**：适合普通接口调用，例如拉取配置、提交表单、请求智能体结果。
- **SSE**：适合服务端持续推送文本增量、状态流、任务进度等单向流式场景。
- **WebSocket**：适合客户端和服务端需要双向实时收发消息的场景，例如多人协作、实时控制、低延迟消息通道。

## 简单示例

例如，请求一个智能体服务接口：

```javascript
const response = await fetch('/api/agent/chat', {
  method: 'POST',
  headers: {
    'content-type': 'application/json',
  },
  body: JSON.stringify({ message: '你好' }),
});
```

## 继续阅读

- **[HTTPS / SSE](/AIUI/api/network-https-sse)**：了解普通请求与服务端推送分别适合什么场景，以及在 AIUI 中该如何使用。
- **[WebSocket](/AIUI/api/network-websocket)**：了解双向实时连接的典型使用方式、连接管理和重连建议。
- **[设备](/AIUI/api/device)**：查看蓝牙连接和设备传感器能力，包括加速度、姿态和陀螺仪等接口。
- **[全局](/AIUI/api/framework-global)**：查看 `fetch`、定时器与全局对象相关能力入口。
- **[网络请求 (networking)](/AIUI/api/weixin-compatible-apis-networking)**：查看 `wx.request`、`WebSocket`、`EventSource` 等兼容接口细节。
