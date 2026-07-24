# 伪元素 (Pseudo-elements)

伪元素是一个附加至选择器末尾的关键词，允许你对被选择元素的特定部分或生成的内容进行样式修改，而无需在文档结构中增加额外的节点。

在 AIUI 的 WXSS 中，常用的伪元素及其代码示例包括：

## 1. 内容生成伪元素

### `::before`
在选中元素的内容前面插入内容。通常用于添加图标、引号或装饰性线条。

```css
.tip-box::before {
  content: "ℹ️";
  margin-right: 10rpx;
}
```

### `::after`
在选中元素的内容后面插入内容。常用于清除浮动（Web 开发习惯）或添加后续修饰。

```css
/* 为必填项添加红色的星号 */
.label-required::after {
  content: "*";
  color: #ff4d4f;
  margin-left: 4rpx;
}
```

## 2. 交互与状态伪元素

### `::placeholder`
用于设置输入框组件（如 `input`、`textarea`）占位符文本的样式。

```css
.search-input::placeholder {
  color: #999;
  font-size: 28rpx;
  font-style: italic;
}
```

## 3. 复杂组件伪元素

部分内置组件支持通过伪元素访问其内部结构。例如，自定义的滚动条或开关组件。

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

**注意事项：**
- 使用 `::before` 和 `::after` 时，必须设置 `content` 属性（即使是空字符串 `""`），否则伪元素不会显示。
- 伪元素默认是 `display: inline`，如果需要设置宽高，通常需要改为 `display: block` 或 `display: inline-block`。

*完整的伪元素列表及其用法，请参考 [MDN CSS 伪元素](https://developer.mozilla.org/zh-CN/docs/Web/CSS/Pseudo-elements)。*
