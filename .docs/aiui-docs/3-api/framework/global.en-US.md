# Global

AIUI provides a set of global-scope interfaces that follow Web standards. In the agent runtime, `window`, `self`, `global`, and `globalThis` all point to the same global object. You can think of this document as the unified entry point for capabilities related to the global object in AIUI.

## Core Interfaces

### Properties

- **`window.innerWidth`**: `Number`, the inner width of the window in pixels.
- **`window.innerHeight`**: `Number`, the inner height of the window in pixels.

### Timers

These methods allow you to execute code after a specified time interval.

- **`setTimeout(callback, delay, ...args)`**: Calls a function after the specified number of milliseconds.
    - `callback`: `Function`, the function to execute.
    - `delay`: `Number?`, the delay in milliseconds, defaulting to 0.
    - Return value: `TimerId` (`Number`), the unique identifier of the timer.
- **`clearTimeout(timeoutId)`**: Cancels a timer set by `setTimeout()`.
- **`setInterval(callback, delay, ...args)`**: Repeatedly calls a function every specified number of milliseconds.
    - Return value: `TimerId` (`Number`), the unique identifier of the timer.
- **`clearInterval(intervalId)`**: Cancels a repeating task set by `setInterval()`.

### Base64 Encoding and Decoding

- **`atob(encodedData)`**: Decodes a Base64-encoded string.
    - `encodedData`: `String`, a Base64-encoded string.
    - Return value: `String`, the decoded string.
- **`btoa(stringToEncode)`**: Encodes a string to Base64.
    - `stringToEncode`: `String`, the string to encode. Only the Latin1 range is supported.
    - Return value: `String`, the Base64-encoded string.

### Network Requests (Fetch)

- **`fetch(url, options?)`**: Sends an asynchronous network request.
    - `url`: `String`, the request URL.
    - `options`: `Object?`, configuration options such as `method`, `headers`, and `body`.
    - Return value: `Promise<Response>`.

---

### Response

The response object returned after a successful `fetch` request.

#### Properties

- **`ok`**: `Boolean`, whether the status code is in the 200-299 range.
- **`status`**: `Number`, the HTTP status code.
- **`statusText`**: `String`, the status text such as `"OK"`.
- **`url`**: `String`, the final URL of the response.

#### Methods

- **`text()`**: Returns a Promise that resolves to the response body as text.
- **`json()`**: Returns a Promise that resolves to the deserialized JSON object from the response body.
- **`arrayBuffer()`**: Returns a Promise that resolves to the response body as an `ArrayBuffer`.

---

## Global Mounts

In addition to the interfaces above, the following APIs are also mounted onto the global scope and the `window` object:

- **`console`**: Console logging. See [Console](/AIUI/api/console).
- **`localStorage`**: Local storage. See [Storage API](/AIUI/api/storage-api).
- **`speechSynthesis`**: Speech synthesis. Can be used together with [Speech Synthesis](/AIUI/api/ai-speech-synthesis).
- **`performance`**: Performance monitoring. See [Performance](/AIUI/api/performance).
- **`TextEncoder` / `TextDecoder`**: Text encoding and decoding. See [Encoding](/AIUI/api/encoding).

## Code Examples

### 1. Using Timers

```javascript
const timerId = setTimeout(() => {
  console.log('2秒后执行');
}, 2000);

// Cancel if needed
// clearTimeout(timerId);
```

### 2. Fetching Data with Fetch

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

### 3. Base64 Conversion

```javascript
const encoded = btoa('Hello AIUI');
console.log(encoded); // "SGVsbG8gSlNVST=="

const decoded = atob(encoded);
console.log(decoded); // "Hello AIUI"
```
