# HTTPS / SSE

In AIUI, both `HTTPS` and `SSE` are suitable for communication patterns where the frontend initiates a request and the server returns a result. The difference is that `HTTPS` is better for one request and one response, while `SSE` is better when the server needs to keep pushing incremental content to the frontend.

If you are building ordinary business APIs, configuration fetching, form submission, or agent service calls, start with `HTTPS`. If you already have a server interface that needs to continuously output a text stream, status stream, or task progress, then consider `SSE`.

## When to Use HTTPS

- Fetching initial page data
- Calling ordinary REST APIs
- Submitting one-off forms or commands
- Requesting one complete result instead of continuous streaming output

## HTTPS Example

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

## HTTPS Recommendations

- Prefer modeling one business action as one clear request.
- Set clear timeout, failure messaging, and retry strategies for requests.
- For authenticated APIs, handle authentication information consistently in request headers or session mechanisms.
- If the response body is large or takes a long time to process, clearly show a loading state in the UI.

## When to Use SSE

- The server continuously pushes text deltas
- Task execution progress needs to be displayed
- Only one-way server-to-client pushing is needed, without continuous reverse messages from the client
- You want to keep an HTTP-like integration model while still getting a streaming experience

## SSE Example

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

## SSE Recommendations

- When the server is only responsible for continuous pushing, prefer `SSE` instead of switching to `WebSocket` just for streaming output.
- Design each pushed message as an independently consumable fragment, such as text deltas, phase states, or progress updates.
- Clearly distinguish UI states such as "connected", "streaming", "completed", and "interrupted".
- Close the instance promptly after the connection ends to avoid continuing to occupy resources after page switches.

## How to Choose

- Need one request and one result: use `HTTPS`
- Need continuous server push: use `SSE`
- Need bidirectional real-time communication between client and server: use [WebSocket](/AIUI/api/network-websocket) instead

## Read Next

- **[WebSocket](/AIUI/api/network-websocket)**: Learn how to design and manage bidirectional real-time long connections.
- **[Networking](/AIUI/api/weixin-compatible-apis-networking)**: Learn details of `wx.request` and `EventSource` compatible APIs.
