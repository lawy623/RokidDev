# 路由 (router)

提供页面路由导航相关的接口。

## 导航方法

- `wx.switchTab(Object object)`: 跳转到 tabBar 页面，并关闭其他所有非 tabBar 页面。
- `wx.reLaunch(Object object)`: 关闭所有页面，打开到应用内的某个页面。
- `wx.redirectTo(Object object)`: 关闭当前页面，跳转到应用内的某个页面。但是不允许跳转到 tabbar 页面。
- `wx.navigateTo(Object object)`: 保留当前页面，跳转到应用内的某个页面。但是不能跳到 tabbar 页面。
- `wx.navigateBack(Object object)`: 关闭当前页面，返回上一页面或多级页面。
