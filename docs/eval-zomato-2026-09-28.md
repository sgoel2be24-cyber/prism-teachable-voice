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
