# Page bodies for the WeTalks website. Each body(ctx) returns HTML for <main>.
ICONS = {
 'download': '<path d="M12 3v12m0 0 4-4m-4 4-4-4M5 19h14"/>',
 'moon': '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/>',
 'sun': '<path d="M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10zM12 1v2M12 21v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M1 12h2M21 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4"/>',
 'menu': '<path d="M3 6h18M3 12h18M3 18h18"/>',
 'lock': '<rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
 'phone': '<path d="M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2z"/>',
 'users': '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 20a6.5 6.5 0 0 1 13 0M16 4.5a3.5 3.5 0 0 1 0 7M18 14.5a6.5 6.5 0 0 1 3.5 5.5"/>',
 'file': '<path d="M14 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9zM14 3v6h6M8 13h8M8 17h6"/>',
 'mic': '<rect x="9" y="3" width="6" height="11" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/>',
 'globe': '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18"/>',
 'shield': '<path d="M12 3 4 6v6c0 5 3.4 8.3 8 9 4.6-.7 8-4 8-9V6z"/><path d="m9 12 2 2 4-4"/>',
 'code': '<path d="m8 8-4 4 4 4M16 8l4 4-4 4M14 5l-4 14"/>',
 'bolt': '<path d="M13 2 4 14h7l-1 8 9-12h-7z"/>',
 'bell': '<path d="M6 9a6 6 0 0 1 12 0c0 6 2 7 2 7H4s2-1 2-7zM10 20a2 2 0 0 0 4 0"/>',
 'check': '<path d="M5 12l5 5L20 7"/>',
 'palette': '<path d="M12 3a9 9 0 1 0 0 18c1.4 0 2-1 2-2 0-1.5-1-1.5-1-3a2 2 0 0 1 2-2h2a4 4 0 0 0 4-4c0-4-4-7-9-7z"/><circle cx="7.5" cy="11" r="1"/><circle cx="10" cy="7" r="1"/><circle cx="15" cy="7" r="1"/>',
 'camera': '<path d="M4 8h3l2-3h6l2 3h3a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z"/><circle cx="12" cy="13" r="4"/>',
 'key': '<circle cx="8" cy="15" r="4"/><path d="m11 12 9-9M16 7l3 3M14 9l2 2"/>',
 'server': '<rect x="3" y="4" width="18" height="7" rx="2"/><rect x="3" y="13" width="18" height="7" rx="2"/><path d="M7 7.5h.01M7 16.5h.01"/>',
 'eye-off': '<path d="M3 3l18 18M10.6 6.2A9.6 9.6 0 0 1 12 6c6 0 10 6 10 6a17 17 0 0 1-3.2 3.7M6.6 7.6A16 16 0 0 0 2 12s4 7 10 7a9 9 0 0 0 4-1"/>',
 'android': '<path d="M5 16a7 7 0 0 1 14 0zM8 6 6.5 3.5M16 6l1.5-2.5"/><path d="M9 12h.01M15 12h.01"/>',
}
CHECK = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12l5 5L20 7"/></svg>'

def card(c, ic, title, text):
    return f'<div class="card"><div class="ico">{c["icon"](ic)}</div><h3>{title}</h3><p>{text}</p></div>'

def head_block(title, text, eyebrow=''):
    return f'<div class="page-head"><div class="container">{f"<span class=eyebrow>{eyebrow}</span>" if eyebrow else ""}<h1 style="font-size:clamp(1.9rem,5.5vw,2.8rem)">{title}</h1><p>{text}</p></div></div>'

