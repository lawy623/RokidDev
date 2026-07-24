# ScrollView 滚动视图

`scroll-view` 组件是一个可滚动的视图容器，允许用户在内容超出可视区域时滚动查看。

## 使用方法

```xml
<scroll-view class="scroll-container" scroll-y="true">
  <view class="item">项目 1</view>
  <view class="item">项目 2</view>
  <view class="item">项目 3</view>
  <!-- 更多项目 -->
</scroll-view>
```

## 属性

| 属性 | 类型 | 默认值 | 描述 |
| :--- | :--- | :--- | :--- |
| `scroll-x` | Boolean | `false` | 允许横向滚动。 |
| `scroll-y` | Boolean | `false` | 允许纵向滚动。 |
| `scroll-top` | Number | - | 设置竖向滚动条位置。 |
| `scroll-left` | Number | - | 设置横向滚动条位置。 |
| `scroll-into-view` | String | - | 滚动到该 ID 对应的元素位置。 |
| `auto-scroll` | Boolean | `false` | 启用自动滚动。 |
| `scroll-speed` | Number | `25.0` | 自动滚动的速度。 |
| `scroll-direction` | String | `vertical` | 自动滚动的方向 (`vertical` 或 `horizontal`)。 |

## 功能特性

- 支持横向和纵向滚动。
- 支持触摸和鼠标拖拽手势。
- 支持鼠标滚轮滚动。
- 支持自定义速度和方向的自动滚动。
