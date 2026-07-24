# HTTPS / SSE

在 AIUI 中，`HTTPS` 和 `SSE` 都适合“由前端发起、由服务端返回结果”的通信模式。区别在于，`HTTPS` 更适合一次请求一次返回，而 `SSE` 更适合服务端持续向前端推送增量内容。

如果你在做普通业务接口、配置拉取、提交表单、调用智能体服务，通常先用 `HTTPS`。如果你已经有一个服务端接口需要持续输出文本流、状态流或任务进度，再考虑 `SSE`。

## 什么时候用 HTTPS

- 拉取页面初始化数据
- 调用普通 REST 接口
- 提交一次性表单或指令
- 请求一个完整结果，而不是持续流式输出

## HTTPS 示例

```javascript
const response = await fetch('/api/agent/chat', {
  method: 'POST',
  headers: {
    'content-type': 'application/json',
  },
  body: JSON.stringify({
    message: '帮我总结今天的会议内容',
  }),
});

const data = await response.json();
console.log(data);
```

## HTTPS 使用建议

- 优先把一次业务动作建模成一次明确的请求。
- 为请求设置清晰的超时、失败提示和重试策略。
- 对于需要鉴权的接口，把认证信息统一放在请求头或会话机制里处理。
- 如果返回体较大或处理时间较长，要在 UI 上明确展示加载状态。

## 什么时候用 SSE

- 服务端会持续推送文本增量
- 需要展示任务执行进度
- 只需要服务端单向推送，不需要客户端持续反向发送消息
- 希望保留接近 HTTP 的接入方式，同时获得流式体验

## SSE 示例

```javascript
const eventSource = new EventSource('/api/agent/stream');

eventSource.onmessage = (event) => {
  console.log('收到增量内容:', event.data);
};

eventSource.onerror = () => {
  console.error('SSE 连接异常');
  eventSource.close();
};
```

## SSE 使用建议

- 当服务端只负责持续推送时，优先考虑 `SSE`，不要为了流式输出直接切到 `WebSocket`。
- 把每条推送消息设计成可独立消费的片段，例如文本增量、阶段状态、进度更新。
- 在界面上明确区分“连接已建立”“流式输出中”“已完成”“已中断”这几种状态。
- 连接结束后及时关闭实例，避免页面切换后继续占用资源。

## 如何选择

- 需要一次请求一次结果：用 `HTTPS`
- 需要服务端持续推送：用 `SSE`
- 需要客户端和服务端双向实时通信：改用 [WebSocket](/AIUI/api/network-websocket)

## 继续阅读

- **[WebSocket](/AIUI/api/network-websocket)**：查看双向实时长连接场景如何设计与管理。
- **[网络请求 (networking)](/AIUI/api/weixin-compatible-apis-networking)**：查看 `wx.request` 与 `EventSource` 兼容接口细节。
