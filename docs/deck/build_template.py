# TIET_Update_Submission_ppt.pptx on the organisers' template (Samsung PRISM GenAI Hackathon, 3rd edition).
# Usage: node gen_icons.js && python3 build_template.py ../../TIET_Update_Submission_ppt.pptx   (needs python-pptx; icons need react-icons + sharp)
# The template's 12 slides stay exactly as they are, in order, with their headings; we fill each one.
import copy, os, sys
from pptx import Presentation
from pptx.util import Inches, Pt, Emu
from pptx.dml.color import RGBColor
from pptx.enum.shapes import MSO_SHAPE, MSO_CONNECTOR
from pptx.enum.text import PP_ALIGN, MSO_ANCHOR
from pptx.oxml.ns import qn
from lxml import etree

HERE = os.path.dirname(os.path.abspath(__file__))
TEMPLATE = os.path.join(HERE, "prism_template.pptx")
OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "TIET_Update_Submission_ppt.pptx")
IMG = os.path.join(HERE, "img")
ICON = os.path.join(HERE, "icons")

# Template palette: violet accent, near-black ink, lavender lines. Green = verified, red = stop.
PURPLE, TITLE, INK, INK2 = "6D28D9", "704EA6", "14142B", "2B2560"
TEXT, MUTED, LAV, TINT, WHITE = "1F2037", "5B5F77", "D9D3F0", "F5F3FC", "FFFFFF"
GREEN, RED, ICE = "0E9F6E", "E5484D", "D9D3F0"
BODY, HEAD = "Calibri", "Arial"
X0, XW = 1.02, 11.3          # content column, aligned with the template's title text
REPO = "github.com/sgoel2be24-cyber/prism-teachable-voice"

prs = Presentation(TEMPLATE)
S = list(prs.slides)
LAYOUT_OBJECT = prs.slide_layouts[1]


def rgb(h): return RGBColor.from_string(h)


def text(slide, x, y, w, h, runs, size=14, color=TEXT, bold=False, font=BODY, align=PP_ALIGN.LEFT,
         anchor=MSO_ANCHOR.TOP, italic=False, space_after=0, line=None):
    """runs: str, or list of paragraphs; a paragraph is a str or a list of (text, {opts})."""
    tb = slide.shapes.add_textbox(Inches(x), Inches(y), Inches(w), Inches(h))
    tf = tb.text_frame
    tf.word_wrap = True
    tf.margin_left = tf.margin_right = tf.margin_top = tf.margin_bottom = 0
    tf.vertical_anchor = anchor
    paras = runs if isinstance(runs, list) else [runs]
    for i, para in enumerate(paras):
        p = tf.paragraphs[0] if i == 0 else tf.add_paragraph()
        p.alignment = align
        if space_after: p.space_after = Pt(space_after)
        if line: p.line_spacing = line
        parts = para if isinstance(para, list) else [(para, {})]
        for t, o in parts:
            r = p.add_run()
            r.text = t
            f = r.font
            f.name = o.get("font", font)
            f.size = Pt(o.get("size", size))
            f.bold = o.get("bold", bold)
            f.italic = o.get("italic", italic)
            f.color.rgb = rgb(o.get("color", color))
    return tb


def rect(slide, x, y, w, h, fill, line=None, radius=0.08, shape=MSO_SHAPE.ROUNDED_RECTANGLE, lw=1.0, dash=False):
    s = slide.shapes.add_shape(shape, Inches(x), Inches(y), Inches(w), Inches(h))
    if shape == MSO_SHAPE.ROUNDED_RECTANGLE:
        s.adjustments[0] = min(0.5, radius / min(w, h))
    if fill: s.fill.solid(); s.fill.fore_color.rgb = rgb(fill)
    else: s.fill.background()
    if line:
        s.line.color.rgb = rgb(line); s.line.width = Pt(lw)
        if dash: s.line.dash_style = 4  # dash
    else: s.line.fill.background()
    s.shadow.inherit = False
    st = s._element.find(qn("p:style"))
    if st is not None: s._element.remove(st)  # no theme shadow or theme colours on our shapes
    s.text_frame.text = ""
    return s


def icon(slide, name, color, x, y, d):
    slide.shapes.add_picture(os.path.join(ICON, f"{name}_{color}.png"), Inches(x), Inches(y), Inches(d), Inches(d))


def circle_icon(slide, name, x, y, d, fill):
    rect(slide, x, y, d, d, fill, shape=MSO_SHAPE.OVAL)
    p = d * 0.22
    icon(slide, name, WHITE, x + p, y + p, d - 2 * p)


def arrow(slide, x1, y1, x2, y2, color=MUTED, w=1.25, dash=False, head=True):
    c = slide.shapes.add_connector(MSO_CONNECTOR.STRAIGHT, Inches(x1), Inches(y1), Inches(x2), Inches(y2))
    c.line.color.rgb = rgb(color); c.line.width = Pt(w)
    st = c._element.find(qn("p:style"))
    if st is not None: c._element.remove(st)
    ln = c.line._get_or_add_ln()
    if dash:
        pd = etree.SubElement(ln, qn("a:prstDash")); pd.set("val", "dash")
    if head:
        te = etree.SubElement(ln, qn("a:tailEnd")); te.set("type", "triangle"); te.set("w", "med"); te.set("len", "med")
    return c


