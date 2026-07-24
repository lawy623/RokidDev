# Page Configuration

Page configuration is used to define how the current page is presented, its functional description, and its input parameter structure. The exact format depends on whether you are using a **multi-file structure** or a **single-page application / `.ink` page**.

## How To Configure A Multi-File Structure

When a page uses a multi-file structure, page-level configuration is usually written separately in `page.json`. For example:

```text
pages/
  weather/
    index.js
    index.wxml
    index.wxss
    page.json
```

In this case, `page.json` is responsible for the configuration of the current page itself. It can override the global window configuration and add the page's functional description and parameter structure.

### Common Fields

#### `navigationBarTitleText`
- **Type**: `String`
- **Description**: The title of the current page.

#### `description`
- **Type**: `String`
- **Description**: The functional description of the page. When the page serves as a callable UI capability, this field can be used to explain the purpose of the page.

#### `schema.data`
- **Type**: `Object`
- **Description**: The parameter definition of the page. This field follows the JSON Schema specification and is used to declare which input parameters the page accepts.

### Example

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

## How To Configure A Single-Page Application

When a page uses the `.ink` single-file format, page configuration is usually written directly inside the page file's `<script def>` block, instead of maintaining a separate `page.json`.

This approach is better suited to single-page applications or immersive pages, because configuration, logic, structure, and styles can all be maintained together in one file.

### Example

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

In a single-page application, the page's own configuration is written inside the page file, while the application entry is still managed by `app.json`:

```json
{
  "pages": ["pages/landlord/index"],
  "window": {
    "navigationBarTitleText": "斗地主"
  }
}
```

You can think of it like this:

- `app.json`: Defines the application entry and global window configuration
- Page configuration: Defines a specific page's own title, description, and parameters

## Recommendations

- If the page uses a multi-file structure such as `index.js + index.wxml + index.wxss`: use `page.json`
- If the page is a `.ink` single-file page: define the page configuration directly inside the page file
