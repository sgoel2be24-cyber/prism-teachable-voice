# Teachable Voice Automation

Samsung PRISM GenAI Hackathon 2026, Theme 3.

An Android assistant that learns a task in a third-party app from one spoken command plus one demonstration, then repeats it on voice command, including with changed values ("order a Farmhouse instead", "two of them", "deliver to Work"). It works only through Android's Accessibility Service: no app-specific APIs, no deep links. It stops and hands control back at payment, OTP and login screens.

Target apps: Zomato (Domino's ordering flow) and Amazon (search, then add to cart).

## Status

Work in progress.

| Done | Where |
|---|---|
| Screen-structure recon of Zomato and Amazon | `docs/recon-zomato.md` |
| Recording and replay test: which taps apps report, and whether the service can click and type in them | `docs/spike-results.md` |
| Test build: accessibility service that logs events and accepts dev commands (dump screen, click, type) | `android/` |
| Recon scripts: screen capture over adb, tree summaries, dev command helper | `recon/` |

## Pipeline (planned)

1. **Teach:** the user speaks a command, then performs the task once. Every tap is captured with a screen snapshot; typing comes from text events.
2. **Generalise:** an LLM turns the recording into a recipe of steps with slots (item, restaurant, quantity, address). The user reviews and confirms it.
3. **Match:** a later command is matched to a recipe and its slot values, or to "not learned" or "ask to clarify".
4. **Replay:** each step first tries a fast element match. If the screen differs, an LLM picks the action from the live screen and the step's goal. If it's still unsure, the app asks the user. A guard stops at payment, OTP and login screens. Every run is logged.

## Building the test app

Requirements: JDK 17 or newer and the Android SDK (platform 35). Create `android/local.properties` containing `sdk.dir=<path to your Android SDK>`.

```
cd android
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then turn on **Teach Spike recorder** under Settings → Accessibility on the phone.