# ----------------------------------------------------------------------------------------------------------------- home
def home(c):
    i = c['icon']; v = c['info']
    feats = ''.join([
        card(c, 'lock', 'End-to-end encrypted', 'Messages, photos and voice notes are locked on your phone with keys only you and your contact hold.'),
        card(c, 'phone', 'Voice &amp; video calls', 'Clear one-to-one calls with a draggable mini window, camera flip and one-tap switch from voice to video.'),
        card(c, 'users', 'Groups &amp; channels', 'Group chats with roles and slow mode, plus public or private channels with a shareable link.'),
        card(c, 'file', 'Photos now, files soon', 'Send photos from the gallery or the camera today. Video, audio and documents up to 30 MB are coming soon with WeTalks Plus, each file locked with its own key.'),
        card(c, 'mic', 'Smarter voice notes', 'Record with live noise removal, read a transcript, translate it, or have a message read aloud.'),
        card(c, 'palette', 'Make it yours', 'Animated wallpapers and bubble styles, light and dark themes, and the app in many languages including Hindi and Hinglish.'),
    ])
    return f'''<section class="hero"><div class="container hero-grid"><div>
<span class="pill">{i("shield")} End-to-end encrypted · Android</span>
<h1>Private messaging. <span class="grad">Truly yours.</span></h1>
<p class="lead">WeTalks is a fast, private messenger with voice and video calls, groups, channels and photo sharing. Only you and the people you talk to can read your chats, not us, not anyone else.</p>
<div class="cta-row"><a class="btn-primary" href="/download.html">{i("download")} Download for Android</a><a class="btn" href="/features.html">See what it does</a></div>
<ul class="chips"><li>{CHECK} Free to use</li><li>{CHECK} No ads</li><li>{CHECK} Android 9 and up</li><li>{CHECK} <span data-latest="size">{v.get("size") or "~34 MB"}</span></li></ul></div>
<div class="phone float" aria-hidden="true"><div class="screen"><div class="bar"><div class="av">G</div><div><b>Guru</b><small>online</small></div></div>
<div class="chat"><div class="b in">Check this out 👀<i>11:02</i></div>
<div class="b out">Sent the design file<i>11:03 ✓✓</i></div>
<div class="b file"><span class="fi">PDF</span><span>Pricing-v2.pdf<br><small style="opacity:.6">1.2 MB</small></span></div>
<div class="b voice"><span class="pl"></span><span class="wave"></span><small>0:27</small></div>
<div class="b out">Perfect, calling you now 📞<i>11:05 ✓✓</i></div></div>
<div class="input"><span>Message</span><span>＋</span></div></div></div></div></section>

<section><div class="container"><div class="grid g4">
<div class="card stat"><b>30 MB</b><span>files, video and audio, coming soon</span></div><div class="card stat"><b>256-bit</b><span>AES-GCM encryption</span></div>
<div class="card stat"><b>0</b><span>ads or trackers</span></div><div class="card stat"><b>2</b><span>ways to build on it: chat and API</span></div></div></div></section>

<section class="alt"><div class="container"><div class="sec-head"><span class="eyebrow">Why WeTalks</span><h2>Everything you need to talk, in one place</h2><p>Simple on the surface, careful underneath. Every feature is explained on the <a href="/features.html">features page</a>.</p></div>
<div class="grid g3">{feats}</div></div></section>

<section><div class="container"><div class="dark-card"><div class="split"><div><span class="eyebrow" style="color:#9fd0ff">Security first</span><h2>We can't read your messages. By design.</h2>
<p>Your chats are encrypted on your phone before they leave it. Our servers only ever hold scrambled data they have no key for, and the keys never leave your device.</p>
<ul class="ticks"><li>{CHECK}<span>Per-device keys protected by the Android Keystore</span></li><li>{CHECK}<span>Every shared file is encrypted with its own random key</span></li><li>{CHECK}<span>Notifications carry no message text, only a nudge to wake the app</span></li><li>{CHECK}<span>Optional app lock and passphrase-protected key backup</span></li></ul>
<a class="btn" href="/security.html" style="margin-top:8px">How security works</a></div>
<div class="card" style="background:rgba(255,255,255,.08);border-color:rgba(255,255,255,.2);color:#fff"><h3 style="color:#fff">What our servers see</h3><div class="tablewrap" style="background:transparent;border-color:rgba(255,255,255,.2)"><table><tbody>
<tr><td>Your message</td><td class="mono" style="word-break:break-all">k8Zr…Q2xA (scrambled)</td></tr><tr><td>Your file</td><td class="mono">encrypted bytes</td></tr><tr><td>Your keys</td><td>never uploaded</td></tr></tbody></table></div><p style="color:rgba(255,255,255,.7);font-size:.85rem;margin:0">Account details and basic delivery data are needed to run the service. See the <a style="color:#9fd0ff" href="/privacy.html">privacy policy</a>.</p></div></div></div></div></section>

<section class="alt"><div class="container"><div class="sec-head"><span class="eyebrow">Get started</span><h2>Up and running in a minute</h2></div>
<div class="steps"><div class="step"><h3>Download</h3><p>Get the APK from the <a href="/download.html">download page</a>. Android asks you to allow installs from your browser once.</p></div>
<div class="step"><h3>Sign up</h3><p>Use your email or phone number, pick a name and a username. Create a key backup so you never lose old chats.</p></div>
<div class="step"><h3>Start talking</h3><p>Search a username, scan a QR code, or share a link. Chats, calls and files all work end-to-end encrypted.</p></div></div></div></section>

<section><div class="container"><div class="grid g2" style="align-items:center"><div><span class="eyebrow">For developers</span><h2>Reach your users on WeTalks</h2>
<p class="lead" style="font-size:1rem">Send one-time codes, alerts and follow-ups straight to people who use WeTalks, with a simple REST API, per-app keys and a friendly portal. Users stay in control and can block any app.</p>
<div class="cta-row"><a class="btn-primary" href="/developers/">Explore the API</a><a class="btn" href="/developers/portal.html">Open developer portal</a></div></div>
<div class="codebox"><pre><span class="tk-c"># send a verification code</span>
curl -X POST {c['site']}/api/v1/otp \\
  -H <span class="tk-s">"Authorization: Bearer wt_live_…"</span> \\
  -d <span class="tk-s">'{{"to":"@asha"}}'</span>

<span class="tk-c"># {{"otp_id":"otp_9f2…","expires_at":"…"}}</span></pre></div></div></div></section>

<section class="alt"><div class="container center"><h2>Ready to try WeTalks?</h2><p class="lead" style="margin:0 auto 18px">Free, private, and built for how people actually chat.</p>
<div class="cta-row" style="justify-content:center"><a class="btn-primary" href="/download.html">{i("download")} Download v<span data-latest="version">{v.get("version","")}</span></a></div></div></section>'''

