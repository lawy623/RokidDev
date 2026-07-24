# Networking (Networking)

AIUI provides a set of network communication APIs compatible with WeChat Mini Programs, including HTTPS requests, WebSocket connections, and EventSource.

## Making Requests

### `wx.request(Object object)`

Initiates an HTTPS network request.

**Parameters (Object object)**:

| Property | Type | Required | Default | Description |
| :--- | :--- | :--- | :--- | :--- |
| `url` | String | Yes | | Developer server API address |
| `data` | String/Object/ArrayBuffer | No | | Request parameters |
| `header` | Object | No | | Sets the request header. `Referer` cannot be set in the header. `content-type` defaults to `application/json` |
| `method` | String | No | `GET` | HTTP request method. Supports `GET`, `POST`, `PUT`, `DELETE`, `HEAD`, etc. |
| `dataType` | String | No | `json` | Returned data format. If set to `json`, the returned data will be passed through `JSON.parse` when possible |
| `responseType` | String | No | `text` | Response data type. Supports `text`, `arraybuffer` |
| `success` | Function | No | | Callback function invoked when the API call succeeds |
| `fail` | Function | No | | Callback function invoked when the API call fails |
| `complete` | Function | No | | Callback function invoked when the API call finishes, whether it succeeds or fails |

**success callback parameter (Object res)**:

| Property | Type | Description |
| :--- | :--- | :--- |
| `data` | String/Object/ArrayBuffer | Data returned by the developer server |
| `statusCode` | Number | HTTP status code returned by the developer server |
| `header` | Object | HTTP response header returned by the developer server |
| `cookies` | Array.<String> | Cookies returned by the developer server |
| `errMsg` | String | Success message, always `"request:ok"` |

**Return value**:

Returns a `RequestTask` object, which can be used to abort the request or listen for response headers.

---

### `RequestTask`

The object returned by `wx.request`.

#### Methods

- **`abort()`**: Aborts the network request task.
- **`onHeadersReceived(Function callback)`**: Listens for the HTTP response header event. This fires earlier than the request completion event.
- **`offHeadersReceived(Function callback)`**: Removes the listener for the HTTP response header event.
- **`onChunkReceived(Function callback)`**: Listens for chunk received events.
- **`offChunkReceived(Function callback)`**: Removes the listener for chunk received events.

---

## WebSocket

### `wx.connectSocket(Object object)` / `wx.createSocket(Object object)`

Creates a WebSocket connection.

**Parameters (Object object)**:

| Property | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `url` | String | Yes | Developer server API address. Must use the `ws` or `wss` protocol |
| `header` | Object | No | HTTP header. `Referer` cannot be set in the header |

**Return value**:

Returns a `SocketTask` object.

---

### `SocketTask`

The object returned by `wx.connectSocket`.

#### Methods

- **`send(Object object)`**: Sends data through the WebSocket connection.
    - `data`: `String` or `ArrayBuffer`.
- **`close()`**: Closes the WebSocket connection.
- **`onOpen(Function callback)`**: Listens for the WebSocket open event.
- **`onClose(Function callback)`**: Listens for the WebSocket close event.
- **`onError(Function callback)`**: Listens for WebSocket error events.
- **`onMessage(Function callback)`**: Listens for messages received from the server over WebSocket.
    - Callback parameter `data`: `String` or `ArrayBuffer`.

---

## EventSource

### `wx.createEventSource(Object object)`

Creates an EventSource connection for Server-Sent Events (SSE).

**Parameters (Object object)**:

| Property | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `url` | String | Yes | Developer server API address |

**Return value**:

Returns an `EventSourceTask` object.

---

## Code Examples

### 1. Send a GET Request

```javascript
wx.request({
  url: 'https://api.example.com/data',
  method: 'GET',
  success(res) {
    console.log('收到数据:', res.data);
  },
  fail(err) {
    console.error('请求失败:', err.errMsg);
  }
});
```

### 2. Send a POST Request (JSON Format)

```javascript
wx.request({
  url: 'https://api.example.com/update',
  method: 'POST',
  data: {
    id: 1,
    name: 'test'
  },
  header: {
    'content-type': 'application/json'
  },
  success(res) {
    console.log('更新成功');
  }
});
```

### 3. Use WebSocket

```javascript
const task = wx.connectSocket({
  url: 'wss://socket.example.com'
});

task.onOpen(() => {
  console.log('连接已打开');
  task.send({ data: 'Hello Server' });
});

task.onMessage((res) => {
  console.log('收到消息:', res.data);
});

task.onError((err) => {
  console.error('连接错误:', err);
});
```
