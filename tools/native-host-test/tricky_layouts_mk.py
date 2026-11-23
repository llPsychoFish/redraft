from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import letter
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
pdfmetrics.registerFont(TTFont("R", "/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf"))
pdfmetrics.registerFont(TTFont("B", "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf"))
c = canvas.Canvas("tricky.pdf", pagesize=letter)
Y = {}
def line(name, y): Y[name] = y
y = 740
def nxt():
    global y; y -= 40; return y

# 0 normal single object
yy = nxt(); line("normal", yy); c.setFont("R", 12); c.drawString(72, yy, "KOFORIDUA TECHNICAL UNIVERSITY")
# 1 per-word objects
yy = nxt(); line("per_word", yy); x = 72
for w in "KOFORIDUA TECHNICAL UNIVERSITY".split(): c.drawString(x, yy, w); x += c.stringWidth(w + " ", "R", 12)
# 2 non-breaking spaces (very common from Word)
yy = nxt(); line("nbsp", yy); c.drawString(72, yy, "KOFORIDUA\u00a0TECHNICAL\u00a0UNIVERSITY")
# 3 ligatures
yy = nxt(); line("ligature", yy); c.drawString(72, yy, "The o\ufb03ce \ufb01nal \ufb02ow chart")
# 4 superscript as a separate raised, smaller object (6th NOVEMBER)
yy = nxt(); line("superscript", yy); c.setFont("R", 12); c.drawString(72, yy, "DATE: 6"); w = c.stringWidth("DATE: 6", "R", 12)
c.setFont("R", 7); c.drawString(72 + w, yy + 5, "th"); c.setFont("R", 12); c.drawString(72 + w + c.stringWidth("th", "R", 7), yy, " NOVEMBER, 2026")
# 5 faux bold: same text drawn twice with a tiny offset
yy = nxt(); line("faux_bold", yy); c.setFont("R", 12); c.drawString(72, yy, "KOFORIDUA TECHNICAL"); c.drawString(72.4, yy, "KOFORIDUA TECHNICAL")
# 6 one object per character
yy = nxt(); line("per_char", yy); x = 72
for ch in "KOFORIDUA TECHNICAL":
    c.drawString(x, yy, ch); x += c.stringWidth(ch, "R", 12)
# 7 letter-spaced heading
yy = nxt(); line("letterspaced", yy)
t = c.beginText(72, yy); t.setFont("B", 12); t.setCharSpace(3); t.textOut("KOFORIDUA TECHNICAL"); c.drawText(t)
# 8 two columns on one baseline (the user selects the left column only)
yy = nxt(); line("two_columns", yy); c.setFont("R", 12); c.drawString(72, yy, "Left column text"); c.drawString(330, yy, "Right column text")
# 9 text with a trailing/leading tab-like gap and mixed fonts (bold word in a sentence)
yy = nxt(); line("mixed_bold", yy); c.setFont("R", 12); c.drawString(72, yy, "This is "); w = c.stringWidth("This is ", "R", 12)
c.setFont("B", 12); c.drawString(72 + w, yy, "important"); w += c.stringWidth("important", "B", 12); c.setFont("R", 12); c.drawString(72 + w, yy, " text.")
c.save()
import json; json.dump(Y, open("lines.json", "w")); print(Y)