# ----------------------------------------------------------------------------------------------------------------- features
def features(c):
    def blk(title, items):
        return f'<section><div class="container"><div class="sec-head"><h2>{title}</h2></div><div class="grid g3">' + ''.join(card(c, ic, t, d) for ic, t, d in items) + '</div></div></section>'
    return head_block('Features', 'Everything WeTalks can do today, explained plainly.', 'Features') + \
    blk('Messaging', [
      ('lock', 'End-to-end encryption', 'X25519 key agreement and AES-256-GCM. Group chats use a shared group key that changes when someone is removed.'),
      ('check', 'Replies, reactions, pins', 'Swipe a message to reply, react with animated emoji, pin important messages, forward, edit and delete for everyone.'),
      ('globe', 'Translate &amp; transcribe', 'Translate any message into your language and read a transcript of voice notes. Hindi, Hinglish and English work out of the box.'),
      ('mic', 'Read aloud, naturally', 'Tap Listen on a message, caption or transcript and it is read in the right language. Use your phone\'s voice, or download our optional human-like voices (English, Hindi, Hinglish, Spanish, French) that run entirely on your phone.'),
      ('bell', 'Notifications you control', 'Reply, mark as read or mute right from the notification. Calls ring even when the app is in the background.'),
      ('eye-off', 'Privacy controls', 'Choose who sees your last seen, online status and phone number. Turn read receipts off. Block anyone.')]) + \
    blk('Calls', [
      ('phone', 'Voice and video', 'Peer-to-peer calls over WebRTC with echo cancellation, speaker and mute controls.'),
      ('camera', 'Built for video', 'Rounded mini window you can drag, swap and hide, camera flip, and a one-tap switch from voice to video.'),
      ('bolt', 'Slide to answer', 'A smooth slide-up to answer and slide-down to decline. Nothing happens until you let go.')]) + \
    blk('Files &amp; media', [
      ('file', 'Documents <span class="badge warn">Coming soon</span>', 'PDF, Word, Excel, PowerPoint, text, CSV, e-books and archives up to 30 MB with WeTalks Plus. Dangerous file types such as apps and scripts are blocked.'),
      ('camera', 'Photos &amp; camera', 'Take a photo or pick several, edit with crop, filters, text and shapes, add a caption and send.'),
      ('shield', 'Checked twice', 'Files are validated for type, size and content on your phone and again on our server, then re-checked when they are opened.'),
      ('mic', 'Better voice notes', 'Hold to record with live noise removal. Slide to cancel, lock for hands-free. Extra clean-up tools are coming to WeTalks Plus.'),
      ('file', 'Video &amp; audio files <span class="badge warn">Coming soon</span>', 'Videos show a preview in the chat and play with a built-in player. Music and audio files too. Part of WeTalks Plus.'),
      ('palette', 'Smart storage', 'See what uses space, clear the cache and control what downloads over mobile data or Wi-Fi.')]) + \
    blk('Groups &amp; channels', [
      ('users', 'Groups', 'Add people, make admins, set who can send what and turn on slow mode. Removing someone rotates the group key.'),
      ('globe', 'Channels', 'Broadcast to followers with public or private channels and a short link such as wetalks.fireclashpro.com/c/yourchannel.'),
      ('key', 'Links and QR', 'Share a profile or channel by link or QR code. Links open straight in the app.')]) + \
    blk('Personalise', [
      ('palette', 'Wallpapers &amp; bubbles', 'More than ten animated wallpapers and bubble styles, plus light and dark themes.'),
      ('globe', 'Your language', 'The app speaks English, Hindi, Hinglish, Tamil, Marathi, Telugu, Spanish, French, Russian and more.'),
      ('lock', 'App lock', 'Protect WeTalks with your fingerprint or screen lock.')]) + \
    f'<section class="alt"><div class="container center"><h2>Try it yourself</h2><div class="cta-row" style="justify-content:center"><a class="btn-primary" href="/download.html">{c["icon"]("download")} Download WeTalks</a></div></div></section>'

# ----------------------------------------------------------------------------------------------------------------- security
def security(c):
    return head_block('Security', 'How WeTalks protects your conversations, and where the limits are. We prefer to be honest and specific.', 'Security') + f'''
<section><div class="container prose"><h2>The short version</h2>
<p>Everything you send is encrypted on your phone <strong>before</strong> it leaves it. Our servers store and forward scrambled data. The keys that unlock it stay on your device, so we can't read your chats, photos, voice notes or files, even if we wanted to or were asked to.</p>
<h2>How it works</h2><ul>
<li><strong>Keys.</strong> Each phone makes its own X25519 key pair. The private key is wrapped by the Android Keystore and never uploaded. The public key is published so contacts can start a chat.</li>
<li><strong>Chats.</strong> Two people derive a shared secret from their keys and use AES-256-GCM to encrypt every message. Groups use a random group key that is delivered to each member encrypted for them, and replaced when a member is removed.</li>
<li><strong>Files.</strong> Each file gets its own random 256-bit key and is encrypted in 1 MB pieces, so tampering is detected. The key travels inside the encrypted message. Our file server only sees encrypted bytes.</li>
<li><strong>Calls.</strong> Voice and video travel peer-to-peer (WebRTC, DTLS-SRTP). The call setup messages are encrypted with your chat key.</li>
<li><strong>Notifications.</strong> A push only says "something new" and which chat. It never contains a name or message text.</li></ul>
<h2>What we protect on the servers</h2><ul>
<li><strong>Sign-in checked everywhere.</strong> Every request to our media and developer servers carries a Firebase sign-in token that is verified against Google's public keys.</li>
<li><strong>Rules, not trust.</strong> Who can read or write a chat is enforced by database security rules that we test automatically (30+ checks), not by application code that could be bypassed.</li>
<li><strong>Files are stored outside the web root</strong> under random 128-bit names and are only released after the server confirms you belong to that chat.</li>
<li><strong>Limits.</strong> File type and size limits, per-user quota and rate limits on uploads, and rate limits on every API call.</li>
<li><strong>No admin keys on web servers.</strong> The developer API signs in as an ordinary, tightly-limited account. Powerful credentials are never stored on the website server.</li></ul>
<h2>What you should know (the honest part)</h2>
<div class="note warn"><strong>Service messages are different.</strong> Codes and alerts from apps that use the developer API are generated by their servers, so they are protected in transit and in storage but are not end-to-end encrypted. They are shown in a separate inbox, labelled with the app's name, and you can switch them off or block any app.</div>
<ul><li><strong>Metadata.</strong> To deliver messages we need to know who is talking to whom and when, plus your account details. We don't sell it or use it for ads.</li>
<li><strong>Lost phone, lost keys.</strong> Because only your device holds the keys, a reinstall can't read old chats unless you made a key backup. Create one in Settings → Privacy & security, protected by a passphrase only you know.</li>
<li><strong>Translation &amp; speech.</strong> Translating a message or transcribing a voice note sends that one piece of text or audio to an online engine when you ask for it. Nothing is sent unless you tap.</li>
<li><strong>Verify safety numbers</strong> with a contact if you need certainty that nobody is in the middle.</li></ul>
<h2>Report a vulnerability</h2><p>Found a problem? Please tell us before making it public so we can fix it for everyone. {('Write to <a href="mailto:%s">%s</a>.' % (c['support'], c['support'])) if c['support'] else 'A security contact address will be listed here soon.'}</p></div></section>'''

