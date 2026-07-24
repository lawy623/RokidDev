# Storage

AIUI storage capabilities are used to save local state, user preferences, business caches, and contextual information generated during runtime. This page does not expand on every API detail. Its main purpose is to help you quickly find the corresponding detailed documentation.

## Simple Example

For example, save a user setting:

```javascript
localStorage.setItem('theme', 'green');
const theme = localStorage.getItem('theme');
```

Continue reading:

- **[Storage API](/AIUI/api/storage-api)**: See how to use `localStorage` and `sessionStorage`.
- **[Storage](/AIUI/api/weixin-compatible-apis-storage)**: See `wx.setStorage`, `wx.getStorage`, and the synchronous variants.
