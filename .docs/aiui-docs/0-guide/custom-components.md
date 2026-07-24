# 自定义组件

开发者可以将复杂的页面拆分成多个自定义组件，从而提高代码的复用性和可维护性。

## 创建自定义组件

一个自定义组件由 `.json`、`.wxml`、`.wxss`、`.js` 四个文件组成。

在 `component.json` 中声明：

```json
{
  "component": true
}
```

在 `component.js` 中注册：

```javascript
export default {
  properties: {
    title: {
      type: String,
      value: 'Default Title'
    }
  },
  data: {
    internalData: 1
  },
  methods: {
    handleTap() {
      this.triggerEvent('customevent', { data: 123 });
    }
  }
}
```

## 使用自定义组件

在页面的 `.json` 文件中引入组件：

```json
{
  "usingComponents": {
    "my-component": "/components/my-component/index"
  }
}
```

在 WXML 中使用：

```html
<my-component title="Hello" bind:customevent="onCustomEvent"></my-component>
```