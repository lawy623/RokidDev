# Modularization

AIUI fully embraces the modern JavaScript ecosystem and provides comprehensive support for **ES Modules (ESM)** as the standard modularization solution.

## 1. ESM Support

The underlying AIUI runtime (Ink) supports ESM natively, which means you can use the `import` and `export` keywords when developing agents.

> [!IMPORTANT]
> **`.ink` files (single-file components) do not support exports.** They serve as page or component entry points for agents and can only import other modules. Only standard `.js` files support exporting.

### Export Modules

You can export variables, functions, or classes from any `.js` file:

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

### Import Modules

Use standard syntax to import local files or third-party packages:

```javascript
// pages/index/index.js
import Calculator, { VERSION, formatTime } from '../../utils.js';

console.log('Current Version:', VERSION);
```

## 2. Dynamic Import

AIUI supports `import()` for code splitting and on-demand loading, which is very useful for optimizing startup speed in large agents:

```javascript
async function loadChart() {
  const { Chart } = await import('../../libs/chart.js');
  const chart = new Chart();
  // ...
}
```

## 3. Using Third-Party Libraries

Since AIUI currently does not support importing npm packages directly from `node_modules`, follow these steps if you need to use a third-party library:

1.  **Download manually**: Download the ESM build of the library, usually a `.mjs` file or a bundled `.js` file.
2.  **Store it in your project**: Put the file into your project directory, for example in a `libs/` folder.
3.  **Import it locally**: Use a relative path to import it.

```javascript
import { throttle } from './libs/lodash-es.js';

const handledScroll = throttle(() => {
  console.log('Scrolling...');
}, 100);
```

## 4. Differences From Traditional CommonJS

Unlike older mini-program frameworks, AIUI does **not support** `module.exports` or `require()`. Please use ESM consistently.

*   **Native performance**: ESM is parsed natively by the JS engine and offers better loading performance than a CommonJS compatibility layer.
*   **Unified standard**: It aligns completely with the modern Web development experience.

## 5. Notes

*   **Path extensions**: When importing local files, you must include the full file extension such as `.js`, which follows the ESM specification in modern browsers.
*   **Top-level await**: AIUI currently does **not support** top-level `await`. Use `await` inside async functions instead.
