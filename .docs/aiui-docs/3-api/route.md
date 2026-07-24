# 路由

AIUI 的路由能力用于在不同页面之间完成跳转、返回、重定向与页面栈管理。这里不展开接口细节，主要帮助你快速找到与页面导航相关的具体文档。

## 简单示例

例如，从首页跳转到详情页：

```javascript
wx.navigateTo({
  url: '/pages/detail/index?id=1'
});
```

继续阅读：

- **[路由 (router)](/AIUI/api/weixin-compatible-apis-router)**：查看 `wx.navigateTo`、`wx.redirectTo`、`wx.navigateBack` 等接口。
- **[App](/AIUI/api/framework-app)**：了解应用级配置与生命周期入口。
- **[Page](/AIUI/api/framework-page)**：了解页面的定义方式与页面级行为。
