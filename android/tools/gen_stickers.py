#!/usr/bin/env python3
"""Generates the built-in Minigram sticker pack (die-cut WebP stickers) into app/src/main/assets/stickers.
Run: python3 android/tools/gen_stickers.py   (needs Pillow)"""
import math, os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageChops

OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'stickers')
FONT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res', 'font', 'inter_bold.ttf')
S = 1024            # working canvas
INK = (43, 35, 64, 255)
W = 22              # outline width

def font(sz): return ImageFont.truetype(FONT, sz)

def new(): return Image.new('RGBA', (S, S), (0, 0, 0, 0))

def ell(d, cx, cy, rx, ry, fill, outline=INK, w=W):
    d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=fill, outline=outline, width=w)

def arc(d, cx, cy, rx, ry, a0, a1, w=W, col=INK):
    d.arc([cx - rx, cy - ry, cx + rx, cy + ry], a0, a1, fill=col, width=w)
    for a in (a0, a1):   # round caps
        x = cx + rx * math.cos(math.radians(a)); y = cy + ry * math.sin(math.radians(a))
        d.ellipse([x - w / 2, y - w / 2, x + w / 2, y + w / 2], fill=col)

def heart(d, cx, cy, sz, fill=(236, 72, 108, 255), outline=INK, w=14):
    pts = []
    for t in range(0, 360, 4):
        r = math.radians(t)
        x = 16 * math.sin(r) ** 3
        y = 13 * math.cos(r) - 5 * math.cos(2 * r) - 2 * math.cos(3 * r) - math.cos(4 * r)
        pts.append((cx + x * sz / 17, cy - y * sz / 17))
    d.polygon(pts, fill=fill, outline=outline, width=w)

def star(d, cx, cy, r, fill=(250, 204, 21, 255), outline=INK, w=10):
    pts = []
    for i in range(10):
        rr = r if i % 2 == 0 else r * 0.45
        a = math.radians(-90 + i * 36)
        pts.append((cx + rr * math.cos(a), cy + rr * math.sin(a)))
    d.polygon(pts, fill=fill, outline=outline, width=w)

def tear(d, x, y, s=44):
    d.polygon([(x, y - s), (x - s * .6, y + s * .3), (x + s * .6, y + s * .3)], fill=(96, 165, 250, 255))
    d.ellipse([x - s * .6, y - s * .3, x + s * .6, y + s * .9], fill=(96, 165, 250, 255))

def eyes(d, kind, cx=512, cy=470, gap=150):
    L, R = cx - gap, cx + gap
    if kind == 'dot':
        for x in (L, R): ell(d, x, cy, 30, 40, INK, INK, 0)
    elif kind == 'happy':
        for x in (L, R): arc(d, x, cy + 20, 46, 46, 200, 340, 24)
    elif kind == 'closed':
        for x in (L, R): arc(d, x, cy - 10, 46, 36, 20, 160, 22)
    elif kind == 'heart':
        for x in (L, R): heart(d, x, cy, 70, w=0, outline=None)
    elif kind == 'star':
        for x in (L, R): star(d, x, cy, 56)
    elif kind == 'wide':
        for x in (L, R):
            ell(d, x, cy, 56, 62, (255, 255, 255, 255), INK, 14); ell(d, x, cy + 6, 22, 26, INK, INK, 0)
    elif kind == 'cry':
        for x in (L, R): ell(d, x, cy, 30, 40, INK, INK, 0)
        tear(d, L - 20, cy + 90); tear(d, R + 20, cy + 90)
    elif kind == 'wink':
        ell(d, L, cy, 30, 40, INK, INK, 0); arc(d, R, cy + 10, 46, 40, 200, 340, 24)
    elif kind == 'shades':
        d.rounded_rectangle([L - 110, cy - 55, L + 20, cy + 45], 36, fill=INK)
        d.rounded_rectangle([R - 20, cy - 55, R + 110, cy + 45], 36, fill=INK)
        d.rectangle([L + 10, cy - 30, R - 10, cy - 8], fill=INK)
        d.line([L - 80, cy - 25, L - 40, cy - 25], fill=(255, 255, 255, 140), width=10)
        d.line([R + 40, cy - 25, R + 80, cy - 25], fill=(255, 255, 255, 140), width=10)
    elif kind == 'angry':
        for x in (L, R): ell(d, x, cy + 10, 28, 36, INK, INK, 0)
        d.line([L - 70, cy - 70, L + 50, cy - 30], fill=INK, width=30)
        d.line([R + 70, cy - 70, R - 50, cy - 30], fill=INK, width=30)
    elif kind == 'sleep':
        for x in (L, R): arc(d, x, cy - 10, 46, 36, 20, 160, 22)

