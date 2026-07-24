# JavaScript Compatibility

AIUI runs inside the underlying Ink container, and Ink's JavaScript engine is based on **QuickJS (version 2024-01-13)**.

QuickJS is a lightweight JavaScript engine with full support for the ES2023 specification. Understanding its features and limitations helps you write more robust agent code.

## Supported ECMAScript Features

QuickJS provides support for very modern JavaScript syntax, so you can safely use the following features in agent development:

### 1. Asynchronous Programming (Async / Await)
Native support for `Promise` and `async/await`, with no need for a Polyfill.

```javascript
async function fetchAgentData() {
  try {
    const response = await wx.request({ url: '...' });
    return response.data;
  } catch (error) {
    console.error(error);
  }
}
```

### 2. Modern Data Structures
Full support for `Map`, `Set`, `WeakMap`, and `WeakSet`, as well as the latest array and object methods.

```javascript
const agentState = new Map();
agentState.set('status', 'active');

// 可选链 (Optional Chaining) 与 空值合并 (Nullish Coalescing)
const userName = user?.profile?.name ?? 'Guest';
```

### 3. ES Modules
Supports the standard modular syntax of `import` and `export`.

```javascript
import { formatTime } from '../../utils/util.js';
export const config = { timeout: 5000 };
```

### 4. BigInt
Native support for large integer operations, which is very useful when handling high-precision timestamps or hardware identifiers.

```javascript
const timestamp = 9007199254740991n;
```

