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

## Cross-app (bonus) — same recipe run in Myntra
Taught on Amazon only. The LLM drives each step in Myntra, and the Guard still applies.

| Command | Outcome | Time | LLM calls | Notes |
|---|---|---|---|---|
| search for a laptop backpack on myntra and add the first result to my bag | success | 54 s | 15 | one-size item |
| search for sneakers on myntra and add the first result to my bag | success | 52 s | 13 | asked "Which size?", answered "9"; verified in the bag |
| search for trekking shoes on myntra and add the first result to my bag | success | 64 s | 14 | asked size; scrolled the size row to find 9 |
