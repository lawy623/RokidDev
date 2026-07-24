# 函数 (Functions)

CSS 函数被用作 CSS 属性的值，允许你在样式表中执行简单的计算、处理颜色或引用外部资源。WXSS 同样支持标准的 CSS 函数表达式。

以下是在 WXSS 开发中最常用的函数及其代码示例：

## 1. 计算与变量

### `calc()`
用于动态计算长度值。支持加减乘除运算，常用于混合不同单位。

```css
.container {
  /* 宽度为视口宽度减去两侧的边距 */
  width: calc(100vw - 60rpx);
  margin: 0 auto;
}
```

### `var()`
用于引用自定义属性（CSS 变量）的值。

```css
:root {
  --primary-color: #40FF5E;
}

.button {
  /* 引用变量，并提供回退值 */
  background-color: var(--primary-color, #444);
}
```

## 2. 视觉效果

### `linear-gradient()`
创建线性渐变背景。

```css
.header {
  background: linear-gradient(180deg, #40FF5E 0%, #06a954 100%);
}
```

### `rgba()`
定义带透明度的颜色。

```css
.mask {
  background-color: rgba(0, 0, 0, 0.6);
}
```

## 3. 资源引入

### `url()`
用于引入外部资源。在 WXSS 中建议使用网络路径或 base64。

```css
.icon {
  background-image: url('https://res.rokid.com/static/icon.png');
  background-size: cover;
}
```

## 4. 几何变换

### `translate()`, `rotate()`, `scale()`
配合 `transform` 属性实现元素的位移、旋转和缩放。

```css
.active-item {
  transform: translateX(20rpx) scale(1.1);
}

.loading-spinner {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
```

---

*完整的函数列表及其详细语法，请参考 [MDN CSS 函数](https://developer.mozilla.org/zh-CN/docs/Web/CSS/CSS_Functions)。*
