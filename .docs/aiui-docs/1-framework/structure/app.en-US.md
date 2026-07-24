# App (`app.js`)

`App` is used to register an agent application.

## Example Code

```javascript
export default {
  onLaunch(options) {
    // 智能体初始化
  },
  onShow(options) {
    // 智能体显示
  },
  onHide() {
    // 智能体隐藏
  },
  globalData: {
    // 全局数据
  }
}
```

## Lifecycle Callbacks

| Callback | Description | Trigger Timing |
| :--- | :--- | :--- |
| `onLaunch` | Listens for agent initialization | When agent initialization completes (triggered only once globally) |
| `onShow` | Listens for the agent being shown | When the agent starts, or returns to the foreground from the background |
| `onHide` | Listens for the agent being hidden | When the agent moves from the foreground to the background |
| `onError` | Error listener function | When a script error occurs in the agent, or when an API call fails |
