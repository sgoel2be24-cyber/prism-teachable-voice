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