def voice(slide, said, x, y, w, h=0.5, size=13, dark=True):
    rect(slide, x, y, w, h, INK if dark else WHITE, None if dark else LAV, radius=h / 2)
    d = h - 0.14
    rect(slide, x + 0.07, y + 0.07, d, d, PURPLE, shape=MSO_SHAPE.OVAL)
    icon(slide, "MdMic", WHITE, x + 0.07 + d * 0.2, y + 0.07 + d * 0.2, d * 0.6)
    text(slide, x + d + 0.22, y, w - d - 0.32, h, said, size=size, italic=True, color=WHITE if dark else TEXT, anchor=MSO_ANCHOR.MIDDLE)


def phone(slide, f, x, y, h):
    w = h * 600 / 1088; b = 0.07
    rect(slide, x - b, y - b, w + 2 * b, h + 2 * b, INK, radius=0.22)
    slide.shapes.add_picture(os.path.join(IMG, f), Inches(x), Inches(y), Inches(w), Inches(h))
    return w


def kicker(slide, t, x=X0, y=1.72, color=PURPLE):
    text(slide, x, y, 8, 0.3, t.upper(), size=11, bold=True, color=color, font=HEAD)


def notes(slide, t):
    slide.notes_slide.notes_text_frame.text = t


def drop_body(slide):
    for ph in list(slide.placeholders):
        if ph.placeholder_format.type != 1 and ph.placeholder_format.idx != 0:  # keep the title only
            ph._element.getparent().remove(ph._element)


def set_title(slide, t):
    title = slide.shapes.title
    tf = title.text_frame
    p = tf.paragraphs[0]
    for r in list(p.runs)[1:]: r._r.getparent().remove(r._r)
    r = p.runs[0] if p.runs else p.add_run()
    r.text = t
    r.font.bold = True; r.font.name = "Calibri"; r.font.color.rgb = rgb(TITLE)


def new_slide(after, t):
    """A follow-on slide in the template's content layout, placed right after `after`."""
    s = prs.slides.add_slide(LAYOUT_OBJECT)
    set_title(s, t)
    drop_body(s)
    lst = prs.slides._sldIdLst
    el = lst[-1]
    lst.remove(el)
    ids = [int(e.get("id")) for e in lst]
    lst.insert(ids.index(after.slide_id) + 1, el)
    return s


def table(slide, x, y, w, rows, col_w, row_h=0.42, head=True, size=11.5):
    shp = slide.shapes.add_table(len(rows), len(col_w), Inches(x), Inches(y), Inches(w), Inches(row_h * len(rows)))
    tbl = shp.table
    tblPr = tbl._tbl.tblPr
    sid = tblPr.find(qn("a:tableStyleId"))
    if sid is None: sid = etree.SubElement(tblPr, qn("a:tableStyleId"))
    sid.text = "{2D5ABB26-0587-4C30-8999-92F81FD0307C}"  # No Style, No Grid
    tblPr.set("firstRow", "0"); tblPr.set("bandRow", "0")
    for i, cw in enumerate(col_w): tbl.columns[i].width = Inches(cw)
    for ri, row in enumerate(rows):
        tbl.rows[ri].height = Inches(row_h)
        for ci, cell_spec in enumerate(row):
            t, o = cell_spec if isinstance(cell_spec, tuple) else (cell_spec, {})
            c = tbl.cell(ri, ci)
            c.margin_left = c.margin_right = Inches(0.08); c.margin_top = c.margin_bottom = Inches(0.03)
            c.vertical_anchor = MSO_ANCHOR.MIDDLE
            fill = o.get("fill")
            if fill: c.fill.solid(); c.fill.fore_color.rgb = rgb(fill)
            else: c.fill.background()
            tf = c.text_frame; tf.word_wrap = True
            p = tf.paragraphs[0]; p.text = ""
            main, _, sub = t.partition("\n")
            r = p.add_run(); r.text = main
            r.font.name = o.get("font", BODY); r.font.size = Pt(o.get("size", size))
            r.font.bold = o.get("bold", False); r.font.color.rgb = rgb(o.get("color", TEXT))
            if sub:  # a second, quieter line (examples under an approach)
                p2 = tf.add_paragraph(); r2 = p2.add_run(); r2.text = sub
                r2.font.name = BODY; r2.font.size = Pt(o.get("size", size) - 2); r2.font.color.rgb = rgb(MUTED)
            # a thin rule under every row
            tcPr = c._tc.get_or_add_tcPr()
            for k, side in enumerate(("a:lnL", "a:lnR", "a:lnT", "a:lnB")):  # borders come before the cell fill
                ln = etree.Element(qn(side))
                if side == "a:lnB":
                    ln.set("w", "9525")
                    sf = etree.SubElement(ln, qn("a:solidFill")); cl = etree.SubElement(sf, qn("a:srgbClr")); cl.set("val", LAV)
                else:
                    ln.set("w", "0"); etree.SubElement(ln, qn("a:noFill"))
                tcPr.insert(k, ln)
    return tbl


