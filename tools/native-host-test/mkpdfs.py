from reportlab.pdfgen import canvas
from reportlab.lib.pagesizes import letter
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
pdfmetrics.registerFont(TTFont("DJS", "/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf"))
pdfmetrics.registerFont(TTFont("DJR", "/usr/share/fonts/truetype/dejavu/DejaVuSerif.ttf"))

# A: each visual line is ONE text object (typical simple export)
c = canvas.Canvas("A_single.pdf", pagesize=letter)
c.setFont("DJS", 12); c.drawCentredString(306, 740, "KOFORIDUA TECHNICAL UNIVERSITY")
c.setFont("DJR", 10); c.drawString(72, 700, "Ghana Water Limited (GWL) is a state-owned utility company.")
c.save()

# B: each WORD is its own text object (what many exporters do for headings / justified text)
c = canvas.Canvas("B_perword.pdf", pagesize=letter)
c.setFont("DJS", 12)
x = 160
for w in ["KOFORIDUA", "TECHNICAL", "UNIVERSITY"]:
    c.drawString(x, 740, w); x += c.stringWidth(w + " ", "DJS", 12)
c.setFont("DJR", 10)
x = 72
for w in "Ghana Water Limited (GWL) is a state-owned utility company.".split(" "):
    c.drawString(x, 700, w); x += c.stringWidth(w + " ", "DJR", 10)
c.save()
print("ok")
