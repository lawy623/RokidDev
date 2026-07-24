# Global Configuration (app.json)

`app.json` is used to configure the global properties of an agent, such as page paths, window styles, and multi-tab settings.

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

## Agent Description File (AGENTS.md)

In addition to `app.json`, each agent project also requires an `AGENTS.md` file to declare the agent's metadata, system prompts, and capability permissions.

For more details about `AGENTS.md`, see [AGENTS.md Specification](/AIUI/framework/config-agents).
