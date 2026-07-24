# 什么是 AIUI？

AIUI 是专门为大模型时代设计的 **AIUI（AI 原生用户界面）** 框架。它提供了一个高效的协作平台，让**开发者与 AI 深度协同**，共同构建出智能、直观的界面，让 AI 的能力不再只是文字，而是能精准响应用户需求的交互体验。本质上，**AIUI 就是一套 AI 原生的 UI 开发框架**。

## 什么是 AIUI？

AIUI（AI-Native User Interface）可以先被理解成一种新的交互方式：AI 不只是输出一段文字，而是直接把理解结果组织成可操作的界面。用户与 AI 的交互，不再只发生在聊天回复里，也可以发生在卡片、表单、面板、游戏界面或任务界面中。换句话说，AIUI 关注的不是“让 AI 会说”，而是“让 AI 会以界面的方式和用户协作”。

### 对话内交互

对话内交互指的是：UI 直接出现在聊天流里，以卡片、结果块、表单或工具面板的形式嵌在上下文中。用户一边聊天，一边点击、选择、输入或查看结果。这类 AIUI 适合天气卡片、行程建议、任务确认、搜索结果、表单填写等场景，本质上是在对话里插入可交互的界面。

下面这个示例用天气场景演示了对话内交互的典型样子：用户先提问，AI 在消息流里返回天气卡片，随后用户还可以继续点击卡片上的操作，让交互自然地延续在同一段对话中。

<in-chat-interaction-demo></in-chat-interaction-demo>

### 沉浸式界面交互

沉浸式界面交互指的是：UI 不再附着在聊天消息里，而是由一个独立界面来承载完整的交互过程。这个界面可以是任务面板、应用窗口、游戏界面或其他更完整的操作空间，用户仍然可以结合语音或其他输入方式与它交互。比如在一个斗地主界面里，桌面、手牌、按钮和状态区都由 UI 呈现，而用户依然可以通过语音来出牌、确认操作或和 Agent 协作。

下面这个示例用斗地主场景演示了沉浸式界面交互的典型样子：主交互发生在一个完整界面里，AI 不只是回复一句话，而是和用户一起围绕这个界面持续协作。

<standalone-interaction-demo></standalone-interaction-demo>

因此，AIUI 更像是把 AI 从“文本助手”推进到“界面化助手”。开发者负责定义界面的边界、交互方式和业务能力，AI 则根据上下文决定何时调用能力、如何组织内容，以及怎样让整个交互过程更自然。

## 如何实现 AIUI？

AIUI 并不是一种固定的界面生成方式。在实际产品里，通常可以从两个维度来判断该怎么实现：

<aiui-implementation-chart></aiui-implementation-chart>

沿着这两个维度来看，AIUI 当前常见的实现方式主要有三类：

### 1. Tool Rendering

Tool Rendering 的核心是：借助 LLM 的 `tools` 机制，把一个 UI 的意图和它需要的结构化输入注册给模型，让模型在合适的上下文里主动发起这次 Tool Call。开发者把“显示某种 UI”抽象成一个可调用能力，当前端收到这次调用后，再用预定义组件把它渲染出来。因此，它本质上是一种高控制、低自由度的 AIUI 实现方式。

例如天气场景里，你可以向模型注册一个类似 `render_weather_card` 的 tool，并声明它需要 `location` 这样的输入参数。用户问“杭州今天天气怎么样”时，模型不一定只回复一段文本，而是可以直接调用这个 tool，传入 `location: "杭州"`。随后前端渲染一个天气卡片，展示加载态、天气结果以及相关操作。

一个简化后的 `tools` 注册可以理解成这样：

```json
{
  "name": "render_weather_card",
  "description": "Render a weather card for a city",
  "input_schema": {
    "type": "object",
    "properties": {
      "location": {
        "type": "string",
        "description": "The city name"
      }
    },
    "required": ["location"]
  }
}
```

当前端收到这次 Tool Call 后，可以把参数放进页面的 `query` 中，再由页面在 `onLoad(query)` 里接收：

```javascript
export default {
  data: {
    city: "",
    loading: true,
    weather: null
  },

  async onLoad(query) {
    const city = query.location;

    this.setData({
      city,
      loading: true
    });

    const weather = await getWeatherByCity(city);

    this.setData({
      weather,
      loading: false
    });
  }
}
```

对应的页面模板可以这样渲染：

```html
<page>
  <view class="weather-card">
    <text class="title">{{ city }} 天气</text>

    <view ink:if="{{ loading }}">
      <text>天气加载中...</text>
    </view>

    <view ink:else>
      <text>{{ weather.condition }}</text>
      <text>{{ weather.temperature }}°C</text>
    </view>
  </view>
</page>
```

也就是说，LLM 决定“现在应该调用 `render_weather_card`，并传入 `location` 参数”，而页面通过 `onLoad(query)` 接收这些输入，再按照预定义的 UI 结构把天气卡片渲染出来。

适合做的事情：

- 把某种固定 UI 能力以 `tool` 的形式注册给模型
- 让模型基于上下文决定何时展示某类卡片、表单或结果组件
- 用结构化参数驱动界面渲染，而不是靠模型直接生成整段 UI 代码
- 在聊天流或任务流中插入稳定、可控的交互界面

不太适合的事情：

- 需要模型自由决定页面结构的大范围界面生成
- 需要脱离 Tool Call 生命周期独立存在的复杂业务页面

### 2. MCP Apps

