# Canvas 画布

`canvas` 组件提供了一个 2D 绘图上下文，类似于 HTML5 中的 `<canvas>` 元素。它允许通过脚本动态渲染 2D 形状和位图图像。

## 使用方法

```xml
<canvas id="myCanvas" width="300" height="150"></canvas>
```

在你的 JavaScript 中：

```javascript
// 获取 canvas 实例
const canvas = this.selectComponent('#myCanvas');
// 获取 2D 绘图上下文
const ctx = canvas.getContext('2d');

ctx.fillStyle = 'red';
ctx.fillRect(10, 10, 150, 75);
```

## 属性

| 属性 | 类型 | 描述 | 默认值 |
|-----------|------|-------------|---------|
| `width` | Number | 画布的像素宽度。 | `300` |
| `height` | Number | 画布的像素高度。 | `150` |

## API

`canvas` 组件通过标准的 Web Canvas API 进行控制。你可以使用 `canvas.getContext('2d')` 获取绘图上下文。

详细的 API 列表请参考 [Canvas API 规范](/AIUI/api/canvas)。
