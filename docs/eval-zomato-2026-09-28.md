# Zomato — taught by hand, replayed by voice (28 Sep 2026, 11:07–12:04 IST)

**Demonstration.** Shikhar taught "add margherita pizza in Domino's" once, by hand, on his phone.
It was a real, messy demo: 40 scroll flicks down the menu to find Margherita, then (after adding it)
an accidental tap on another pizza's photo and a "Movie voucher unlocked · Got it" pop-up.

**What it learned** (after the fixes below; re-learned from the same saved recording, no re-teach):
open Zomato → search → Domino's suggestion → Domino's Pizza → ADD next to `{product}` → Add item,
with slots `restaurant = dominos`, `product = margherita pizza`, a quantity goal before "Add item",
and the photo tap and the pop-up dismissal marked as noise.

**Replay.** Zomato force-stopped before every run (`recon/session_bench.sh`); commands go through
the same path as a spoken command. "Which pizza?" was answered by `recon/autoanswer.sh`.

| # | Command | Test | Outcome | Time | LLM calls | Notes |
|---|---|---|---|---|---|---|
| 1 | add margherita pizza in Domino's | T2 exact | success | 42.6 s | 0 | menu search finds the dish, then the fast matcher |
| 2 | order a margherita from dominos on zomato | T3 paraphrase | success | 28.6 s | 0 | "Margherita Pizza" preferred over "Double Cheese Margherita" |
| 3 | add farmhouse pizza in dominos | T4 new value | success | 37.6 s | 0 | |
| 4 | add two margherita pizzas from dominos | T5 quantity | success | 63.1 s | 3 | "+" in the crust sheet, verified "2", then Add item |
| 5 | add margherita pizza in dominos and deliver it to work | T6 address | success | 78.1 s | 5 | picked the saved "Work" address on the home screen first |
| 6 | order something from dominos on zomato | T13 ambiguity | success | 62.6 s | 1 | asked "Which pizza would you like to add?" |

**6/6. 35 of 38 steps replayed by the on-device matcher.** Payment (T11): runs end at "Add item"
and say "complete the payment yourself"; the guard recognises Zomato's payment page
(`PaymentsOptionsActivityV5`, "Bill total", "Pay by any UPI app") and "Add Payment Method" is on the
never-tap list. Pop-ups (T7): the voucher pop-up was dismissed by the LLM when it covered the cart.

## Repeatability: the same commands, again and again (13:38–14:23 IST)

The task was re-taught at 12:51 so the demo ends on Zomato's payment step (Continue → voucher
"Got it" → Continue). Each command was then run several times from a cold start
(`recon/repeat_bench.sh`, which saves the screen of any failed run). A run passes if it stops at
the payment page with exactly one Margherita in the cart.

| Command | Test | Final batch (stable network) |
|---|---|---|
| Order a Margherita pizza from Domino's on Zomato | T2, the judges' sentence | 3/3, 35–41 s, 0 LLM calls |
| I want to order margherita pizza on zomato | T3, asks "Which restaurant…?" | 3/3, 39–44 s, 0 LLM calls |

Then once each, same conditions:

| Command | Test | Outcome |
|---|---|---|
| order Margarita pizza from Domino's on Zomato (the taught sentence) | T2 exact | payment handover, 46 s, 0 LLM calls |
| Get me a margherita from dominos | T3 paraphrase | payment handover, 38 s, 0 LLM calls |
| Order pizza. | T13 ambiguity | asked "Which restaurant…?" and "Which pizza…?", then payment handover, 39 s |

All nine stopped on the payment step without tapping anything there. One of them said "complete
the payment yourself" rather than "I've stopped at the payment page", because the cart's payment
bar was still loading when the run ended; the end-of-run check now waits up to 4 s for it.

Earlier batches failed 2 out of 5 times on the judges' sentence, and each failure showed a real bug:

- **A swipe typed into the search box.** After using Domino's own "Search in…" box the keyboard stays
  up; a scroll swipe that crossed it was read as glide typing ("margherita pizza by by by by").
  Swipes now stay above the keyboard, and the keyboard is closed after the page search.
- **The pizza was already in the cart.** The model saw it (the card shows − 1 +), but the next step
  still waited for the options sheet. The cart check now runs before matching, and an add step
  found already done skips the "Add item" step.
