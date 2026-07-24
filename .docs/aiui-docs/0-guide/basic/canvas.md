# 画布 (Canvas)

AIUI 提供了标准的 `<canvas>` 组件，目前支持 2D 上下文。你可以利用它来绘制动态图表、复杂的 2D 动画等。

## 1. 2D 绘图

2D 绘图主要通过 `CanvasRenderingContext2D` 接口实现，API 与 Web 标准基本保持一致。

### 基础用法

在 `.wxml` 中定义 canvas 组件，并指定 `canvas-id`：

```html
<canvas canvas-id="myCanvas" style="width: 300px; height: 150px;"></canvas>
```

在 `.js` 中使用 `wx.createCanvasContext` 获取上下文并绘图：

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

## 2. AR 场景下的特殊考量

在 AR 眼镜端使用 Canvas 时，有以下几点建议：

*   **分层渲染**：由于 AR 是在现实空间上叠加内容，建议 canvas 背景保持透明，以便能够看到现实场景。
*   **性能优化**：Canvas 的重绘会消耗 GPU 资源。对于静态或低频更新的内容，建议减少 `ctx.draw()` 的调用频率。
*   **分辨率适配**：AIUI 会自动处理逻辑像素与物理像素的转换，但在绘制极细线条或文字时，建议考虑设备的 `devicePixelRatio`。

## 3. 注意事项

*   **获取时机**：建议在 `onLoad` 生命周期回调中调用 `wx.createCanvasContext`。
*   **draw 方法**：与标准的 Web Canvas 不同，`wx.createCanvasContext` 所有的绘图命令都会先存入命令队列，必须调用 `ctx.draw()` 才会真正执行渲染。
*   **即将支持**：目前 AIUI 正在开发 **WebGL** 以及 **离屏 Canvas (OffscreenCanvas)** 支持，敬请期待。
*   **资源回收**：当页面卸载（`onUnload`）时，如果开启了复杂的渲染循环，请务必手动停止计时器或销毁相关资源，以节省眼镜电量。
