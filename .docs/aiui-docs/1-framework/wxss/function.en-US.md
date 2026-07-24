# Functions

CSS functions are used as the values of CSS properties. They let you perform simple calculations, process colors, or reference external resources in stylesheets. WXSS also supports standard CSS function expressions.

The following are the functions most commonly used in WXSS development and their code examples:

## 1. Calculation and Variables

### `calc()`
Used to calculate length values dynamically. It supports addition, subtraction, multiplication, and division, and is often used to mix different units.

```css
.container {
  /* 宽度为视口宽度减去两侧的边距 */
  width: calc(100vw - 60rpx);
  margin: 0 auto;
}
```

### `var()`
Used to reference the value of a custom property, also known as a CSS variable.

```css
:root {
  --primary-color: #40FF5E;
}

.button {
  /* 引用变量，并提供回退值 */
  background-color: var(--primary-color, #444);
}
```

## 2. Visual Effects

### `linear-gradient()`
Creates a linear gradient background.

```css
.header {
  background: linear-gradient(180deg, #40FF5E 0%, #06a954 100%);
}
```

### `rgba()`
Defines a color with transparency.

```css
.mask {
  background-color: rgba(0, 0, 0, 0.6);
}
```

## 3. Resource Inclusion

### `url()`
Used to include external resources. In WXSS, using a network path or base64 is recommended.

```css
.icon {
  background-image: url('https://res.rokid.com/static/icon.png');
  background-size: cover;
}
```

## 4. Geometric Transforms

### `translate()`, `rotate()`, `scale()`
Used together with the `transform` property to move, rotate, and scale elements.

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

*For the complete list of functions and detailed syntax, see [MDN CSS Functions](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_Functions).*
