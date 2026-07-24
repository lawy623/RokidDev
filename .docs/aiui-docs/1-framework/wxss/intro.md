# WXSS 介绍

WXSS (WeiXin Style Sheets) 是一套样式语言，用于描述 AIUI 智能体的组件样式。

AIUI 沿用了微信小程序的 WXSS 规范，它与标准的 CSS (层叠样式表) 具有极高的相似度和兼容性。大部分基于 [MDN CSS 参考](https://developer.mozilla.org/zh-CN/docs/Web/CSS/Reference) 中的属性和用法，你都可以在 WXSS 中直接使用。

## WXSS 特性

为了更适合开发智能体界面与移动端应用，WXSS 在 CSS 的基础上进行了少量扩充：

### 1. 尺寸单位 `rpx`
`rpx` (responsive pixel) 是 WXSS 特有的响应式长度单位。它可以根据屏幕宽度进行自适应，规定屏幕宽为 `480rpx`。

**代码示例：**
```css
/* 在所有设备上，该视图将占据屏幕宽度的一半 */
.half-width-box {
  width: 240rpx;
  height: 100rpx;
  background-color: #40FF5E;
}
```

### 2. 样式导入 `@import`
使用 `@import` 语句可以方便地导入外联样式表，实现样式的模块化和复用。

**代码示例：**
```css
/** common.wxss **/
.text-danger {
  color: #ff4d4f;
}

/** index.wxss **/
@import "./common.wxss";

.title {
  font-size: 32rpx;
}
```

## 编写建议

1. **优先使用类选择器**：为了性能和可维护性，建议尽量使用 `.class` 选择器。
2. **避免过度嵌套**：过深的 CSS 选择器嵌套会增加渲染负担。
3. **善用 Flex 布局**：AIUI 的视图引擎对 Flexbox 提供了完美的底层支持，是构建响应式界面的首选。

通过掌握标准的 CSS 知识，并了解这几项特性，你就能轻松编写出漂亮且适配良好的智能体 UI 界面。