# ----------------------------------------------------------------------------------------------------------------- download
def download(c):
    v = c['info']; i = c['icon']
    return head_block('Download WeTalks', 'Free for Android. Install the app directly from this page.', 'Download') + f'''
<section><div class="container"><div class="dl"><div class="card"><div class="row" style="gap:14px;margin-bottom:6px"><img src="/assets/img/icon-64.png" width="56" height="56" alt="" style="border-radius:14px"><div><h2 style="margin:0;font-size:1.4rem">WeTalks for Android</h2><span class="badge ok">Version <span data-latest="version">{v.get("version","")}</span></span></div></div>
<dl class="kv"><dt>Size</dt><dd data-latest="size">{v.get("size","")}</dd><dt>Requires</dt><dd>Android 9 or newer, 64-bit phone</dd><dt>Updated</dt><dd data-latest="updated">{v.get("updated","")}</dd><dt>SHA-256</dt><dd class="mono" style="font-size:.78rem" data-latest="sha256">{v.get("sha256","")}</dd></dl>
<div class="cta-row"><a class="btn-primary" href="/download/WeTalks.apk" download>{i("download")} Download APK</a><a class="btn" href="#install">How to install</a></div>
<p class="muted" style="font-size:.88rem;margin:0">Older 32-bit phone? <a href="/download/WeTalks-armeabi.apk" download>Get the 32-bit build</a>.</p></div>
<div class="center"><div class="qr" role="img" aria-label="QR code that opens this page">{c['qr']}</div><p class="muted" style="font-size:.85rem;margin-top:10px">Scan with your phone to open this page</p></div></div></div></section>
<section class="alt" id="install"><div class="container"><div class="sec-head"><h2>Install in four steps</h2></div><div class="steps" style="grid-template-columns:1fr">
<div class="step"><h3>1. Download the file</h3><p>Tap <strong>Download APK</strong>. If your browser warns that this type of file can harm your device, that is Android's standard message for any app installed outside Google Play.</p></div>
<div class="step"><h3>2. Open it</h3><p>Tap the downloaded file. When Android asks, allow your browser to install apps ("Install unknown apps"), then go back and tap <strong>Install</strong>.</p></div>
<div class="step"><h3>3. Allow notifications</h3><p>Open WeTalks and allow notifications so calls and messages can reach you. On some phones also set battery to "No restrictions" and enable Autostart.</p></div>
<div class="step"><h3>4. Create your account</h3><p>Sign up, then open Settings → Privacy &amp; security and create a key backup.</p></div></div></div></section>
<section><div class="container prose"><h2>Verify your download (optional)</h2><p>Check that the file wasn't altered. The SHA-256 above must match the file you downloaded:</p>
<div class="codebox"><pre>sha256sum WeTalks.apk        <span class="tk-c"># Linux / Mac</span>
certutil -hashfile WeTalks.apk SHA256   <span class="tk-c"># Windows</span></pre></div>
<p>Releases are signed with the same key every time. Android refuses to install an update signed by anyone else.</p></div></section>'''

