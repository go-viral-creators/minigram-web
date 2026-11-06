#!/usr/bin/env python3
"""Builds the WeTalks website (hosting/public) from hosting/site: shared header/footer + page bodies.
   usage: python3 tools/site/build.py [--apk dist/WeTalks-vX-arm64.apk]   (the APK is hashed for the download page; it is uploaded separately)"""
import hashlib, json, os, re, shutil, sys, io, datetime
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '..', '..'))
SRC = os.path.join(ROOT, 'hosting', 'site'); OUT = os.path.join(ROOT, 'hosting', 'public')
SITE = 'https://wetalks.fireclashpro.com'
SUPPORT_EMAIL = os.environ.get('WT_SUPPORT_EMAIL', '')      # shown only when set
sys.path.insert(0, os.path.dirname(__file__))
from pages import PAGES, ICONS  # noqa

def icon(name, cls=''):
    return '<svg viewBox="0 0 24 24" aria-hidden="true"%s>%s</svg>' % (' class="%s"' % cls if cls else '', ICONS[name])

NAV = [('features.html', 'Features'), ('security.html', 'Security'), ('developers/', 'Developers'), ('download.html', 'Download'), ('faq.html', 'FAQ')]

def head(p):
    t = p['title'] if p['path'] == 'index.html' else p['title'] + ' · WeTalks'
    canon = SITE + '/' + ('' if p['path'] == 'index.html' else p['path'].replace('index.html', ''))
    robots = '<meta name="robots" content="noindex">' if p.get('noindex') else ''
    ld = ''
    if p['path'] == 'index.html':
        ld = '<script type="application/ld+json">' + json.dumps({"@context": "https://schema.org", "@type": "SoftwareApplication", "name": "WeTalks", "operatingSystem": "Android 9+", "applicationCategory": "CommunicationApplication",
              "description": p['desc'], "url": SITE + "/", "downloadUrl": SITE + "/download.html", "offers": {"@type": "Offer", "price": "0", "priceCurrency": "USD"}}) + '</script>'
    return f'''<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>{t}</title><meta name="description" content="{p['desc']}"><meta name="theme-color" content="#1f6feb"><link rel="canonical" href="{canon}">{robots}
<meta property="og:type" content="website"><meta property="og:site_name" content="WeTalks"><meta property="og:title" content="{t}"><meta property="og:description" content="{p['desc']}"><meta property="og:url" content="{canon}"><meta property="og:image" content="{SITE}/assets/img/og.png">
<meta name="twitter:card" content="summary_large_image">
<link rel="icon" href="/assets/img/favicon-32.png" sizes="32x32" type="image/png"><link rel="apple-touch-icon" href="/assets/img/apple-touch-icon.png">
<link rel="preload" href="/assets/fonts/inter-latin.woff2" as="font" type="font/woff2" crossorigin><link rel="stylesheet" href="/assets/css/site.css">
<script>try{{var t=localStorage.getItem('wt-theme');if(t==='dark'||t==='light')document.documentElement.setAttribute('data-theme',t)}}catch(e){{}}</script>{ld}</head>'''

def nav(p):
    cur = p['path'].split('/')[0] if '/' in p['path'] else p['path']
    links = ''
    for href, label in NAV:
        key = href.split('/')[0]
        links += f'<a href="/{href}"{" aria-current=\"page\"" if key == cur else ""}>{label}</a>'
    return f'''<body><a class="skip" href="#main">Skip to content</a><header class="nav"><div class="container nav-in">
<a class="brand" href="/"><img src="/assets/img/icon-64.png" width="34" height="34" alt="">WeTalks</a>
<nav class="links" id="navLinks" aria-label="Main">{links}</nav>
<div class="nav-actions"><a class="btn-primary sm hide-sm" href="/download.html">{icon('download')} Download</a>
<button class="icon-btn theme-btn" id="themeBtn" type="button" aria-label="Switch light or dark theme"><span class="moon">{icon('moon')}</span><span class="sun">{icon('sun')}</span></button>
<button class="icon-btn menu-btn" id="menuBtn" type="button" aria-label="Menu" aria-expanded="false" aria-controls="navLinks">{icon('menu')}</button></div></div></header>'''

def foot():
    contact = f'<a href="mailto:{SUPPORT_EMAIL}">{SUPPORT_EMAIL}</a>' if SUPPORT_EMAIL else '<small>Support email coming soon</small>'
    y = datetime.date.today().year
    return f'''<footer><div class="container foot"><div><a class="brand" href="/"><img src="/assets/img/icon-64.png" width="34" height="34" alt="">WeTalks</a>
<p class="muted" style="margin:.8em 0 .4em;color:var(--muted)">Private messaging with calls, groups, channels and photos. End-to-end encrypted.</p>{contact}</div>
<div><h4>Product</h4><a href="/features.html">Features</a><a href="/security.html">Security</a><a href="/download.html">Download</a><a href="/faq.html">FAQ</a></div>
<div><h4>Developers</h4><a href="/developers/">Overview</a><a href="/developers/docs.html">API docs</a><a href="/developers/portal.html">Developer portal</a></div>
<div><h4>Legal</h4><a href="/privacy.html">Privacy policy</a><a href="/terms.html">Terms of use</a><small>© {y} WeTalks</small></div></div></footer>
<script src="/assets/js/site.js" defer></script>'''

