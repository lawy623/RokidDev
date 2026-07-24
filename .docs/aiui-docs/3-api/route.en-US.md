# Routing

AIUI routing capabilities are used to navigate, go back, redirect, and manage the page stack across different pages. This page does not expand on every API detail. Its main purpose is to help you quickly find the specific documentation related to page navigation.

## Simple Example

For example, navigate from the home page to a detail page:

```javascript
wx.navigateTo({
  url: '/pages/detail/index?id=1'
});
```

Continue reading:

- **[Router](/AIUI/api/weixin-compatible-apis-router)**: See APIs such as `wx.navigateTo`, `wx.redirectTo`, and `wx.navigateBack`.
- **[App](/AIUI/api/framework-app)**: Learn about application-level configuration and lifecycle entry points.
- **[Page](/AIUI/api/framework-page)**: Learn how pages are defined and how page-level behavior works.
