# 网络请求 (Networking)

AIUI 提供了一套与微信小程序兼容的网络通信接口，包括 HTTPS 请求、WebSocket 连接以及 EventSource。

## 发起请求

### `wx.request(Object object)`

发起 HTTPS 网络请求。

**参数 (Object object)**:

| 属性 | 类型 | 必填 | 默认值 | 描述 |
| :--- | :--- | :--- | :--- | :--- |
| `url` | String | 是 | | 开发者服务器接口地址 |
| `data` | String/Object/ArrayBuffer | 否 | | 请求的参数 |
| `header` | Object | 否 | | 设置请求的 header，header 中不能设置 Referer。`content-type` 默认为 `application/json` |
| `method` | String | 否 | `GET` | HTTP 请求方法。支持 `GET`, `POST`, `PUT`, `DELETE`, `HEAD` 等 |
| `dataType` | String | 否 | `json` | 返回的数据格式。如果设为 `json`，会尝试对返回的数据做一次 `JSON.parse` |
| `responseType` | String | 否 | `text` | 响应的数据类型。支持 `text`, `arraybuffer` |
| `success` | Function | 否 | | 接口调用成功的回调函数 |
| `fail` | Function | 否 | | 接口调用失败的回调函数 |
| `complete` | Function | 否 | | 接口调用结束的回调函数（调用成功、失败都会执行） |

**success 回调参数 (Object res)**:

| 属性 | 类型 | 描述 |
| :--- | :--- | :--- |
| `data` | String/Object/ArrayBuffer | 开发者服务器返回的数据 |
| `statusCode` | Number | 开发者服务器返回的 HTTP 状态码 |
| `header` | Object | 开发者服务器返回的 HTTP Response Header |
| `cookies` | Array.<String> | 开发者服务器返回的 cookies |
| `errMsg` | String | 成功信息，始终为 `"request:ok"` |

**返回值**:

返回一个 `RequestTask` 对象，可用于中断请求或监听响应头。

---

### `RequestTask`

通过 `wx.request` 返回的对象。

#### 方法

- **`abort()`**: 中断网络请求任务。
- **`onHeadersReceived(Function callback)`**: 监听 HTTP Response Header 事件。会比请求完成事件更早。
- **`offHeadersReceived(Function callback)`**: 移除 HTTP Response Header 事件的监听函数。
- **`onChunkReceived(Function callback)`**: 监听分块接收事件。
- **`offChunkReceived(Function callback)`**: 移除分块接收事件的监听函数。

---

## WebSocket

### `wx.connectSocket(Object object)` / `wx.createSocket(Object object)`

创建一个 WebSocket 连接。

**参数 (Object object)**:

| 属性 | 类型 | 必填 | 描述 |
| :--- | :--- | :--- | :--- |
| `url` | String | 是 | 开发者服务器接口地址，必须是 `ws` 或 `wss` 协议 |
| `header` | Object | 否 | HTTP Header，Header 中不能设置 Referer |

**返回值**:

返回一个 `SocketTask` 对象。

---

### `SocketTask`

通过 `wx.connectSocket` 返回的对象。

#### 方法

- **`send(Object object)`**: 通过 WebSocket 连接发送数据。
    - `data`: `String` 或 `ArrayBuffer`。
- **`close()`**: 关闭 WebSocket 连接。
- **`onOpen(Function callback)`**: 监听 WebSocket 连接打开事件。
- **`onClose(Function callback)`**: 监听 WebSocket 连接关闭事件。
- **`onError(Function callback)`**: 监听 WebSocket 错误事件。
- **`onMessage(Function callback)`**: 监听 WebSocket 接受到服务器的消息事件。
    - 回调参数 `data`: `String` 或 `ArrayBuffer`。

---

## EventSource

### `wx.createEventSource(Object object)`

创建一个 EventSource 连接（用于服务器发送事件 SSE）。

**参数 (Object object)**:

| 属性 | 类型 | 必填 | 描述 |
| :--- | :--- | :--- | :--- |
| `url` | String | 是 | 开发者服务器接口地址 |

**返回值**:

返回一个 `EventSourceTask` 对象。

---

## 代码示例

### 1. 发起 GET 请求

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

### 2. 发起 POST 请求（JSON 格式）

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

### 3. 使用 WebSocket

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
