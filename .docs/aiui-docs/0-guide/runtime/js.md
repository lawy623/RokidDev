# JavaScript 兼容性

AIUI 运行在底层的 Ink 容器中，而 Ink 的 JavaScript 引擎基于 **QuickJS (版本 2024-01-13)**。

QuickJS 是一个轻量级且完全支持 ES2023 规范的 JavaScript 引擎。了解它的特性和限制，有助于编写更健壮的智能体代码。

## 支持的 ECMAScript 特性

QuickJS 提供了非常现代的 JavaScript 语法支持，你可以在智能体开发中安全地使用以下特性：

### 1. 异步编程 (Async / Await)
原生支持 `Promise` 和 `async/await`，无需引入 Polyfill。

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

### 2. 现代数据结构
全面支持 `Map`、`Set`、`WeakMap` 和 `WeakSet`，以及最新的数组/对象方法。

```javascript
const agentState = new Map();
agentState.set('status', 'active');

// 可选链 (Optional Chaining) 与 空值合并 (Nullish Coalescing)
const userName = user?.profile?.name ?? 'Guest';
```

### 3. ES Modules
支持标准的 `import` 和 `export` 模块化语法。

```javascript
import { formatTime } from '../../utils/util.js';
export const config = { timeout: 5000 };
```

### 4. BigInt
原生支持大整数运算，这在处理高精度时间戳或硬件标识符时非常有用。

```javascript
const timestamp = 9007199254740991n;
```
