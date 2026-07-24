# Icon 图标

`icon` 组件用于渲染图标，通常基于字体图标库（如 Material Design Icons）。它本质上是一个特殊的 `text` 组件。

## 使用方法

```xml
<icon class="material-icons">home</icon>
```

## 属性

`icon` 组件继承了 `text` 组件的所有属性。

| 属性 | 类型 | 描述 | 默认值 |
|-----------|------|-------------|---------|
| `font-size` | Number | 图标的像素大小。 | 继承 |
| `color` | String | 图标的颜色。 | 继承 |

## 样式

通常，可以通过 CSS 设置 `font-family` 和其他与文本相关的属性来为图标设置样式。
