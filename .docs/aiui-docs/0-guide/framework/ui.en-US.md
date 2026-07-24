# User Interface (UI) Development

The AIUI view layer is mainly composed of WXML (WeiXin Markup Language) and WXSS (WeiXin Style Sheets). Together with base components and the event system, they build high-performance interactive interfaces for intelligent agents.

## WXML (Page Structure)

WXML is a markup language similar to HTML and is used to describe page structure. It supports data binding, conditional rendering, list rendering, and template references.

### Core Features

- **Data binding**: Use Mustache syntax (`{{ }}`) to bind data from the logic layer to the view layer.
- **List rendering**: Use `ink:for` to bind an array to a control property, and use `item` and `index` to access the current item.
- **Conditional rendering**: Use `ink:if`, `ink:elif`, and `ink:else` to decide whether a code block should be rendered based on a condition.
- **Event binding**: Use attributes such as `bindtap` to bind user interaction events.

### Example Code

```html
<!-- index.wxml -->
<view class="container">
  <view class="header">
    <text class="title">{{title}}</text>
  </view>
  
  <view class="list">
    <view class="item" ink:for="{{items}}" ink:key="id" bindtap="handleItemClick">
      <text>{{index + 1}}. {{item.name}}</text>
      <text ink:if="{{item.status === 'active'}}" class="badge">进行中</text>
    </view>
  </view>
</view>
```

## WXSS (Page Styles)

WXSS is a style language used to describe component styles in WXML. It extends CSS features to better fit agent development scenarios.

### Core Features

- **Sizing unit**: Introduces the `rpx` (responsive pixel) unit, which can adapt based on screen width. The screen width is defined as `480rpx`.
- **Style import**: Use the `@import` statement to import external stylesheets.
- **Inline styles**: Supports the `style` and `class` attributes to control component styles.
- **Selectors**: Supports common CSS selectors such as `.class`, `#id`, `element`, `::after`, and `::before`.

### Example Code

```css
/* index.wxss */
.container {
  padding: 20rpx;
  background-color: #f8f8f8;
}

.title {
  font-size: 36rpx;
  font-weight: bold;
  color: #333;
}

.item {
  display: flex;
  justify-content: space-between;
  padding: 30rpx;
  margin-top: 10rpx;
  background-color: #fff;
  border-radius: 8rpx;
}

.badge {
  color: #40FF5E;
  font-size: 24rpx;
}
```

## Rendering Flow

1. **Data-driven updates**: When the logic layer calls `this.setData`, the view layer performs incremental updates based on the new data.
2. **High-performance rendering**: The AIUI view layer runs on the JSAR rendering engine, which delivers a smooth 2D/3D hybrid rendering experience and is especially suitable for AI + AR devices.

