# Code Composition and Directory Structure

A standard AIUI agent can use either a traditional **multi-file structure** or a more modern **Single File Component (SFC)** structure.

## 1. Traditional Multi-file Structure

In this mode, an agent usually consists of the following files:

### Global Configuration
- `app.js`: Application logic that defines global lifecycle functions.
- `app.json`: Global configuration file that defines page paths, window appearance, and more.
- `app.wxss`: Global stylesheet that defines shared UI styles.
- `AGENTS.md`: Agent description file that defines the agent's capabilities, system instructions, and metadata.

### Page Directory (`pages/`)
Each page is usually placed in its own subdirectory under `pages/` and consists of four files:
- `page.wxml`: Page structure written with WXML tags.
- `page.wxss`: Page styles that apply only to the current page.
- `page.js`: Page logic that handles data and interactions.
- `page.json`: Page configuration that can override global configuration.

### Typical Directory Structure Example

```filetree
```

---

## 2. Single File Component (SFC) Structure

To improve development efficiency and reduce file fragmentation, AIUI supports **Single File Components (SFCs)**. You can put a page's structure, logic, and configuration into a single `.ink` file.

The `.ink` extension is used because **Ink is the Agent runtime underlying AIUI**, so the SFC structure follows that naming convention.

### File Structure (`.ink`)
A typical `.ink` file contains the following four parts:

- **`<script def>`**: Corresponds to the original `.json` configuration.
- **`<script setup>`**: Corresponds to the original `.js` logic.
- **`<page>`**: Corresponds to the original `.wxml` structure.
- **`<style>`**: Corresponds to the original `.wxss` styles.

### Example (`index.ink`)

```html
<script def>
{
  "navigationBarTitleText": "SFC Example"
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

### Advantages
- **Separation of concerns**: Even though the code is in one file, each part still has a clear responsibility.
- **Less fragmentation**: No need to switch back and forth between multiple files.
- **Developer experience**: The syntax is closer to modern frontend frameworks such as Vue.

When both a multi-file page and a `.ink` file exist for the same page, **the framework loads the `.ink` file first**.
