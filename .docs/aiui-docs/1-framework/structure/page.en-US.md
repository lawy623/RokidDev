# Page

Registers a page in the agent. It accepts an `Object` parameter that specifies the page's initial data, lifecycle callbacks, event handlers, and more.

The AIUI page structure follows a `Page` definition pattern similar to WeChat Mini Programs.

## Page Organization

In AIUI, pages are usually organized in two ways:

- **Multi-file page**: A page consists of files such as `index.js`, `index.wxml`, `index.wxss`, and `page.json`, where `Page({...})` or the page export object is usually written in `index.js`.
- **Single-file page**: A page is organized as a `.ink` single file, where configuration, logic, structure, and styles are declared in the same file; the page logic is usually written in `<script setup>`.

You can think of it like this:

- Multi-file page: Suitable for maintaining "configuration / logic / structure / style" separately
- Single-file page: Suitable for reading and editing everything for the same page in one place

No matter which organization method is used, the page lifecycle, `data` object, and event handling remain the same and still follow the `Page` specification introduced in this document.

## Parameters

### Object object

| Property | Type | Default | Required | Description |
| :--- | :--- | :--- | :--- | :--- |
| `data` | Object | | No | The initial data of the page |
| `options` | Object | | No | The component options of the page |
| `onLoad` | function | | No | Lifecycle callback - listens for page load |
| `onShow` | function | | No | Lifecycle callback - listens for page display |
| `onReady` | function | | No | Lifecycle callback - listens for the first render completion of the page |
| `onHide` | function | | No | Lifecycle callback - listens for page hide |
| `onUnload` | function | | No | Lifecycle callback - listens for page unload |
| Other | any | | No | Developers can add any functions or data to the `Object` parameter and access them with `this` inside page functions. These properties are deeply copied once when the page instance is created. |

## Example Code

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

## Lifecycle Callbacks

The AIUI page lifecycle follows a fixed invocation order. When a page is opened for the first time, `onLoad` → `onShow` → `onReady` are triggered in sequence. When switching to another page, the current page triggers `onHide`, and when switching back it triggers `onShow`; when the page is destroyed, `onUnload` is triggered.

<page-lifecycle-demo></page-lifecycle-demo>

### onLoad(Object query)
Triggered when the page loads. A page is called only once. You can access the parameters in the path used to open the current page through the `onLoad` parameter.

### onShow()
Triggered when the page is displayed or brought to the foreground.

### onReady()
Triggered when the page finishes its first render. A page is called only once, indicating that the page is ready and can interact with the view layer.

### onHide()
Triggered when the page is hidden or moved to the background, such as when switching to another page via the bottom tab or when the mini program goes into the background.

### onUnload()
Triggered when the page is unloaded, such as when navigating to another page and destroying the current page.

## Page Data Object `data`

`data` is the initial data used for the first render of the page. When the page loads, `data` is passed from the logic layer to the rendering layer as a JSON string, so the data in `data` must be of types that can be converted to JSON: strings, numbers, booleans, objects, and arrays.

The rendering layer can bind data through WXML/INK.
