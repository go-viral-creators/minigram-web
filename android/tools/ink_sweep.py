#!/usr/bin/env python3
"""One-off: map hard-coded colours in res/layout/*.xml to the Ink & Paper theme colours.
Attribute-aware (text vs background vs tint), so dark mode works and white-on-accent text stays readable.
Run:  python3 -I tools/ink_sweep.py app/src/main/res/layout"""
import re, sys, glob, os

BG = {  # backgrounds / card fills / dividers
 'FFFFFF': 'ink_surface', 'F0F0F0': 'ink_surface_alt', 'F5F5F5': 'ink_surface_alt', 'F5F5F7': 'ink_surface_alt',
 'F0F2F5': 'ink_surface_alt', 'EEEEEE': 'ink_surface_alt', 'FAFAFA': 'ink_bg', 'E0E0E0': 'ink_line',
 'BDBDBD': 'ink_line', 'E8F5FC': 'ink_accent_soft', 'E3F2FD': 'ink_accent_soft',
}
ACCENT = {'229ED9', '2196F3', '1A73E8', '1565C0', '1976D2', '2E3248', '3F51B5', '7986CB'}
TEXT = {'000000': 'ink_text', '212121': 'ink_text', '1A1A1A': 'ink_text', '111111': 'ink_text', '424242': 'ink_text',
        '9E9E9E': 'ink_text_2', '757575': 'ink_text_2', '8E8E93': 'ink_text_2', '8E8E8E': 'ink_text_2',
        '616161': 'ink_text_2', 'BDBDBD': 'ink_text_2', '607D8B': 'ink_text_2'}
DANGER = {'E53935', 'FF3B30', 'F44336', 'EF5350', 'B71C1C'}
OK = {'4CAF50', '388E3C', '1B5E20', '25D366'}

TAG = re.compile(r'<[A-Za-z][\w.]*(?:\s[^<>]*)?>', re.S)
ATTR = re.compile(r'(android:(?:background|backgroundTint|textColor|tint|textColorHint|src|color)|app:(?:cardBackgroundColor|tint|strokeColor|backgroundTint|iconTint|boxStrokeColor))="#([0-9A-Fa-f]{6})"')

def map_tag(tag):
    bg_accent = False
    for m in re.finditer(r'android:background(?:Tint)?="#([0-9A-Fa-f]{6})"', tag):
        if m.group(1).upper() in ACCENT: bg_accent = True
    def sub(m):
        attr, hx = m.group(1), m.group(2).upper()
        is_text = attr.endswith(('textColor', 'textColorHint'))
        is_bg = attr.endswith(('background', 'cardBackgroundColor', 'backgroundTint', 'strokeColor', 'boxStrokeColor'))
        name = None
        if hx in ACCENT: name = 'ink_accent'
        elif hx in DANGER: name = 'ink_danger'
        elif hx in OK: name = 'ink_ok'
        elif is_text and hx in TEXT: name = TEXT[hx]
        elif is_text and hx == 'FFFFFF' and bg_accent: name = 'ink_on_accent'
        elif is_bg and hx in BG: name = BG[hx]
        elif attr.endswith('tint') and hx in TEXT: name = TEXT[hx]
        return f'{attr}="@color/{name}"' if name else m.group(0)
    return ATTR.sub(sub, tag)

changed = 0
for f in sorted(glob.glob(os.path.join(sys.argv[1], '*.xml'))):
    s = open(f, encoding='utf-8').read()
    n = TAG.sub(lambda m: map_tag(m.group(0)), s)
    if n != s:
        open(f, 'w', encoding='utf-8').write(n); changed += 1
print('layouts changed:', changed)
