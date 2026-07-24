# 存储

提供本地数据缓存能力，支持异步和同步两组接口，适合保存用户偏好、页面状态和业务缓存等 JSON 可序列化数据。

## 支持的接口

- 异步接口：`wx.setStorage`、`wx.getStorage`、`wx.removeStorage`、`wx.clearStorage`
- 同步接口：`wx.setStorageSync`、`wx.getStorageSync`、`wx.removeStorageSync`、`wx.clearStorageSync`

## 使用示例

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

## 行为说明

- `wx.getStorage` 在读取成功时会通过 `success` 返回 `{ data }`
- `wx.getStorageSync` 在 key 不存在时返回 `undefined`
- 删除单个 key 可使用 `wx.removeStorage` / `wx.removeStorageSync`
- 清空全部本地缓存可使用 `wx.clearStorage` / `wx.clearStorageSync`