# ----------------------------------------------------------------------------------------------------------------- faq
FAQ = [
 ('Is WeTalks free?', 'Yes. Messaging, calls, groups, channels and photo sharing are free. Some advanced voice tools will be part of an optional WeTalks Plus later.'),
 ('Can you read my messages?', 'No. Messages and files are encrypted on your phone and we don\'t have the keys. See <a href="/security.html">Security</a> for exactly what we can and cannot see.'),
 ('Why isn\'t WeTalks on Google Play?', 'WeTalks is in early release, so it is shared as a direct download for now. The APK is signed with a fixed key, so updates install over it normally.'),
 ('I got new phone. How do I keep my old chats?', 'Create a key backup (Settings → Privacy &amp; security → Back up encryption key) protected by a passphrase. On the new phone sign in and choose Restore. Without a backup, old messages can\'t be decrypted on a new device.'),
 ('Why do I see "Can\'t decrypt this message"?', 'The message was encrypted for a key that is no longer on this phone, usually after clearing app data or reinstalling without a backup. New messages will work normally.'),
 ('What files can I send?', 'Today you can send photos (gallery or camera), voice notes and GIFs. Video, audio files and documents up to 30 MB are coming soon with WeTalks Plus. Apps, scripts and other risky file types will always be blocked.'),
 ('Do calls work when the app is closed?', 'Calls and messages arrive while WeTalks is running in the background. If your phone stops background apps aggressively, allow WeTalks to run in the background and enable Autostart. Instant wake-up for fully closed apps needs push services that are being rolled out.'),
 ('How do I read a message aloud?', 'Long-press a message and tap Listen. For voice notes, tap Transcribe first. The app picks the language automatically and uses the best voice installed on your phone.'),
 ('What are service messages?', 'Websites and apps that use the WeTalks developer API can send you one-time codes or alerts. They arrive in a separate "Service messages" inbox, never mixed with chats. You can turn them off or block any app in Privacy &amp; security.'),
 ('Is there an iPhone version?', 'Not yet. WeTalks is Android only for now.'),
 ('How do I delete my account?', 'You can delete chats from inside the app at any time. To remove your account and its data completely, use the contact details on this site and we will do it.'),
]
def faq(c):
    items = ''.join(f'<details><summary>{q}</summary><div class="ans">{a}</div></details>' for q, a in FAQ)
    return head_block('Frequently asked questions', 'Quick answers about WeTalks.', 'FAQ') + f'<section><div class="container prose">{items}<p class="muted">Still stuck? {("Write to <a href=\"mailto:%s\">%s</a>." % (c["support"], c["support"])) if c["support"] else "Support contact details will appear here soon."}</p></div></section>' + \
      '<script type="application/ld+json">' + __import__('json').dumps({"@context": "https://schema.org", "@type": "FAQPage", "mainEntity": [{"@type": "Question", "name": q, "acceptedAnswer": {"@type": "Answer", "text": __import__('re').sub('<[^>]+>', '', a).replace('&amp;', '&')}} for q, a in FAQ]}) + '</script>'

# ----------------------------------------------------------------------------------------------------------------- privacy / terms
def privacy(c):
    contact = ('Email <a href="mailto:%s">%s</a>.' % (c['support'], c['support'])) if c['support'] else 'Contact details will be published on this page.'
    return head_block('Privacy policy', 'What WeTalks collects, why, and the choices you have. Last updated: November 2026.', 'Legal') + f'''
<section><div class="container prose">
<p>WeTalks is a messaging app and service. This policy explains what information we handle. We keep it short and specific.</p>
<h2>Information we handle</h2><ul>
<li><strong>Account:</strong> your sign-in method (email address or phone number), display name, username, optional bio and profile photo.</li>
<li><strong>Messages and files:</strong> stored only in encrypted form. We cannot read them. They are kept until you or the sender delete them.</li>
<li><strong>Delivery data:</strong> which chats exist, message times, read state, online status and last seen (subject to your privacy settings), and notification tokens so we can wake your phone.</li>
<li><strong>Developer portal:</strong> if you use it, your email, your app names and API key fingerprints (never the keys themselves) and usage counts.</li>
<li><strong>Service messages:</strong> messages from apps that use our API are stored in your service inbox until you delete them.</li></ul>
<h2>What we do not do</h2><ul><li>We do not sell your data and we show no ads.</li><li>We do not read your end-to-end encrypted content and cannot give it to anyone.</li><li>We do not upload your contacts unless you choose to find friends by phone number, and then only a hash is used.</li></ul>
<h2>Services that help us run WeTalks</h2><ul><li><strong>Google Firebase</strong> for sign-in, the message database and notifications.</li><li><strong>Hostinger</strong> for this website, encrypted file storage and the developer API.</li><li><strong>Online translation and speech engines</strong>, only when you ask to translate a message or transcribe a voice note. That single piece of text or audio is sent to the engine.</li><li><strong>Natural voices</strong> are an optional download from our server. After that, reading aloud happens on your phone and the text is not sent anywhere.</li></ul>
<h2>Your choices</h2><ul><li>Control last seen, online status, phone number visibility and read receipts in Privacy &amp; security.</li><li>Turn off or block service messages at any time.</li><li>Delete messages for yourself or for everyone, and clear a chat's history.</li><li>Ask us to delete your account and associated data.</li></ul>
<h2>Security</h2><p>See the <a href="/security.html">security page</a> for how we protect data and the honest limits of that protection.</p>
<h2>Children</h2><p>WeTalks is not intended for children under 13.</p>
<h2>Changes</h2><p>If this policy changes in a meaningful way we will update the date above and, where it matters, tell you in the app.</p>
<h2>Contact</h2><p>{contact}</p></div></section>'''

def terms(c):
    return head_block('Terms of use', 'The rules for using WeTalks and its developer API. Last updated: November 2026.', 'Legal') + '''
<section><div class="container prose">
<p>By using WeTalks you agree to these terms. If you do not agree, please do not use the service.</p>
<h2>Using WeTalks</h2><ul><li>You must be at least 13 years old and able to form a binding agreement.</li><li>You are responsible for your account and for keeping your device and key backup passphrase safe.</li><li>Don't use WeTalks to harass, threaten, defraud or spam people, to spread malware, or for anything illegal.</li><li>Don't try to break, overload or reverse engineer the service, or to access other people's data.</li></ul>
<h2>Content</h2><p>You own what you send. Because messages are end-to-end encrypted we can't moderate private chats, but we may remove public channels and accounts that break these rules, and we may limit accounts that abuse the service.</p>
<h2>Developer API</h2><ul><li>API keys are secret and tied to your app. You are responsible for everything done with them. Revoke a key at once if it leaks.</li><li>Only send messages people expect from your app. Don't send marketing, phishing or anything misleading. Never ask users for their WeTalks password or encryption passphrase.</li><li>Don't impersonate WeTalks or other companies. App names are reviewed and we can rename, suspend or remove apps and keys that break these terms.</li><li>Respect the published rate limits. We may change limits to protect the service and the people who use it.</li><li>People can turn off or block your app at any time and you must respect that.</li></ul>
<h2>Availability and changes</h2><p>WeTalks is provided "as is", without warranties. We work hard to keep it running but can't promise it will always be available or error-free. We may change or discontinue features and will try to give notice for major changes.</p>
<h2>Liability</h2><p>To the extent the law allows, WeTalks is not liable for indirect or consequential loss, or for loss of messages caused by a lost device or missing key backup.</p>
<h2>Ending</h2><p>You can stop using WeTalks at any time. We can suspend or end access if these terms are broken.</p>
<h2>Changes to these terms</h2><p>We may update these terms. Continued use after an update means you accept the new version.</p></div></section>'''

