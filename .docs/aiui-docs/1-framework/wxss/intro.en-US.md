# WXSS Introduction

WXSS (WeiXin Style Sheets) is a style language used to describe component styles for AIUI agents.

AIUI follows the WXSS specification from WeChat Mini Programs. It is highly similar and compatible with standard CSS (Cascading Style Sheets). Most properties and usage described in the [MDN CSS Reference](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference) can be used directly in WXSS.

## WXSS Features

To better suit agent interfaces and mobile application development, WXSS adds a small number of extensions on top of CSS:

### 1. Size Unit `rpx`
`rpx` (responsive pixel) is a WXSS-specific responsive length unit. It adapts based on the screen width, and the screen width is defined as `480rpx`.

**Code example:**
```css
/* 在所有设备上，该视图将占据屏幕宽度的一半 */
.half-width-box {
  width: 240rpx;
  height: 100rpx;
  background-color: #40FF5E;
}
```

### 2. Style Import `@import`
The `@import` statement lets you conveniently import external stylesheets for modularity and reuse.

**Code example:**
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

## Writing Recommendations

1. **Prefer class selectors**: For performance and maintainability, it is recommended to use `.class` selectors whenever possible.
2. **Avoid excessive nesting**: Deeply nested CSS selectors increase rendering overhead.
3. **Make good use of Flex layout**: AIUI's view engine provides strong underlying support for Flexbox, making it the preferred choice for building responsive interfaces.

By mastering standard CSS knowledge and understanding these features, you can easily build polished and adaptive AIUI interfaces.
