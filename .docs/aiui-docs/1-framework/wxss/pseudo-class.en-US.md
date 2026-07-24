# Pseudo-classes

A pseudo-class is a keyword added to a selector to specify a special state of the selected element. In WXSS, you can use pseudo-classes just like in standard CSS to enhance interaction feedback and structured styling in the interface.

The following are common pseudo-classes in WXSS and their code examples:

## 1. Interactive Pseudo-classes

### `:active`
Applied when an element is activated by the user, such as during a press or click.

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
Applied when the user hovers a pointer, such as a mouse or stylus, over an element.

```css
.card:hover {
  box-shadow: 0 4rpx 12rpx rgba(0, 0, 0, 0.1);
  transform: translateY(-2rpx);
}
```

### `:focus`
Applied when an element receives focus, typically an `input` or `textarea`.

```css
.input {
  border: 1px solid #ddd;
}

.input:focus {
  border-color: #40FF5E;
  background-color: #fff;
}
```

## 2. Structural Pseudo-classes

### `:first-child` & `:last-child`
Select the first and last child element of the parent, respectively.

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
Selects the nth child element of its parent. Formulas and keywords such as `odd` and `even` are supported.

```css
/* 为奇数行添加浅灰色背景 */
.row:nth-child(odd) {
  background-color: #fafafa;
}
```

## 3. State Pseudo-classes

### `:disabled`
Selects components that have the `disabled` attribute.

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

*For the full list of pseudo-classes and advanced usage, see [MDN CSS Pseudo-classes](https://developer.mozilla.org/en-US/docs/Web/CSS/Pseudo-classes).*
