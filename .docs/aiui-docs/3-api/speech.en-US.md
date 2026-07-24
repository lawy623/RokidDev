# Speech

AIUI speech capabilities are mainly used for speech synthesis and more natural human-computer interaction. This page does not expand on every API detail. Its main purpose is to help you quickly find the specific documentation related to speech.

## Simple Example

For example, let the agent speak a welcome message:

```javascript
const utterance = new SpeechSynthesisUtterance('欢迎使用 AIUI');
utterance.lang = 'zh-CN';
speechSynthesis.speak(utterance);
```

Continue reading:

- **[Speech Synthesis](/AIUI/api/ai-speech-synthesis)**: See how to synthesize text into spoken output.
