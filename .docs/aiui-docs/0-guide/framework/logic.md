# 逻辑开发

AIUI 智能体的逻辑开发采用了类似于现代前端框架的模块化开发方式。你可以通过 `export default` 导出配置对象来注册智能体应用或页面。

## 注册智能体 (App)

每个智能体项目必须在根目录下有一个 `app.js` 或 `app.ink`（在 SFC 模式下）。在 `.js` 文件中，通过 `export default` 导出应用实例以注册应用；而 `.ink` 文件作为 SFC 入口，本身不支持模块导出，只能通过脚本块定义应用逻辑并导入其他 ESM 模块。应用实例用于处理全局生命周期和存储全局数据。

### 示例代码

```javascript
// app.js
export default {
  // 智能体初始化时触发
  onLaunch(options) {
    console.log('Agent Launch', options);
  },
  // 智能体启动或从后台进入前台时触发
  onShow(options) {
    console.log('Agent Show');
  },
  // 智能体从前台进入后台时触发
  onHide() {
    console.log('Agent Hide');
  },
  // 全局共享数据
  globalData: {
    userInfo: null
  }
}
```

### 生命周期回调

| 回调函数 | 说明 | 触发时机 |
| :--- | :--- | :--- |
| `onLaunch` | 监听智能体初始化 | 智能体初始化完成时（全局只触发一次） |
| `onShow` | 监听智能体显示 | 智能体启动，或从后台进入前台显示时 |
| `onHide` | 监听智能体隐藏 | 智能体从前台进入后台时 |
| `onError` | 错误监听函数 | 智能体发生脚本错误，或者 API 调用失败时 |

## 注册页面 (Page)

每个页面通过在其逻辑文件（`.js` 或 `.ink`）中定义页面配置对象。在 `.js` 文件中，通过 `export default` 注册页面；而 `.ink` 文件则直接在 `<script setup>` 中定义逻辑并引入所需模块。该对象定义了页面的初始数据、生命周期回调、事件处理函数等。

### 示例代码

```javascript
// pages/index/index.js
export default {
  // 页面的初始数据
  data: {
    title: 'Hello AIUI',
    count: 0
  },
  // 页面加载时触发
  onLoad(query) {
    console.log('Page Load', query);
  },
  // 页面显示时触发
  onShow() {
    console.log('Page Show');
  },
  // 页面初次渲染完成时触发
  onReady() {
    console.log('Page Ready');
  },
  // 页面隐藏时触发
  onHide() {
    console.log('Page Hide');
  },
  // 页面卸载时触发
  onUnload() {
    console.log('Page Unload');
  },
  // 事件处理函数
  handleIncrement() {
    this.setData({
      count: this.data.count + 1
    });
  }
}
```

### 生命周期回调

| 回调函数 | 说明 | 触发时机 |
| :--- | :--- | :--- |
| `onLoad` | 监听页面加载 | 页面加载时触发（全局只触发一次） |
| `onShow` | 监听页面显示 | 页面显示/切入前台时触发 |
| `onReady` | 监听页面初次渲染完成 | 页面初次渲染完成时触发（全局只触发一次） |
| `onHide` | 监听页面隐藏 | 页面隐藏/切入后台时触发 |
| `onUnload` | 监听页面卸载 | 页面卸载时触发 |

## 页面实例方法

在页面逻辑中，你可以通过 `this` 访问页面实例，并调用以下常用方法：

- **`this.setData(Object data, Function callback)`**: 将数据从逻辑层发送到视图层（异步），同时改变对应的 `this.data` 的值。
- **`this.data`**: 获取当前页面的数据。
- **`this.route`**: 暂不支持。
