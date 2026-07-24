# Pseudo-elements

A pseudo-element is a keyword appended to the end of a selector that lets you style a specific part of the selected element or generated content, without adding extra nodes to the document structure.

In AIUI WXSS, common pseudo-elements and their code examples include:

## 1. Content-generating Pseudo-elements

### `::before`
Inserts content before the content of the selected element. It is commonly used to add icons, quotation marks, or decorative lines.

```css
.tip-box::before {
  content: "ℹ️";
  margin-right: 10rpx;
}
```

### `::after`
Inserts content after the content of the selected element. It is often used to clear floats in web development or add trailing decorations.

```css
/* 为必填项添加红色的星号 */
.label-required::after {
  content: "*";
  color: #ff4d4f;
  margin-left: 4rpx;
}
```

## 2. Interaction and State Pseudo-elements

### `::placeholder`
Used to set the style of placeholder text for input components such as `input` and `textarea`.

```css
.search-input::placeholder {
  color: #999;
  font-size: 28rpx;
  font-style: italic;
}
```

## 3. Pseudo-elements for Complex Components

Some built-in components support access to their internal structure through pseudo-elements, such as customized scrollbars or switch components.

```css
/* 修改滚动条宽度 (如果平台支持) */
::-webkit-scrollbar {
  width: 6rpx;
  background-color: transparent;
}

::-webkit-scrollbar-thumb {
  background-color: rgba(0, 0, 0, 0.2);
  border-radius: 3rpx;
}
```

---

**Notes:**
- When using `::before` and `::after`, you must set the `content` property, even if it is an empty string `""`, otherwise the pseudo-element will not be displayed.
- Pseudo-elements are `display: inline` by default. If you need to set width and height, you usually need to change them to `display: block` or `display: inline-block`.

*For the full list of pseudo-elements and their usage, see [MDN CSS Pseudo-elements](https://developer.mozilla.org/en-US/docs/Web/CSS/Pseudo-elements).*
