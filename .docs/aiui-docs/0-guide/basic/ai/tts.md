# 语音播报

语音播报 (TTS) 用于把文本内容转换成语音输出，适合欢迎语、回复播报、导航提示和状态提醒等场景。

在 AIUI 中，语音播报通常是语音交互链路的最后一步：当模型或业务逻辑生成文本结果后，把它读给用户听。

## 适用场景

- 智能体回复播报
- 系统提示音后的文字播报
- 导航与状态提醒
- 免手查看场景下的语音输出

## 基本用法

通过全局 `speechSynthesis` 和 `SpeechSynthesisUtterance` 发起播报：

```javascript
const utterance = new SpeechSynthesisUtterance('欢迎使用 AIUI');
utterance.lang = 'zh-CN';
utterance.rate = 1.0;
utterance.pitch = 1.0;
utterance.volume = 1.0;

speechSynthesis.speak(utterance);
```

## 关键配置

| 属性 | 说明 |
|:--|:--|
| `text` | 要播报的文本内容 |
| `lang` | 播报语言，例如 `zh-CN`、`en-US` |
| `rate` | 播报语速 |
| `pitch` | 播报音高 |
| `volume` | 播报音量，范围 `0.0` ~ `1.0` |
| `voice` | 指定要使用的声音名称 |

## 使用建议

- 控制单次播报的文字长度，避免整段长文本直接朗读。
- 根据场景调整 `rate` 和 `pitch`，不同场景适合不同语速和语气。
- 多条连续回复时，在业务层控制播报节奏，避免用户同时收到过多语音输出。
- 对重要提示使用简短有力的文案，普通信息可以用更自然的表达。

## 继续阅读

- **[语音识别](/AIUI/guide/basic-ai-asr)**：查看如何把用户语音转为文本。
- **[大语言模型](/AIUI/guide/basic-ai-llm)**：查看如何生成可播报的回复内容。
- **[语音播报 (API 参考)](/AIUI/api/ai-speech-synthesis)**：查看完整 API 参考文档。