# ----------------------------------------------------------------------------------------------------------------- developers
def dev_home(c):
    i = c['icon']
    return head_block('Build on WeTalks', 'A small, friendly REST API to send one-time codes, alerts and follow-ups to people on WeTalks. Per-app keys, clear limits, and users always in control.', 'Developers') + f'''
<section><div class="container"><div class="cta-row" style="margin-top:0"><a class="btn-primary" href="/developers/portal.html">Open developer portal</a><a class="btn" href="/developers/docs.html">Read the docs</a></div>
<div class="grid g3" style="margin-top:26px">{card(c,'bolt','Send in one request','POST a message or a verification code with a plain JSON body. No SDK needed.')}
{card(c,'key','Codes done right','WeTalks generates the code, delivers it and checks it. Your server never has to store or see the code.')}
{card(c,'shield','People stay in control','Every message is labelled with your app name. Users can switch service messages off or block your app, and we enforce it in the database rules.')}
{card(c,'server','Secure by design','Keys are stored as hashes, shown once, and can be revoked instantly. Every call is rate limited and logged for you.')}
{card(c,'code','Works from anywhere','Plain HTTPS and JSON. Examples for curl, Node.js, Python and PHP in the docs.')}
{card(c,'users','Free to start','Free plan with generous daily limits. Create an app and a key in under two minutes.')}</div></div></section>
<section class="alt"><div class="container"><div class="sec-head"><span class="eyebrow">Quickstart</span><h2>From zero to a delivered message in 3 steps</h2></div>
<div class="steps"><div class="step"><h3>Create an account</h3><p>Sign up in the <a href="/developers/portal.html">portal</a> with your email and verify it.</p></div><div class="step"><h3>Create an app and key</h3><p>Name your app, then create an API key. Copy it, it is shown once.</p></div><div class="step"><h3>Send a message</h3><p>Call the API with the key and a WeTalks username. It shows up in the person's service inbox.</p></div></div>
<div class="codebox mt"><pre>curl -X POST {c['site']}/api/v1/messages \\
  -H <span class="tk-s">"Authorization: Bearer wt_live_YOUR_KEY"</span> \\
  -H <span class="tk-s">"Content-Type: application/json"</span> \\
  -d <span class="tk-s">'{{"to":"@asha","title":"Order shipped","text":"Your order #1234 is on the way","kind":"alert"}}'</span></pre></div></div></section>'''

def sample(lang, body):
    return f'<div class="tabpanel" data-lang="{lang}"{"" if lang == "curl" else " hidden"}><div class="codebox"><pre>{body}</pre></div></div>'

def tabs(samples):
    t = ''.join(f'<button class="tab" type="button" role="tab" aria-selected="{"true" if k == 0 else "false"}" data-lang="{l}">{n}</button>' for k, (l, n, _) in enumerate(samples))
    return f'<div data-tabs><div class="tabs" role="tablist">{t}</div>' + ''.join(sample(l, b) for l, n, b in samples) + '</div>'

def endpoint(method, path, title, desc, req, resp, samples):
    cls = {'GET': 'm-get', 'POST': 'm-post', 'DELETE': 'm-del', 'PATCH': 'm-patch'}[method]
    return f'<h3 id="{path.strip("/").replace("/", "-")}"><span class="method {cls}">{method}</span><code>{path}</code></h3><p>{desc}</p>{req}{tabs(samples)}<p class="muted" style="margin-top:.6em"><strong>Response</strong></p><div class="codebox"><pre>{resp}</pre></div>'

