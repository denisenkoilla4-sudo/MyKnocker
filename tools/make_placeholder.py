#!/usr/bin/env python3
"""Draws a simple placeholder figure (pale face, dark clothes) so the mod works before you add your photo."""
import os
from PIL import Image, ImageDraw, ImageFilter

W, H = 420, 960
im = Image.new("RGBA", (W, H), (0, 0, 0, 0))
d = ImageDraw.Draw(im)
cx = W // 2

# legs
d.rectangle([cx - 62, 560, cx - 8, 940], fill=(24, 24, 28, 255))
d.rectangle([cx + 8, 560, cx + 62, 940], fill=(24, 24, 28, 255))
d.rectangle([cx - 70, 920, cx - 4, 958], fill=(10, 10, 12, 255))
d.rectangle([cx + 4, 920, cx + 70, 958], fill=(10, 10, 12, 255))
# torso
d.rounded_rectangle([cx - 82, 200, cx + 82, 580], radius=30, fill=(34, 36, 42, 255))
d.rectangle([cx - 8, 215, cx + 8, 560], fill=(18, 18, 22, 255))
# arms
d.rounded_rectangle([cx - 132, 205, cx - 84, 600], radius=22, fill=(30, 32, 38, 255))
d.rounded_rectangle([cx + 84, 205, cx + 132, 600], radius=22, fill=(30, 32, 38, 255))
d.ellipse([cx - 134, 590, cx - 82, 650], fill=(190, 180, 170, 255))
d.ellipse([cx + 82, 590, cx + 134, 650], fill=(190, 180, 170, 255))
# neck + head
d.rectangle([cx - 20, 150, cx + 20, 215], fill=(196, 186, 176, 255))
d.ellipse([cx - 70, 10, cx + 70, 180], fill=(206, 196, 186, 255))
# hair
d.pieslice([cx - 76, 0, cx + 76, 150], 180, 360, fill=(20, 16, 14, 255))
# eyes (dark hollows), nose, mouth
for ex in (cx - 30, cx + 30):
    d.ellipse([ex - 15, 70, ex + 15, 104], fill=(12, 10, 10, 255))
    d.ellipse([ex - 4, 82, ex + 4, 92], fill=(235, 235, 235, 255))
d.polygon([(cx, 100), (cx - 8, 128), (cx + 8, 128)], fill=(170, 158, 148, 255))
d.arc([cx - 30, 120, cx + 30, 165], 20, 160, fill=(40, 14, 14, 255), width=5)

im = im.filter(ImageFilter.GaussianBlur(0.6))
out = os.path.join(os.path.dirname(__file__), "placeholder_person.png")
im.save(out)
print("wrote", out)
