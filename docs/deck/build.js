// Deck: TIET_Update_Submission_ppt.pptx — Samsung PRISM GenAI Hackathon 2026, Theme 3
const pptxgen = require("pptxgenjs");
const React = require("react");
const RDS = require("react-dom/server");
const sharp = require("sharp");
const md = require("react-icons/md");
const path = require("path");

// Team members, one per entry: "Name|year, branch"
const MEMBERS = (process.env.MEMBERS || "Shikhar Goel|3rd year, Computer Engineering;Ishpreet Singh|3rd year, Computer Engineering")
  .split(";").map((m) => m.split("|")).filter((m) => m[0]);
const OUT = process.env.OUT || "TIET_Update_Submission_ppt.pptx";

// Palette: ink dominates, coral is the one sharp accent, teal means "verified".
const INK = "121833", INK2 = "222B55", CORAL = "FF5B3A", TEAL = "0E9F8A";
const TEXT = "1A1F36", MUTED = "5C637A", LINE = "E2E5EE", TINT = "F4F5FA", WHITE = "FFFFFF", ICE = "C8CDE6";
const HEAD = "Arial", BODY = "Calibri";
const W = 13.333, H = 7.5, M = 0.6;

const img = (f) => path.join(__dirname, "img", f);
const shadow = () => ({ type: "outer", color: "000000", blur: 10, offset: 3, angle: 90, opacity: 0.18 });

async function icon(name, color, size = 256) {
  const svg = RDS.renderToStaticMarkup(React.createElement(md[name], { color: "#" + color, size }));
  const buf = await sharp(Buffer.from(svg)).resize(size, size).png().toBuffer();
  return "image/png;base64," + buf.toString("base64");
}

