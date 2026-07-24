# 代码构成与目录结构

一个标准的 AIUI 智能体可以采用传统的 **多文件结构** 或更现代的 **单文件组件 (SFC)** 结构。

## 1. 传统多文件结构

在这种模式下，一个智能体通常由以下文件组成：

### 全局配置
- `app.js`: 应用逻辑，定义全局生命周期函数。
- `app.json`: 全局配置文件，配置页面路径、窗口外观等。
- `app.wxss`: 全局样式表，定义通用的 UI 样式。
- `AGENTS.md`: 智能体描述文件，定义智能体的能力、系统指令和元数据。

### 页面目录 (`pages/`)
每个页面通常位于 `pages/` 下的独立子目录中，由四个文件组成：
- `page.wxml`: 页面结构，使用 WXML 标签。
- `page.wxss`: 页面样式，仅作用于当前页面。
- `page.js`: 页面逻辑，处理数据和交互。
- `page.json`: 页面配置，可覆盖全局配置。

### 典型目录结构示例

```filetree
```

---

## 2. 单文件组件 (SFC) 结构

为了提升开发效率并减少文件碎片，AIUI 支持 **单文件组件 (Single File Component)**。你可以将一个页面的结构、逻辑和配置统一写在一个 `.ink` 文件中。

之所以使用 `.ink` 作为后缀名，是因为 **Ink 是 AIUI 底层实现的 Agent 运行时**，因此 SFC 结构沿用了这一命名。

### 文件构成 (`.ink`)
一个典型的 `.ink` 文件包含以下三个部分：

- **`<script def>`**: 对应原有的 `.json` 配置。
- **`<script setup>`**: 对应原有的 `.js` 逻辑。
- **`<page>`**: 对应原有的 `.wxml` 结构。
- **`<style>`**: 对应原有的 `.wxss` 样式。

### 示例代码 (`index.ink`)

```html
<script def>
{
  "navigationBarTitleText": "SFC 示例"
}
</script>

<script setup>
export default {
  data: {
    message: 'Hello from Ink SFC!'
  },
  onLoad() {
    console.log('SFC Page Loaded');
  },
  handleTap() {
    this.setData({
      message: 'You clicked the text!'
    });
  }
}
</script>

<page>
  <view class="container">
    <text class="title" bindtap="handleTap">{{message}}</text>
  </view>
</page>

<style>
.container {
  padding: 40rpx;
  display: flex;
  justify-content: center;
}
.title {
  font-size: 32rpx;
  color: #40FF5E;
}
</style>
```

### 优势
- **关注点分离**：虽然代码在同一个文件中，但各部分职责明确。
- **减少碎片化**：无需在多个文件间频繁切换。
- **开发体验**：语法更接近现代前端框架（如 Vue）。

当项目目录中同时存在同一页面的多文件和 `.ink` 文件时，**框架会优先加载 `.ink` 文件**。
