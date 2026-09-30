# Teachable Voice Automation

Samsung PRISM GenAI Hackathon 2026, Theme 3.

An Android assistant you teach by doing. Say a command, perform the task once in any app, and from then on it does the task on voice command, including with different values ("get me a Farmhouse instead", "two of them", "deliver to Work") and different wording. It works only through Android's Accessibility Service, with no app-specific APIs and no deep links. It stops and hands control back at payment, OTP and login screens.

**Target apps:** Zomato (ordering from Domino's) and Amazon (search, add the first result to the cart); the Amazon task also runs on Myntra.

## What works today

- **Teach by demonstration.** A tap-capture overlay records every tap, including on Jetpack Compose screens and web views that don't report taps to accessibility services. It also records typing, keyboard search and back. Taps in other apps (a call, the notification shade) are ignored.
- **Generalisation.** Slots are found from the command ("search for {product} on amazon…", "ADD next to {item}"), and a language model names them, writes step intents and adds quantity/address goals.
- **Understanding commands.** Exact wording is matched offline; paraphrases, changed values, Hinglish, missing values and "did the last run succeed?" go through the language model. The Amazon test set scores 18/18, median 1.1 s.
- **Reliable replay.** A fast path does scored element matching with no network call. The language model steps in only when the screen differs (pop-ups, unseen screens), and the user is asked when a value is missing or the screen can't be handled.
- **The right item.** "The first result" skips Sponsored/Ad cards. A dish's options sheet must name the dish asked for, or it is closed without adding. On a restaurant it wasn't taught on, it searches the menu for the dish and prefers the plain "Margherita" over "Margherita Ultimate Cheese Pizza". An item already in the cart isn't added twice.
- **Safety.** A payment/login/OTP/password guard is checked on every action.
- **Run log**, spoken replies, and an on-screen status pill with Stop.
- **Noisy demonstrations.** Switching to another app mid-demo (and the back gesture used to return) is dropped from the learned task.
- **Another app, same task.** A task taught on Amazon runs on Myntra when the user says "on myntra": it opens the first result that isn't an advert, the language model finds the equivalent buttons ("Add to Bag"), it asks for a size instead of choosing one, and it stops once the bag count goes up.

## Measured on the phone (Oppo Reno3, Android 12)

| What | Result |
|---|---|
| Replay across sessions (Amazon force-stopped before every run; exact, new values, paraphrases, Hinglish) | 8/8, median 21.9 s, 39/40 steps without the language model |
| Learn success (teach once, replay once with a new value; one demo with an app-switch detour) | 4/4 |
| Command understanding (Amazon test set) | 18/18, median 1.1 s |
| Amazon, taught by hand: earbuds ×5, phone case ×4, water bottle ×2, plus "put a notebook in my amazon cart", "amazon pe pencil box search karke pehla result cart mein daal do", "i need a desk lamp, add the first one on amazon to my cart" | 14/14, 32–61 s, first non-sponsored result every time |
| Zomato, a restaurant it wasn't taught on ("Order a Margherita pizza from Pizza Hut on Zomato": outlet picker, menu search) | Margherita (Pan, Personal) in the cart, stopped before checkout, 64 s, 2 language-model calls |
| Cross-app: taught by hand on Amazon, run on Myntra (backpack, sunglasses, wallet, sneakers, t-shirt, denim jacket, belt), cold start each time | 7/7, 43–61 s, 2–5 language-model calls; skipped Myntra's "AD" tiles every time; asked for the size on all four sized items |
| Zomato, taught once by hand (with a mis-tap and a pop-up in the demo): exact, paraphrase, new pizza, two pizzas, deliver to Work, "order something" | 6/6, 29–78 s, 35/38 steps without the language model |
| Zomato repeatability, to the payment page: the judges' sentence ×3 and "I want to order margherita pizza on zomato" (asks the restaurant) ×3, cold start each time | 6/6, 35–44 s, no language-model calls; stops at payment every time |
| The judges' sentences verbatim on 30 Sep (exact, both paraphrases, Farmhouse, two pizzas, deliver to Work, "Order garlic bread from dominos", "Order pizza.", "Book a cab to the airport.", "Did the last run succeed?") | All handled: the right item each time (garlic bread → Classic Stuffed Garlic Bread), quantity 2, address switched to Work, both missing values asked for in "Order pizza.", no attempt on the cab, accurate report; 36–66 s |
| A new app it had never seen (GitHub, read-only): taught "Open the pytorch repository on GitHub" once, then tensorflow, linux and "show me the react repo" | 3/3 opened the right repository; tensorflow and linux in 17–18 s with no language-model calls |

Tables and method: [docs/eval-replay-sessions-2026-09-28.md](docs/eval-replay-sessions-2026-09-28.md) · [docs/eval-zomato-2026-09-28.md](docs/eval-zomato-2026-09-28.md) · [docs/eval-2026-09-30.md](docs/eval-2026-09-30.md)

Presentation: [TIET_Update_Submission_ppt.pptx](TIET_Update_Submission_ppt.pptx) (generated by [docs/deck/build.js](docs/deck/build.js))

Details: [docs/architecture.md](docs/architecture.md) · recon of the target apps: [docs/recon-zomato.md](docs/recon-zomato.md) · recording/replay study: [docs/spike-results.md](docs/spike-results.md)

## Try it

1. Install the APK and open **Teachable Voice**. Allow the microphone.
   - **If the install is blocked** ("Blocked by Play Protect", "App not installed"): in India, Google Play Protect blocks apps that use an accessibility service when they are installed from a browser, a chat app or a file manager. Either install over USB with `adb install TeachableVoice-1.0.apk` (USB debugging on), or turn off **Play Store → profile → Play Protect → ⚙ → Scan apps with Play Protect**, install, and turn it back on.
   - Samsung phones: if **Settings → Security and privacy → Auto Blocker** is on, it blocks every app from outside the store; turn it off to install.
   - If a download says the file "might be harmful", choose **Download anyway**.
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

The APK handed out (`assembleRelease`) takes its key from `.fireworks_key_submission` instead, so a
separate, spend-limited key can ship in it. Both key files are git-ignored; keys never appear in
source. Any key can be replaced at runtime in the app's **Language model key** field.

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
- A task is learned from one demonstration in one app; running it in a similar app relies on the language model for the steps that look different, so it is slower (about 45–60 s on Myntra versus about 35–50 s on Amazon).
- The phone must stay unlocked with the screen on while a task runs; accessibility services cannot act on a locked screen.
- Web-based pages (Amazon's results) can take several seconds to appear to accessibility services on a slow connection; the assistant waits up to 15 s per step.
- On a failing connection the target app itself stalls (Zomato's suggestions spin, "Something went wrong"). The assistant taps the app's "Try Again" up to three times and then reports where it stopped; it can't fix the network.
- Spoken answers use the phone's speech recogniser (English, India); in a noisy room, typing the answer in the app works too.
- It never picks personal options (size, colour, address) on its own: it asks. It never pays, places an order or enters a login, OTP or password.
- Zomato now keeps a separate cart per restaurant, so ordering from a second restaurant doesn't touch the first cart. If an app does ask to replace or clear a cart, the assistant asks the user instead of deciding.
- A task taught with the app in English can't be run after the app is switched to Hindi (the buttons can no longer be recognised): the assistant says so at the first step instead of guessing, and suggests switching the app back or teaching the task again in Hindi. The payment and login guard knows the common Hindi phrases too.
- If a command names an app that isn't installed ("… in Swiggy"), the assistant says so rather than running the task in the app it was taught in.
- A dish must be recognisable by name: if the options sheet that opens doesn't name the dish asked for (a very different menu name, or the name only in a picture), the assistant closes it without adding and tries again; if it can't find the right one, it stops and says what it found.
