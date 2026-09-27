# Teachable Voice Automation

Samsung PRISM GenAI Hackathon 2026, Theme 3.

An Android assistant you teach by doing. Say a command, perform the task once in any app, and from then on it does the task on voice command, including with different values ("get me a Farmhouse instead", "two of them", "deliver to Work") and different wording. It works only through Android's Accessibility Service, with no app-specific APIs and no deep links. It stops and hands control back at payment, OTP and login screens.

**Target apps:** Zomato (ordering from Domino's) and Amazon (search, add the first result to the cart).

## What works today

- **Teach by demonstration.** A tap-capture overlay records every tap, including on Jetpack Compose screens and web views that don't report taps to accessibility services. It also records typing, keyboard search and back. Taps in other apps (a call, the notification shade) are ignored.
- **Generalisation.** Slots are found from the command ("search for {product} on amazon…", "ADD next to {item}"), and a language model names them, writes step intents and adds quantity/address goals.
- **Understanding commands.** Exact wording is matched offline; paraphrases, changed values, Hinglish, missing values and "did the last run succeed?" go through the language model. The Amazon test set scores 18/18, median 1.1 s.
- **Reliable replay.** A fast path does scored element matching with no network call. The language model steps in only when the screen differs (pop-ups, unseen screens), and the user is asked when a value is missing or the screen can't be handled.
- **Safety.** A payment/login/OTP/password guard is checked on every action.
- **Run log**, spoken replies, and an on-screen status pill with Stop.

Details: [docs/architecture.md](docs/architecture.md) · recon of the target apps: [docs/recon-zomato.md](docs/recon-zomato.md) · recording/replay study: [docs/spike-results.md](docs/spike-results.md)

## Try it

1. Install the APK and open **Teachable Voice**. Allow the microphone.
2. Turn on the assistant: **Settings → Accessibility → Teachable Voice assistant**.
   - Android 13 and later: if the switch is greyed out, open **Settings → Apps → Teachable Voice → ⋮ → Allow restricted settings**, then try again.
   - Oppo/ColorOS, Xiaomi, Vivo: allow the app to run in the background (battery settings), or the system may stop the assistant.
3. **Teach:** tap **Teach a new task**, say e.g. "Order a Margherita pizza from Domino's on Zomato", then do it once while the red border is showing, stopping before payment. Tap **Done**. Review the learned steps.
4. **Run:** tap **Tap and speak a command** and say the same thing, a paraphrase, or a changed value.

## Build

Requires JDK 17+ and the Android SDK (platform 35).

```
echo "sdk.dir=/path/to/Android/sdk" > android/local.properties
printf '%s' "fw_your_fireworks_key" > .fireworks_key      # optional; enables the language-model features
cd android && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or with Docker:

```
docker build -t teachable-voice --build-arg FIREWORKS_API_KEY=fw_your_fireworks_key .
docker run --rm -v "$PWD/out:/out" teachable-voice      # -> out/teachable-voice.apk
```

## Repository

| Path | Contents |
|---|---|
| `android/app/src/main/java/com/prism/tva/teach/` | Recorder (tap-capture overlay) |
| `.../core/` | Screen snapshot and hit-testing, actions, guard, text matching |
| `.../recipe/` | Recipe builder (slots), matcher, storage |
| `.../run/` | Element resolver (fast path) and executor |
| `.../llm/` | Fireworks client and the prompts (matching, acting, refining) |
| `.../ui/` | Status pill, text-to-speech, voice questions |
| `recon/` | Scripts used to study the target apps and to test the assistant over adb (`eval_match.py` measures paraphrase accuracy) |
| `docs/` | Architecture, app recon, recording/replay study, evaluation output |

## Known limitations

- Needs a Fireworks API key for paraphrases, pop-up handling, quantity/address changes and questions; exact and template commands work offline.
- The overlay adds about 0.2 s to each tap while teaching (replay is unaffected).
- A task is learned from one demonstration in one app; running it in a similar app relies on the language model step by step.
