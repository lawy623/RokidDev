# WebSocket

`WebSocket` is suitable for scenarios where the client and server need bidirectional real-time communication. Compared with `HTTPS` and `SSE`, it is better for frequent message exchange, low-latency interaction, and continuous communication after a connection is established.

If your scenario only needs to request a result once, or only needs the server to keep pushing content, you usually do not need `WebSocket`. Prefer it only when the client also needs to keep sending messages actively and both sides need a long-lived online channel.

## When to Use WebSocket

- Chat, multi-user collaboration, and real-time synchronization
- The client needs to continuously report status to the server
- The server also needs to push messages to the client at any time
- Lower latency and a more stable bidirectional channel are needed compared with polling

## Example

```javascript
const socket = new WebSocket('wss://example.com/realtime');

socket.addEventListener('open', () => {
  socket.send(JSON.stringify({
    type: 'hello',
    sessionId: 'demo-session',
  }));
});

socket.addEventListener('message', (event) => {
  console.log('收到消息:', event.data);
});

socket.addEventListener('close', () => {
  console.log('连接已关闭');
});
```

## Recommendations

- Split connection setup, message sending, message parsing, and connection teardown into separate logic instead of putting everything into page code.
- Define a stable message format such as `type`, `payload`, and `sessionId` to avoid mixing different messages into the same parsing logic.
- Design reconnect behavior explicitly so the page does not become unavailable for a long time because of network fluctuations.
- Close the connection proactively when the page is destroyed, the session ends, or the user exits.

## Connection Management Recommendations

- Clarify session identity and authentication before establishing the connection.
- Send business messages only after the connection succeeds. Do not assume the connection is available immediately after creation.
- Handle abnormal closure, authentication failure, and server rejection separately instead of reporting them all as "network errors".
- If your business logic depends on long-lived connections, consider adding heartbeat or activity detection.

## When Not to Use WebSocket

- When only calling ordinary APIs
- When only requesting one complete result
- When only needing one-way incremental pushes from the server

For these scenarios, [HTTPS / SSE](/AIUI/api/network-https-sse) is usually simpler and easier to maintain.

## Read Next

- **[HTTPS / SSE](/AIUI/api/network-https-sse)**: Learn what kinds of business scenarios are better suited to request-response and one-way streaming pushes from the server.
- **[Networking](/AIUI/api/weixin-compatible-apis-networking)**: Learn details of compatible APIs such as `wx.connectSocket`.
