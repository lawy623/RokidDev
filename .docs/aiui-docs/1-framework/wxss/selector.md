# 选择器 (Selectors)

AIUI 的 WXSS 支持绝大部分标准的 CSS 选择器，用于在文档中精确地选择要应用样式的元素。

以下是 WXSS 中常用的基础选择器及其代码示例：

## 1. 类选择器 (Class Selector)
`.class` 用于选择所有具有指定 `class` 属性的组件。这是最常用且推荐的选择器类型。

```html
<!-- WXML -->
<view class="container">
  <text class="title">Hello World</text>
</view>
```

```css
/* WXSS */
.container {
  padding: 20rpx;
}

.title {
  color: #40FF5E;
  font-size: 32rpx;
}
```

## 2. ID 选择器 (ID Selector)
`#id` 用于选择具有唯一 `id` 属性的组件。

```html
<!-- WXML -->
<view id="main-content">
  主内容区域
</view>
```

```css
/* WXSS */
#main-content {
  background-color: #f8f8f8;
  border-radius: 8rpx;
}
```

## 3. 元素选择器 (Type Selector)
`element` 用于选择所有指定类型的组件（如 `view`、`text`、`image` 等）。

```html
<!-- WXML -->
<view>
  <text>第一行文本</text>
  <text>第二行文本</text>
</view>
```

```css
/* WXSS */
text {
  line-height: 1.5;
  color: #333;
}
```

## 4. 群组选择器 (Selector List)
`A, B` 用于同时选择多种组件并应用相同的样式。

```html
<!-- WXML -->
<view class="box-a">Box A</view>
<view class="box-b">Box B</view>
```

```css
/* WXSS */
.box-a, .box-b {
  width: 100rpx;
  height: 100rpx;
  border: 1px solid #ddd;
}
```

## 5. 后代选择器 (Descendant Combinator)
`A B` 选择 `A` 组件内部所有的 `B` 组件，无论嵌套层级有多深。

```html
<!-- WXML -->
<view class="card">
  <view class="header">
    <text>标题</text>
  </view>
  <view class="body">
    <text>内容详情</text>
  </view>
</view>
```

```css
/* WXSS */
.card text {
  font-size: 28rpx;
  color: #666;
}
```

## 6. 子元素选择器 (Child Combinator)
`A > B` 仅选择直接作为 `A` 子组件的 `B` 组件。

```html
<!-- WXML -->
<view class="list">
  <view class="item">
    <text>直接子元素</text>
    <view class="nested">
      <text>非直接子元素</text>
    </view>
  </view>
</view>
```

```css
/* WXSS */
.list > .item {
  border-bottom: 1px solid #eee;
}
```

---

在实际开发中，推荐优先使用 **类选择器** 来控制样式，以保持较高的渲染性能和样式的可维护性。

*更多高级选择器的用法，请参考 [MDN CSS 选择器](https://developer.mozilla.org/zh-CN/docs/Web/CSS/CSS_Selectors)。*