MCP Apps 可以理解为把一个可交互 UI 直接挂到 MCP tool 上。它和 Tool Rendering 在输入方式上没有本质区别，二者都是通过向 LLM 注册 `tools`，让模型根据上下文决定何时调用，并传入结构化参数。区别主要在渲染承载方式：Tool Rendering 往往由 Agent Loop 系统自身把调用结果映射到内建 UI，而 MCP Apps 则通常由宿主根据 tool 声明的 UI 资源去加载一个独立的界面，例如通过 iframe 承载第三方 URL，并在会话上下文中内联显示。关于这一模型可以参考 [MCP Apps](https://apps.extensions.modelcontextprotocol.io/api/)。

对于 AIUI 来说，这条边界并没有那么绝对。因为 AIUI 的渲染器 `InkView` 本身就支持在沙箱环境里运行界面，所以在 in-context 场景下，AIUI 往往更接近 MCP Apps 这一路线，也就是把被调用的 UI 作为一个独立但受控的运行单元来承载。

适合做的事情：

- 图表、表单、仪表盘、设计画布等需要独立交互的内联 UI
- 通过 `tools` 调用一个完整的受控界面，而不只是渲染一个简单组件
- 需要在会话上下文中嵌入第三方或独立运行的交互视图

不太适合的事情：

- 完全不受宿主约束的开放式页面生成
- 对宿主集成成本极度敏感、只需要简单组件映射的场景

### 3. A2UI

A2UI 更准确地说，是一种声明式、对 LLM 友好的 Generative UI 规范。它的核心不是让模型直接输出某个平台的界面代码，也不是让模型只调用一个现成页面入口，而是让模型输出结构化的 UI 描述，再由宿主根据预先定义好的组件目录和渲染器把这些描述还原成真实界面。它通常具备几个关键特征：`JSONL` 友好、适合流式输出、跨平台渲染、对模型生成更友好。

在运行时，这类系统通常会向 Agent 注入一个类似 `render_a2ui` 的工具，让模型在需要时输出 A2UI surface。前端不需要为每一次调用单独写页面入口，而是准备一套基础组件和自定义 catalog，由渲染器自动把这些结构化描述拼装成卡片、列表、指标区块、图片或其他 UI 片段。

A2UI 常见的组件通常包括两类：

- 基础组件：例如 `Text`、`Image`、`Card`、`List`
- 业务组件：例如 `Metric`、`StatusBadge`，以及你自己扩展到 catalog 里的组件

模型在生成 A2UI 时，并不是直接输出 HTML 或某个平台的页面代码，而是输出一串 commands。常见的流程可以理解为三步：

1. `surfaceUpdate`：先声明这次要渲染哪些组件
2. `dataModelUpdate`：再把数据注入到这些组件
3. `beginRendering`：最后通知宿主开始渲染

下面这个交互式组件用天气卡片演示了 A2UI 的三个核心部分：组件目录、commands 流，以及最终如何被渲染成界面。

<a2ui-explorer></a2ui-explorer>

因此，A2UI 介于 Tool-based UI 和更开放的生成式界面之间：它保留了生成能力，但生成的是声明式结构，而不是任意页面实现。

适合做的事情：

- 流式输出的卡片、列表、指标面板、状态区块
- 需要跨平台渲染的 in-context UI，例如同一份描述渲染到 Web、移动端或其他宿主
- 希望模型容易生成 UI，但又不希望放开到完整自由界面代码的场景
- 基于组件 catalog 动态拼装局部界面，而不是每次都跳到独立页面

不太适合的事情：

- 完全无约束的整页生成
- 需要直接嵌入任意第三方页面或独立 URL 的场景
- 要求严格像素级一致性、且不希望模型参与布局决策的核心页面

### 怎么选？

- 如果你优先考虑可解释性和调试能力，先从 **Tool Rendering** 开始。
- 如果你希望通过 `tools` 调起一个独立、可交互、受控运行的内联界面，优先选择 **MCP Apps**。
- 如果你想在受控边界内引入生成能力，适合用 **A2UI**。

很多真实产品不会只用其中一种方式，而是组合使用：例如用 **Tool Rendering** 渲染轻量的 in-context 组件，用 **MCP Apps** 承载更完整的交互界面，用 **A2UI** 组织动态结果区。

## 为什么选择兼容微信小程序生态？

AIUI 深度兼容了**微信小程序**的开发规范（WXML/WXSS/API），这并非偶然，而是基于以下核心考量：

1.  **成熟的生态积累**：微信小程序拥有千万级的开发者和极其丰富的组件、工具库。兼容它，意味着开发者可以零门槛地利用现有的技能和资源。
2.  **一套 Agent，多端部署**：这是 AIUI 的终极目标。通过这套标准，你开发的智能体应用不仅能跑在 **AR 眼镜**上，还能无缝适配到**手机、桌面端（Desktop）**以及更多新型智能硬件，实现“一次编写，处处智能”。
3.  **原生 Web API 支持**：在兼容小程序的基础上，我们也提供了对常用 Web API 的原生支持，让 Web 开发者能以最习惯的方式进行创作。

## AIUI 有啥不一样的？

它不只是一个渲染引擎，更是开发者与 AI 协同表达的“窗口”：

*   **懂你所需（意图驱动）**：这是**开发者与 AI 协作**的终极体现。界面不再是预设好的“死模板”，而是由 AI 在开发者定义的逻辑框架内，根据对话语境实时为用户“组装”并返回最精准的内容承载。
*   **AR 眼镜绝配（低功耗）**：针对 AR 眼镜等穿戴设备进行了极限优化，确保在这种对功耗极其敏感的设备上，AI 与开发者的协同成果依然能流畅展现。

总而言之，**AIUI 是连接 AI、开发者与用户的桥梁**，让大模型的能力以最懂用户的方式走进现实世界。
