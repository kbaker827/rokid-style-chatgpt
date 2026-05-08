# Rokid Style ChatGPT

Audio-first ChatGPT assistant for Rokid AI Glasses Style.

This Android/Kotlin port removes the iOS helper and HUD output from the original Rokid ChatGPT app. It runs as a foreground microphone service, listens for speech, sends the prompt to OpenAI Chat Completions, and reads the response aloud with Android TextToSpeech.

## Features

- Voice input through Android SpeechRecognizer
- OpenAI Chat Completions streaming client
- Conversation history trimming
- Android TextToSpeech response playback
- Minimal settings screen for API key, model, system prompt, token limit, and history size

## Build

Open the folder in Android Studio and build the `app` module.

## Notes

This is designed for the display-free Rokid Style workflow: voice in, spoken answer out.
