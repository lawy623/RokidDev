# Large Language Model

A large language model (LLM) provides the core understanding and generation capabilities of an agent. It is used for tasks such as question answering, summarization, extraction, rewriting, generation, and reasoning.

In AIUI, `LanguageModel` lets you send model requests directly from JavaScript and receive either full text responses or streamed incremental output.

## Use Cases

- Conversational question answering
- Content summarization and rewriting
- Information extraction and structured output
- Streaming responses (display while generating)
- Multi-turn interactions with context

## Basic Usage

### 1. Check Availability

```javascript
const status = await LanguageModel.availability();

if (status !== 'available') {
  throw new Error('LanguageModel 当前不可用');
}
```

### 2. Create a Session

```javascript
const session = await LanguageModel.create({
  initialPrompts: [
    { role: 'system', content: '请用简洁中文回答。' },
  ],
});
```

### 3. Send a Request

```javascript
const text = await session.prompt('请用一句话介绍 AIUI');
console.log(text);
```

### 4. Stream Output

Suitable for scenarios where content needs to be displayed while it is being generated:

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

## Using Tools

If your model needs to declare available tools, you can pass function declarations through `tools` when creating the session. When the model decides that a tool should be called, it triggers a `toolcall` event.

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

## Common Methods

| Method | Description |
|:--|:--|
| `availability()` | Checks whether the current environment can provide large language model capabilities |
| `create(options?)` | Creates a new model session |
| `prompt(input)` | Sends one request and returns the complete result |
| `promptStreaming(input)` | Starts a streaming request and reads incremental output step by step |
| `clone()` | Copies the current session context |
| `destroy()` | Destroys the session and releases resources |

## Best Practices

- Treat a session as a context-aware interaction unit rather than a global singleton.
- Prefer `promptStreaming()` for long text output. It is better suited to incremental rendering and speech playback coordination.
- Only one active request should run in the same session at a time.
- `initialPrompts` is suitable for system constraints and initial context. It is not recommended to stuff every user input into it.
- Call `destroy()` promptly when leaving the page or ending the session.

## Notes

- If `model` is not explicitly passed in, the runtime environment must provide a default model configuration.
- Streaming output returns a poll-based reader object, which is suitable for consuming text increments chunk by chunk.
- [x] `tools` currently supports receiving structured tool call requests in JavaScript through the `toolcall` event.

## Continue Reading

- **[Speech Recognition](/AIUI/guide/basic-ai-asr)**: See how to convert the user's voice input into text for the model.
- **[Speech Synthesis](/AIUI/guide/basic-ai-tts)**: See how to speak the model's reply back to the user.
- **[Large Language Model (API Reference)](/AIUI/api/ai-language-model)**: See the complete API reference.
