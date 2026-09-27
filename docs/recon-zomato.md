# Recon: Zomato (Domino's → Margherita → cart)

Captured 27 Sep 2026 on an Oppo Reno3 (CPH2035), ColorOS 12.1 / Android 12 (SDK 31), 1080x2400, with Zomato 19.9.0. That run produced 33 screen captures (uiautomator XML plus screenshot) with no failed dumps. Raw captures stay local because they contain personal addresses.

## Flow as demonstrated
Home → search box (typed "dominos") → suggestion "Domino's Pizza" → restaurant menu (`MenuResCartActivity`) → Margherita ADD → crust sheet ("Required • Select any 1 option", default New Hand Tossed) → "Add item ₹112" → "1 item added · Continue" bar → cart → address picker (`LocationActivity`).

## Findings that shape the design
1. **Trees are rich, but IDs are generic and reused.** The menu screen has 176 nodes, 131 of them with IDs, but IDs such as `title`, `subtitle`, `button`, `dish_name` and `edittext` repeat. Identify elements by ID plus text plus neighbours, never by ID alone.
2. **Some buttons are icon-only.** The quantity +/− controls are icon-font glyphs (`` / ``) with no readable label; only their IDs (`button_add` / `button_remove`) describe them. Keep IDs in recorded steps and in anything sent to the LLM.
3. **Labels embed changing values.** The price in "Add item ₹112" changes per item. Match on the stable part and treat prices and counts as variable.
4. **The flow branches in ways a demo may not show.** ADD opens a required crust sheet. Pressing + on a customised item opens a "Repeat last used customization?" sheet, which matters for the quantity test. The crust sheet has its own quantity stepper next to "Add item", which is the cleanest place to set quantity.
5. **Pop-ups occur naturally.** "Movie voucher unlocked! · Got it" appeared over the cart.
6. **Loading and errors.** Screen transitions show a quip line with shimmer placeholders ("Take a break. Even rockets refuel."), and one run hit "Something went wrong. Please try again · Try again". Replay must wait for the screen to settle and must recognise error screens.
7. **Search hint text rotates** ('Search "tacos"', 'Search "bread"'). Screen fingerprints must ignore hint text.
8. **Payment boundary.** Without a saved payment method, the cart's main button reads "Add Payment Method". With a saved method it reads "Pay ₹…" or "Place order"; the guard must catch all three.
9. **Invisible and background nodes.** The tree contains nodes with `[0,0][0,0]` bounds and views from the screen underneath (the menu behind the cart). Filter by visibility and window.
10. **Addresses for the Work test.** The address picker lists saved addresses by label, so the test account needs a Home and a Work address that the restaurant delivers to.

## Amazon (same session)
- Search results and the cart are web views. They have few IDs but descriptive labels: "Increase quantity by one, Quantity is 1, <product>", "Delete <product>", "Proceed to checkout".
- "First result" is ambiguous: filter chips, "Search for …" suggestion tiles, a sponsored video and sponsored products come before the organic "Results" section.
- The cart's main button reads "Proceed to Buy (N items)", which marks the payment boundary.
- ColorOS warns "USB data transfer has been allowed. Please switch to 'Charge only' before using payment apps" when Amazon opens with USB in data mode. Use Charge only (adb still works), and check that ColorOS Payment Protection doesn't flag the accessibility service.
