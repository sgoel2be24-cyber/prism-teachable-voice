# Replay across sessions — Amazon (28 Sep 2026, 04:05 IST)

One recipe, taught once by demonstration: *"Search for wireless earbuds on Amazon and add the first
result to cart"* (5 steps). Before every run the bench (`recon/session_bench.sh`) force-stops Amazon
and goes to the home screen, so each run is a cold start in a fresh app session. Commands go through
the same path as a spoken command (command matching → slot filling → replay). Numbers come from the
phone's run log (`recon/session_report.py`).

| # | Command | Kind | Outcome | Time | LLM calls | Step methods |
|---|---|---|---|---|---|---|
| 1 | Search for wireless earbuds on Amazon and add the first result to cart | exact | success | 25.5 s | 1 | launch, match×2, type, llm |
| 2 | search for a mouse pad on amazon and add the first result to cart | new value | success | 27.9 s | 0 | launch, match×2, type, scroll+match |
| 3 | search for a water bottle on amazon and add the first result to cart | new value | success | 18.1 s | 0 | launch, match×2, type, scroll+match |
| 4 | put a notebook in my amazon cart | paraphrase + new value | success | 23.9 s | 0 | launch, match×2, type, scroll+match |
| 5 | amazon pe pencil box search karke pehla result cart mein daal do | Hinglish + new value | success | 20.1 s | 0 | launch, match×2, type, scroll+match |
| 6 | find a phone stand on amazon and add the top result to my cart | paraphrase + new value | success | 23.7 s | 0 | launch, match×2, type, scroll+match |
| 7 | Search for wireless earbuds on Amazon and add the first result to cart | exact (repeat) | success | 16.0 s | 0 | launch, match×2, type, scroll+match |
| 8 | i need a desk lamp, add the first one on amazon to my cart | paraphrase + new value | success | 18.3 s | 0 | launch, match×2, type, scroll+match |

**Success 8/8. Median 21.9 s per run. Median LLM calls per run: 0 (39 of 40 steps replayed by the
on-device matcher; the one LLM step opened the product page when the results list had no visible
"Add to cart").** Command matching adds about 1 s (one LLM call, not counted above).

## What the bench caught first
An earlier pass the same night went 5/6 and then 0/3: Amazon had started showing a sponsored banner
above the results, pushing the first result's "Add to cart" below the fold. The executor went
straight to the LLM, which opened the product page and ran out of time scrolling to its "Add to
cart". Fix: up to three quick scrolls with the fast matcher before asking the LLM (`scroll+match`
above), plus shorter waits on pages that never go quiet. After the fix: 8/8.

## Learn success — teach once, replay once (28 Sep, 08:45–09:01 IST)
Each row is a fresh demonstration (scripted adb taps standing in for the finger, `recon/learn_bench.sh`
and `recon/teach_amazon_noise.sh`), then one cold-start replay of the new recipe with a different value.

| Taught with | Noise in the demo | Learned | Replayed with | Outcome | Time | LLM calls |
|---|---|---|---|---|---|---|
| "Search for a steel water bottle on Amazon and add the first result to cart" | switched to Calculator, tapped, came back with the back gesture | 5 steps; Calculator tap and back dropped as noise | glass water bottle | success | 29.3 s | 0 |
| "Search for a yoga mat on Amazon and add the first result to cart" | — | 5 steps, slot `product` | dumbbells | success | 30.2 s | 0 |
| "Find a coffee mug on Amazon and put the first one in my cart" | — | 5 steps, slot `product` | tea kettle | success | 23.7 s | 0 |
| "Add the first shaving razor from Amazon search to my cart" | — | 5 steps, slot `product` | beard trimmer | success | 11.3 s | 0 |

**4/4 demonstrations learned a working, parameterised task on the first try.** Two fixes came out of
this pass: a back gesture used to leave another app is now dropped with that detour (it used to be
kept as a step), and a results page that is still blank (slow network) is waited for instead of
being scrolled or handed to the LLM.

## Cross-app (bonus) — same recipe run in Myntra
Taught on Amazon only. The LLM drives each step in Myntra, and the Guard still applies.

| Command | Outcome | Time | LLM calls | Notes |
|---|---|---|---|---|
| search for a laptop backpack on myntra and add the first result to my bag | success | 54 s | 15 | one-size item |
| search for sneakers on myntra and add the first result to my bag | success | 52 s | 13 | asked "Which size?", answered "9"; verified in the bag |
| search for trekking shoes on myntra and add the first result to my bag | success | 64 s | 14 | asked size; scrolled the size row to find 9 |

## Regression after the Zomato reliability changes (28 Sep, 14:24–14:34 IST)

