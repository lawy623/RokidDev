# 伪类 (Pseudo-classes)

伪类是添加到选择器的关键字，用于指定所选元素的特殊状态。在 WXSS 中，你可以像使用普通 CSS 一样使用伪类来增强界面的交互反馈和结构化样式。

以下是 WXSS 中常用的伪类及其代码示例：

## 1. 交互伪类

### `:active`
当元素被用户激活（如手指按下或鼠标点击）时应用。

```css
.button {
  background-color: #40FF5E;
  transition: background-color 0.2s;
}

/* 按下时背景变深 */
.button:active {
  background-color: #40FF5E;
}
```

### `:hover`
当用户将指针（鼠标或触控笔）悬停在元素上时应用。

```css
.card:hover {
  box-shadow: 0 4rpx 12rpx rgba(0, 0, 0, 0.1);
  transform: translateY(-2rpx);
}
```

### `:focus`
当元素获得焦点（通常是输入框 `input` 或 `textarea`）时应用。

```css
.input {
  border: 1px solid #ddd;
}

.input:focus {
  border-color: #40FF5E;
  background-color: #fff;
}
```

## 2. 结构伪类

### `:first-child` & `:last-child`
分别选中作为其父级的第一个和最后一个子元素。

```css
.list-item {
  border-bottom: 1px solid #eee;
}

/* 移除最后一项的底边框 */
.list-item:last-child {
  border-bottom: none;
}
```

### `:nth-child(n)`
选中作为其父级的第 n 个子元素。支持公式或关键字（如 `odd`, `even`）。

```css
/* 为奇数行添加浅灰色背景 */
.row:nth-child(odd) {
  background-color: #fafafa;
}
```

## 3. 状态伪类

### `:disabled`
选中具有 `disabled` 属性的组件。

```css
.btn[disabled] {
  opacity: 0.5;
  cursor: not-allowed;
}

/* 或者直接使用伪类 */
.btn:disabled {
  background-color: #ccc;
}
```

---

*完整的伪类列表及其高级用法，请参考 [MDN CSS 伪类](https://developer.mozilla.org/zh-CN/docs/Web/CSS/Pseudo-classes)。*