def docs(c):
    S = c['site'] + '/api/v1'
    def smp(path, body, extra=''):
        j = body
        return [
         ('curl', 'curl', f'curl -X POST {S}{path} \\\n  -H "Authorization: Bearer $WETALKS_KEY" \\\n  -H "Content-Type: application/json" \\\n  -d \'{j}\''),
         ('node', 'Node.js', f'const res = await fetch("{S}{path}", {{\n  method: "POST",\n  headers: {{ "Authorization": `Bearer ${{process.env.WETALKS_KEY}}`, "Content-Type": "application/json" }},\n  body: JSON.stringify({j}),\n}});\nconsole.log(res.status, await res.json());'),
         ('python', 'Python', f'import os, requests\nr = requests.post("{S}{path}",\n    headers={{"Authorization": "Bearer " + os.environ["WETALKS_KEY"]}},\n    json={j})\nprint(r.status_code, r.json())'),
         ('php', 'PHP', f'$ch = curl_init("{S}{path}");\ncurl_setopt_array($ch, [CURLOPT_POST => true, CURLOPT_RETURNTRANSFER => true,\n  CURLOPT_HTTPHEADER => ["Authorization: Bearer " . getenv("WETALKS_KEY"), "Content-Type: application/json"],\n  CURLOPT_POSTFIELDS => \'{j}\']);\necho curl_exec($ch);'),
        ]
    def params(rows):
        return '<div class="tablewrap"><table><thead><tr><th>Field</th><th>Type</th><th>Description</th></tr></thead><tbody>' + ''.join(f'<tr><td><code>{a}</code></td><td>{b}</td><td>{d}</td></tr>' for a, b, d in rows) + '</tbody></table></div>'
    nav = ''.join(f'<a class="btn sm" href="#{i}">{t}</a> ' for i, t in [('intro', 'Overview'), ('auth', 'Authentication'), ('messages', 'Messages'), ('otp', 'Codes (OTP)'), ('usage', 'Usage'), ('errors', 'Errors'), ('limits', 'Limits'), ('security', 'Security tips'), ('changelog', 'Changelog')])
    return head_block('API documentation', 'Everything you need to send messages and verification codes through WeTalks.', 'API reference v1') + f'''
<section><div class="container prose" style="max-width:860px"><div class="row" style="margin-bottom:22px;gap:6px">{nav}</div>
<h2 id="intro">Overview</h2><p>The WeTalks API lets your server send a message or a verification code to a person who uses WeTalks. Messages appear in the person's <strong>Service messages</strong> inbox in the app, labelled with your app's name, and trigger a notification.</p>
<div class="tablewrap"><table><tbody><tr><td>Base URL</td><td><code>{S}</code></td></tr><tr><td>Format</td><td>JSON over HTTPS. Send <code>Content-Type: application/json</code>.</td></tr><tr><td>Auth</td><td>API key in the <code>Authorization</code> header</td></tr><tr><td>Recipient</td><td>A WeTalks username, with or without <code>@</code></td></tr></tbody></table></div>
<div class="note">Service messages are delivered to the person's inbox, not into an end-to-end encrypted chat, because your server (not their phone) creates the content. See <a href="/security.html">Security</a>.</div>

<h2 id="auth">Authentication</h2><p>Create an app in the <a href="/developers/portal.html">developer portal</a>, then create an API key. Keys start with <code>wt_live_</code> and are shown <strong>once</strong>. Send the key on every request:</p>
<div class="codebox"><pre>Authorization: Bearer wt_live_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</pre></div>
<p>Keep keys on your server. Never put one in a mobile app or website code. If a key leaks, revoke it in the portal and it stops working immediately. Test the key with:</p>
{endpoint('GET', '/ping', 'Ping', 'Checks that your key works and returns your app.', '', '{\n  "ok": true,\n  "app": { "id": "app_1a2b3c4d5e6f", "name": "Acme Shop", "verified": false }\n}', [('curl', 'curl', f'curl {S}/ping -H "Authorization: Bearer $WETALKS_KEY"')])}

<h2 id="messages">Send a message</h2>
{endpoint('POST', '/messages', 'Send message', 'Delivers a message to one person. They get a notification and can find it in Service messages.', params([('to', 'string, required', 'WeTalks username, e.g. <code>@asha</code>'), ('text', 'string, required', 'The message, 1 to 500 characters'), ('title', 'string', 'Short heading, up to 60 characters'), ('kind', 'string', '<code>info</code> (default), <code>alert</code> or <code>followup</code>'), ('link', 'string', 'Optional <code>https://</code> link, up to 200 characters. People are asked before it opens.')]),
 '{\n  "id": "71144c0c9e261f99",\n  "status": "delivered",\n  "to": "@asha"\n}', smp('/messages', '{"to":"@asha","title":"Order shipped","text":"Your order #1234 is on the way","kind":"alert","link":"https://shop.example.com/o/1234"}'))}
<p>Use <code>followup</code> for things the person asked for (a reminder, a status update). Do not send promotions.</p>

<h2 id="otp">Verification codes (OTP)</h2>
<p>WeTalks creates the code, delivers it to the person and checks it for you, so you never have to generate, store or protect the code yourself.</p>
<ol><li>Your user types their WeTalks username in your app.</li><li>You call <code>POST /otp</code>. WeTalks sends a code to their inbox and returns an <code>otp_id</code>.</li><li>The user types the code into your app.</li><li>You call <code>POST /otp/verify</code> with the <code>otp_id</code> and the code. If <code>valid</code> is true, they own that account.</li></ol>
{endpoint('POST', '/otp', 'Send code', 'Sends a one-time code. The response never contains the code.', params([('to', 'string, required', 'WeTalks username'), ('length', 'integer', '4 to 8 digits, default 6'), ('ttl', 'integer', 'Seconds the code is valid, 60 to 600, default 300')]),
 '{\n  "otp_id": "otp_4e763ad9a89da06026f5",\n  "to": "@asha",\n  "length": 6,\n  "expires_at": "2026-11-06T11:23:51+00:00"\n}', smp('/otp', '{"to":"@asha","length":6,"ttl":300}'))}
{endpoint('POST', '/otp/verify', 'Verify code', 'Checks a code. A code works once, expires after its time-to-live and is locked after 5 wrong tries.', params([('otp_id', 'string, required', 'The id returned by <code>/otp</code>'), ('code', 'string, required', 'The digits the user typed')]),
 '{ "valid": true }\n\n// or\n{ "valid": false, "reason": "invalid" }   // invalid | expired | used | too_many_attempts', smp('/otp/verify', '{"otp_id":"otp_4e763ad9a89da06026f5","code":"123456"}'))}

<h2 id="usage">Usage</h2>
{endpoint('GET', '/usage', 'Usage', 'Today\'s counts and your daily limits.', '', '{\n  "date": "2026-11-06",\n  "messages": { "used": 12, "limit": 200 },\n  "otps": { "used": 4, "limit": 100 },\n  "errors": 1\n}', [('curl', 'curl', f'curl {S}/usage -H "Authorization: Bearer $WETALKS_KEY"')])}

<h2 id="errors">Errors</h2><p>Errors use standard HTTP status codes and a JSON body:</p>
<div class="codebox"><pre>{{ "error": {{ "code": "user_not_found", "message": "no WeTalks user with that username" }} }}</pre></div>
<div class="tablewrap"><table><thead><tr><th>HTTP</th><th>code</th><th>Meaning</th></tr></thead><tbody>
<tr><td>401</td><td>—</td><td>Missing, malformed, wrong or revoked API key</td></tr>
<tr><td>403</td><td><code>recipient_not_accepting</code></td><td>The person turned off service messages or blocked your app. Don't retry, ask them to enable it.</td></tr>
<tr><td>404</td><td><code>user_not_found</code></td><td>No WeTalks user with that username</td></tr>
<tr><td>422</td><td><code>invalid_text</code>, <code>invalid_kind</code>, <code>invalid_link</code>, <code>invalid_length</code>, <code>invalid_ttl</code></td><td>The request body failed validation. The message explains what to fix.</td></tr>
<tr><td>429</td><td><code>daily_limit</code>, <code>recipient_limit</code>, <code>recipient_busy</code></td><td>A limit was reached. Check the <code>Retry-After</code> header.</td></tr></tbody></table></div>

<h2 id="limits">Limits (free plan)</h2><div class="tablewrap"><table><tbody>
<tr><td>Requests per key</td><td>120 per minute</td></tr><tr><td>Messages per app</td><td>200 per day</td></tr><tr><td>Codes per app</td><td>100 per day</td></tr>
<tr><td>Per person, per app</td><td>10 messages per day, 5 codes per hour</td></tr><tr><td>Per person, all apps</td><td>30 service messages per day</td></tr>
<tr><td>Apps / keys</td><td>3 apps per developer, 3 active keys per app</td></tr><tr><td>Text</td><td>500 characters (title 60, link 200)</td></tr></tbody></table></div>
<p>Need more? Tell us what you are building and we will raise your limits.</p>

<h2 id="security">Security tips</h2><ul><li>Store the key in an environment variable or secret manager, never in source control or client code.</li><li>Use one key per environment and revoke keys you no longer need.</li><li>Always verify codes on your server, never in the browser or app.</li><li>Tell users never to share codes, and never ask for a WeTalks password.</li><li>Rate limit the "send code" button in your own product to avoid annoying people.</li></ul>
<h2 id="changelog">Changelog</h2><div class="tablewrap"><table><tbody><tr><td>v1 · Nov 2026</td><td>First release: <code>/ping</code>, <code>/usage</code>, <code>/messages</code>, <code>/otp</code>, <code>/otp/verify</code>. Developer portal with apps, keys, usage and logs.</td></tr></tbody></table></div>
<p class="muted">Coming next: webhooks, higher limits, verified-app badges.</p></div></section>'''

