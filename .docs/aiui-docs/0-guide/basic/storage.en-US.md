# Storage

Provides local data caching capabilities, with both asynchronous and synchronous APIs. It is suitable for storing JSON-serializable data such as user preferences, page state, and business caches.

## Supported APIs

- Asynchronous APIs: `wx.setStorage`, `wx.getStorage`, `wx.removeStorage`, `wx.clearStorage`
- Synchronous APIs: `wx.setStorageSync`, `wx.getStorageSync`, `wx.removeStorageSync`, `wx.clearStorageSync`

## Usage Example

```javascript
// 异步写入
wx.setStorage({
  key: "user_info",
  data: { name: "AIUI User", role: "developer" },
  success() {
    console.log("storage saved");
  },
  fail(res) {
    console.error(res.errMsg);
  }
});

// 异步读取
wx.getStorage({
  key: "user_info",
  success(res) {
    console.log(res.data);
  },
  fail(res) {
    console.error(res.errMsg);
  }
});

// 同步写入与读取
wx.setStorageSync("theme", { mode: "dark" });
const theme = wx.getStorageSync("theme");
```

## Behavior

- `wx.getStorage` returns `{ data }` through `success` when the read succeeds
- `wx.getStorageSync` returns `undefined` when the key does not exist
- To delete a single key, use `wx.removeStorage` or `wx.removeStorageSync`
- To clear all local cache, use `wx.clearStorage` or `wx.clearStorageSync`
