# 页面配置

AIUI 中的“页面配置”并不只有一种写法，它取决于你当前页面是以哪种形式组织的：

- **多文件结构**：通常通过独立的 `page.json` 配置页面标题、功能描述和参数结构。
- **单页应用**：通常直接在页面文件里定义页面配置，再由 `app.json` 指定应用入口页面。

## 多文件结构如何配置

如果你使用的是传统页面目录结构，例如：

```text
pages/
  weather/
    index.js
    index.wxml
    index.wxss
    page.json
```

那么页面级配置通常写在 `page.json` 中。它的作用主要有两类：

- 覆盖全局窗口配置，例如页面标题
- 定义页面的功能描述和入参结构

常见字段包括：

- **`navigationBarTitleText`**：页面标题
- **`description`**：页面功能描述，可用于说明这个页面能做什么
- **`schema.data`**：页面参数定义，遵循 JSON Schema 结构

示例：

```json
{
  "navigationBarTitleText": "天气查询",
  "description": "查询指定城市的天气信息",
  "schema": {
    "data": {
      "type": "object",
      "properties": {
        "city": {
          "type": "string",
          "description": "城市名称，例如杭州"
        }
      },
      "required": ["city"]
    }
  }
}
```

## 单页应用如何配置

如果你使用的是 `.ink` 单文件页面，页面配置通常不再单独写在 `page.json` 中，而是直接写在页面文件的配置块里。

例如：

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
        }
      }
    }
  }
}
</script>
```

这种写法更适合单页应用或 `.ink` 单文件开发方式，因为页面配置、页面逻辑和页面结构可以放在同一个文件里阅读和维护。

与此同时，整个应用仍然需要在 `app.json` 中声明页面入口，例如：

```json
{
  "pages": ["pages/landlord/index"],
  "window": {
    "navigationBarTitleText": "斗地主"
  }
}
```

你可以把它理解成：

- `app.json`：决定应用从哪个页面开始
- 页面配置：决定当前页面本身怎么展示、怎么描述、接受什么参数

## 什么时候用哪种方式

- 如果你的页面由 `index.js + index.wxml + index.wxss` 这类多文件结构组成：优先使用 `page.json`
- 如果你的页面是 `.ink` 单文件形式：优先在页面文件内直接定义页面配置

更多详细字段说明，请继续阅读 [页面配置规范](/framework/config/page)。