Same recipe, same cold-start method (`recon/repeat_bench.sh`), phone on a phone hotspot.

| Command | Runs | Result |
|---|---|---|
| Search for wireless earbuds on Amazon and add the first result to cart | 3 | 2 passed (45–48 s, 1–4 LLM calls); 1 failed |
| i need a desk lamp, add the first one on amazon to my cart | 2 | 2 passed, 36–37 s, 0 LLM calls |
| put a notebook in my amazon cart | 1 | passed, 37 s, 0 LLM calls |
| amazon pe pencil box search karke pehla result cart mein daal do | 1 | passed, 35 s, 0 LLM calls |
| find a phone stand on amazon and add the top result to my cart | 1 | passed, 29 s, 0 LLM calls |

**7/8.** For earbuds the first "Add to cart" now sits below several sponsored rows, so the fast
path gives up and the model scrolls on. In the failed run the model tapped the first product's
title instead; that added the item (Amazon's "added to cart" page, cart count up) but the model
didn't recognise that page as proof and ran out of time. Runs now end with "I've stopped before
checkout" when a pay/checkout button is on screen.

## T8/T9: Amazon taught by hand (28 Sep, 15:57–16:14 IST)

Shikhar taught "search for wireless earbuds on Amazon and add the first result to cart" by hand.
The app confirmed: "Learned: Add wireless earbuds to cart on Amazon. 8 steps."

**What went wrong first, and the fixes**
- *The old, script-taught Amazon task was used.* The phone heard "add the first result to card",
  so the offline template missed and the model picked the older of two identical tasks. A task
  taught again (same app, same command shape) now replaces the older one for matching.
- *Two taps recorded with no target.* Amazon's results page nests a WebView that reports a 42-px-high
  box around the whole page, so the tap on "Add to cart" fell "outside" it and hit-testing found
  nothing. Hit-testing now looks inside web views regardless of their own box. The recording was
  re-learned from its saved screens (`REBUILD`): "Tap 'Add to cart' for the first result", the
  repeat tap marked as not needed, then the product page's own add button, and the
  protection-plan pop-up marked as not needed.
- *The product page's add button isn't always there.* For some results "Add to cart" opens the
  product page (to pick a colour/variant); for others it adds straight away. If the cart count
  went up after the first add, the second add step is skipped ("the item went straight into the
  cart (20 → 21)").
- *Five scrolls on a web page used up the model's time* before it looked once; the model now always
  gets a first look and ~15 s of its own.

| Command | Test | Outcome |
|---|---|---|
| Search for a phone case on Amazon and add the first result to cart | T9 | added from the results page (cart 20 → 21), 46 s, 0 LLM calls |
| Search for wireless earbuds on Amazon and add the first result to cart | T8 replay | results "Add to cart" → product page → its add button → stopped before checkout, 35 s, 0 LLM calls |
| Order a Margherita pizza from Domino's on Zomato | regression | reached checkout, 40 s, 0 LLM calls |

## Skipping sponsored results (28 Sep, 17:15–17:49 IST)

"Add the first result" used to take Amazon's first "Add to cart", which is almost always a
sponsored slot (Amazon's own links for these carry `sr_1_1_sspa`, `sr_1_2_sspa`). It now means the
first result that isn't an advert:

- In a generic list pick (repeated buttons, an "Add to cart", or a "first/top result" step) a
  result whose card says "Sponsored", "Sponsored Ad", "Ad" or "Promoted", or links with `_sspa`,
  is skipped. The card's "Sponsored" line is often already scrolled off the top, so hidden parts of
  the card count too.
- A container that only scores through an ad button (the page itself) is dropped as well; once it
  was tapped in the middle and opened a sponsored product.
- While only adverts are on screen, it keeps scrolling with the fast matcher (up to 10 screens)
  instead of handing a page of ads to the model; the model has the same rule.
- Named picks are left alone: Zomato marks Domino's itself as an ad in search results, and
  "Domino's" is what the user asked for.
- If the model ever taps an "Add to cart" that raises the cart count, the step ends there (it once
  added three products in one run before noticing).

| Command | Picked (Amazon's own position) | Result |
|---|---|---|
| wireless earbuds ×4 | `sr_1_3` Sony WF-C510 (first organic, after two ads) | 4/4, 48–57 s, 0 LLM calls in the last 3 |
| phone case ×3 | `sr_1_3` Meyaar grip/case mount (first organic) | 3/3, 39–48 s, 0 LLM calls |
| water bottle ×2 | `sr_1_4` Milton Torino 1000 (first organic, after three ads) | 2/2, 36–38 s |
| Zomato judges' sentence ×2 (regression) | — | 2/2 to payment, 40–45 s, 0 LLM calls |
