# What Is AIUI?

AIUI is an **AIUI (AI-native user interface)** framework designed specifically for the era of large models. It provides an efficient collaboration platform that enables **deep collaboration between developers and AI**, allowing them to build intelligent and intuitive interfaces together. As a result, AI is no longer limited to text, but can instead deliver interactive experiences that respond precisely to user needs. In essence, **AIUI is a UI development framework built for AI-native experiences**.

## What Is AIUI?

AIUI (AI-Native User Interface) can first be understood as a new interaction model: AI no longer just outputs a piece of text, but directly organizes its understanding into an actionable interface. Interaction between users and AI no longer happens only in chat responses, but can also take place in cards, forms, panels, game interfaces, or task interfaces. In other words, AIUI is not about "making AI able to speak," but about "making AI able to collaborate with users through interfaces."

### In-Chat Interaction

In-chat interaction means that the UI appears directly in the chat flow, embedded in the context as cards, result blocks, forms, or tool panels. Users can chat while clicking, selecting, entering data, or viewing results. This kind of AIUI is suitable for scenarios such as weather cards, itinerary suggestions, task confirmation, search results, and form filling. At its core, it inserts interactive interfaces into the conversation.

The following example uses a weather scenario to demonstrate what in-chat interaction typically looks like: the user asks a question, the AI returns a weather card in the message flow, and then the user can keep clicking actions on the card so the interaction continues naturally within the same conversation.

<in-chat-interaction-demo></in-chat-interaction-demo>

### Immersive Interface Interaction

Immersive interface interaction means that the UI is no longer attached to chat messages. Instead, a standalone interface carries the complete interaction flow. This interface can be a task panel, an application window, a game interface, or any other more complete operating space, while the user can still interact with it through voice or other input methods. For example, in a Landlord game interface, the table, hand cards, buttons, and status area are all presented by the UI, while the user can still play cards, confirm actions, or collaborate with the agent through voice.

The following example uses a Landlord scenario to demonstrate what immersive interface interaction typically looks like: the main interaction happens inside a complete interface, and the AI does more than reply with a sentence. It continuously collaborates with the user around that interface.

<standalone-interaction-demo></standalone-interaction-demo>

As a result, AIUI is more like moving AI from a "text assistant" to an "interface assistant." Developers define the interface boundaries, interaction model, and business capabilities, while the AI decides when to call capabilities, how to organize content, and how to make the overall interaction flow feel more natural based on context.

## How Can AIUI Be Implemented?

AIUI is not a fixed way of generating interfaces. In real products, there are usually two dimensions for deciding how to implement it:

<aiui-implementation-chart></aiui-implementation-chart>

Looking along these two dimensions, the most common implementation approaches for AIUI today mainly fall into three categories:

### 1. Tool Rendering

The core of Tool Rendering is to register the UI intent and the structured inputs it requires with the model through the LLM `tools` mechanism, allowing the model to actively initiate the Tool Call in the appropriate context. Developers abstract "displaying a certain kind of UI" as a callable capability. After the frontend receives that call, it renders the UI with predefined components. In essence, this is a high-control, low-freedom AIUI implementation approach.

For example, in a weather scenario, you can register a tool such as `render_weather_card` with the model and declare that it needs an input parameter like `location`. When the user asks, "What's the weather like in Hangzhou today?", the model does not have to respond with a block of text. It can directly call the tool and pass `location: "杭州"`. The frontend then renders a weather card that shows the loading state, weather result, and related actions.

A simplified `tools` registration can be understood like this:

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

After the frontend receives this Tool Call, it can place the parameters into the page `query`, and then let the page receive them in `onLoad(query)`:

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

The corresponding page template can be rendered like this:

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

In other words, the LLM decides that "it should call `render_weather_card` now and pass the `location` parameter," while the page receives those inputs through `onLoad(query)` and renders the weather card using a predefined UI structure.

Suitable use cases:

- Register a fixed UI capability with the model in the form of a `tool`
- Let the model decide when to display a certain card, form, or result component based on context
- Drive interface rendering with structured parameters instead of letting the model generate a whole block of UI code directly
- Insert stable and controllable interactive interfaces into chat flows or task flows

Less suitable use cases:

- Large-scale interface generation where the model needs to decide page structure freely
- Complex business pages that need to exist independently outside the Tool Call lifecycle

### 2. MCP Apps

