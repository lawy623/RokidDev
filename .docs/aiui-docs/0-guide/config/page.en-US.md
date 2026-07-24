# Page Configuration

In AIUI, there is more than one way to define page configuration. The actual approach depends on how the current page is organized:

- **Multi-file structure**: Usually uses a standalone `page.json` to configure the page title, feature description, and parameter schema.
- **Single-page application**: Usually defines page configuration directly inside the page file, and then uses `app.json` to specify the app entry page.

## How to Configure a Multi-file Structure

If you use a traditional page directory structure such as:

```text
pages/
  weather/
    index.js
    index.wxml
    index.wxss
    page.json
```

Then page-level configuration is usually written in `page.json`. It mainly serves two purposes:

- Override global window configuration, such as the page title
- Define the page's feature description and input parameter schema

Common fields include:

- **`navigationBarTitleText`**: Page title
- **`description`**: A description of the page feature, used to explain what the page can do
- **`schema.data`**: Page parameter definition that follows the JSON Schema structure

Example:

```json
{
  "navigationBarTitleText": "Weather Query",
  "description": "Query weather information for a specified city",
  "schema": {
    "data": {
      "type": "object",
      "properties": {
        "city": {
          "type": "string",
          "description": "City name, for example Hangzhou"
        }
      },
      "required": ["city"]
    }
  }
}
```

## How to Configure a Single-page Application

If you use a `.ink` single-file page, the page configuration is usually no longer written separately in `page.json`. Instead, it is defined directly in the configuration block inside the page file.

For example:

```html
<script def>
{
  "navigationBarTitleText": "Fight the Landlord",
  "description": "Provides a complete Fight the Landlord game interface and interaction capabilities",
  "schema": {
    "data": {
      "type": "object",
      "properties": {
        "roomId": {
          "type": "string",
          "description": "Current game room ID"
        }
      }
    }
  }
}
</script>
```

This approach is more suitable for single-page applications or `.ink` single-file development because page configuration, page logic, and page structure can all be read and maintained in the same file.

At the same time, the entire application still needs to declare the page entry in `app.json`, for example:

```json
{
  "pages": ["pages/landlord/index"],
  "window": {
    "navigationBarTitleText": "Fight the Landlord"
  }
}
```

You can understand it like this:

- `app.json`: Decides which page the application starts from
- Page configuration: Decides how the current page is displayed, described, and what parameters it accepts

## When to Use Which Approach

- If your page uses a multi-file structure such as `index.js + index.wxml + index.wxss`: prefer `page.json`
- If your page is a `.ink` single-file page: prefer defining page configuration directly inside the page file

For more detailed field descriptions, continue reading [Page Configuration Specification](/framework/config/page).
