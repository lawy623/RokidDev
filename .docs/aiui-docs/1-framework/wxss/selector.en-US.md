# Selectors

AIUI WXSS supports most standard CSS selectors for precisely targeting the elements to which styles should be applied in a document.

The following are common basic selectors in WXSS and their code examples:

## 1. Class Selector
`.class` selects all components that have the specified `class` attribute. This is the most commonly used and recommended selector type.

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

## 2. ID Selector
`#id` selects the component with a unique `id` attribute.

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

## 3. Type Selector
`element` selects all components of the specified type, such as `view`, `text`, and `image`.

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

## 4. Selector List
`A, B` selects multiple kinds of components at the same time and applies the same styles to them.

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

## 5. Descendant Combinator
`A B` selects all `B` components inside component `A`, no matter how deeply nested they are.

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

## 6. Child Combinator
`A > B` selects only `B` components that are direct children of `A`.

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

In actual development, it is recommended to prioritize **class selectors** for style control so that rendering performance and style maintainability remain high.

*For more advanced selector usage, see [MDN CSS Selectors](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_Selectors).*