# ================= 1. Title =================
s = S[0]
box = [sh for sh in s.shapes if sh.has_text_frame and sh.text_frame.text.startswith("Theme ID")][0]
vals = [
    "Theme ID - 03 · Teachable Voice Automation",
    "Team Name - Update",
    "College Name - Thapar Institute of Engineering and Technology (TIET), Patiala",
    "Member Name & Email 1 - Shikhar Goel · sgoel2_be24@thapar.edu",
    "Member Name & Email 2 - Ishpreet Singh · isingh4_be24@thapar.edu",
    None, None,
    "Submission Github link - " + REPO,
]
paras = box.text_frame.paragraphs
for p, v in zip(list(paras), vals):
    if v is None:
        p._p.getparent().remove(p._p); continue
    runs = p.runs
    runs[0].text = v
    for r in runs[1:]: r._r.getparent().remove(r._r)
box.width = Inches(6.7)
notes(s, "Theme 03, Teachable Voice Automation. Team Update from TIET: Shikhar Goel and Ishpreet Singh, third-year Computer Engineering. "
         "One sentence: you teach the phone a task by doing it once while it listens and watches; after that you just say it, with different "
         "values or wording, and it does it through the accessibility service, stopping before payment.")

# ================= 2. Theme =================
s = S[1]; drop_body(s)
kicker(s, "Theme 03 · the problem, in our words")
text(s, X0, 2.1, 5.4, 0.6, "Teachable Voice Automation", size=28, bold=True, color=INK, font=HEAD)
text(s, X0, 2.85, 5.4, 2.0, [
    "Voice assistants stop where an app’s own integrations stop. Ordering a pizza on Zomato or adding something to an Amazon cart still takes ten or more taps, every time.",
    "The user already knows those taps. There is no way to hand that knowledge to the assistant — and have it cope with new values, new wording and a changed screen.",
], size=15, color=TEXT, space_after=8)
rect(s, X0, 5.2, 5.4, 1.45, TINT)
text(s, X0 + 0.25, 5.33, 5.0, 0.3, "Target apps", size=12, bold=True, color=PURPLE, font=HEAD)
text(s, X0 + 0.25, 5.66, 5.0, 0.95, [
    [("Zomato", {"bold": True}), (" — order from Domino’s (item, quantity, address)", {})],
    [("Amazon", {"bold": True}), (" — search, add the first real result to the cart", {})],
    [("Myntra", {"bold": True}), (" — the Amazon task, run in a similar app", {})],
], size=13, color=TEXT, space_after=2)
rect(s, 6.85, 2.1, 5.47, 4.55, WHITE, LAV)
text(s, 7.15, 2.3, 5.0, 0.4, "What the theme asks for — and what we built", size=15, bold=True, color=INK, font=HEAD)
asks = [
    ("Teach by voice and taps, once", "say the command, then do the task; every tap is recorded"),
    ("A reusable flow, not a tap replay", "values from the command become slots: item, quantity, address"),
    ("Paraphrases and changed values", "“Get me a farmhouse from dominos” runs the same task"),
    ("Replay when screens change", "pop-ups, sponsored results, another restaurant — ask only when stuck"),
    ("No credential capture", "stops at payment, OTP, password and login: “Your turn.”"),
]
for i, (t, d) in enumerate(asks):
    y = 2.88 + i * 0.74
    icon(s, "MdCheckCircle", GREEN, 7.15, y + 0.04, 0.3)
    text(s, 7.6, y, 4.6, 0.7, [[(t, {"bold": True, "color": TEXT, "size": 14})], [(d, {"color": MUTED, "size": 12})]])
notes(s, "Theme 03 in our words: assistants only reach what apps expose, so in-app tasks take many taps. The theme asks us to learn a task from one demonstration by voice and taps, generalise it into slots, match paraphrases and new values, cope with changed screens, and never touch credentials. Our target apps are Zomato, Amazon, and Myntra for the cross-app bonus.")

# ================= 3. Existing solutions & gaps =================
s = S[2]; drop_body(s)
kicker(s, "Where today’s options fall short")
H = {"bold": True, "color": MUTED, "size": 11, "font": HEAD}
rows = [
    [("Approach", H), ("What it does", H), ("The gap", H)],
    [("Voice assistants\nGoogle Assistant / Gemini, Siri, Bixby, Alexa", {"bold": True}),
     ("Act through the few actions an app chooses to expose", {"color": MUTED}),
     ("Everything else inside Zomato or Amazon is out of reach; it falls back to a web result", {"color": MUTED})],
    [("Routines and macro apps\nBixby Routines, Tasker, MacroDroid", {"bold": True}),
     ("Chain device actions; add-ons can tap on-screen elements", {"color": MUTED}),
     ("Built step by step by hand, with fixed values; a changed screen breaks the macro", {"color": MUTED})],
    [("LLM screen agents\nresearch prototypes such as AppAgent, Mobile-Agent", {"bold": True}),
     ("A large model reads the screen and decides every single step", {"color": MUTED}),
     ("A model call per step: slower, costlier, varies from run to run, can wander near payment", {"color": MUTED})],
    [("Programming by demonstration\nSUGILITE (CMU, 2017)", {"bold": True}),
     ("Learns a parameterised task from one demo plus the spoken command", {"color": MUTED}),
     ("Pre-LLM research prototype: no model to recover when a screen differs from the demo", {"color": MUTED})],
    [("Teachable Voice (ours)", {"bold": True, "color": PURPLE, "fill": TINT}),
     ("Learns from one demo; replays with a scored matcher; the model only on unfamiliar screens", {"fill": TINT}),
     ("Asks when a value is missing or a choice is personal; always hands back at payment and login", {"fill": TINT})],
]
table(s, X0, 2.1, XW, rows, [3.5, 3.7, 4.1], row_h=0.72, size=12.5)
notes(s, "Existing options: assistants reach only exposed app actions; macro apps are hand-built and brittle; LLM screen agents call a model for every step, which is slow, costly and not repeatable; programming by demonstration, like SUGILITE, had the right idea but no way to recover when a screen changes. We combine demonstration with a deterministic replay engine and use the model only as a fallback, with a hard hand-off at payment and login.")