def write(path, html):
    f = os.path.join(OUT, path); os.makedirs(os.path.dirname(f), exist_ok=True)
    open(f, 'w', encoding='utf-8').write(html)

def main():
    apk = None
    if '--apk' in sys.argv: apk = sys.argv[sys.argv.index('--apk') + 1]
    # clean output but keep the Firebase well-known folder
    keep = os.path.join(OUT, '.well-known')
    for e in os.listdir(OUT):
        if e == '.well-known': continue
        p = os.path.join(OUT, e); shutil.rmtree(p) if os.path.isdir(p) else os.remove(p)
    shutil.copytree(os.path.join(SRC, 'assets'), os.path.join(OUT, 'assets'))
    # images
    from PIL import Image, ImageDraw, ImageFont
    logo = Image.open(os.path.join(ROOT, 'dist', 'WeTalks-icon-512.png')).convert('RGBA')
    img = os.path.join(OUT, 'assets', 'img'); os.makedirs(img, exist_ok=True)
    for n, s in (('icon-64.png', 64), ('icon-192.png', 192), ('apple-touch-icon.png', 180), ('favicon-32.png', 32)): logo.resize((s, s), Image.LANCZOS).save(os.path.join(img, n), optimize=True)
    og = Image.new('RGB', (1200, 630), '#0b1d3f'); d = ImageDraw.Draw(og)
    for y in range(630):
        t = y / 630; d.line([(0, y), (1200, y)], fill=(int(11 + 20 * t), int(29 + 60 * t), int(63 + 150 * t)))
    og.paste(logo.resize((200, 200), Image.LANCZOS), (90, 140), logo.resize((200, 200), Image.LANCZOS))
    try:
        fb = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 84); fs = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 38)
    except Exception: fb = fs = ImageFont.load_default()
    d.text((330, 170), 'WeTalks', font=fb, fill='white'); d.text((330, 290), 'Private messaging. Truly yours.', font=fs, fill='#cfe0ff')
    d.text((330, 350), 'Calls · Groups · Channels · Developer API', font=fs, fill='#9fc0ff')
    og.save(os.path.join(img, 'og.png'), optimize=True)
    # download info
    info = {'version': '', 'size': '', 'sha256': '', 'file': ''}
    ver = re.search(r'versionName = "([^"]+)"', open(os.path.join(ROOT, 'app', 'build.gradle.kts')).read()).group(1)
    info['version'] = ver
    if apk and os.path.exists(apk):
        b = open(apk, 'rb').read(); info.update({'size': '%.1f MB' % (len(b) / 1048576), 'sha256': hashlib.sha256(b).hexdigest(), 'file': '/download/WeTalks.apk', 'updated': datetime.date.today().isoformat()})
    elif os.path.exists(os.path.join(SRC, 'latest.json')): info.update(json.load(open(os.path.join(SRC, 'latest.json'))))
    os.makedirs(os.path.join(OUT, 'download'), exist_ok=True)
    json.dump(info, open(os.path.join(OUT, 'download', 'latest.json'), 'w'), indent=1)
    json.dump(info, open(os.path.join(SRC, 'latest.json'), 'w'), indent=1)
    # QR for the download page
    import qrcode, qrcode.image.svg
    q = qrcode.make(SITE + '/download.html', image_factory=qrcode.image.svg.SvgPathImage, box_size=10, border=0)
    buf = io.BytesIO(); q.save(buf); qr = buf.getvalue().decode().split('?>', 1)[-1]
    for p in PAGES:
        body = p['body'](dict(icon=icon, info=info, qr=qr, site=SITE, support=SUPPORT_EMAIL))
        scripts = ''.join(f'<script src="{s}" defer></script>' for s in p.get('scripts', []))
        write(p['path'], head(p) + nav(p) + '<main id="main">' + body + '</main>' + foot() + scripts + '</body></html>')
    # deep-link card used by the app's https links
    shutil.copy(os.path.join(SRC, 'link.html'), os.path.join(OUT, 'link.html'))
    write('robots.txt', 'User-agent: *\nAllow: /\nDisallow: /api/\nDisallow: /c/\nDisallow: /u/\nSitemap: %s/sitemap.xml\n' % SITE)
    urls = [SITE + '/' + ('' if p['path'] == 'index.html' else p['path'].replace('index.html', '')) for p in PAGES if not p.get('noindex')]
    write('sitemap.xml', '<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">' + ''.join(f'<url><loc>{u}</loc></url>' for u in urls) + '</urlset>')
    print('built', len(PAGES), 'pages, version', ver)

main()
