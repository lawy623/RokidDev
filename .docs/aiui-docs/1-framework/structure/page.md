# 页面

注册智能体中的一个页面。接受一个 `Object` 类型参数，其指定页面的初始数据、生命周期回调、事件处理函数等。

AIUI 页面结构遵循类似微信小程序的 `Page` 定义规范。

## 页面组织方式

在 AIUI 中，页面通常有两种组织方式：

- **多文件页面**：页面由 `index.js`、`index.wxml`、`index.wxss` 和 `page.json` 等文件共同组成，其中 `Page({...})` 或页面导出对象通常写在 `index.js` 中。
- **单文件页面**：页面使用 `.ink` 单文件组织，在同一个文件中同时声明配置、逻辑、结构和样式；其中页面逻辑通常写在 `<script setup>` 中。

你可以把它理解成：

- 多文件页面：适合按“配置 / 逻辑 / 结构 / 样式”拆分维护
- 单文件页面：适合把同一个页面的内容放在一起阅读和编辑

无论采用哪种组织方式，页面本身的生命周期、`data` 数据对象和事件处理方式保持一致，仍然遵循本文介绍的 `Page` 规范。

## 参数

### Object object

| 属性 | 类型 | 默认值 | 必填 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| `data` | Object | | 否 | 页面的初始数据 |
| `options` | Object | | 否 | 页面的组件选项 |
| `onLoad` | function | | 否 | 生命周期回调—监听页面加载 |
| `onShow` | function | | 否 | 生命周期回调—监听页面显示 |
| `onReady` | function | | 否 | 生命周期回调—监听页面初次渲染完成 |
| `onHide` | function | | 否 | 生命周期回调—监听页面隐藏 |
| `onUnload` | function | | 否 | 生命周期回调—监听页面卸载 |
| 其他 | any | | 否 | 开发者可以添加任意的函数或数据到 Object 参数中，在页面的函数中用 `this` 可以访问。这部分属性会在页面实例创建时进行一次深拷贝。 |

## 示例代码

```javascript
// index.js
export default {
  data: {
    text: "This is page data."
  },
  onLoad: function(options) {
    // 页面创建时执行
  },
  onShow: function() {
    // 页面出现在前台时执行
  },
  onReady: function() {
    // 页面首次渲染完毕时执行
  },
  onHide: function() {
    // 页面从前台变为后台时执行
  },
  onUnload: function() {
    // 页面销毁时执行
  },
  // 自由数据或方法
  customData: {
    hi: 'MINA'
  }
}
```

## 生命周期回调函数

AIUI 页面的生命周期遵循固定的调用顺序。首次打开页面时，依次触发 `onLoad` → `onShow` → `onReady`；切换到其他页面时当前页触发 `onHide`，切回时触发 `onShow`；页面销毁时触发 `onUnload`。

<page-lifecycle-demo></page-lifecycle-demo>

### onLoad(Object query)
页面加载时触发。一个页面只会调用一次，可以在 `onLoad` 的参数中获取打开当前页面路径中的参数。

### onShow()
页面显示/切入前台时触发。

### onReady()
页面初次渲染完成时触发。一个页面只会调用一次，代表页面已经准备妥当，可以和视图层进行交互。

### onHide()
页面隐藏/切入后台时触发。如通过底部 tab 切换到其他页面，小程序切入后台等。

### onUnload()
页面卸载时触发。如跳转到其他页面销毁当前页面时。

## 页面数据对象 data

`data` 是页面第一次渲染使用的初始数据。页面加载时，`data` 将会以 JSON 字符串的形式由逻辑层传至渲染层，因此 `data` 中的数据必须是可以转成 JSON 的类型：字符串，数字，布尔值，对象，数组。

渲染层可以通过 WXML/INK 对数据进行绑定。
