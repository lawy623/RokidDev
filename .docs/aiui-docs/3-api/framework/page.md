# Page

`Page` 用于注册智能体中的一个页面。每个页面的逻辑通过 `export default` 导出一个配置对象。

## 示例代码

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

## 实例方法

在页面逻辑中，可以通过 `this` 访问页面实例，并调用以下方法：

### `this.setData(Object data, Function? callback)`
用于将数据从逻辑层发送到视图层（异步），同时改变对应的 `this.data` 的值。
- **参数**:
    - `data`: 包含需要更新的数据键值对。支持以数据路径的形式给出（例如 `'a.b.c': 1`）。
    - `callback`: 可选。数据更新完成后的回调函数。

### `this.finish()`
通知系统当前页面任务已完成。
- 对于 **Cut (快切)** 智能体，调用此方法将主动交回焦点并退出当前展示状态。
- 对于 **Scene (场景)** 智能体，通常用于结束当前特定交互流程。

## 生命周期回调

| 回调函数 | 说明 | 触发时机 |
| :--- | :--- | :--- |
| `onLoad` | 监听页面加载 | 页面加载时触发（全局只触发一次） |
| `onShow` | 监听页面显示 | 页面显示/切入前台时触发 |
| `onReady` | 监听页面初次渲染完成 | 页面初次渲染完成时触发（全局只触发一次） |
| `onHide` | 监听页面隐藏 | 页面隐藏/切入后台时触发 |
| `onUnload` | 监听页面卸载 | 页面卸载时触发 |
