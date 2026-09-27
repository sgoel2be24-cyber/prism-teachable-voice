# Spike: can we record taps and replay them?

27 Sep 2026. Test app: a minimal accessibility service (`com.prism.tva`, "Teach Spike") that logs every accessibility event and accepts dev-only adb commands (dump all windows, click by text or ID, type text). Phone: Oppo Reno3, ColorOS 12.1 / Android 12.

## Recording: which taps did the apps report?

| Tap | Reported? | How |
|---|---|---|
| Zomato search bar | yes | `VIEW_CLICKED`, id `search_bar_view_flipper` |
| Typing "dominos" | yes | `VIEW_TEXT_CHANGED` per keystroke, id `edittext` |
| Zomato suggestion "Domino's Pizza" | no | only a screen change |
| Zomato result card (Jetpack Compose, no IDs) | no | only a screen change |
| Menu "+" stepper, repeat-sheet "+", Continue bar | yes | `VIEW_CLICKED`, ids `button_add` / `container` |
| Amazon search box | yes | `VIEW_CLICKED` |
| Typing "wireless earbuds" | yes | `VIEW_TEXT_CHANGED` |
| Search submit (keyboard) | no | none |
| Amazon product tap (web view) | no | only `VIEW_FOCUSED` on the price text |
| Amazon "Add to cart" (web view) | partial | `VIEW_FOCUSED` on the button, no `VIEW_CLICKED` |
| Amazon Cart tab | yes | `VIEW_CLICKED` |

About 8 of 13 taps can be identified from events alone. Compose screens and web views don't report touch taps.

**Proposed approach:** teach mode records through a transparent tap-capture overlay. Every tap or swipe is caught with its coordinates. The tapped element is found by hit-testing the screen tree captured just before the tap, and then the tap is re-injected with `dispatchGesture`. Accessibility events are still used for text entry and as a cross-check. The keyboard area is excluded, and Enter is recorded as a submit.

## Replay: can the service act inside the apps?

All of these worked:
- **Zomato:** click by ID on a View-based screen (48 ms); `ACTION_SET_TEXT` into search; click a suggestion by text through its clickable ancestor; click the Compose result card (clickable ancestor, no IDs), which opened the Domino's menu.
- **Amazon:** click the search box by ID; `ACTION_SET_TEXT`; click a web-view product link by its label, which opened the product page.

Reading every window on screen took 235–740 ms for 187–276 nodes.

## What the executor must handle
- **Ambiguous text matches.** After a search, the search box itself contains "Domino's Pizza", and one Amazon product name matched 4 nodes. Use scored matching (role, clickability, position, recorded neighbours), not first match.
- **Submitting a search.** Use `ACTION_IME_ENTER` (API 30+), with tapping the first matching suggestion as a fallback.
- **Off-screen targets.** Amazon's "Add to cart" is below the fold, and so are most Zomato menu items. Scroll and search.
- **Amazon's "first result".** A sponsored video and sponsored products come before the organic results.
- **Enabling the service.** ColorOS blocks enabling accessibility services from adb (`WRITE_SECURE_SETTINGS`), so it has to be switched on on the phone.
