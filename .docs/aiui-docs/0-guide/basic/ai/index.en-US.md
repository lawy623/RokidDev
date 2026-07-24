# AI Capabilities

AIUI includes core AI capabilities that allow your agent to understand users, speak responses, and perform comprehension and reasoning.

These three capability types are often combined to form a complete voice agent pipeline:

- **Speech Recognition (ASR)**: Converts what the user says into text.
- **Large Language Model (LLM)**: Understands context and generates replies.
- **Speech Synthesis (TTS)**: Reads the generated result back to the user.

## How To Choose

- If you want your app to "hear and understand" user input: use **Speech Recognition**
- If you want your app to "speak out" text: use **Speech Synthesis**
- If you want your app to handle Q&A, summarization, generation, or reasoning: use **Large Language Model**

## Typical Flow

A complete voice agent interaction flow usually looks like this:

```
User speaks → Speech recognition → Text input → Large language model → Text output → Speech synthesis → User hears the reply
```

## Continue Reading

- **[Speech Recognition](/AIUI/guide/basic-ai-asr)**: Learn how to capture user speech and receive recognition results.
- **[Speech Synthesis](/AIUI/guide/basic-ai-tts)**: Learn how to synthesize text into spoken audio.
- **[Large Language Model](/AIUI/guide/basic-ai-llm)**: Learn how to create model sessions, request replies, and read streaming output.