# ================= 4. Our solution & architecture diagram (one slide) =================
s = S[3]; drop_body(s)
steps = [("1", "Teach", "Say the command, do the task once"), ("2", "Generalise", "Command words become slots"),
         ("3", "Replay by voice", "New wording, values, even another app"), ("4", "Ask or hand back", "One question; “Your turn.” at payment")]
cw, gap = 2.66, 0.22
for i, (n, t, d) in enumerate(steps):
    x = X0 + i * (cw + gap)
    rect(s, x, 1.62, cw, 0.78, TINT)
    col = PURPLE if i == 3 else INK
    rect(s, x + 0.15, 1.78, 0.46, 0.46, col, shape=MSO_SHAPE.OVAL)
    text(s, x + 0.15, 1.78, 0.46, 0.46, n, size=15, bold=True, color=WHITE, font=HEAD, align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
    text(s, x + 0.72, 1.66, cw - 0.8, 0.7, [[(t, {"bold": True, "size": 13.5, "color": INK, "font": HEAD})], [(d, {"size": 11, "color": MUTED})]],
         anchor=MSO_ANCHOR.MIDDLE)
    if i < 3: arrow(s, x + cw + 0.02, 2.01, x + cw + gap - 0.02, 2.01)
rect(s, X0 - 0.05, 2.58, XW + 0.1, 3.42, TINT, LAV, radius=0.14)
text(s, X0 + 0.2, 2.66, 8, 0.28, "ARCHITECTURE  ·  ALL ON THE PHONE, THROUGH ANDROID’S ACCESSIBILITY SERVICE", size=10, bold=True, color=MUTED, font=HEAD)
bw, bh, g, x0 = 1.62, 0.8, 0.29, X0 + 0.22
def lane(label, y, boxes):
    text(s, x0, y - 0.3, 3, 0.26, label, size=12, bold=True, color=INK, font=HEAD)
    for i, (t, sub, llm, fill) in enumerate(boxes):
        x = x0 + i * (bw + g)
        rect(s, x, y, bw, bh, fill or WHITE, None if fill else "C9C3E6", radius=0.08)
        text(s, x + 0.06, y + 0.03, bw - 0.12, bh - 0.06, [[(t, {"bold": True, "size": 11.5, "color": WHITE if fill else INK})],
                                                         [(sub, {"size": 9, "color": ICE if fill else MUTED})]],
             align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
        if llm:
            rect(s, x + bw - 0.48, y - 0.12, 0.44, 0.22, PURPLE, radius=0.11)
            text(s, x + bw - 0.48, y - 0.12, 0.44, 0.22, "LLM", size=8, bold=True, color=WHITE, font=HEAD, align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
        if i < len(boxes) - 1:
            arrow(s, x + bw + 0.03, y + bh / 2, x + bw + g - 0.03, y + bh / 2)
lane("Teach once", 3.3, [
    ("Voice command", "speech → text", False, None),
    ("Recorder", "tap-capture overlay + UI-tree snapshot per tap", False, None),
    ("Generaliser", "slots from command words, card anchors", False, None),
    ("Refine", "slot names, step intents, noise, goals", True, None),
    ("User review", "steps shown in words", False, None),
    ("Recipe store", "JSON on the phone", False, INK),
])
lane("Run on command", 5.0, [
    ("Voice command", "or typed", False, None),
    ("Matcher", "speech → intent: flow + slots + app", True, None),
    ("Executor", "fast path → scroll / search → LLM action → ask", True, None),
    ("Checks", "cart count, the right dish, not an ad", False, None),
    ("Guard", "payment, login, OTP, password", False, None),
    ("Done / hand back", "spoken reply + run log", False, PURPLE),
])
xs, xe = x0 + 5 * (bw + g) + bw / 2, x0 + 2 * (bw + g) + bw / 2
arrow(s, xs, 3.3 + bh, xs, 4.42, INK, dash=True, head=False)
arrow(s, xs, 4.42, xe, 4.42, INK, dash=True, head=False)
arrow(s, xe, 4.42, xe, 4.98, INK, dash=True)
rect(s, X0 - 0.05, 6.14, XW + 0.1, 0.56, WHITE, PURPLE, radius=0.1, dash=True)
circle_icon(s, "MdCloudQueue", X0 + 0.1, 6.19, 0.46, PURPLE)
text(s, X0 + 0.72, 6.14, XW - 0.85, 0.56, [[("Fireworks AI · gpt-oss-120b  ", {"bold": True, "color": INK}),
     ("— the only network call: refine a demo once, understand a paraphrase, choose one action on an unfamiliar screen (1–2 s). Known screens replay with no model call.", {"color": MUTED})]],
     size=11.5, anchor=MSO_ANCHOR.MIDDLE)
notes(s, "Four stages on top: teach, generalise, replay by voice, and ask or hand back. Below, the architecture: two pipelines on the phone. Teach: voice command, the recorder with our tap-capture overlay and a UI-tree snapshot per tap, the generaliser that turns command words into slots, one LLM call to refine, a review screen, and a JSON recipe. Run: the matcher turns speech into a flow with slot values, offline first; the executor escalates from a scored fast path to scrolling and page search, and only then an LLM action or a question; checks demand proof on screen; the guard stops at payment, login, OTP and password. The violet tags mark the only places the LLM is used.")


# ================= 5. Demo & walkthrough =================
s = S[4]; drop_body(s)
shots = [("teach.png", "1  Teaching", "Say the command, then do it. A red border and “Done” while you show the task."),
         ("learned.png", "2  What it learned", "Steps in words, values in braces, the stray tap marked “not needed”."),
         ("ask.png", "3  A missing value", "“Which product should I use?” — asked at the step that needs it."),
         ("size_q.png", "4  A personal choice", "It asks for the size and lists what’s available. It never picks for you.")]
h = 3.55; pw = h * 600 / 1088; gap = (XW - 4 * pw) / 3
for i, (f, t, d) in enumerate(shots):
    x = X0 + i * (pw + gap) + 0.07
    phone(s, f, x, 1.72, h)
    text(s, x - 0.05, 5.42, pw + 0.5, 0.3, t, size=13.5, bold=True, color=INK, font=HEAD)
    text(s, x - 0.05, 5.72, pw + 0.55, 0.6, d, size=11, color=MUTED)
rect(s, X0, 6.38, XW, 0.42, TINT, radius=0.1)
text(s, X0 + 0.2, 6.38, XW - 0.4, 0.42, [[("Demo video (≤ 5 min, one unedited take): ", {"bold": True, "color": PURPLE}),
     ("teach by voice + taps → exact replay → a paraphrase → a changed value → the assistant asking a question. Link in the README and the form.", {"color": TEXT})]],
     size=11.5, anchor=MSO_ANCHOR.MIDDLE)
notes(s, "Four moments from real runs: the teaching border with Done and Cancel; the learned Zomato task, in words, with its slots and the Veg filter marked not needed; a missing value asked mid-run; and on Myntra, a size question listing the sizes on offer. The demo video follows the order the theme asks for.")

# ================= 6. Tools & tech stack =================
s = S[5]; drop_body(s)
cols = [("MdPhoneAndroid", INK, "On the phone", ["Kotlin", "AccessibilityService", "Accessibility overlay (tap capture)", "SpeechRecognizer · TextToSpeech", "JSON recipes and run log", "Android 9+ · target SDK 35"]),
        ("MdCloudQueue", PURPLE, "AI model", ["Fireworks AI", "gpt-oss-120b, low reasoning effort", "JSON-only prompts: match, act, refine", "Hard timeout per call", "Separate spend-limited key in the APK"]),
        ("MdCode", INK, "Build", ["Gradle (Android SDK 35)", "Dockerfile builds the APK", "Keys kept out of git", "Release APK on GitHub"]),
        ("MdScience", GREEN, "Testing", ["adb benches: cold start, repeat runs", "Auto-answer for spoken questions", "Python eval of command matching", "Screen dumps on every failure"])]
cw, g = 2.66, 0.22
for i, (ic, col, t, lst) in enumerate(cols):
    x = X0 + i * (cw + g)
    rect(s, x, 1.75, cw, 3.95, WHITE, LAV)
    circle_icon(s, ic, x + 0.22, 1.95, 0.6, col)
    text(s, x + 0.95, 1.95, cw - 1.05, 0.6, t, size=15, bold=True, color=INK, font=HEAD, anchor=MSO_ANCHOR.MIDDLE)
    tb = text(s, x + 0.22, 2.8, cw - 0.4, 2.8, [[("•  " + l, {})] for l in lst], size=12.5, color=TEXT, space_after=7)
for i, (n, d) in enumerate([("~4,300", "lines of Kotlin, 17 files"), ("1", "library: Kotlin coroutines"), ("3.5 MB", "release APK"), ("0", "app SDKs, deep links or root")]):
    x = X0 + i * (cw + g)
    text(s, x, 5.95, cw, 0.5, [[(n + "  ", {"bold": True, "color": PURPLE, "size": 22, "font": HEAD}), (d, {"color": MUTED, "size": 12.5})]], anchor=MSO_ANCHOR.MIDDLE)
notes(s, "Plain native Android in Kotlin; no root, no app SDKs. One hosted model through Fireworks' OpenAI-compatible API. Everything is reproducible with the Dockerfile, and the test benches drive the phone over adb.")

# ================= 7. Impact & use case =================
s = S[6]; drop_body(s)
rect(s, X0, 1.75, 3.9, 4.95, INK)
text(s, X0 + 0.3, 1.98, 3.4, 0.3, "ONE ZOMATO ORDER", size=11, bold=True, color="C4B5FD", font=HEAD)
text(s, X0 + 0.3, 2.35, 3.4, 1.0, [[("11 steps", {"bold": True, "color": WHITE, "size": 38, "font": HEAD})]])
text(s, X0 + 0.3, 3.25, 3.4, 0.5, "→ one sentence", size=22, bold=True, color="C4B5FD", font=HEAD)
text(s, X0 + 0.3, 3.95, 3.35, 1.6, "Taught once by doing it. Then 36–66 s hands-free with a new pizza, a quantity or a delivery address — stopping before payment every time.", size=13.5, color=ICE)
voice(s, "“Order two Margherita pizzas from Domino’s.”", X0 + 0.25, 5.85, 3.4, h=0.5, size=11.5, dark=False)
cases = [("MdRepeat", PURPLE, "Everyday repeats", "Reorder from the same restaurant, restock the same item, check the same page: said with today’s values."),
         ("MdAccessibilityNew", INK, "Accessibility", "For people who find many small taps hard, one sentence replaces a whole flow — and it asks rather than guesses."),
         ("MdFamilyRestroom", INK, "Set up once for family", "Someone teaches a task once; a parent just says it. It never pays, logs in or picks a personal option alone."),
         ("MdTranslate", GREEN, "Built for India", "Hinglish commands work (“amazon pe pencil box search karke…”); the guard knows Hindi payment and login phrases.")]
for i, (ic, col, t, d) in enumerate(cases):
    x = X0 + 4.15 + (i % 2) * 3.62; y = 1.75 + (i // 2) * 1.95
    rect(s, x, y, 3.47, 1.8, TINT)
    circle_icon(s, ic, x + 0.22, y + 0.22, 0.55, col)
    text(s, x + 0.9, y + 0.22, 2.45, 0.55, t, size=14.5, bold=True, color=INK, font=HEAD, anchor=MSO_ANCHOR.MIDDLE)
    text(s, x + 0.22, y + 0.88, 3.08, 0.9, d, size=12, color=MUTED)
rect(s, X0 + 4.15, 5.7, 7.09, 1.0, WHITE, LAV)
text(s, X0 + 4.4, 5.7, 6.6, 1.0, [[("As a worklet: ", {"bold": True, "color": PURPLE}),
     ("a Galaxy assistant that learns in-app tasks from its own user, through the accessibility layer every app already has — no partner integration, and the user stays in charge of money and logins.", {"color": TEXT})]],
     size=12.5, anchor=MSO_ANCHOR.MIDDLE)
notes(s, "Impact: the Zomato order we taught is eleven steps; after one demonstration it's one sentence and under a minute hands-free. Use cases: everyday repeats, accessibility for people who find many small taps hard, a family member setting up tasks once for a parent, and Hinglish commands. As a worklet, it extends an assistant into any app through accessibility, without partner integrations.")


# ================= 8. Innovation highlights, results and limitations (one slide) =================
s = S[7]; drop_body(s)
colw, colg, top = 3.62, 0.22, 1.62
xs = [X0 + i * (colw + colg) for i in range(3)]
heads = [("Innovation highlights", PURPLE), ("Results on a real phone", GREEN), ("Known limitations", INK)]
for x, (h, c) in zip(xs, heads):
    rect(s, x, top, colw, 5.1, TINT)
    text(s, x + 0.22, top + 0.15, colw - 0.4, 0.35, h, size=15, bold=True, color=c, font=HEAD)
inn = [("MdTouchApp", "Tap capture everywhere", "our overlay hit-tests every touch, even where apps report none"),
       ("MdBolt", "The model only when needed", "known screens replay with a scored matcher, no network"),
       ("MdVerifiedUser", "Proof-checked steps", "cart count, − 1 +, the dish’s name before “done”"),
       ("MdCleaningServices", "Clean tasks from messy demos", "mis-taps, a call, an unneeded filter are dropped"),
       ("MdApps", "One demo, similar apps", "the Amazon task runs on Myntra, skips “AD” tiles"),
       ("MdHelpOutline", "Asks like a person", "one question when a value is missing or personal")]
for i, (ic, t, d) in enumerate(inn):
    y = top + 0.62 + i * 0.73
    circle_icon(s, ic, xs[0] + 0.22, y + 0.04, 0.42, INK if i % 2 else PURPLE)
    text(s, xs[0] + 0.78, y, colw - 0.95, 0.7, [[(t, {"bold": True, "size": 12, "color": INK})], [(d, {"size": 10.5, "color": MUTED})]])
stats = [("8 / 8", "judges’ sentence, 0 LLM calls"), ("14 / 14", "Amazon, taught by hand"), ("7 / 7", "Myntra (Amazon task)"), ("14 / 14", "theme test cases T1–T14")]
for i, (n, d) in enumerate(stats):
    x = xs[1] + 0.22 + (i % 2) * 1.62; y = top + 0.62 + (i // 2) * 1.02
    rect(s, x, y, 1.52, 0.92, WHITE, LAV)
    text(s, x + 0.12, y + 0.06, 1.3, 0.45, n, size=20, bold=True, color=PURPLE if i == 3 else GREEN, font=HEAD)
    text(s, x + 0.12, y + 0.5, 1.33, 0.4, d, size=9.5, color=MUTED)
for i, t in enumerate(["“Order garlic bread from dominos” → Classic Stuffed Garlic Bread",
                       "Taught on Domino’s, ran on Pizza Hut (63 s)",
                       "App switched to Hindi → says why in 5 s",
                       "Every judges’ sentence handled, verbatim"]):
    y = top + 2.75 + i * 0.56
    icon(s, "MdCheckCircle", GREEN, xs[1] + 0.22, y + 0.04, 0.24)
    text(s, xs[1] + 0.56, y, colw - 0.72, 0.52, t, size=10.5, color=TEXT)
lim = [("MdWifiOff", "Paraphrases need the model", "exact commands work offline"),
       ("MdSpeed", "Another app is slower", "45–60 s on Myntra vs 35–50 s on Amazon"),
       ("MdScreenLockPortrait", "Screen on, phone unlocked", "services can’t act on a locked screen"),
       ("MdInstallMobile", "Sideloading in India", "Play Protect: install over USB or pause it"),
       ("MdLanguage", "Taught in English, app in Hindi", "stops and says so; re-teach in Hindi"),
       ("MdRestaurantMenu", "Loose dish names", "“garlic bread” → the closest name on screen")]
for i, (ic, t, d) in enumerate(lim):
    y = top + 0.62 + i * 0.73
    circle_icon(s, ic, xs[2] + 0.22, y + 0.04, 0.42, INK2)
    text(s, xs[2] + 0.78, y, colw - 0.95, 0.7, [[(t, {"bold": True, "size": 12, "color": INK})], [(d, {"size": 10.5, "color": MUTED})]])
text(s, X0, 6.78, XW, 0.25, "Oppo Reno3, Android 12 · 28–30 Sep · app force-stopped before every run · full tables in docs/ of the repository", size=9.5, color=MUTED)
notes(s, "Left: six ideas that make one demonstration dependable; the language model is a fallback, not the engine. Middle: measured cold-start runs; the judges' sentence reached payment eight times out of eight with no language-model call, and all fourteen theme test cases pass, including the rubric's garlic-bread example. Right: what we know today and how the assistant behaves in each case; all of these are in the README too.")


# ================= 9. What's next =================
s = S[8]; drop_body(s)
nxt = [("MdMemory", PURPLE, "On-device model", "Run matching and single actions on a small on-device model: private, offline, faster."),
       ("MdShare", INK, "Share what you taught", "Export a learned task so family or a team can run it without re-teaching."),
       ("MdSchedule", INK, "Routines", "Run a taught task on a schedule, or chain two of them: “every Friday, order…”."),
       ("MdTranslate", INK, "More languages", "Hindi and other Indian languages for commands and questions, not just Hinglish.")]
for i, (ic, col, t, d) in enumerate(nxt):
    y = 1.8 + i * 1.18
    circle_icon(s, ic, X0, y, 0.66, col)
    text(s, X0 + 0.9, y - 0.02, 5.9, 0.38, t, size=16, bold=True, color=INK, font=HEAD)
    text(s, X0 + 0.9, y + 0.38, 5.9, 0.6, d, size=13, color=MUTED)
rect(s, 8.05, 1.8, 4.27, 4.0, TINT)
text(s, 8.35, 2.0, 3.7, 0.35, "TOWARDS A WORKLET", size=11, bold=True, color=PURPLE, font=HEAD)
text(s, 8.35, 2.4, 3.75, 2.3, [
    "An assistant that learns in-app tasks from its own user, through the accessibility layer every app already has.",
    "The pieces that make it safe to ship are already there: proof-checked steps, a hand-off at payment and login, and a readable log of every run.",
], size=13.5, color=TEXT, space_after=8)
voice(s, "“Did the last run succeed?”", 8.35, 5.0, 3.7, h=0.52, size=13, dark=False)
notes(s, "Where it goes next: an on-device model for privacy and speed, sharing taught tasks, routines, and more Indian languages. As a worklet, the safety pieces are already in place.")

# ================= 10. Brownie points =================
s = S[9]; drop_body(s)
kicker(s, "The three bonus criteria — and what goes beyond them", y=1.62)
bonus = [("+3", "Touches the task didn’t need", "A mis-tap that was undone, an answered call, a Veg filter and a pop-up in our demos were dropped on their own — shown as “ignored: not needed”."),
         ("+4", "The same task in a similar app", "Taught on Amazon, run on Myntra 7/7: it found “Add to Bag”, skipped the “AD” tiles and asked for the size."),
         ("+3", "A missing value, asked mid-flow", "“Order pizza.” → “Which restaurant should I order from?”, then “Which pizza?” — and it carries on with the answers.")]
cw = 3.62
for i, (b, t, d) in enumerate(bonus):
    x = X0 + i * (cw + 0.22)
    rect(s, x, 2.0, cw, 2.15, TINT)
    rect(s, x + 0.25, 2.22, 0.62, 0.36, PURPLE, radius=0.18)
    text(s, x + 0.25, 2.22, 0.62, 0.36, b, size=13, bold=True, color=WHITE, font=HEAD, align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
    text(s, x + 1.0, 2.2, cw - 1.15, 0.42, t, size=13.5, bold=True, color=INK, font=HEAD, anchor=MSO_ANCHOR.MIDDLE)
    text(s, x + 0.25, 2.75, cw - 0.45, 1.35, d, size=12, color=MUTED)
rect(s, X0, 4.4, 6.6, 2.3, INK)
text(s, X0 + 0.25, 4.55, 6.1, 0.35, "Taught on an app it had never seen: GitHub", size=14, bold=True, color=WHITE, font=HEAD)
text(s, X0 + 0.25, 4.92, 6.2, 0.3, "One demo with pytorch → learned “Open {repository} repository on GitHub”", size=11.5, color=ICE)
for i, (said, got) in enumerate([("“Open the tensorflow repository on GitHub”", "opened · 18 s · no LLM calls"),
                                 ("“open the linux repository on github”", "torvalds/linux · 17 s · no LLM calls"),
                                 ("“show me the react repo on github”", "paraphrase understood · opened")]):
    y = 5.33 + i * 0.43
    icon(s, "MdCheckCircle", GREEN, X0 + 0.25, y + 0.05, 0.26)
    text(s, X0 + 0.65, y, 5.9, 0.38, [[(said + "  ", {"italic": True, "color": WHITE}), (got, {"color": "A7F3D0"})]], size=12, anchor=MSO_ANCHOR.MIDDLE)
rect(s, X0 + 6.8, 4.4, 4.5, 2.3, WHITE, LAV)
text(s, X0 + 7.05, 4.55, 4.1, 0.35, "Also", size=14, bold=True, color=PURPLE, font=HEAD)
for i, t in enumerate(["No per-app code path — not even for our target apps", "App switched to Hindi → says why in 5 s",
                       "Payment and login guard knows Hindi phrases", "Learns from a typed prefix + a tapped suggestion"]):
    y = 4.98 + i * 0.42
    icon(s, "MdCheckCircle", PURPLE, X0 + 7.05, y + 0.05, 0.25)
    text(s, X0 + 7.42, y, 3.85, 0.36, t, size=12, color=TEXT, anchor=MSO_ANCHOR.MIDDLE)
notes(s, "All three bonus criteria are met with evidence from real runs. Beyond them: to check nothing is tuned to our target apps, we taught a task on GitHub, which the assistant had never seen, and new repository names replayed in 17 to 18 seconds with no model calls.")

# ================= 11. Checklist =================
s = S[10]; drop_body(s)
chk = [("Working prototype code — public or shared GitHub repo", REPO + "  ·  release tag PRISM_GENAI_HACKATHON_Y2026"),
       ("README with reproducible setup instructions", "README with setup steps, a Dockerfile that builds the APK, and the installable APK in the GitHub release"),
       ("Demo video, max 5 minutes (YouTube or Drive link)", "One unedited take in the order the theme asks for; the link is in the README and the form"),
       ("Presentation file (PPT or PDF)", "TIET_Update_Submission_ppt.pptx, in the repository"),
       ("Architecture, target apps and known limitations (Theme 3)", "docs/architecture.md with diagrams; target apps and limitations in the README")]
for i, (t, d) in enumerate(chk):
    y = 1.85 + i * 0.95
    rect(s, X0, y, XW, 0.8, TINT)
    rect(s, X0 + 0.2, y + 0.16, 0.48, 0.48, GREEN, shape=MSO_SHAPE.OVAL)
    text(s, X0 + 0.2, y + 0.16, 0.48, 0.48, "Y", size=16, bold=True, color=WHITE, font=HEAD, align=PP_ALIGN.CENTER, anchor=MSO_ANCHOR.MIDDLE)
    text(s, X0 + 0.9, y + 0.07, XW - 1.1, 0.68, [[(t, {"bold": True, "color": INK, "size": 14})], [(d, {"color": MUTED, "size": 12})]], anchor=MSO_ANCHOR.MIDDLE)
notes(s, "Everything the submission asks for is in the public repository under the release tag: code, README with setup and a Dockerfile, the APK, the demo video link, this presentation, and the architecture and limitations the theme asks for.")

# ================= 12. Thank you =================
s = S[11]
text(s, 1.14, 5.05, 9.5, 0.9, [[("Team Update · TIET — ", {"bold": True, "color": INK}), ("Shikhar Goel, Ishpreet Singh", {"color": INK})],
                               [(REPO, {"color": PURPLE, "size": 13})]], size=15, space_after=4)
notes(s, "Thank you. The repository has the code, README, Dockerfile, APK and every evaluation table.")

prs.save(OUT)
print("wrote", OUT, len(prs.slides), "slides")
