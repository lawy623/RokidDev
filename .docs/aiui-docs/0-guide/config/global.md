# 全局配置 (app.json)

`app.json` 用于配置智能体的全局属性，如页面路径、窗口样式、多标签等。

```json
{
  "pages": [
    "pages/index/index",
    "pages/logs/logs"
  ],
  "window": {
    "backgroundTextStyle": "light",
    "navigationBarBackgroundColor": "#fff",
    "navigationBarTitleText": "AIUI Agent",
    "navigationBarTextStyle": "black"
  }
}
```

## 智能体描述文件 (AGENTS.md)

除了 `app.json`，每个智能体项目还需要一个 `AGENTS.md` 文件，用于声明智能体的元数据、系统指令和能力权限。

更多关于 `AGENTS.md` 的详细规范，请参考 [AGENTS.md 规范](/AIUI/framework/config-agents)。