# Streamdown 流式富文本

`streamdown` 组件用于渲染支持流式输出的 markdown 内容，非常适合显示动态、增量接收的文本（例如 AI 助手的回复）。

## 使用方法

```xml
<streamdown 
  content="{{markdownContent}}" 
  streaming="true" 
  color="#333" 
  font-size="16"
></streamdown>
```

## 属性

| 属性 | 类型 | 描述 | 默认值 |
|-----------|------|-------------|---------|
| `content` | String | 要渲染的 markdown 内容。 | `""` |
| `streaming` | Boolean | 是否在文本末尾显示闪烁的光标（表示正在输入）。 | `false` |
| `color` | String | markdown 内容的主要文本颜色。 | 继承 |
| `font-size` | Number | markdown 内容的字体大小。 | 继承 |

## 功能特性

- **Markdown 支持**: 支持标准 markdown 语法，包括标题、列表、加粗/斜体等。
- **增量渲染**: 针对部分内容的更新进行了优化，可实现“流式”输出效果。
- **光标动画**: 可以启用闪烁光标以指示正处于活动打字/流式传输状态。
- **样式设置**: 可以使用标准 CSS 属性（如文本颜色、字体大小等）进行样式设置。
