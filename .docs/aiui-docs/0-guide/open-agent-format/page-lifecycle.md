# 生命周期

AIUI 页面的生命周期遵循固定的调用顺序。首次打开页面时，依次触发 `onLoad` → `onShow` → `onReady`；切换到其他页面时当前页触发 `onHide`，切回时触发 `onShow`；页面销毁时触发 `onUnload`。

## 生命周期回调表

| 回调函数 | 说明 | 触发时机 |
| :--- | :--- | :--- |
| `onLoad` | 监听页面加载 | 页面加载时触发（全局只触发一次） |
| `onShow` | 监听页面显示 | 页面显示或切入前台时触发 |
| `onReady` | 监听页面初次渲染完成 | 页面初次渲染完成时触发（全局只触发一次） |
| `onHide` | 监听页面隐藏 | 页面隐藏或切入后台时触发 |
| `onUnload` | 监听页面卸载 | 页面卸载时触发 |

## 生命周期演示

<page-lifecycle-demo></page-lifecycle-demo>

## 各回调说明

### onLoad(Object query)

页面加载时触发。一个页面只会调用一次，可以在 `onLoad` 的参数中获取打开当前页面路径中的参数。

### onShow()

页面显示或切入前台时触发。

### onReady()

页面初次渲染完成时触发。一个页面只会调用一次，代表页面已经准备妥当，可以和视图层进行交互。

### onHide()

页面隐藏或切入后台时触发。

### onUnload()

页面卸载时触发，例如跳转到其他页面并销毁当前页面时。

## 推荐阅读

- [页面概览](/AIUI/guide/open-agent-format-page)
- [页面定义](/AIUI/guide/open-agent-format-page-definition)
- [事件](/AIUI/guide/open-agent-format-page-events)
