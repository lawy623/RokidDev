# Custom Components

Developers can split complex pages into multiple custom components to improve code reuse and maintainability.

## Create a Custom Component

A custom component consists of four files: `.json`, `.wxml`, `.wxss`, and `.js`.

Declare it in `component.json`:

```json
{
  "component": true
}
```

Register it in `component.js`:

```javascript
export default {
  properties: {
    title: {
      type: String,
      value: 'Default Title'
    }
  },
  data: {
    internalData: 1
  },
  methods: {
    handleTap() {
      this.triggerEvent('customevent', { data: 123 });
    }
  }
}
```

## Use a Custom Component

Import the component in the page's `.json` file:

```json
{
  "usingComponents": {
    "my-component": "/components/my-component/index"
  }
}
```

Use it in WXML:

```html
<my-component title="Hello" bind:customevent="onCustomEvent"></my-component>
```
