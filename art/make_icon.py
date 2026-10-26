"""Trade Seeker's icon, in the house style (Hoard Eye, Naughty or Nice): drawn at 1024 on the same dark tile, scaled down."""
import math
from PIL import Image, ImageDraw, ImageFilter, ImageChops

S = 1024
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
# The tile, as on the other mods.
d.rounded_rectangle((24, 24, S - 24, S - 24), radius=190, fill=(25, 29, 36, 255), outline=(48, 56, 66, 255), width=14)

GOLD, GOLD_DARK = (255, 176, 32, 255), (176, 104, 10, 255)

# The re-roll arrow: a thick ring with a gap at the top right, and a head where it ends.
cx, cy, r, w = 512, 500, 330, 64
start, end = -50, 250  # degrees, clockwise from 3 o'clock (PIL measures clockwise)
def arc(draw, colour, width, a0, a1, rad):
    draw.arc((cx - rad, cy - rad, cx + rad, cy + rad), a0, a1, fill=colour, width=width)
arc(d, GOLD_DARK, w + 20, start, end, r + 10)
arc(d, GOLD, w, start, end, r)
# Arrow head at the end of the arc, pointing on clockwise.
a = math.radians(end)
hx, hy = cx + r * math.cos(a) - w / 2 * math.cos(a), cy + r * math.sin(a) - w / 2 * math.sin(a)
tangent = a + math.pi / 2
tip = (hx + 120 * math.cos(tangent), hy + 120 * math.sin(tangent))
side1 = (hx + 95 * math.cos(a), hy + 95 * math.sin(a))
side2 = (hx - 95 * math.cos(a) + 10 * math.cos(tangent), hy - 95 * math.sin(a) + 10 * math.sin(tangent))
d.polygon([tip, side1, side2], fill=GOLD, outline=GOLD_DARK)
d.line([tip, side1, side2, tip], fill=GOLD_DARK, width=14, joint="curve")

# The enchanted book, open, in the middle: purple cover, cream pages, a spine.
COVER, COVER_DARK, PAGE, PAGE_SHADE, INK = (132, 64, 210, 255), (62, 24, 110, 255), (246, 240, 224, 255), (214, 204, 182, 255), (40, 22, 60, 255)
bx, by = 512, 520
cover = [(bx - 250, by - 130), (bx, by - 90), (bx + 250, by - 130), (bx + 250, by + 150), (bx, by + 190), (bx - 250, by + 150)]
d.polygon(cover, fill=COVER, outline=COVER_DARK)
d.line(cover + [cover[0]], fill=COVER_DARK, width=18, joint="curve")
left = [(bx - 215, by - 150), (bx - 10, by - 110), (bx - 10, by + 150), (bx - 215, by + 112)]
right = [(bx + 215, by - 150), (bx + 10, by - 110), (bx + 10, by + 150), (bx + 215, by + 112)]
for page in (left, right):
    d.polygon(page, fill=PAGE, outline=INK)
    d.line(page + [page[0]], fill=INK, width=10, joint="curve")
d.line([(bx, by - 100), (bx, by + 168)], fill=COVER_DARK, width=22)
# Lines of text on the pages (the enchantment).
for i in range(4):
    yy = by - 70 + i * 52
    d.line([(bx - 175, yy - 18 + i * 2), (bx - 50, yy + 2 + i * 2)], fill=PAGE_SHADE, width=16)
    d.line([(bx + 50, yy + 2 + i * 2), (bx + 175, yy - 18 + i * 2)], fill=PAGE_SHADE, width=16)

# The enchantment glint: a soft purple glow behind, and two sparkles.
glow = Image.new("L", (S, S), 0)
ImageDraw.Draw(glow).polygon(cover, fill=255)
glow = glow.filter(ImageFilter.GaussianBlur(40)).point(lambda v: int(v * 0.45))
halo = Image.new("RGBA", (S, S), (190, 120, 255, 0))
halo.putalpha(glow)
img = Image.alpha_composite(halo, img)
d = ImageDraw.Draw(img)
def sparkle(x, y, size, colour):
    d.polygon([(x, y - size), (x + size * 0.28, y - size * 0.28), (x + size, y), (x + size * 0.28, y + size * 0.28),
               (x, y + size), (x - size * 0.28, y + size * 0.28), (x - size, y), (x - size * 0.28, y - size * 0.28)], fill=colour)
sparkle(bx - 120, by - 230, 62, (236, 210, 255, 255))
sparkle(bx + 150, by - 255, 40, (255, 255, 255, 255))

# The price: an emerald in the bottom right corner, Minecraft's cut.
ex, ey, er = 760, 770, 130
gem = [(ex, ey - er), (ex + er * 0.72, ey - er * 0.45), (ex + er * 0.72, ey + er * 0.45), (ex, ey + er), (ex - er * 0.72, ey + er * 0.45), (ex - er * 0.72, ey - er * 0.45)]
shadow = Image.new("L", (S, S), 0)
ImageDraw.Draw(shadow).polygon([(x + 10, y + 14) for x, y in gem], fill=255)
sh = Image.new("RGBA", (S, S), (0, 0, 0, 0))
sh.putalpha(shadow.filter(ImageFilter.GaussianBlur(12)).point(lambda v: int(v * 0.6)))
img = Image.alpha_composite(img, sh)
d = ImageDraw.Draw(img)
d.polygon(gem, fill=(38, 196, 96, 255))
inner = [(ex, ey - er * 0.6), (ex + er * 0.42, ey - er * 0.26), (ex + er * 0.42, ey + er * 0.26), (ex, ey + er * 0.6), (ex - er * 0.42, ey + er * 0.26), (ex - er * 0.42, ey - er * 0.26)]
d.polygon(inner, fill=(92, 236, 140, 255))
d.polygon([(ex - er * 0.42, ey - er * 0.26), (ex, ey - er * 0.6), (ex, ey - er * 0.1), (ex - er * 0.2, ey)], fill=(190, 255, 210, 255))
d.line(gem + [gem[0]], fill=(10, 80, 40, 255), width=18, joint="curve")

for size, name in [(128, "icon.png"), (512, "icon-512.png")]:
    img.resize((size, size), Image.LANCZOS).save(name)
# Previews: how it looks in Mod Menu (32) and next to the others.
prev = Image.new("RGBA", (128 * 4 + 50, 128), (18, 18, 22, 255))
for i, p in enumerate(["/home/play/Code/Mods/hoard-eye/src/main/resources/assets/hoardeye/icon.png",
                       "/home/play/Code/Mods/naughty-or-nice/src/main/resources/assets/naughtyornice/icon.png",
                       "/home/play/Code/Mods/base-insurance/src/main/resources/assets/baseinsurance/icon.png", "icon.png"]):
    prev.paste(Image.open(p).convert("RGBA"), (i * 128, 0))
small = img.resize((32, 32), Image.LANCZOS)
prev.paste(small, (128 * 4 + 9, 48), small)
prev.save("compare.png")
print("ok")