def portal(c):
    return '<div class="container portal"><div id="app"><div class="center mt"><span class="spin"></span></div></div></div><div class="toast" id="toast" role="status" aria-live="polite"></div><noscript><div class="container"><p>The developer portal needs JavaScript.</p></div></noscript>'

PAGES = [
 {'path': 'index.html', 'title': 'WeTalks: private messaging with calls, groups, channels and files', 'desc': 'WeTalks is a private, end-to-end encrypted messenger for Android with voice and video calls, groups, channels, photos and a developer API.', 'body': home},
 {'path': 'features.html', 'title': 'Features', 'desc': 'Encrypted chats, voice and video calls, groups, channels, photos, voice notes with noise removal, translation and more.', 'body': features},
 {'path': 'security.html', 'title': 'Security', 'desc': 'How WeTalks protects your chats with end-to-end encryption, and the honest limits of that protection.', 'body': security},
 {'path': 'download.html', 'title': 'Download', 'desc': 'Download WeTalks for Android. Free, private messaging with calls, groups and files.', 'body': download},
 {'path': 'faq.html', 'title': 'FAQ', 'desc': 'Answers about WeTalks: encryption, backups, files, calls, service messages and more.', 'body': faq},
 {'path': 'privacy.html', 'title': 'Privacy policy', 'desc': 'What WeTalks collects, why, and the choices you have.', 'body': privacy},
 {'path': 'terms.html', 'title': 'Terms of use', 'desc': 'The rules for using WeTalks and its developer API.', 'body': terms},
 {'path': 'developers/index.html', 'title': 'Developers', 'desc': 'Send one-time codes, alerts and follow-ups to WeTalks users with a simple REST API.', 'body': dev_home},
 {'path': 'developers/docs.html', 'title': 'API documentation', 'desc': 'WeTalks API reference: authentication, messages, one-time codes, errors, limits and examples in curl, Node.js, Python and PHP.', 'body': docs},
 {'path': 'developers/portal.html', 'title': 'Developer portal', 'desc': 'Manage your WeTalks apps, API keys, usage and logs.', 'body': portal, 'scripts': ['/assets/js/portal.js'], 'noindex': True},
]
