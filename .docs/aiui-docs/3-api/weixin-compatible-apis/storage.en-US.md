# Storage (storage)

Provides read and write APIs for local data caching, supporting both asynchronous and synchronous calls.

## Synchronous Methods

- `wx.setStorageSync(string key, any data)`: Writes data to the specified key.
- `wx.getStorageSync(string key)`: Reads the data for the specified key. Returns `undefined` if the key does not exist.
- `wx.removeStorageSync(string key)`: Deletes the specified key.
- `wx.clearStorageSync()`: Clears all local cache.

## Asynchronous Methods

- `wx.setStorage(Object object)`: Writes data to the specified key.
- `wx.getStorage(Object object)`: Asynchronously reads the content of the specified key.
- `wx.removeStorage(Object object)`: Asynchronously deletes the specified key.
- `wx.clearStorage(Object object)`: Asynchronously clears all local cache.

## Parameter Details

### `wx.setStorage(Object object)`

- `key`: Storage key name.
- `data`: Data to save. JSON-serializable data is recommended.
- `success`: Callback invoked when the write succeeds.
- `fail`: Callback invoked when the write fails. Returns `{ errMsg }`.
- `complete`: Callback invoked when the call completes.

### `wx.getStorage(Object object)`

- `key`: Storage key name.
- `success`: Callback invoked when the read succeeds. Returns `{ data }`.
- `fail`: Callback invoked when the read fails. Returns `{ errMsg }`.
- `complete`: Callback invoked when the call completes.

### `wx.removeStorage(Object object)`

- `key`: The storage key name to delete.
- `success`: Callback invoked when the deletion succeeds.
- `fail`: Callback invoked when the deletion fails. Returns `{ errMsg }`.
- `complete`: Callback invoked when the call completes.

### `wx.clearStorage(Object object)`

- `success`: Callback invoked when clearing succeeds.
- `fail`: Callback invoked when clearing fails. Returns `{ errMsg }`.
- `complete`: Callback invoked when the call completes.

## Example

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
