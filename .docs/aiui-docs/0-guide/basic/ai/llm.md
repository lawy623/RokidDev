# 大语言模型

大语言模型 (LLM) 是智能体的核心理解和生成能力，用于完成问答、总结、提取、改写、生成和推理等任务。

在 AIUI 中，`LanguageModel` 让你可以直接从 JavaScript 发起模型请求，接收文本回复或流式增量输出。

## 适用场景

- 对话式问答
- 内容总结与改写
- 信息提取与结构化输出
- 流式生成回复（边生成边展示）
- 带上下文的多轮交互

## 基本用法

### 1. 检查可用性

```javascript
const status = await LanguageModel.availability();

if (status !== 'available') {
  throw new Error('LanguageModel 当前不可用');
}
```

### 2. 创建会话

```javascript
const session = await LanguageModel.create({
  initialPrompts: [
    { role: 'system', content: '请用简洁中文回答。' },
  ],
});
```

### 3. 发起一次请求

```javascript
const text = await session.prompt('请用一句话介绍 AIUI');
console.log(text);
```

### 4. 流式输出

适合需要边生成边展示的场景：

```javascript
const stream = session.promptStreaming('请分点总结这段内容');

while (true) {
  const { done, value } = await stream.read();
  if (done) break;
  if (value !== undefined) {
    console.log(value);
  }
}
```

## 使用工具 (tools)

如果你的模型需要声明可用工具，可以在创建会话时通过 `tools` 传入函数声明。当模型认为需要调用工具时，会触发 `toolcall` 事件。

```javascript
const session = await LanguageModel.create({
  initialPrompts: [
    { role: 'system', content: '你是一个天气助手，请优先使用已声明的工具。' },
  ],
  tools: [
    {
      type: 'function',
      function: {
        name: 'get_weather',
        description: '查询某个城市的天气',
        parameters: {
          type: 'object',
          properties: {
            city: { type: 'string' },
          },
          required: ['city'],
        },
      },
    },
  ],
});

// 监听工具调用事件
session.addEventListener('toolcall', (event) => {
  if (event.functionName === 'get_weather') {
    const { city } = event.arguments;
    console.log(`正在查询 ${city} 的天气...`);
  }
});

const result = await session.prompt('帮我查询一下杭州今天天气');
```

## 常用方法

| 方法 | 说明 |
|:--|:--|
| `availability()` | 检查当前环境是否可提供大语言模型能力 |
| `create(options?)` | 创建一个新的模型会话 |
| `prompt(input)` | 发送一次请求并返回完整结果 |
| `promptStreaming(input)` | 发起流式请求，逐步读取增量输出 |
| `clone()` | 复制当前会话上下文 |
| `destroy()` | 销毁会话并释放资源 |

## 使用建议

- 把会话视为一次有上下文的交互单元，而不是全局单例。
- 对长文本输出优先使用 `promptStreaming()`，更适合增量展示 and 播报联动。
- 同一会话在同一时间只应运行一个活跃请求。
- `initialPrompts` 适合放系统约束和初始上下文，不建议每次用户输入都塞进去。
- 页面离开或会话结束时及时调用 `destroy()`。

## 注意事项

- 如果没有显式传入 `model`，运行环境需要提供默认模型配置。
- 流式输出返回轮询式读取对象，适合逐段消费文本增量。
- [x] 当前 `tools` 支持通过 `toolcall` 事件在 JavaScript 中接收结构化调用请求。

## 继续阅读

- **[语音识别](/AIUI/guide/basic-ai-asr)**：查看如何把用户语音输入转为文本交给模型。
- **[语音播报](/AIUI/guide/basic-ai-tts)**：查看如何把模型回复播报给用户。
- **[大语言模型 (API 参考)](/AIUI/api/ai-language-model)**：查看完整 API 参考文档。
