# 页面配置

页面配置用于定义当前页面的展示方式、功能描述和入参结构。具体写法取决于你使用的是 **多文件结构** 还是 **单页应用 / `.ink` 页面**。

## 多文件结构如何配置

当页面采用多文件结构时，页面级配置通常单独写在 `page.json` 中。例如：

```text
pages/
  weather/
    index.js
    index.wxml
    index.wxss
    page.json
```

此时，`page.json` 负责当前页面自己的配置。它可以覆盖全局窗口配置，并补充页面的功能描述与参数结构。

### 常见字段

#### `navigationBarTitleText`
- **类型**: `String`
- **描述**: 当前页面标题。

#### `description`
- **类型**: `String`
- **描述**: 页面的功能描述。在页面作为可调用 UI 能力时，这个字段可用于说明页面用途。

#### `schema.data`
- **类型**: `Object`
- **描述**: 页面的参数定义。该字段遵循 JSON Schema 规范，用于声明页面接受哪些输入参数。

### 示例

```json
{
  "navigationBarTitleText": "天气查询",
  "description": "查询指定城市的天气信息并展示趋势图",
  "schema": {
    "data": {
      "type": "object",
      "properties": {
        "city": {
          "type": "string",
          "description": "城市名称，例如 '杭州'"
        },
        "days": {
          "type": "integer",
          "description": "查询天数",
          "default": 3
        }
      },
      "required": ["city"]
    }
  }
}
```

## 单页应用如何配置

当页面采用 `.ink` 单文件形式时，页面配置通常直接写在页面文件的 `<script def>` 中，而不是单独维护一个 `page.json`。

这种方式更适合单页应用或沉浸式页面，因为配置、逻辑、结构和样式可以放在同一个文件里统一维护。

### 示例

```html
<script def>
{
  "navigationBarTitleText": "斗地主",
  "description": "提供完整的斗地主对局界面和操作能力",
  "schema": {
    "data": {
      "type": "object",
      "properties": {
        "roomId": {
          "type": "string",
          "description": "当前对局房间 ID"
        },
        "playerName": {
          "type": "string",
          "description": "玩家昵称"
        }
      }
    }
  }
}
</script>
```

单页应用下，页面本身的配置写在页面文件中，而应用入口仍由 `app.json` 管理：

```json
{
  "pages": ["pages/landlord/index"],
  "window": {
    "navigationBarTitleText": "斗地主"
  }
}
```

你可以这样理解：

- `app.json`：定义应用入口和全局窗口配置
- 页面配置：定义某个具体页面自己的标题、描述和参数

## 选择建议

- 如果页面是 `index.js + index.wxml + index.wxss` 这类多文件结构：使用 `page.json`
- 如果页面是 `.ink` 单文件页面：直接在页面文件里定义页面配置