def mouth(d, kind, cx=512, cy=640):
    if kind == 'smile': arc(d, cx, cy - 30, 110, 80, 25, 155, 24)
    elif kind == 'grin':
        d.pieslice([cx - 150, cy - 110, cx + 150, cy + 130], 0, 180, fill=INK)
        d.chord([cx - 90, cy + 20, cx + 90, cy + 120], 180, 360, fill=(244, 114, 114, 255))
        d.rectangle([cx - 120, cy - 20, cx + 120, cy + 4], fill=(255, 255, 255, 255)) if False else None
    elif kind == 'laugh':
        d.pieslice([cx - 160, cy - 120, cx + 160, cy + 150], 0, 180, fill=INK)
        d.pieslice([cx - 100, cy + 10, cx + 100, cy + 160], 180, 360, fill=(244, 114, 114, 255)) if False else None
        ell(d, cx, cy + 70, 80, 44, (244, 114, 114, 255), None, 0)
    elif kind == 'flat': d.line([cx - 80, cy, cx + 80, cy], fill=INK, width=24)
    elif kind == 'frown': arc(d, cx, cy + 90, 100, 70, 205, 335, 24)
    elif kind == 'o': ell(d, cx, cy + 10, 44, 56, INK, INK, 0)
    elif kind == 'tongue':
        arc(d, cx, cy - 30, 110, 80, 25, 155, 24)
        d.rounded_rectangle([cx - 36, cy + 30, cx + 36, cy + 120], 30, fill=(244, 114, 114, 255), outline=INK, width=14)
    elif kind == 'kiss':
        ell(d, cx, cy + 10, 30, 30, (236, 72, 108, 255), INK, 12)
    elif kind == 'big': arc(d, cx, cy - 50, 150, 110, 20, 160, 26)

def blush(d, cx=512, cy=580, gap=250):
    for x in (cx - gap, cx + gap): ell(d, x, cy, 52, 30, (251, 146, 160, 190), None, 0)

def body(d, color, shape='round'):
    if shape == 'round': ell(d, 512, 540, 330, 330, color)
    elif shape == 'bean': ell(d, 512, 540, 320, 345, color)
    # glossy highlight
    d.pieslice([300, 260, 520, 440], 190, 280, fill=(255, 255, 255, 70))

def zzz(d):
    f = font(120)
    for i, (x, y, s) in enumerate([(780, 260, 120), (850, 160, 90), (900, 90, 64)]):
        d.text((x, y), 'Z', font=font(s), fill=INK)

def party_hat(d, cx=512, top=140):
    d.polygon([(cx, top), (cx - 120, top + 260), (cx + 120, top + 260)], fill=(250, 204, 21, 255), outline=INK, width=16)
    for i in range(3): d.line([(cx - 80 + i * 50, top + 220 - i * 5), (cx - 30 + i * 30, top + 90 - i * 10)], fill=(236, 72, 108, 255), width=14)
    ell(d, cx, top, 28, 28, (236, 72, 108, 255), INK, 12)

