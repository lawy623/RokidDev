# Network

Provides `wx.request` for making HTTPS network requests and supports WebSocket communication.

```javascript
wx.request({
  url: 'https://api.example.com/data',
  method: 'GET',
  success(res) {
    console.log(res.data);
  }
});
```
