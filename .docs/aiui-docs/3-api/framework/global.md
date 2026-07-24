# 全局

AIUI 提供了一套遵循 Web 标准的全局作用域接口。在智能体运行环境中，`window`、`self`、`global` 和 `globalThis` 都指向同一个全局对象。你可以把这篇文档理解成 AIUI 中与“全局对象”相关能力的统一入口。

## 核心接口

### 属性

- **`window.innerWidth`**: `Number`，窗口的内部宽度（以像素为单位）。
- **`window.innerHeight`**: `Number`，窗口的内部高度（以像素为单位）。

### 定时器 (Timers)

这些方法允许你在指定的时间间隔后执行代码。

- **`setTimeout(callback, delay, ...args)`**: 在指定的毫秒数后调用函数。
    - `callback`: `Function`，要执行的函数。
    - `delay`: `Number?`，延迟的毫秒数（默认为 0）。
    - 返回值: `TimerId` (Number)，定时器的唯一标识。
- **`clearTimeout(timeoutId)`**: 取消由 `setTimeout()` 设置的定时器。
- **`setInterval(callback, delay, ...args)`**: 每隔指定的毫秒数重复调用函数。
    - 返回值: `TimerId` (Number)，定时器的唯一标识。
- **`clearInterval(intervalId)`**: 取消由 `setInterval()` 设置的重复定时任务。

### Base64 编解码

- **`atob(encodedData)`**: 解码 base64 编码的字符串。
    - `encodedData`: `String`，base64 编码的字符串。
    - 返回值: `String`，解码后的字符串。
- **`btoa(stringToEncode)`**: 将字符串编码为 base64。
    - `stringToEncode`: `String`，要编码的字符串（仅支持 Latin1 范围）。
    - 返回值: `String`，base64 编码后的字符串。

### 网络请求 (Fetch)

- **`fetch(url, options?)`**: 发起异步网络请求。
    - `url`: `String`，请求地址。
    - `options`: `Object?`，配置项（如 `method`, `headers`, `body`）。
    - 返回值: `Promise<Response>`。

---

### Response

`fetch` 请求成功后返回的响应对象。

#### 属性

- **`ok`**: `Boolean`，状态码是否在 200-299 范围内。
- **`status`**: `Number`，HTTP 状态码。
- **`statusText`**: `String`，状态描述（如 "OK"）。
- **`url`**: `String`，响应的最终 URL。

#### 方法

- **`text()`**: 返回一个 Promise，解析为响应体的文本字符串。
- **`json()`**: 返回一个 Promise，解析为响应体反序列化后的 JSON 对象。
- **`arrayBuffer()`**: 返回一个 Promise，解析为响应体的 `ArrayBuffer`。

---

## 全局挂载

除了上述接口，以下 API 也被挂载到了全局作用域和 `window` 对象上：

- **`console`**: 控制台日志，详见 [控制台](/AIUI/api/console)。
- **`localStorage`**: 本地存储，详见 [Storage API](/AIUI/api/storage-api)。
- **`speechSynthesis`**: 语音合成，可配合 [语音播报](/AIUI/api/ai-speech-synthesis) 使用。
- **`performance`**: 性能监控，详见 [性能](/AIUI/api/performance)。
- **`TextEncoder` / `TextDecoder`**: 文本编解码，详见 [编码 (Encoding)](/AIUI/api/encoding)。

## 代码示例

### 1. 使用定时器

```javascript
const timerId = setTimeout(() => {
  console.log('2秒后执行');
}, 2000);

// 如果需要取消
// clearTimeout(timerId);
```

### 2. 使用 Fetch 获取数据

```javascript
async function getData() {
  try {
    const response = await fetch('https://api.example.com/info');
    if (response.ok) {
      const data = await response.json();
      console.log('数据:', data);
    }
  } catch (error) {
    console.error('请求失败:', error);
  }
}
```

### 3. Base64 转换

```javascript
const encoded = btoa('Hello AIUI');
console.log(encoded); // "SGVsbG8gSlNVST=="

const decoded = atob(encoded);
console.log(decoded); // "Hello AIUI"
```