- **A false "done".** The model took "In your Collection" (Zomato's saved-dishes tag) as proof of
  the cart; only a − 1 + stepper or a "1 item added" bar counts now.

Two batches ran on a failing Wi-Fi (Zomato's search suggestions spinning for over a minute, its
"Something went wrong · Try Again" page, model calls timing out). They are not counted above, but
they led to: tapping the app's own "Try Again", waiting longer for suggestions after typing,
skipping a demo pop-up step ("Got it") when the pop-up doesn't recur, and a hard deadline on
every model call (one had hung for 138 s).

**T1 (the learned task is inspectable):** the app lists it as "Order {item} from {restaurant} on
Zomato" with the example values, the 11 steps in words, the ignored step marked "not needed",
and the two follow-ups ("ensure {quantity} of {item}", "set delivery address to {address}").

## Re-check of the other tests on the current build (14:40–14:47 IST)

Same recipe (re-taught at 12:51), cold start, phone on a hotspot.

| Command | Test | Outcome |
|---|---|---|
| Order a Farmhouse pizza from Domino's on Zomato | T4 / T9 new value | Farmhouse added (cart checked afterwards), stopped at payment, 44 s, 0 LLM calls |
| Order two Margherita pizzas from Domino's on Zomato | T5 quantity | empty cart → "+" in the crust sheet, "2" verified, Add item, stopped at payment, 58 s |
| same, with the 2 still in the cart | T5 + T7 stale cart | "already in the cart (2)", nothing added, stopped before checkout, 40 s |
| Order a Margherita pizza from Domino's on Zomato and deliver it to work | T6 address | picked the saved "Work" address first, then (Margherita already in the cart) went straight to checkout, 43 s |
| book a cab to the railway station | T12 unknown task | "I haven't learned that yet. Do you want to teach me?" |
| did the last run succeed? | T14 run log | "Your last run, Order Margherita pizza from Domino's on Zomato, at 2:45 PM, went as far as it safely could and stopped at checkout for you to finish." |

The first attempt at "two Margherita pizzas" went wrong: the offline template matcher read the item
as "two margherita pizzas", so no quantity was set and the cart ended with one. A value that starts
with a number now goes to the language model, which reads `item = Margherita pizza, quantity = 2`.

## T7: the shop can't take the order (14:50–15:00 IST, raining)

With the Domino's recipe, asked for pizza/burgers from other restaurants. It was raining, and every
restaurant that uses Zomato's delivery partners showed "Currently not accepting orders".

| Command | Outcome |
|---|---|
| Order a Margherita pizza from Pizza Hut on Zomato | before the fix: the model dismissed the notice with OK, tapped ADD again, and once tapped "Schedule for later"; failed after 91 s |
| Order a McAloo Tikki burger from McDonald's on Zomato | "I couldn't finish: the app says 'Currently not accepting orders as delivery partners are unavailable due to rain'", 18 s |
| Order a Margherita pizza from La Pino'z on Zomato | same message, 34 s |
| Order a Margherita pizza from Oven Story on Zomato | "…the app says 'Currently not accepting orders'", 28 s |

The executor now recognises these notices ("not accepting orders", "currently closed", "delivery
partners are occupied", "not delivering to…") when the next button can't be found, and stops with
the app's own words instead of dismissing and retrying. The model has the same rule ("fail" with a
reason).

Also added, not yet exercised because no other restaurant was open: a dialog that would throw
away something the user has (Zomato's "replace cart?" when the cart holds another restaurant's
items) makes the assistant ask, e.g. "Your cart has items from Domino's. Replace them with this
order?". "No" leaves the cart as it is and stops.

## What the hand demo broke, and the fixes
- **Wrong slot and a lost ADD.** The ADD button's "card" was a small box holding only "customisable";
  the dish name sat two levels up. The card of a repeated button is now the largest ancestor holding
  no other button like it, so "Margherita Pizza" is in it; slots take the longest command phrase
  shown ("margherita pizza", not "pizza").
- **Needed steps marked as noise.** The model called the restaurant tap a duplicate of the suggestion
  tap and dropped the ADD. It now sees each step's screen and the next one, and a step that carries
  the user's value or opens the screen the demo continues on can't be marked noise.
- **Item 40 flicks down the menu.** The demo's scroll count is kept; far items are found with the
  page's own search (Zomato's "Search in Domino's Pizza"), then the fast matcher.
- **LLM added the wrong item.** Repeated buttons now tell the model which item they belong to
  ("ADD" for "Margherita Pizza"), and the closest name wins.
- **Ignored taps.** Zomato ignores accessibility clicks on its location bar; LLM taps are now real
  finger taps.
- **Goals trusted too early.** Quantity and address goals now need proof on screen (the "2", the
  selected "Work").
- **"…and deliver it to work"** was swallowed into the restaurant name by the template matcher; such
  commands now go to the language model, and values no step uses become goals.
- `REBUILD` re-learns a saved recording with the current rules, so none of this needed a re-teach.
