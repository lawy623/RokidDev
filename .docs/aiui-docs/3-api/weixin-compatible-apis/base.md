# 基础 (base)

提供一些基础的方法和属性，主要用于环境变量、基础类型的转换和编码。

## 方法

- `wx.env`: 获取环境变量对象。
- `wx.canIUse(schema)`: 判断小程序的 API、回调、参数、组件等是否在当前版本可用。
- `wx.base64ToArrayBuffer(base64)`: 将 Base64 字符串转成 ArrayBuffer 数据。
- `wx.arrayBufferToBase64(buffer)`: 将 ArrayBuffer 数据转成 Base64 字符串。
