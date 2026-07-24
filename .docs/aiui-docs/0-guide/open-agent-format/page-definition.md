# 页面定义

`Page` 用于注册智能体中的一个页面。每个页面的逻辑通过 `export default` 导出一个配置对象。

页面接受一个 `Object` 类型参数，用来指定页面的初始数据、生命周期回调、事件处理函数等。

AIUI 页面结构遵循类似微信小程序的 `Page` 定义规范。

## Object object

| 属性 | 类型 | 默认值 | 必填 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `data` | Object | | 否 | 页面的初始数据 |
| `options` | Object | | 否 | 页面的组件选项 |
| `onLoad` | function | | 否 | 生命周期回调，监听页面加载 |
| `onShow` | function | | 否 | 生命周期回调，监听页面显示 |
| `onKeyUp` | function | | 否 | 按键抬起事件回调，监听页面级 `keyup` 事件，可通过 `event.code` 获取按键编码 |
| `onKeyDown` | function | | 否 | 按键按下事件回调，监听页面级 `keydown` 事件，可通过 `event.code` 获取按键编码 |
| `onVoiceWakeup` | function | | 否 | 语音唤醒事件回调，监听页面级 `voicewakeup` 事件，可通过 `event.keyword` 获取唤醒词，默认值为 `leqi` |
| `onReady` | function | | 否 | 生命周期回调，监听页面初次渲染完成 |
| `onHide` | function | | 否 | 生命周期回调，监听页面隐藏 |
| `onUnload` | function | | 否 | 生命周期回调，监听页面卸载 |
| 其他 | any | | 否 | 开发者可以添加任意函数或数据，在页面函数中通过 `this` 访问 |

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

## 生命周期回调说明

### `onLoad(Object options)`

页面加载时触发，全局只调用一次。可以通过 `options` 获取打开当前页面时传入的参数，通常用于初始化页面数据、处理路由参数等。

```javascript
export default {
  onLoad(options) {
    this.setData({
      pageId: options.id || '',
      status: 'page loaded'
    });
  }
}
```

### `onShow()`

页面显示或切入前台时触发。适合在这里执行页面可见性相关的逻辑，例如刷新展示状态、恢复轮询或重新拉取轻量数据。

```javascript
export default {
  onShow() {
    this.setData({
      visible: true,
      status: 'page visible'
    });
  }
}
```

### `onReady()`

页面初次渲染完成时触发，全局只调用一次。此时页面已准备就绪，适合执行依赖首屏渲染完成的逻辑。

```javascript
export default {
  onReady() {
    this.setData({
      status: 'page ready'
    });
  }
}
```

### `onHide()`

页面隐藏或切入后台时触发。常用于暂停页面中的计时器、动画、轮询请求或清理临时状态。

```javascript
export default {
  onHide() {
    this.setData({
      visible: false,
      status: 'page hidden'
    });
  }
}
```

### `onUnload()`

页面卸载时触发。适合在这里释放页面占用的资源，避免页面销毁后仍保留无效引用或任务。

```javascript
export default {
  onUnload() {
    console.log('page unloaded');
  }
}
```

## 事件处理

页面除了生命周期回调外，还支持页面级事件处理函数，例如 `onKeyDown`、`onKeyUp` 和 `onVoiceWakeup`。

事件触发方式、参数说明和示例代码请参考：[事件](/AIUI/guide/open-agent-format-page-events)

## 实例方法

在页面逻辑中，可以通过 `this` 访问页面实例，并调用以下方法：

### `this.setData(Object data, Function? callback)`

用于将数据从逻辑层发送到视图层（异步），同时改变对应的 `this.data` 的值。

- `data`：包含需要更新的数据键值对，支持以数据路径的形式给出，例如 `'a.b.c': 1`
- `callback`：可选，数据更新完成后的回调函数

### `this.finish()`

通知系统当前页面任务已完成。

- 对于 **Cut（快切）** 智能体，调用此方法会主动交回焦点并退出当前展示状态
- 对于 **Scene（场景）** 智能体，通常用于结束当前特定交互流程

## 页面数据对象 `data`

`data` 是页面第一次渲染使用的初始数据。页面加载时，`data` 会以 JSON 字符串形式由逻辑层传至渲染层，因此 `data` 中的数据必须是可以转成 JSON 的类型，例如字符串、数字、布尔值、对象和数组。

渲染层可以通过 WXML 或 `.ink` 页面结构对数据进行绑定。

## 推荐阅读

- [页面概览](/AIUI/guide/open-agent-format-page)
- [生命周期](/AIUI/guide/open-agent-format-page-lifecycle)
- [事件](/AIUI/guide/open-agent-format-page-events)
