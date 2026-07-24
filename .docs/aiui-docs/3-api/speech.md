# 语音

AIUI 的语音能力主要用于支持语音合成以及更自然的人机交互。这里不展开接口细节，主要帮助你快速找到语音相关的具体文档。

## 简单示例

例如，让智能体播报一段欢迎语：

```javascript
const utterance = new SpeechSynthesisUtterance('欢迎使用 AIUI');
utterance.lang = 'zh-CN';
speechSynthesis.speak(utterance);
```

继续阅读：

- **[语音播报](/AIUI/api/ai-speech-synthesis)**：查看如何把文本合成为播报语音。