(async () => {
  const pres = new pptxgen();
  pres.layout = "LAYOUT_WIDE";
  pres.title = "Teachable Voice Automation — Team Update, TIET";
  pres.author = "Team Update (TIET)";

  const I = {};
  const need = [
    ["MdMic", WHITE], ["MdTouchApp", WHITE], ["MdAccountTree", WHITE], ["MdAutoFixHigh", WHITE], ["MdTextFields", WHITE],
    ["MdReplay", WHITE], ["MdVerifiedUser", WHITE], ["MdBlock", WHITE], ["MdHelpOutline", WHITE], ["MdBolt", WHITE],
    ["MdCleaningServices", WHITE], ["MdApps", WHITE], ["MdMemory", WHITE], ["MdShare", WHITE], ["MdSchedule", WHITE],
    ["MdTranslate", WHITE], ["MdCheckCircle", TEAL], ["MdBlock", CORAL], ["MdPanTool", WHITE], ["MdStorefront", WHITE],
    ["MdRecordVoiceOver", WHITE], ["MdWifiOff", WHITE], ["MdScreenLockPortrait", WHITE], ["MdInstallMobile", WHITE],
    ["MdPhoneAndroid", WHITE], ["MdSpeed", WHITE], ["MdCode", WHITE], ["MdScience", WHITE], ["MdCloudQueue", WHITE],
    ["MdMic", CORAL], ["MdCheckCircle", WHITE], ["MdLanguage", WHITE], ["MdRestaurantMenu", WHITE],
  ];
  for (const [n, c] of need) I[n + "_" + c] = await icon(n, c);

  // ---------- helpers ----------
  let page = 0;
  function footer(slide, dark = false) {
    page++;
    slide.addText(`Teachable Voice Automation · Theme 3 · Team Update, TIET`, {
      x: M, y: H - 0.42, w: 8, h: 0.3, fontFace: BODY, fontSize: 10, color: dark ? "8A91B4" : MUTED, margin: 0, isTextBox: true,
    });
    slide.addText(String(page), {
      x: W - M - 1, y: H - 0.42, w: 1, h: 0.3, fontFace: BODY, fontSize: 10, color: dark ? "8A91B4" : MUTED, align: "right", margin: 0, isTextBox: true,
    });
  }
  function heading(slide, kicker, title, dark = false) {
    slide.addText(kicker.toUpperCase(), {
      x: M, y: 0.38, w: 10, h: 0.3, fontFace: HEAD, fontSize: 12, bold: true, color: CORAL, charSpacing: 3, margin: 0, isTextBox: true,
    });
    slide.addText(title, {
      x: M, y: 0.7, w: W - 2 * M, h: 0.75, fontFace: HEAD, fontSize: 30, bold: true, color: dark ? WHITE : TEXT, margin: 0, valign: "top", isTextBox: true,
    });
  }
  function circleIcon(slide, key, x, y, d, fill) {
    slide.addShape(pres.shapes.OVAL, { x, y, w: d, h: d, fill: { color: fill }, line: { color: fill } });
    const p = d * 0.22;
    slide.addImage({ data: I[key], x: x + p, y: y + p, w: d - 2 * p, h: d - 2 * p });
  }
  // The motif: a spoken command, as a pill with a mic.
  function voice(slide, text, x, y, w, opts = {}) {
    const h = opts.h || 0.5, dark = opts.dark !== false;
    slide.addShape(pres.shapes.ROUNDED_RECTANGLE, {
      x, y, w, h, rectRadius: h / 2, fill: { color: dark ? INK : WHITE }, line: { color: dark ? INK : LINE, width: 1 },
      shadow: opts.shadow ? shadow() : undefined,
    });
    const d = h - 0.14;
    slide.addShape(pres.shapes.OVAL, { x: x + 0.07, y: y + 0.07, w: d, h: d, fill: { color: CORAL }, line: { color: CORAL } });
    slide.addImage({ data: I["MdMic_" + WHITE], x: x + 0.07 + d * 0.2, y: y + 0.07 + d * 0.2, w: d * 0.6, h: d * 0.6 });
    slide.addText(text, {
      x: x + d + 0.2, y, w: w - d - 0.3, h, fontFace: BODY, fontSize: opts.size || 14, italic: true,
      color: dark ? WHITE : TEXT, valign: "middle", margin: 0, fit: "shrink", isTextBox: true,
    });
  }
  function phone(slide, file, x, y, h) {
    const w = h * 600 / 1088, b = 0.07;
    slide.addShape(pres.shapes.ROUNDED_RECTANGLE, {
      x: x - b, y: y - b, w: w + 2 * b, h: h + 2 * b, rectRadius: 0.22, fill: { color: INK }, line: { color: INK }, shadow: shadow(),
    });
    slide.addImage({ path: img(file), x, y, w, h });
    return w;
  }
  function card(slide, x, y, w, h, fill = WHITE, line = LINE) {
    slide.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w, h, rectRadius: 0.12, fill: { color: fill }, line: { color: line, width: 1 } });
  }

  // ---------- 1. Title ----------
  {
    const s = pres.addSlide();
    s.background = { color: INK };
    s.addText("SAMSUNG PRISM GENAI HACKATHON 2026  ·  THEME 3", {
      x: M, y: 0.7, w: 8, h: 0.35, fontFace: HEAD, fontSize: 13, bold: true, color: CORAL, charSpacing: 3, margin: 0, isTextBox: true,
    });
    s.addText("Teachable Voice\nAutomation", {
      x: M, y: 1.2, w: 8.2, h: 1.65, fontFace: HEAD, fontSize: 46, bold: true, color: WHITE, margin: 0, valign: "top", isTextBox: true,
    });
    s.addText("An Android assistant you teach by doing. Show it a task once, then just say it — with new values, new wording, even in another app.", {
      x: M, y: 2.95, w: 7.6, h: 1.0, fontFace: BODY, fontSize: 20, color: ICE, margin: 0, valign: "top", isTextBox: true,
    });
    voice(s, "“Order a Margherita pizza from Domino’s on Zomato”", M, 4.05, 6.4, { dark: false, h: 0.6, size: 16 });
    s.addText([
      { text: "Team Update", options: { bold: true, color: WHITE, fontSize: 18, breakLine: true } },
      { text: "Thapar Institute of Engineering and Technology (TIET), Patiala", options: { color: ICE, fontSize: 14 } },
    ], { x: M, y: 4.9, w: 7.6, h: 0.7, fontFace: BODY, margin: 0, valign: "top", paraSpaceAfter: 2, isTextBox: true });
    MEMBERS.forEach(([name, what], i) => {
      const x = M + i * 3.7;
      s.addText([
        { text: name, options: { bold: true, color: WHITE, fontSize: 15, breakLine: true } },
        { text: what || "", options: { color: ICE, fontSize: 12.5 } },
      ], { x, y: 5.65, w: 3.5, h: 0.62, fontFace: BODY, margin: 0, valign: "top", isTextBox: true });
    });
    s.addText("Zomato · Amazon · Myntra   |   Accessibility Service only   |   Stops at payment and login", {
      x: M, y: 6.5, w: 8, h: 0.35, fontFace: BODY, fontSize: 12, color: "8A91B4", margin: 0, isTextBox: true,
    });
    phone(s, "learned.png", 9.35, 0.75, 6.0);
    s.addNotes("Theme 3, Teachable Voice Automation. Team Update from TIET: Shikhar Goel and Ishpreet Singh, third-year Computer Engineering. One sentence: you teach the phone a task by doing it once while it listens and watches; after that you just say it, with different values or wording, and it does it through the accessibility service, stopping before payment. The phone shows the task it learned from one Zomato demo.");
    footer(s, true);
  }

  // ---------- 2. Problem ----------
  {
    const s = pres.addSlide();
    s.background = { color: WHITE };
    heading(s, "The problem, in our words", "Phones can do almost anything — but only by hand");
    s.addText("Voice assistants stop where an app’s own integrations stop. Ordering a pizza or adding something to a cart still takes ten or more taps, every time.", {
      x: M, y: 1.75, w: 4.9, h: 1.6, fontFace: BODY, fontSize: 20, color: TEXT, margin: 0, valign: "top", isTextBox: true,
    });
    s.addText("What a real assistant has to do", {
      x: M, y: 3.55, w: 4.9, h: 0.4, fontFace: HEAD, fontSize: 15, bold: true, color: MUTED, margin: 0, isTextBox: true,
    });
    s.addText([
      { text: "Learn a new task from the user, not from a developer", options: { bullet: true, breakLine: true } },
      { text: "Understand the same task said differently, with new values", options: { bullet: true, breakLine: true } },
      { text: "Notice when the screen changed, and ask when it can’t decide", options: { bullet: true, breakLine: true } },
      { text: "Never pay, place an order or type a password", options: { bullet: true } },
    ], { x: M, y: 3.95, w: 4.9, h: 2.4, fontFace: BODY, fontSize: 15, color: TEXT, margin: 0, paraSpaceAfter: 8, valign: "top", isTextBox: true });
    const rows = [
      ["MdStorefront_" + WHITE, INK, "Every app is its own world", "Assistants act only through the few actions an app exposes. The rest of the app — search, menus, carts, sheets — is out of reach."],
      ["MdReplay_" + WHITE, CORAL, "Recorded taps are brittle", "Replaying coordinates breaks the moment the item, the wording or the screen changes: a pop-up, a sponsored result, another restaurant."],
      ["MdBlock_" + WHITE, TEAL, "Money and logins stay with the user", "Automation that can reach a payment button or an OTP screen must stop there and hand control back, every single time."],
    ];
    rows.forEach(([ic, col, t, d], i) => {
      const y = 1.75 + i * 1.62;
      card(s, 6.1, y, 6.63, 1.42, TINT, TINT);
      circleIcon(s, ic, 6.35, y + 0.33, 0.76, col);
      s.addText(t, { x: 7.35, y: y + 0.18, w: 5.2, h: 0.4, fontFace: HEAD, fontSize: 16, bold: true, color: TEXT, margin: 0, isTextBox: true });
      s.addText(d, { x: 7.35, y: y + 0.58, w: 5.2, h: 0.75, fontFace: BODY, fontSize: 13.5, color: MUTED, margin: 0, valign: "top", isTextBox: true });
    });
    s.addNotes("Problem in our words: assistants only reach what apps expose; macros that replay coordinates break on the smallest change; and anything that can reach a pay button has to stop. So the assistant must learn from the user, generalise, notice change, ask, and stay out of payments and logins.");
    footer(s);
  }

  // ---------- 3. Solution flow ----------
  {
    const s = pres.addSlide();
    s.background = { color: WHITE };
    heading(s, "What we built", "Teach once by doing. Run it by voice.");
    const steps = [
      ["1", "Teach", "Say the command, then do the task once. A tap-capture overlay records every tap, typed text, search and back."],
      ["2", "Generalise", "Words from the command become slots: “ADD next to {item}”, “search {product}”. Stray taps are marked as noise."],
      ["3", "Replay by voice", "New wording, new values, Hinglish, another app. Screens it knows replay with no language-model call."],
      ["4", "Ask or hand back", "A missing value or an unexpected screen gets one clear question. Payment, OTP or login: “Your turn.”"],
    ];
    const cw = 2.83, gap = 0.3, y = 1.8;
    steps.forEach(([n, t, d], i) => {
      const x = M + i * (cw + gap);
      card(s, x, y, cw, 2.75, TINT, TINT);
      s.addShape(pres.shapes.OVAL, { x: x + 0.25, y: y + 0.25, w: 0.55, h: 0.55, fill: { color: i === 3 ? CORAL : INK }, line: { color: i === 3 ? CORAL : INK } });
      s.addText(n, { x: x + 0.25, y: y + 0.25, w: 0.55, h: 0.55, fontFace: HEAD, fontSize: 18, bold: true, color: WHITE, align: "center", valign: "middle", margin: 0, isTextBox: true });
      s.addText(t, { x: x + 0.25, y: y + 0.95, w: cw - 0.45, h: 0.45, fontFace: HEAD, fontSize: 18, bold: true, color: TEXT, valign: "middle", margin: 0, isTextBox: true });
      s.addText(d, { x: x + 0.25, y: y + 1.45, w: cw - 0.45, h: 1.2, fontFace: BODY, fontSize: 14, color: MUTED, valign: "top", margin: 0, isTextBox: true });
      if (i < 3) s.addShape(pres.shapes.LINE, { x: x + cw + 0.04, y: y + 0.52, w: gap - 0.08, h: 0, line: { color: MUTED, width: 1.5, endArrowType: "triangle" } });
    });
    s.addText("Taught once, then said like this:", { x: M, y: 4.85, w: 6, h: 0.35, fontFace: HEAD, fontSize: 14, bold: true, color: MUTED, margin: 0, isTextBox: true });
    voice(s, "“Get me a margherita from dominos”", M, 5.3, 3.45, { size: 12.5 });
    voice(s, "“amazon pe pencil box search karke pehla result cart mein daal do”", M + 3.65, 5.3, 4.4, { size: 12.5 });
    voice(s, "“search for a wallet on myntra and add the first result to my bag”", M + 8.25, 5.3, 3.88, { size: 12.5 });
    s.addText("Everything on screen is read and tapped through Android’s Accessibility Service — no app APIs, no deep links, no hard-coded flows.", {
      x: M, y: 6.2, w: W - 2 * M, h: 0.4, fontFace: BODY, fontSize: 13, color: TEXT, margin: 0, isTextBox: true,
    });
    s.addNotes("Four stages. Teach: the user speaks the command and performs it once; our overlay captures every tap even on screens that report nothing. Generalise: command words that show up in the demo become slots. Replay: paraphrases, new values, Hinglish, even another app. Ask or hand back: one specific question when something is missing, and a hard stop before payment or login. The three commands at the bottom are real runs.");
    footer(s);
  }

  // ---------- 4. Screens ----------
  {
    const s = pres.addSlide();
    s.background = { color: TINT };
    heading(s, "What the user sees", "Four moments from real runs on the phone");
    const shots = [
      ["teach.png", "Teaching", "A red border and “Done” while the user shows the task."],
      ["learned.png", "What it learned", "Steps in words, slots in braces, the stray tap marked “not needed”."],
      ["ask.png", "A missing value", "“Which product should I use?” — asked at the step that needs it."],
      ["size_q.png", "A personal choice", "It asks for the size and lists what’s available. It never picks for you."],
    ];
    const h = 4.25, pw = h * 600 / 1088, gap = (W - 2 * M - 4 * pw) / 3;
    shots.forEach(([f, t, d], i) => {
      const x = M + i * (pw + gap) + 0.07;
      phone(s, f, x, 1.65, h);
      s.addText(t, { x: x - 0.1, y: 6.02, w: pw + 0.4, h: 0.32, fontFace: HEAD, fontSize: 14, bold: true, color: TEXT, margin: 0, isTextBox: true });
      s.addText(d, { x: x - 0.1, y: 6.34, w: pw + 0.4, h: 0.62, fontFace: BODY, fontSize: 12, color: MUTED, margin: 0, valign: "top", isTextBox: true });
    });
    s.addNotes("Left to right: the teaching border with Done and Cancel; the learned Zomato task, in words, with its slots and the Veg filter marked not needed; a missing value asked mid-run; and on Myntra, a size question listing the sizes on offer.");
    footer(s);
  }

  // ---------- 5. Architecture ----------
  {
    const s = pres.addSlide();
    s.background = { color: WHITE };
    heading(s, "Architecture", "All on the phone, except the language-model calls");
    // phone boundary
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: M, y: 1.65, w: W - 2 * M, h: 4.25, rectRadius: 0.15, fill: { color: TINT }, line: { color: LINE, width: 1 } });
    s.addText("ON THE PHONE  ·  Android Accessibility Service", { x: M + 0.25, y: 1.78, w: 6, h: 0.3, fontFace: HEAD, fontSize: 11, bold: true, color: MUTED, charSpacing: 2, margin: 0, isTextBox: true });
    const bw = 1.72, bh = 0.95, g = 0.32, x0 = M + 0.3;
    function lane(label, y, boxes) {
      s.addText(label, { x: x0, y: y - 0.36, w: 3, h: 0.3, fontFace: HEAD, fontSize: 13, bold: true, color: TEXT, margin: 0, isTextBox: true });
      boxes.forEach(([t, sub, llm, fill], i) => {
        const x = x0 + i * (bw + g);
        s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x, y, w: bw, h: bh, rectRadius: 0.08, fill: { color: fill || WHITE }, line: { color: fill ? fill : "C9CEDD", width: 1 } });
        s.addText([
          { text: t, options: { bold: true, fontSize: 12.5, color: fill ? WHITE : TEXT, breakLine: true } },
          { text: sub, options: { fontSize: 10, color: fill ? ICE : MUTED } },
        ], { x: x + 0.08, y: y + 0.05, w: bw - 0.16, h: bh - 0.1, fontFace: BODY, align: "center", valign: "middle", margin: 0, isTextBox: true });
        if (llm) {
          s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: x + bw - 0.5, y: y - 0.13, w: 0.46, h: 0.24, rectRadius: 0.12, fill: { color: CORAL }, line: { color: CORAL } });
          s.addText("LLM", { x: x + bw - 0.5, y: y - 0.13, w: 0.46, h: 0.24, fontFace: HEAD, fontSize: 8.5, bold: true, color: WHITE, align: "center", valign: "middle", margin: 0, isTextBox: true });
        }
        if (i < boxes.length - 1) {
          s.addShape(pres.shapes.LINE, { x: x + bw + 0.03, y: y + bh / 2, w: g - 0.06, h: 0, line: { color: MUTED, width: 1.25, endArrowType: "triangle" } });
        }
      });
    }
    lane("Teach once", 2.55, [
      ["Voice command", "phone’s speech recogniser"],
      ["Recorder", "tap-capture overlay + UI-tree snapshot per tap"],
      ["Generaliser", "slots from command words, card anchors"],
      ["Refine", "slot names, step intents, noise, goals", true],
      ["User review", "steps shown in words; delete / re-teach"],
      ["Recipe store", "JSON on the phone", false, INK],
    ]);
    lane("Run on command", 4.55, [
      ["Voice command", "or typed"],
      ["Matcher", "exact / template offline, else LLM → flow + slots + app", true],
      ["Executor", "fast path → scroll / search → LLM action → ask", true],
      ["Checks", "cart count, the right dish, not an advert"],
      ["Guard", "payment, login, OTP, password, closed shop"],
      ["Done / hand back", "spoken reply + run log", false, CORAL],
    ]);
    // recipe store feeds executor
    const xs = x0 + 5 * (bw + g) + bw / 2, xe = x0 + 2 * (bw + g) + bw / 2;
    s.addShape(pres.shapes.LINE, { x: xs, y: 2.55 + bh, w: 0, h: 0.5, line: { color: INK, width: 1.25, dashType: "dash" } });
    s.addShape(pres.shapes.LINE, { x: xe, y: 2.55 + bh + 0.5, w: xs - xe, h: 0, line: { color: INK, width: 1.25, dashType: "dash" } });
    s.addShape(pres.shapes.LINE, { x: xe, y: 2.55 + bh + 0.5, w: 0, h: 0.5 - 0.02, line: { color: INK, width: 1.25, dashType: "dash", endArrowType: "triangle" } });
    // cloud
    s.addShape(pres.shapes.ROUNDED_RECTANGLE, { x: M, y: 6.1, w: W - 2 * M, h: 0.62, rectRadius: 0.1, fill: { color: WHITE }, line: { color: CORAL, width: 1.25, dashType: "dash" } });
    circleIcon(s, "MdCloudQueue_" + WHITE, M + 0.15, 6.17, 0.48, CORAL);
    s.addText([
      { text: "Fireworks AI · gpt-oss-120b  ", options: { bold: true, color: TEXT } },
      { text: "— the only network call: refine a demo once, understand a paraphrase, choose one action on an unfamiliar screen. About 1–2 s each.", options: { color: MUTED } },
    ], { x: M + 0.8, y: 6.1, w: W - 2 * M - 1, h: 0.62, fontFace: BODY, fontSize: 13, valign: "middle", margin: 0, isTextBox: true });
    s.addNotes("Two pipelines on the phone. Teach: voice command, the recorder with our tap-capture overlay and a UI-tree snapshot per tap, the generaliser that finds slots, one LLM call to refine, a review screen, and a JSON recipe. Run: the matcher, offline first; the executor that escalates from a scored fast path to scrolling, page search and only then an LLM action or a question; checks that demand proof; and the guard. The coral tags mark where the LLM is used; it's the only network call.");
    footer(s);
  }

  // ---------- 6. How it works ----------
  {
    const s = pres.addSlide();
    s.background = { color: WHITE };
    heading(s, "How it works", "Speech to intent, UI capture, generalisation, slots, replay");
    const rows = [
      ["MdRecordVoiceOver_" + WHITE, INK, "Speech → intent", "The phone’s recogniser (English, India) gives text. Exact and template matches run offline in milliseconds; paraphrases, Hinglish, missing values and “did the last run succeed?” go to the LLM, which returns flow, slot values, app and confidence as JSON."],
      ["MdAccountTree_" + WHITE, INK, "UI-tree capture", "Just before each demo tap reaches the app, every window’s accessibility tree is snapshotted and the touch is hit-tested the way Android dispatches it — so taps are captured on Jetpack Compose screens and web views that report none."],
      ["MdAutoFixHigh_" + WHITE, INK, "Generalisation", "Each step keeps the element, the card it sits in (“ADD next to Margherita”), its position and a plain-language intent. Mis-taps, an answered call and a filter the task didn’t need are marked as noise."],
      ["MdTextFields_" + WHITE, INK, "Slot extraction", "A command word that reappears in typed text, a tapped label or the tapped card becomes a slot. The LLM adds goals the demo didn’t show — quantity, delivery address — and where to apply them."],
      ["MdReplay_" + WHITE, CORAL, "Replay", "A scored matcher finds each step’s element on the live screen by label, id, card and position. Only when nothing is clearly ahead does the LLM choose one action; every step needs proof on screen before the next."],
    ];
    rows.forEach(([ic, col, t, d], i) => {
      const y = 1.68 + i * 1.0;
      circleIcon(s, ic, M, y + 0.08, 0.62, col);
      s.addText(t, { x: M + 0.85, y: y + 0.1, w: 2.35, h: 0.5, fontFace: HEAD, fontSize: 15, bold: true, color: TEXT, valign: "middle", margin: 0, isTextBox: true });
      s.addText(d, { x: M + 3.25, y: y, w: W - 2 * M - 3.25, h: 0.86, fontFace: BODY, fontSize: 13, color: MUTED, valign: "middle", margin: 0, isTextBox: true });
      if (i < 4) s.addShape(pres.shapes.LINE, { x: M + 0.85, y: y + 0.95, w: W - 2 * M - 0.85, h: 0, line: { color: LINE, width: 0.75 } });
    });
    s.addNotes("These are the five things the theme asks us to explain. The key idea in capture is that we don't trust accessibility click events, which many modern screens never send; we intercept the touch ourselves and hit-test it against the UI tree. Slots follow the SUGILITE idea: a word from the command that reappears in the demo is a parameter.");
    footer(s);
  }

  // ---------- 7. Replay ladder ----------
  {
    const s = pres.addSlide();
    s.background = { color: WHITE };
    heading(s, "Replay engine", "Cheapest step first; the language model only when needed");
    const rungs = [
      ["Fast path", "scored match, no network"],
      ["Scroll, search the page", "thumb-style swipes; the menu’s own search"],
      ["Open the first real result", "in another app; skips Sponsored / AD"],
      ["LLM picks one action", "dismiss a pop-up, pick an outlet"],
      ["Ask one question", "“Which restaurant?” “Which size?”"],
      ["Hand back", "“…before paying. Your turn.”"],
    ];
    const bx = M, bw = 1.2, g = 0.08, base = 6.25;
    rungs.forEach(([t, d], i) => {
      const x = bx + i * (bw + g), h = 1.0 + i * 0.52;
      const fill = i === 0 ? TEAL : i >= 4 ? CORAL : i === 3 ? INK2 : INK;
      s.addShape(pres.shapes.RECTANGLE, { x, y: base - h, w: bw, h, fill: { color: fill }, line: { color: fill } });
      s.addText(String(i + 1), { x, y: base - h + 0.08, w: bw, h: 0.4, fontFace: HEAD, fontSize: 18, bold: true, color: WHITE, align: "center", margin: 0, isTextBox: true });
      s.addText([
        { text: t, options: { bold: true, fontSize: 11.5, color: TEXT, breakLine: true } },
        { text: d, options: { fontSize: 10, color: MUTED } },
      ], { x: x - 0.02, y: base - h - 1.0, w: bw + 0.04, h: 0.95, fontFace: BODY, align: "center", valign: "bottom", margin: 0, isTextBox: true });
    });
    s.addText("cost and latency  →", { x: bx, y: base + 0.08, w: 6 * (bw + g), h: 0.3, fontFace: BODY, fontSize: 11, italic: true, color: MUTED, align: "right", margin: 0, isTextBox: true });
    const stats = [
      ["0", "LLM calls on the judges’ Zomato sentence, in all 8 runs (31–47 s each)"],
      ["35 / 38", "Zomato steps replayed without the LLM across the first six test commands"],
      ["≤ 5 s", "to stop and say why when the app is signed out (2.8 s) or switched to Hindi (5 s) — no taps"],
    ];
    stats.forEach(([n, d], i) => {
      const y = 1.7 + i * 1.55;
      card(s, 8.55, y, 4.18, 1.35, TINT, TINT);
      s.addText(n, { x: 8.8, y: y + 0.12, w: 3.8, h: 0.62, fontFace: HEAD, fontSize: 34, bold: true, color: i === 2 ? CORAL : TEAL, margin: 0, isTextBox: true });
      s.addText(d, { x: 8.8, y: y + 0.74, w: 3.8, h: 0.55, fontFace: BODY, fontSize: 12.5, color: MUTED, margin: 0, valign: "top", isTextBox: true });
    });
    s.addNotes("Each step climbs this ladder only as far as it must. Most steps end on rung one: a deterministic scored match, no network. The LLM is rung four, and it only ever chooses a single action on the live screen, which we then verify. Rungs five and six are the human in the loop.");
    footer(s);
  }

  // ---------- 8. Proof & safety ----------
  {
    const s = pres.addSlide();
    s.background = { color: WHITE };
    heading(s, "Correct and safe", "Every step needs proof. Money and logins stay with you.");
    card(s, M, 1.7, 4.55, 4.95, TINT, TINT);
    s.addText("Proof before “done”", { x: M + 0.3, y: 1.9, w: 4, h: 0.4, fontFace: HEAD, fontSize: 17, bold: true, color: TEAL, margin: 0, isTextBox: true });
    const proof = [
      "The cart or bag count went up",
      "The item’s card now shows − 1 +",
      "The next step’s button is on screen",
      "The options sheet names the dish asked for",
      "“First result” skips Sponsored / AD cards",
      "One add per run — never a second product",
    ];
    proof.forEach((t, i) => {
      const y = 2.45 + i * 0.67;
      s.addImage({ data: I["MdCheckCircle_" + TEAL], x: M + 0.3, y: y + 0.04, w: 0.32, h: 0.32 });
      s.addText(t, { x: M + 0.75, y, w: 3.7, h: 0.42, fontFace: BODY, fontSize: 14, color: TEXT, valign: "middle", margin: 0, isTextBox: true });
    });
    card(s, M + 4.8, 1.7, 4.55, 4.95, INK, INK);
    s.addText("Always hands back", { x: M + 5.1, y: 1.9, w: 4, h: 0.4, fontFace: HEAD, fontSize: 17, bold: true, color: CORAL, margin: 0, isTextBox: true });
    const guard = [
      ["Pay, Place order, Proceed to buy", "never tapped"],
      ["Login, OTP, password screens", "never touched, even without a field"],
      ["“Not accepting orders”", "stops with the app’s own words"],
      ["Replace or clear a cart", "asks the user first"],
      ["Stuck: signed out, app in Hindi", "says why within 5 s, taps nothing"],
    ];
    guard.forEach(([t, d], i) => {
      const y = 2.45 + i * 0.8;
      s.addImage({ data: I["MdBlock_" + CORAL], x: M + 5.1, y: y + 0.05, w: 0.32, h: 0.32 });
      s.addText([
        { text: t, options: { bold: true, color: WHITE, breakLine: true } },
        { text: d, options: { color: ICE, fontSize: 12.5 } },
      ], { x: M + 5.55, y, w: 3.7, h: 0.7, fontFace: BODY, fontSize: 14, valign: "top", margin: 0, isTextBox: true });
    });
    phone(s, "sheet.png", 10.55, 1.72, 4.1);
    s.addText("Checked before adding: the sheet says “Margherita”.", { x: 10.3, y: 5.98, w: 2.45, h: 0.7, fontFace: BODY, fontSize: 11.5, color: MUTED, margin: 0, valign: "top", isTextBox: true });
    s.addNotes("Left: the executor never takes the model's word that a step is done; it needs evidence on screen. Right: the guard, checked on every action, covers payment, login, OTP and password screens, closed shops, and anything that would throw away the user's cart. The phone shows the check that the options sheet is for the dish that was asked for.");
    footer(s);
  }

  // ---------- 9. Results ----------
  {
    const s = pres.addSlide();
    s.background = { color: INK };
    heading(s, "Results on a real phone", "Measured cold-start runs, not a scripted demo", true);
    const big = [
      ["8 / 8", "Judges’ sentence", "Zomato, stops before paying · 0 LLM calls · 31–47 s"],
      ["14 / 14", "Amazon, taught by hand", "first non-sponsored result · incl. Hinglish and paraphrases"],
      ["7 / 7", "Myntra (Amazon task)", "skipped “AD” tiles · asked the size every time"],
      ["18 / 18", "Commands understood", "Amazon test set · median 1.1 s"],
    ];
    const cw = 2.85, g = 0.21;
    big.forEach(([n, t, d], i) => {
      const x = M + i * (cw + g);
      card(s, x, 1.75, cw, 2.55, INK2, INK2);
      s.addText(n, { x: x + 0.25, y: 1.95, w: cw - 0.4, h: 0.95, fontFace: HEAD, fontSize: 44, bold: true, color: i === 2 ? CORAL : "3FD1B8", margin: 0, isTextBox: true });
      s.addText(t, { x: x + 0.25, y: 2.95, w: cw - 0.4, h: 0.4, fontFace: HEAD, fontSize: 15, bold: true, color: WHITE, margin: 0, isTextBox: true });
      s.addText(d, { x: x + 0.25, y: 3.38, w: cw - 0.4, h: 0.8, fontFace: BODY, fontSize: 12.5, color: ICE, margin: 0, valign: "top", isTextBox: true });
    });
    const more = [
      ["Every judges’ sentence, verbatim", "exact, both paraphrases, Farmhouse, two pizzas, to Work, “Order pizza.”: all handled"],
      ["“Order garlic bread from dominos”", "Classic Stuffed Garlic Bread in the cart — 58 s, no LLM calls"],
      ["Taught on Domino’s, ordered from Pizza Hut", "outlet picker, menu search, the plain Margherita — 63 s"],
      ["Learn from one demo, replay with a new value", "4/4, including a demo interrupted by switching apps"],
    ];
    more.forEach(([t, d], i) => {
      const x = M + (i % 2) * 6.17, y = 4.6 + Math.floor(i / 2) * 0.95;
      s.addImage({ data: I["MdCheckCircle_" + WHITE], x, y: y + 0.05, w: 0.3, h: 0.3 });
      s.addText([
        { text: t, options: { bold: true, color: WHITE, breakLine: true } },
        { text: d, options: { color: ICE, fontSize: 12 } },
      ], { x: x + 0.45, y, w: 5.6, h: 0.8, fontFace: BODY, fontSize: 14, valign: "top", margin: 0, isTextBox: true });
    });
    s.addText("Oppo Reno3, Android 12 · 28–30 Sep · app force-stopped before every run · full tables and method in docs/ of the repository", {
      x: M, y: 6.62, w: W - 2 * M, h: 0.3, fontFace: BODY, fontSize: 11, color: "8A91B4", margin: 0, isTextBox: true,
    });
    s.addNotes("All numbers come from benches that force-stop the app before each run and log every step; the method and every table are in the docs folder. The judges' own sentence reached payment eight times out of eight without a single language-model call. On 30 September we ran every sentence from the theme's test table verbatim, including the rubric's own garlic-bread example, and all were handled.");
    footer(s, true);
  }

  // ---------- 9b. A new app ----------
  {
    const s = pres.addSlide();
    s.background = { color: WHITE };
    heading(s, "No hard-coded flows", "Taught on an app it had never seen");
    card(s, M, 1.7, 5.3, 4.9, TINT, TINT);
    s.addText("TAUGHT ONCE, BY TAPPING THROUGH IT", { x: M + 0.3, y: 1.92, w: 4.8, h: 0.3, fontFace: HEAD, fontSize: 11, bold: true, color: MUTED, charSpacing: 2, margin: 0, isTextBox: true });
    voice(s, "“Open the pytorch repository on GitHub”", M + 0.3, 2.3, 4.7, { h: 0.55, size: 14 });
    s.addText("What it learned", { x: M + 0.3, y: 3.15, w: 4.7, h: 0.35, fontFace: HEAD, fontSize: 13, bold: true, color: MUTED, margin: 0, isTextBox: true });
    s.addText([
      { text: "Open ", options: { color: TEXT } },
      { text: "{repository}", options: { color: CORAL } },
      { text: " repository on GitHub", options: { color: TEXT } },
    ], { x: M + 0.3, y: 3.5, w: 4.8, h: 0.45, fontFace: HEAD, fontSize: 17, bold: true, margin: 0, isTextBox: true });
    const learned = [
      ["Launch GitHub"],
      ["Open search"],
      ["Enter ", "{repository}"],
      ["Select ", "{repository}", " in the results"],
      ["Open the repository"],
    ];
    const runs = [];
    learned.forEach((parts, i) => {
      runs.push({ text: `${i + 1}   `, options: { bold: true, color: TEAL } });
      parts.forEach((p, k) => {
        runs.push({ text: p, options: { color: p.startsWith("{") ? CORAL : TEXT, bold: p.startsWith("{"), breakLine: k === parts.length - 1 && i < learned.length - 1 } });
      });
    });
    s.addText(runs, { x: M + 0.3, y: 4.05, w: 4.8, h: 1.85, fontFace: BODY, fontSize: 14.5, paraSpaceAfter: 7, valign: "top", margin: 0, isTextBox: true });
    s.addImage({ data: I["MdCheckCircle_" + TEAL], x: M + 0.3, y: 5.97, w: 0.28, h: 0.28 });
    s.addText("“pytorch” was typed in the demo, so the repository name became a slot on its own.", {
      x: M + 0.7, y: 5.87, w: 4.4, h: 0.5, fontFace: BODY, fontSize: 12.5, color: MUTED, valign: "middle", margin: 0, isTextBox: true,
    });
    const replays = [
      ["“Open the tensorflow repository on GitHub”", "tensorflow/tensorflow opened · 18 s · no LLM calls"],
      ["“open the linux repository on github”", "torvalds/linux opened · 17 s · no LLM calls"],
      ["“show me the react repo on github”", "a paraphrase: understood, and React’s repository opened"],
    ];
    replays.forEach(([said, got], i) => {
      const y = 1.7 + i * 1.22;
      voice(s, said, 6.2, y, 6.53, { h: 0.55, size: 14 });
      s.addImage({ data: I["MdCheckCircle_" + TEAL], x: 6.3, y: y + 0.7, w: 0.28, h: 0.28 });
      s.addText(got, { x: 6.7, y: y + 0.66, w: 6.0, h: 0.36, fontFace: BODY, fontSize: 14, color: TEXT, valign: "middle", margin: 0, isTextBox: true });
    });
    card(s, 6.2, 5.45, 6.53, 1.15, INK, INK);
    s.addText("There is no per-app code path — not for GitHub, and not for Zomato, Amazon or Myntra either. The same recorder, generaliser and replay engine learned this from one demonstration, the way a judge teaches a new flow.", {
      x: 6.45, y: 5.45, w: 6.1, h: 1.15, fontFace: BODY, fontSize: 13.5, color: WHITE, valign: "middle", margin: 0, isTextBox: true,
    });
    s.addNotes("To check that nothing is tuned to our three target apps, we taught a task on GitHub's Android app, which the assistant had never seen: open a repository by name. One demonstration; then new repository names ran in about 17 to 18 seconds with no language-model calls, and a paraphrase was understood too. The repository name became a slot on its own.");
    footer(s);
  }

  // ---------- 10. Theme 3 test cases ----------
  {
    const s = pres.addSlide();
    s.background = { color: WHITE };
    heading(s, "Theme 3 test cases", "All 14 pass on our build, plus the three bonuses");
    const tests = [
      ["T1", "Teach – food", "Learned from one demo; steps shown in words"],
      ["T2", "Exact replay", "Stops before paying, 8/8, no LLM calls"],
      ["T3", "Paraphrase", "“Get me a margherita…”, “I want to order…” (asks restaurant)"],
      ["T4", "Slot: item", "Farmhouse, garlic bread, another restaurant"],
      ["T5", "Slot: quantity", "“two” → quantity 2 on the sheet, verified"],
      ["T6", "Slot: address", "“deliver to work” → Work selected"],
      ["T7", "Screen change", "Pop-up dismissed, item already in cart, shop closed"],
      ["T8", "Teach – e-commerce", "Amazon task learned, distinct from T1"],
      ["T9", "Cross slot + replay", "“phone case” → first non-sponsored result"],
      ["T10", "Genuinely stuck", "Signed out 2.8 s · app in Hindi 5 s · no taps"],
      ["T11", "Credential boundary", "Stops before paying: “Your turn.”"],
      ["T12", "Unknown intent", "“I haven’t learned that yet. Teach me?”"],
      ["T13", "Ambiguity", "“Order pizza.” → asks restaurant, then pizza"],
      ["T14", "Reporting", "“No… stopped at step 2 of 11 (Open search bar)”"],
      ["+3", "Noisy demo", "Mis-tap, call, unneeded filter dropped"],
      ["+4", "Similar app", "Amazon task runs on Myntra, 7/7"],
      ["+3", "Mid-flow parameter", "“Which restaurant…?”, “Which size…?”"],
    ];
    const colW = [0.6, 2.05, 3.35];
    const mk = (rows) => [
      [{ text: "", options: {} }, { text: "Test", options: {} }, { text: "What happens", options: {} }].map((c) => ({
        text: c.text, options: { bold: true, color: MUTED, fontSize: 11, fontFace: HEAD, fill: { color: WHITE }, border: [{ type: "none" }, { type: "none" }, { pt: 1, color: LINE }, { type: "none" }] },
      })),
      ...rows.map(([id, t, d]) => [
        { text: id, options: { bold: true, color: id.startsWith("+") ? CORAL : TEAL, fontFace: HEAD, fontSize: 11.5 } },
        { text: t, options: { bold: true, color: TEXT, fontSize: 11.5 } },
        { text: d, options: { color: MUTED, fontSize: 11 } },
      ].map((c) => ({ text: c.text, options: { ...c.options, fontFace: c.options.fontFace || BODY, valign: "middle", border: [{ type: "none" }, { type: "none" }, { pt: 0.75, color: LINE }, { type: "none" }] } }))),
    ];
    s.addTable(mk(tests.slice(0, 9)), { x: M, y: 1.65, w: 6.0, colW, rowH: 0.47, margin: [0, 0.06, 0, 0.06] });
    s.addTable(mk(tests.slice(9)), { x: M + 6.13, y: 1.65, w: 6.0, colW, rowH: 0.47, margin: [0, 0.06, 0, 0.06] });
    s.addNotes("The theme's own test table, in order, with what our build does on each. Details and timings for every row are in the evaluation documents in the repository.");
    footer(s);
  }

  // ---------- 11. Innovation ----------
  {
    const s = pres.addSlide();
    s.background = { color: WHITE };
    heading(s, "What’s new in our approach", "Six ideas that make a one-shot demo dependable");
    const items = [
      ["MdTouchApp_" + WHITE, INK, "Tap capture that works everywhere", "Our own transparent overlay hit-tests every touch against the UI tree, so taps are recorded even where apps send no accessibility events."],
      ["MdBolt_" + WHITE, TEAL, "The model only where it’s needed", "Known screens replay with a scored matcher: fast, free, deterministic. Exact commands work with no network at all."],
      ["MdVerifiedUser_" + WHITE, INK, "Proof-checked steps", "“Done” needs evidence on screen — the cart count, a quantity stepper, the dish’s name, the next button."],
      ["MdCleaningServices_" + WHITE, INK, "Clean tasks from messy demos", "Mis-taps, an answered call, an unneeded filter or pop-up are dropped from what it learns."],
      ["MdApps_" + WHITE, CORAL, "One demo, similar apps", "The Amazon task runs on Myntra: it finds “Add to Bag”, skips “AD” tiles and asks for your size."],
      ["MdHelpOutline_" + WHITE, INK, "Asks like a person", "One specific question when a value is missing or the choice is personal; a plain-language log of every run."],
    ];
    const cw = 3.9, ch = 2.25, gx = 0.27, gy = 0.3;
    items.forEach(([ic, col, t, d], i) => {
      const x = M + (i % 3) * (cw + gx), y = 1.7 + Math.floor(i / 3) * (ch + gy);
      card(s, x, y, cw, ch, TINT, TINT);
      circleIcon(s, ic, x + 0.28, y + 0.28, 0.66, col);
      s.addText(t, { x: x + 1.1, y: y + 0.28, w: cw - 1.3, h: 0.66, fontFace: HEAD, fontSize: 15, bold: true, color: TEXT, valign: "middle", margin: 0, isTextBox: true });
      s.addText(d, { x: x + 0.28, y: y + 1.1, w: cw - 0.5, h: 1.05, fontFace: BODY, fontSize: 13, color: MUTED, valign: "top", margin: 0, isTextBox: true });
    });
    s.addNotes("If the jury remembers one thing: the language model is a fallback, not the engine. Replays on known screens are deterministic and verified, which is what makes a single demonstration dependable.");
    footer(s);
  }

  // ---------- 12. Tech stack ----------
  {
    const s = pres.addSlide();
    s.background = { color: TINT };
    heading(s, "Tools and tech stack", "Native Android, one hosted model, tested over adb");
    const cols = [
      ["MdPhoneAndroid_" + WHITE, INK, "On the phone", ["Kotlin", "AccessibilityService", "Accessibility overlay (tap capture)", "SpeechRecognizer · TextToSpeech", "JSON recipes and run log", "Android 9+ · target SDK 35"]],
      ["MdCloudQueue_" + WHITE, CORAL, "AI model", ["Fireworks AI", "gpt-oss-120b, low reasoning effort", "JSON-only prompts: match, act, refine", "Hard timeout per call", "Separate spend-limited key in the APK"]],
      ["MdCode_" + WHITE, INK, "Build", ["Gradle (Android SDK 35)", "Dockerfile builds the APK", "Keys kept out of git", "Release APK on GitHub"]],
      ["MdScience_" + WHITE, TEAL, "Testing", ["adb benches: cold start, repeat runs", "Auto-answer for spoken questions", "Python eval of command matching", "Screen dumps on every failure"]],
    ];
    const cw = 2.85, g = 0.21;
    cols.forEach(([ic, col, t, list], i) => {
      const x = M + i * (cw + g);
      card(s, x, 1.7, cw, 4.05, WHITE, LINE);
      circleIcon(s, ic, x + 0.28, 1.95, 0.66, col);
      s.addText(t, { x: x + 1.08, y: 1.95, w: cw - 1.2, h: 0.66, fontFace: HEAD, fontSize: 16, bold: true, color: TEXT, valign: "middle", margin: 0, isTextBox: true });
      s.addText(list.map((l, k) => ({ text: l, options: { bullet: true, breakLine: k < list.length - 1 } })), {
        x: x + 0.28, y: 2.85, w: cw - 0.5, h: 2.6, fontFace: BODY, fontSize: 13.5, color: TEXT, paraSpaceAfter: 9, valign: "top", margin: 0, isTextBox: true,
      });
    });
    const facts = [["~4,300", "lines of Kotlin, 17 files"], ["1", "library: Kotlin coroutines"], ["3.5 MB", "release APK"], ["0", "app SDKs, deep links or root"]];
    facts.forEach(([n, d], i) => {
      const x = M + i * (cw + g);
      s.addText([
        { text: n + "  ", options: { bold: true, color: INK, fontSize: 22, fontFace: HEAD } },
        { text: d, options: { color: MUTED, fontSize: 13 } },
      ], { x, y: 6.0, w: cw, h: 0.55, fontFace: BODY, valign: "middle", margin: 0, isTextBox: true });
    });
    s.addNotes("Plain native Android in Kotlin; no root, no app SDKs. One hosted model through Fireworks' OpenAI-compatible API. Everything is reproducible with the Dockerfile, and the test benches drive the phone over adb.");
    footer(s);
  }

  // ---------- 13. Limitations ----------
  {
    const s = pres.addSlide();
    s.background = { color: WHITE };
    heading(s, "Known limitations", "What we know today, and how it behaves");
    const lim = [
      ["MdWifiOff_" + WHITE, "Paraphrases need the model", "Pop-ups, paraphrases and questions use the LLM key; exact and template commands work offline."],
      ["MdSpeed_" + WHITE, "Another app is slower", "About 45–60 s on Myntra versus 35–50 s on Amazon: unfamiliar screens go to the model."],
      ["MdScreenLockPortrait_" + WHITE, "Screen on, phone unlocked", "Accessibility services can’t act on a locked screen."],
      ["MdInstallMobile_" + WHITE, "Sideloading in India", "Play Protect blocks apps with an accessibility service from browsers and chat apps; install over USB or pause the scan."],
      ["MdStorefront_" + WHITE, "Slow networks stall apps", "It taps the app’s “Try Again” up to three times, then says where it stopped."],
      ["MdTranslate_" + WHITE, "Speech is English (India)", "Spoken answers use the phone’s recogniser; typing in the app works too."],
      ["MdLanguage_" + WHITE, "Taught in English, app now in Hindi", "It stops at the first step and says so; switch the app back or teach the task again in Hindi."],
      ["MdRestaurantMenu_" + WHITE, "Loose dish names", "“Garlic bread” picks the closest name on screen (Classic Stuffed Garlic Bread); say the full name for a specific one."],
    ];
    lim.forEach(([ic, t, d], i) => {
      const x = M + (i % 2) * 6.17, y = 1.65 + Math.floor(i / 2) * 1.2;
      card(s, x, y, 5.95, 1.05, TINT, TINT);
      circleIcon(s, ic, x + 0.22, y + 0.22, 0.6, INK2);
      s.addText(t, { x: x + 1.0, y: y + 0.12, w: 4.8, h: 0.36, fontFace: HEAD, fontSize: 14.5, bold: true, color: TEXT, margin: 0, isTextBox: true });
      s.addText(d, { x: x + 1.0, y: y + 0.48, w: 4.8, h: 0.52, fontFace: BODY, fontSize: 12.5, color: MUTED, valign: "top", margin: 0, isTextBox: true });
    });
    s.addNotes("These are all in the README too. The sideloading one matters for anyone installing the APK in India: Play Protect's enhanced fraud protection blocks apps with an accessibility service when they come from a browser or chat app; adb install or pausing the scan works.");
    footer(s);
  }

  // ---------- 14. Next + close ----------
  {
    const s = pres.addSlide();
    s.background = { color: INK };
    heading(s, "Taking it further", "From a hackathon build to an everyday worklet", true);
    const next = [
      ["MdMemory_" + WHITE, "On-device model", "Run matching and single actions on a small on-device model: private, offline, faster."],
      ["MdShare_" + WHITE, "Share what you taught", "Export a learned task so family or a team can run it without re-teaching."],
      ["MdSchedule_" + WHITE, "Routines", "Run a taught task on a schedule or chain two of them: “every Friday, order…”."],
      ["MdTranslate_" + WHITE, "More languages", "Hindi and other Indian languages for commands and questions, not just Hinglish."],
    ];
    next.forEach(([ic, t, d], i) => {
      const y = 1.75 + i * 1.08;
      circleIcon(s, ic, M, y, 0.66, i === 0 ? CORAL : INK2);
      s.addText(t, { x: M + 0.9, y: y - 0.02, w: 6.5, h: 0.4, fontFace: HEAD, fontSize: 16, bold: true, color: WHITE, margin: 0, isTextBox: true });
      s.addText(d, { x: M + 0.9, y: y + 0.36, w: 6.5, h: 0.5, fontFace: BODY, fontSize: 13.5, color: ICE, valign: "top", margin: 0, isTextBox: true });
    });
    card(s, 8.3, 1.75, 4.43, 4.1, INK2, INK2);
    s.addText("Thank you", { x: 8.6, y: 2.0, w: 3.9, h: 0.7, fontFace: HEAD, fontSize: 32, bold: true, color: WHITE, margin: 0, isTextBox: true });
    s.addText([
      { text: "Code, README, Dockerfile, docs", options: { bold: true, color: WHITE, breakLine: true } },
      { text: "github.com/sgoel2be24-cyber/prism-teachable-voice", options: { color: "3FD1B8", fontSize: 11.5, breakLine: true } },
      { text: " ", options: { breakLine: true, fontSize: 8 } },
      { text: "Release tag", options: { bold: true, color: WHITE, breakLine: true } },
      { text: "PRISM_GENAI_HACKATHON_Y2026", options: { color: ICE } },
    ], { x: 8.6, y: 2.8, w: 3.95, h: 1.8, fontFace: BODY, fontSize: 13.5, valign: "top", margin: 0, isTextBox: true });
    voice(s, "“Did the last run succeed?”", 8.6, 4.9, 3.8, { dark: false, h: 0.55, size: 14 });
    s.addNotes("Where it goes next: an on-device model for privacy and speed, sharing taught tasks, routines, and more Indian languages. Thank you; the repository has the code, README, Dockerfile and every evaluation table.");
    footer(s, true);
  }

  await pres.writeFile({ fileName: OUT });
  console.log("wrote", OUT);
})();
