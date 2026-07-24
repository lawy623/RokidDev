# 用户界面 (UI) 开发

AIUI 的视图层主要由 WXML (WeiXin Markup Language) 和 WXSS (WeiXin Style Sheets) 组成，结合基础组件和事件系统，构建出高性能的智能体交互界面。

## WXML (页面结构)

WXML 是一种类似于 HTML 的标记语言，用于描述页面的结构。它支持数据绑定、条件渲染、列表渲染以及模板引用。

### 核心特性

- **数据绑定**: 使用 Mustache 语法 (`{{ }}`) 将逻辑层的数据绑定到视图层。
- **列表渲染**: 使用 `ink:for` 控制属性绑定一个数组，并使用 `item` 和 `index` 访问当前项。
- **条件渲染**: 使用 `ink:if`、`ink:elif`、`ink:else` 来根据条件决定是否渲染该代码块。
- **事件绑定**: 使用 `bindtap` 等属性绑定用户的交互事件。

### 示例代码

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

## WXSS (页面样式)

WXSS 是一套样式语言，用于描述 WXML 的组件样式。它扩展了 CSS 的特性，以适应智能体开发的场景。

### 核心特性

- **尺寸单位**: 引入了 `rpx` (responsive pixel) 单位，可以根据屏幕宽度进行自适应。规定屏幕宽为 `480rpx`。
- **样式导入**: 使用 `@import` 语句可以导入外联样式表。
- **内联样式**: 支持 `style` 和 `class` 属性来控制组件样式。
- **选择器**: 支持常用的 CSS 选择器（`.class`, `#id`, `element`, `::after`, `::before` 等）。

### 示例代码

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

## 渲染流程

1. **数据驱动**: 当逻辑层调用 `this.setData` 时，视图层会根据新数据进行差量更新。
2. **高性能渲染**: AIUI 视图层运行在 JSAR 渲染引擎中，能够提供平滑的 2D/3D 混合渲染体验，特别适合 AI + AR 设备。
