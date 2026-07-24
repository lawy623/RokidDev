# Page

`Page` is used to register a page in an agent. The logic for each page is exported as a configuration object through `export default`.

## Example Code

```javascript
export default {
  data: {
    text: "This is page data.",
    user: {
      name: 'Rokid'
    }
  },
  onLoad(options) {
    // 页面加载
  },
  handleUpdate() {
    // 更新数据
    this.setData({
      text: 'Updated Text',
      'user.name': 'New Name' // 支持路径式更新
    }, () => {
      console.log('Data updated');
    });
  },
  handleComplete() {
    // 完成当前页面任务
    this.finish();
  }
}
```

## Instance Methods

In page logic, you can access the page instance through `this` and call the following methods:

### `this.setData(Object data, Function? callback)`
Used to send data from the logic layer to the view layer asynchronously, while also updating the corresponding values in `this.data`.
- **Parameters**:
    - `data`: Key-value pairs containing the data to update. Path-style updates are supported, for example `'a.b.c': 1`.
    - `callback`: Optional. A callback function that runs after the data update is complete.

### `this.finish()`
Notifies the system that the current page task has been completed.
- For **Cut** agents, calling this method proactively returns focus and exits the current presentation state.
- For **Scene** agents, it is typically used to end the current specific interaction flow.

## Lifecycle Callbacks

| Callback | Description | Trigger Timing |
| :--- | :--- | :--- |
| `onLoad` | Listens for page loading | Triggered when the page loads, only once globally |
| `onShow` | Listens for the page being shown | Triggered when the page is shown or brought to the foreground |
| `onReady` | Listens for the initial page render to complete | Triggered when the initial render completes, only once globally |
| `onHide` | Listens for the page being hidden | Triggered when the page is hidden or moved to the background |
| `onUnload` | Listens for page unload | Triggered when the page is unloaded |
