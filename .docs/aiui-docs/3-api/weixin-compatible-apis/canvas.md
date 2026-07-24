# 画布 (canvas)

提供用于操作 `canvas` 组件的绘图接口。

## 方法

- `wx.createCanvasContext(string canvasId, Object this)`: 创建 canvas 的绘图上下文 CanvasContext 对象。
- `wx.canvasToTempFilePath(Object object, Object this)`: 把当前画布指定区域的内容导出生成指定大小的图片。
- `wx.canvasGetImageData(Object object, Object this)`: 获取 canvas 区域隐含的像素数据。
- `wx.canvasPutImageData(Object object, Object this)`: 将像素数据绘制到画布。
