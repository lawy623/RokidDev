# AIUI in Chat

If you want to understand the most intuitive form of AIUI first, the best place to begin is conversational AIUI. Its core characteristic is that the UI appears directly in the chat flow, allowing users to talk with the AI while continuing to operate through cards, forms, or result blocks inside messages.

## 1. First Understand the Target Form

Conversational AIUI does not mean having the AI reply with a long block of text. Instead, it means letting the AI insert an interactive interface into the conversation context. For example:

- After checking the weather, directly return a weather card
- After confirming an itinerary, directly return an actionable itinerary confirmation panel
- After searching for products, directly return a result list that can be filtered and clicked

Its key point is: **the interaction stays in the current conversation instead of jumping to another standalone interface.**

## 2. Create a Basic Project

You can start by creating an AIUI project with the scaffolding tool:

```bash
npm create @yodaos-pkg/aiui-agent my-aiui-chat-agent
```

After initialization, you can directly use a single-file `.ink` component to organize your first page. This is better for quickly understanding how an AIUI page is written:

```text
├── app.js
├── app.json
├── AGENTS.md
├── pages
│   └── weather
│       └── index.ink
```

## 3. Define a Weather Card in the Conversation with `.ink`

Suppose we want the model to render a weather card when the user asks about the weather. Here, we can directly use a single `.ink` file to place the page configuration, page logic, page structure, and page styles together.

```html
<script def>
{
  "navigationBarTitleText": "Weather Card",
  "description": "天气相关问题优先返回此工具，支持查询中国大陆地区的实时天气、气温、湿度、风力。支持自动识别当前城市或查询指定城市（如问“北京天气怎么样”）。",
  "schema": {
    "data": {
      "type": "object",
      "properties": {
        "location": {
          "type": "string",
          "description": "目标城市名称（如“北京”、“上海”），仅支持城市名，不支持其他地区。若用户未明确指定，则默认为其当前所在城市。"
        },
        "date": {
          "type": "string",
          "description": "查询日期，格式为 yyyy-mm-dd（如“2023-10-01”）。"
        }
      },
      "required": ["location"]
    }
  }
}
</script>

<script setup>
export default {
  data: {
    city: '',
    loading: true,
    weather: null
  },

  async onLoad(query) {
    const city = query.location || '杭州';

    this.setData({
      city,
      loading: true
    });

    const weather = {
      condition: '多云',
      temperature: 26
    };

    this.setData({
      weather,
      loading: false
    });
  },

  refreshWeather() {
    this.setData({
      weather: {
        condition: '小雨转多云',
        temperature: 25
      },
      loading: false
    });
  }
};
</script>

<page>
  <view class="weather-card">
    <text class="title">{{ city }} 天气</text>

    <view ink:if="{{ loading }}">
      <text class="hint">天气加载中...</text>
    </view>

    <view ink:else>
      <text class="condition">{{ weather.condition }}</text>
      <text class="temperature">{{ weather.temperature }}°C</text>
      <button class="action" bindtap="refreshWeather">刷新天气</button>
    </view>
  </view>
</page>

<style>
.weather-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 20px;
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(7, 193, 96, 0.18), rgba(7, 193, 96, 0.05));
}

.title {
  font-size: 20px;
  font-weight: 600;
}

.hint,
.condition,
.temperature {
  font-size: 16px;
}

.action {
  width: 120px;
}
</style>
```

This `.ink` file contains four parts:

- `<script def>`: page-level configuration, plus the page's `description` and `schema.data`
- `<script setup>`: page logic and data
- `<page>`: interface structure
- `<style>`: page styles

For getting started, the advantage of this approach is obvious: you can see in one file both "what this page should display" and "how this page responds to interaction."

## 4. How Does This Weather Card Work?

In this example, the page receives the `location` parameter through `onLoad(query)`. In other words, when the page is triggered externally with a context such as "Hangzhou," the page can use `query.location` to organize the current card content.

After that, users can continue clicking the `Refresh Weather` button inside the card. This is one of the key characteristics of conversational AIUI: **the user's next action happens directly inside the card in the chat flow.**

## 5. How Is It Different from Normal Chat?

Normal chat is: the AI returns a text description of the weather.

Conversational AIUI is: the AI directly returns a weather card in the conversation flow, and the user can continue checking, refreshing, confirming, or moving to the next step around that card.

This means:

- The AI's output is no longer just text
- The UI remains continuous with the conversation context
- User actions happen inside the message instead of outside the current session

## 6. Which Scenarios Should You Start With?

If this is your first conversational AIUI project, it is recommended to start with these scenarios:

- Weather cards
- Itinerary confirmation cards
- Search result cards
- To-do confirmation panels
- Supplemental form input

What these scenarios have in common is that the interaction is relatively light, the structure is clear, and they are well suited to be completed directly in the conversation flow.