MCP Apps can be understood as attaching an interactive UI directly to an MCP tool. In terms of input, it is not fundamentally different from Tool Rendering. Both approaches register `tools` with the LLM, allowing the model to decide when to call them and pass structured parameters based on context. The main difference lies in how rendering is hosted: Tool Rendering often maps call results to built-in UI within the Agent Loop system itself, while MCP Apps are usually loaded by the host according to the UI resources declared by the tool, for example by hosting a third-party URL in an iframe and displaying it inline in the session context. For more about this model, refer to [MCP Apps](https://apps.extensions.modelcontextprotocol.io/api/).

For AIUI, this boundary is not that absolute. Since AIUI's renderer `InkView` already supports running interfaces in a sandbox environment, AIUI in in-context scenarios is often closer to the MCP Apps approach. That is, the invoked UI is carried as an independent yet controlled runtime unit.

Suitable use cases:

- Inline UI with independent interaction, such as charts, forms, dashboards, and design canvases
- Calling a complete controlled interface through `tools`, instead of just rendering a simple component
- Embedding third-party or independently running interactive views in the session context

Less suitable use cases:

- Open-ended page generation completely unconstrained by the host
- Scenarios that are extremely sensitive to host integration cost and only require simple component mapping

### 3. A2UI

More precisely, A2UI is a declarative, LLM-friendly Generative UI specification. Its core idea is not to let the model directly output interface code for a specific platform, nor to let the model simply call an existing page entry. Instead, the model outputs a structured UI description, and the host reconstructs it into a real interface based on a predefined component catalog and renderer. It usually has several key characteristics: `JSONL` friendly, suitable for streaming output, cross-platform rendering, and more model-friendly generation.

At runtime, systems of this type usually inject a tool such as `render_a2ui` into the agent, allowing the model to output an A2UI surface when needed. The frontend does not need to write a dedicated page entry for each call. Instead, it prepares a set of base components and a custom catalog, and the renderer automatically assembles those structured descriptions into cards, lists, metric blocks, images, or other UI fragments.

Common A2UI components usually fall into two groups:

- Base components: such as `Text`, `Image`, `Card`, and `List`
- Business components: such as `Metric`, `StatusBadge`, and any components you extend into the catalog yourself

When generating A2UI, the model does not directly output HTML or page code for a specific platform. Instead, it outputs a series of commands. A typical flow can be understood as three steps:

1. `surfaceUpdate`: first declare which components should be rendered
2. `dataModelUpdate`: then inject data into those components
3. `beginRendering`: finally notify the host to start rendering

The interactive component below uses a weather card to demonstrate the three core parts of A2UI: the component catalog, the command stream, and how it is ultimately rendered into an interface.

<a2ui-explorer></a2ui-explorer>

As a result, A2UI sits between tool-based UI and more open generative interfaces: it retains generative capability, but what it generates is declarative structure rather than an arbitrary page implementation.

Suitable use cases:

- Streaming cards, lists, metric panels, and status blocks
- Cross-platform in-context UI, where the same description needs to be rendered on the Web, mobile, or other hosts
- Scenarios where you want the model to generate UI more easily, but do not want to fully open up to unrestricted interface code
- Dynamically assembling local interface fragments from a component catalog instead of jumping to a standalone page every time

Less suitable use cases:

- Completely unconstrained full-page generation
- Scenarios that require directly embedding arbitrary third-party pages or standalone URLs
- Core pages that require strict pixel-level consistency and do not want the model to participate in layout decisions

### How Should You Choose?

- If you prioritize explainability and debugging capability, start with **Tool Rendering**.
- If you want to invoke an independent, interactive, and controlled inline interface through `tools`, choose **MCP Apps** first.
- If you want to introduce generative capability within controlled boundaries, **A2UI** is the right fit.

Many real products do not rely on just one approach. Instead, they combine them: for example, use **Tool Rendering** for lightweight in-context components, **MCP Apps** for more complete interactive interfaces, and **A2UI** for dynamic result areas.

## Why Choose Compatibility with the WeChat Mini Program Ecosystem?

AIUI is deeply compatible with the development conventions of **WeChat Mini Programs** (WXML/WXSS/API). This is not accidental, but based on the following core considerations:

1. **A mature ecosystem**: WeChat Mini Programs have tens of millions of developers and an extremely rich set of components and tooling libraries. Compatibility means developers can make use of existing skills and resources with almost no barrier.
2. **One agent, multi-platform deployment**: This is AIUI's ultimate goal. With this standard, the intelligent agent applications you build can run not only on **AR glasses**, but also adapt seamlessly to **phones, desktop environments**, and more emerging intelligent hardware, achieving "write once, deploy intelligence everywhere."
3. **Native Web API support**: While remaining compatible with Mini Programs, we also provide native support for common Web APIs, allowing web developers to create in the way they are most familiar with.

## What Makes AIUI Different?

It is not just a rendering engine, but a "window" for collaborative expression between developers and AI:

- **Intent-driven**: This is the ultimate embodiment of **developer-AI collaboration**. Interfaces are no longer preset static templates, but are assembled by AI in real time within the logical boundaries defined by the developer, returning the most precise content carrier for the current conversation context.
- **Perfect for AR glasses (low power consumption)**: It is heavily optimized for wearable devices such as AR glasses, ensuring that the collaborative result between AI and developers can still be presented smoothly on devices with extremely strict power constraints.

In short, **AIUI is the bridge connecting AI, developers, and users**, bringing the power of large models into the real world in a way that best understands users.

