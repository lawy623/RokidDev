# 存储 (storage)

提供本地数据缓存的读写接口，支持异步和同步两种调用方式。

## 同步方法

- `wx.setStorageSync(string key, any data)`: 将数据写入指定 key。
- `wx.getStorageSync(string key)`: 读取指定 key 对应的数据；key 不存在时返回 `undefined`。
- `wx.removeStorageSync(string key)`: 删除指定 key。
- `wx.clearStorageSync()`: 清空全部本地缓存。

## 异步方法

- `wx.setStorage(Object object)`: 将数据写入指定 key。
- `wx.getStorage(Object object)`: 异步读取指定 key 的内容。
- `wx.removeStorage(Object object)`: 异步删除指定 key。
- `wx.clearStorage(Object object)`: 异步清空全部本地缓存。

## 参数说明

### `wx.setStorage(Object object)`

- `key`: 存储键名。
- `data`: 要保存的数据，建议使用 JSON 可序列化数据。
- `success`: 写入成功回调。
- `fail`: 写入失败回调，返回 `{ errMsg }`。
- `complete`: 调用结束回调。

### `wx.getStorage(Object object)`

- `key`: 存储键名。
- `success`: 读取成功回调，返回 `{ data }`。
- `fail`: 读取失败回调，返回 `{ errMsg }`。
- `complete`: 调用结束回调。

### `wx.removeStorage(Object object)`

- `key`: 要删除的存储键名。
- `success`: 删除成功回调。
- `fail`: 删除失败回调，返回 `{ errMsg }`。
- `complete`: 调用结束回调。

### `wx.clearStorage(Object object)`

- `success`: 清空成功回调。
- `fail`: 清空失败回调，返回 `{ errMsg }`。
- `complete`: 调用结束回调。

## 示例

```javascript
wx.setStorage({
  key: "user_info",
  data: { name: "AIUI User" },
  success() {
    console.log("saved");
  }
});

wx.getStorage({
  key: "user_info",
  success(res) {
    console.log(res.data);
  },
  fail(res) {
    console.error(res.errMsg);
  }
});

wx.setStorageSync("token", "abc123");
const token = wx.getStorageSync("token");
```