def confetti(d):
    cols = [(236, 72, 108, 255), (59, 130, 246, 255), (34, 197, 94, 255), (250, 204, 21, 255)]
    pts = [(120, 200), (900, 240), (160, 820), (870, 800), (80, 500), (950, 520), (300, 90), (730, 70)]
    for i, (x, y) in enumerate(pts): d.rounded_rectangle([x - 18, y - 36, x + 18, y + 36], 8, fill=cols[i % 4])

def hand_thumb(d):
    skin = (253, 224, 71, 255)
    d.rounded_rectangle([330, 470, 700, 840], 80, fill=skin, outline=INK, width=W)
    d.rounded_rectangle([420, 170, 560, 520], 70, fill=skin, outline=INK, width=W)
    d.rounded_rectangle([300, 520, 360, 800], 28, fill=(59, 130, 246, 255), outline=INK, width=16)
    for y in (600, 680, 760): d.line([430, y, 690, y], fill=INK, width=14)

def hand_ok(d):
    skin = (253, 224, 71, 255)
    ell(d, 512, 560, 270, 270, skin)
    ell(d, 470, 470, 120, 120, (255, 255, 255, 0), INK, W)
    for i in range(3): d.rounded_rectangle([590 + i * 40, 150 + i * 30, 660 + i * 40, 470], 36, fill=skin, outline=INK, width=W)

def make_face(name, color, eye, mouth_k, extras=()):
    im = new(); d = ImageDraw.Draw(im)
    body(d, color)
    if 'hat' in extras: pass
    if 'blush' in extras: blush(d)
    eyes(d, eye); mouth(d, mouth_k)
    if 'zzz' in extras: zzz(d)
    if 'hat' in extras: party_hat(d, 512, 70); confetti(d)
    if 'sweat' in extras: tear(d, 800, 330, 50)
    if 'hearts' in extras:
        heart(d, 820, 220, 90); heart(d, 230, 180, 60)
    if 'stars' in extras:
        star(d, 830, 200, 60); star(d, 200, 250, 44)
    return im

def text_sticker(name, text, fg, bg, tilt=-6, sub=None):
    im = new(); d = ImageDraw.Draw(im)
    lines = text.split('\n')
    sz = 260 if max(len(l) for l in lines) <= 5 else 200 if max(len(l) for l in lines) <= 7 else 150 if max(len(l) for l in lines) <= 10 else 112
    f = font(sz)
    wmax = max(d.textlength(l, font=f) for l in lines)
    hh = sz * 1.1 * len(lines)
    x0, y0 = 512 - wmax / 2 - 70, 512 - hh / 2 - 70
    d.rounded_rectangle([x0 + 14, y0 + 14, 512 + wmax / 2 + 84, 512 + hh / 2 + 84], 110, fill=INK)
    d.rounded_rectangle([x0, y0, 512 + wmax / 2 + 70, 512 + hh / 2 + 70], 110, fill=bg, outline=INK, width=W)
    for i, l in enumerate(lines):
        lw = d.textlength(l, font=f)
        d.text((512 - lw / 2 + 5, y0 + 56 + i * sz * 1.1 + 6), l, font=f, fill=INK)
        d.text((512 - lw / 2, y0 + 56 + i * sz * 1.1), l, font=f, fill=fg)
    return im.rotate(tilt, resample=Image.BICUBIC, center=(512, 512))

def die_cut(im):
    a = im.split()[3]
    big = a.resize((S, S))
    for _ in range(3): big = big.filter(ImageFilter.MaxFilter(15))
    big = big.filter(ImageFilter.GaussianBlur(3)).point(lambda v: 255 if v > 90 else 0)
    base = Image.new('RGBA', (S, S), (255, 255, 255, 0))
    white = Image.new('RGBA', (S, S), (255, 255, 255, 255)); base.paste(white, (0, 0), big)
    shadow = Image.new('RGBA', (S, S), (0, 0, 0, 0))
    sh = big.filter(ImageFilter.GaussianBlur(14)).point(lambda v: int(v * 0.30))
    shadow.paste(Image.new('RGBA', (S, S), (20, 10, 40, 255)), (0, 14), sh)
    out = Image.alpha_composite(shadow, base)
    return Image.alpha_composite(out, im)

