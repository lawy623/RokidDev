# 模块化

AIUI 深度拥抱现代 JavaScript 生态，全面支持 **ES Modules (ESM)** 作为标准的模块化方案。

## 1. ESM 支持情况

AIUI 的底层运行时（Ink）原生支持 ESM，这意味着你可以在智能体（Agent）开发中使用 `import` 和 `export` 关键字。

> [!IMPORTANT]
> **.ink 文件（单文件组件）不支持导出（Export）**。它们作为智能体的页面或组件入口，只能通过 `import` 引入其他模块。只有标准的 `.js` 文件支持导出功能。

### 导出模块 (Export)

你可以在任何 `.js` 文件中导出变量、函数或类：

```javascript
// utils.js
export const VERSION = '1.0.0';

export function formatTime(date) {
  return date.toLocaleTimeString();
}

export default class Calculator {
  add(a, b) {
    return a + b;
  }
}
```

### 引入模块 (Import)

使用标准语法引入本地文件或第三方包：

```javascript
// pages/index/index.js
import Calculator, { VERSION, formatTime } from '../../utils.js';

console.log('Current Version:', VERSION);
```

## 2. 动态引入 (Dynamic Import)

AIUI 支持使用 `import()` 进行代码拆分和按需加载，这对于优化大型智能体的启动速度非常有用：

```javascript
async function loadChart() {
  const { Chart } = await import('../../libs/chart.js');
  const chart = new Chart();
  // ...
}
```

## 3. 第三方库使用

由于 AIUI 目前不支持直接从 `node_modules` 导入 npm 包，如果你需要使用第三方库，请遵循以下步骤：

1.  **手动下载**：下载该库的 ESM 版本文件（通常是 `.mjs` 或经过打包后的 `.js` 文件）。
2.  **存入项目**：将文件放入项目目录中（例如 `libs/` 文件夹）。
3.  **本地引入**：使用相对路径进行引入。

```javascript
import { throttle } from './libs/lodash-es.js';

const handledScroll = throttle(() => {
  console.log('Scrolling...');
}, 100);
```

## 4. 与传统 CommonJS 的区别

与旧的小程序框架不同，AIUI **不支持** `module.exports` 和 `require()`，请统一使用 ESM。

*   **原生性能**：ESM 是由 JS 引擎原生解析的，比 CommonJS 模拟层具有更高的加载性能。
*   **规范统一**：与现代 Web 开发体验完全一致。

## 5. 注意事项

*   **路径后缀**：在引入本地文件时，必须包含完整的文件后缀名（如 `.js`），这符合现代浏览器的 ESM 规范。
*   **顶级 await**：目前 AIUI **不支持** 顶级 `await` (Top-level await)，请在异步函数中使用 `await`。
