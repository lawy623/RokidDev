# 网络

提供 `wx.request` 用于发起 HTTPS 网络请求，支持 WebSocket 通信。

```javascript
wx.request({
  url: 'https://api.example.com/data',
  method: 'GET',
  success(res) {
    console.log(res.data);
  }
});
```