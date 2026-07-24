# Canvas

AIUI provides a standard `<canvas>` component and currently supports the 2D context. You can use it to draw dynamic charts, complex 2D animations, and more.

## 1. 2D Drawing

2D drawing is mainly implemented through the `CanvasRenderingContext2D` interface, and the API is largely consistent with the Web standard.

### Basic Usage

Define a canvas component in `.wxml` and specify `canvas-id`:

```html
<canvas canvas-id="myCanvas" style="width: 300px; height: 150px;"></canvas>
```

Get the context in `.js` with `wx.createCanvasContext` and draw on it:

```javascript
export default {
  onLoad() {
    // 使用 wx.createCanvasContext 获取绘图上下文
    const ctx = wx.createCanvasContext('myCanvas', this);

    // 设置绘图样式
    ctx.setFillStyle('#07c160');
    ctx.fillRect(10, 10, 100, 100);

    ctx.setStrokeStyle('#ffffff');
    ctx.setLineWidth(2);
    ctx.strokeRect(30, 30, 100, 100);

    // 调用 draw 方法将绘图操作渲染到画布上
    ctx.draw();
  }
}
```

## 2. Special Considerations in AR Scenarios

When using Canvas on AR glasses, keep the following suggestions in mind:

*   **Layered rendering**: Since AR overlays content on top of the real world, it is recommended to keep the canvas background transparent so the real scene remains visible.
*   **Performance optimization**: Canvas redraws consume GPU resources. For static or low-frequency content, reduce how often `ctx.draw()` is called.
*   **Resolution adaptation**: AIUI automatically handles the conversion between logical pixels and physical pixels, but when drawing very thin lines or text, consider the device's `devicePixelRatio`.

## 3. Notes

*   **Timing**: It is recommended to call `wx.createCanvasContext` in the `onLoad` lifecycle callback.
*   **`draw` method**: Unlike standard Web Canvas, all drawing commands in `wx.createCanvasContext` are first stored in a command queue. You must call `ctx.draw()` to actually render them.
*   **Coming soon**: AIUI is currently developing support for **WebGL** and **OffscreenCanvas**. Stay tuned.
*   **Resource cleanup**: When the page unloads (`onUnload`), if you have enabled a complex rendering loop, be sure to stop timers or destroy related resources manually to save battery on the glasses.