Y = (253, 224, 71, 255); B = (125, 211, 252, 255); P = (249, 168, 212, 255); G = (134, 239, 172, 255); O = (253, 186, 116, 255); V = (196, 181, 253, 255)

FACES = [
    ('happy', Y, 'happy', 'smile', ('blush',)), ('laugh', Y, 'closed', 'laugh', ('blush', 'sweat')), ('love', P, 'heart', 'smile', ('hearts', 'blush')),
    ('cool', B, 'shades', 'smile', ()), ('cry', B, 'cry', 'frown', ()), ('angry', O, 'angry', 'frown', ()),
    ('sleepy', V, 'sleep', 'o', ('zzz',)), ('wow', Y, 'wide', 'o', ('stars',)), ('wink', G, 'wink', 'tongue', ()),
    ('party', P, 'happy', 'grin', ('hat', 'blush')), ('kiss', P, 'wink', 'kiss', ('hearts', 'blush')), ('shock', V, 'wide', 'flat', ('sweat',)),
    ('star', Y, 'star', 'grin', ('stars',)), ('sad', G, 'dot', 'frown', ('sweat',)),
]
TEXTS = [
    ('ok', 'OK', (255, 255, 255, 255), (34, 197, 94, 255)), ('lol', 'LOL', (255, 255, 255, 255), (236, 72, 108, 255)),
    ('thanks', 'Thanks!', (255, 255, 255, 255), (59, 130, 246, 255)), ('wow_t', 'WOW', INK, (250, 204, 21, 255)),
    ('haan', 'Haan\nbhai', (255, 255, 255, 255), (124, 58, 237, 255)), ('nahi', 'Nahi\nyaar', (255, 255, 255, 255), (220, 38, 38, 255)),
    ('chai', 'Chai?', (255, 255, 255, 255), (180, 83, 9, 255)), ('party_t', 'Party!', INK, (244, 114, 182, 255)),
    ('love_t', 'Love you', (255, 255, 255, 255), (225, 29, 72, 255)), ('gn', 'Good\nnight', (255, 255, 255, 255), (30, 58, 138, 255)),
    ('gm', 'Good\nmorning', INK, (251, 191, 36, 255)), ('omw', 'On my\nway', (255, 255, 255, 255), (2, 132, 199, 255)),
    ('sahi', 'Sahi hai', (255, 255, 255, 255), (13, 148, 136, 255)), ('arre', 'Arre\nwah!', INK, (253, 186, 116, 255)),
]

def save(name, im):
    im = die_cut(im).resize((384, 384), Image.LANCZOS)
    im.save(os.path.join(OUT, name + '.webp'), 'WEBP', quality=88, method=6)

def main():
    os.makedirs(OUT, exist_ok=True)
    for n, c, e, m, ex in FACES: save('face_' + n, make_face(n, c, e, m, ex))
    im = new(); hand_thumb(ImageDraw.Draw(im)); save('hand_like', im)
    im = new(); d = ImageDraw.Draw(im); heart(d, 512, 540, 360, w=22); star(d, 810, 210, 70); star(d, 210, 260, 50); save('big_heart', im)
    im = new(); d = ImageDraw.Draw(im); star(d, 512, 520, 360, w=22); save('big_star', im)
    for n, t, fg, bg in TEXTS: save('word_' + n, text_sticker(n, t, fg, bg, tilt=-5 if len(n) % 2 else 4))
    print('wrote', len(os.listdir(OUT)), 'stickers to', os.path.abspath(OUT))

if __name__ == '__main__': main()
