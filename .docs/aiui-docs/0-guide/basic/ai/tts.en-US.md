# Speech Synthesis

Speech synthesis (TTS) converts text into spoken audio output. It is suitable for scenarios such as welcome messages, spoken replies, navigation prompts, and status reminders.

In AIUI, speech synthesis is usually the last step in the voice interaction pipeline: when the model or your business logic generates a text result, it reads it aloud to the user.

## Use Cases

- Spoken agent replies
- Spoken text after system prompt sounds
- Navigation and status reminders
- Voice output in hands-free scenarios

## Basic Usage

Start speech playback with the global `speechSynthesis` and `SpeechSynthesisUtterance`:

```javascript
const utterance = new SpeechSynthesisUtterance('欢迎使用 AIUI');
utterance.lang = 'zh-CN';
utterance.rate = 1.0;
utterance.pitch = 1.0;
utterance.volume = 1.0;

speechSynthesis.speak(utterance);
```

## Key Settings

| Property | Description |
|:--|:--|
| `text` | The text content to speak |
| `lang` | The speech language, for example `zh-CN` or `en-US` |
| `rate` | The speech rate |
| `pitch` | The speech pitch |
| `volume` | The speech volume, ranging from `0.0` to `1.0` |
| `voice` | The name of the voice to use |

## Best Practices

- Keep each utterance reasonably short instead of reading an entire long paragraph at once.
- Adjust `rate` and `pitch` based on the scenario, since different contexts suit different speaking speeds and tones.
- When multiple replies are spoken in sequence, control the playback rhythm in the business layer to avoid overwhelming the user with too much audio at once.
- Use short and direct copy for important prompts, and more natural wording for ordinary information.

## Continue Reading

- **[Speech Recognition](/AIUI/guide/basic-ai-asr)**: See how to convert the user's voice into text.
- **[Large Language Model](/AIUI/guide/basic-ai-llm)**: See how to generate reply content that can be spoken.
- **[Speech Synthesis (API Reference)](/AIUI/api/ai-speech-synthesis)**: See the complete API reference.
