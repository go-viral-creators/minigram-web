# WeTalks – deploy from the phone (Termux)

## One-time setup
```bash
pkg update && pkg install -y nodejs git
npm install -g firebase-tools
firebase login --no-localhost        # open the link in Chrome, sign in, paste the code back
git clone <repo> && cd <repo>/wetalks && git checkout ccr-e7b9ed76-mxxlg5
```

## Deep-link website (Hosting)
```bash
firebase deploy --only hosting --project wetalks-78e5a
```

## Database rules
```bash
firebase deploy --only database --project wetalks-78e5a
```

## Push notifications (needs the Blaze plan)
```bash
cd functions && npm install && cd ..
firebase deploy --only functions --project wetalks-78e5a
```

## Maintenance mode (no app update needed)
```bash
# ON
firebase database:set /config/maintenance --project wetalks-78e5a --force \
  --data '{"on":true,"title":"Maintenance","message":"We are improving WeTalks. Back soon.","eta":"6:00 PM"}'
# OFF
firebase database:set /config/maintenance --project wetalks-78e5a --force --data '{"on":false}'
# Force everyone onto a newer build (versionCode)
firebase database:update /config/maintenance --project wetalks-78e5a --force --data '{"minVersion":13}'
```

## Security rules of thumb
* Never commit or share `*adminsdk*.json`, `*.jks`, `keystore.properties` (all git-ignored). Rotate a key if it was ever pasted anywhere.
* Only the Firebase console / CLI (admin) can change `config/`; the app can only read it.
* Push messages contain no text, names or media – only "msg"/"call" and a chat id.

## Website + developer API (Hostinger, wetalks.fireclashpro.com)
```bash
python3 tools/site/build.py --apk dist/WeTalks-vX-arm64.apk     # builds hosting/public from hosting/site + tools/site/pages.py
# get a temporary upload URL with the Hostinger MCP (hosting_files_generate-upload-url), then:
URL=... AUTH=... REST=... tools/hostinger_upload.sh hosting/hostinger/api wetalks/api   # PHP API
URL=... AUTH=... REST=... tools/hostinger_upload.sh hosting/public wetalks              # website (+ download/*.apk)
firebase deploy --only hosting --project wetalks-78e5a                                   # web.app mirror (deep links)
```
* API code: `hosting/hostinger/api/` (lib, media, dev, service). Data lives outside the web root in `wt_store/` (SQLite, encrypted-file store, relay login).
* `FILES_ENABLED` in `lib.php` and `Premium.files` in the app switch video/audio/documents on together.
* Support email on the site: `WT_SUPPORT_EMAIL=you@example.com python3 tools/site/build.py ...`

## Natural voices (TTS) pack
* Pack: Supertonic 3 INT8 (MIT code, OpenRAIL-M model; converted for sherpa-onnx by k2-fsa), zipped at `/models/tts/supertonic3-int8-v1.zip` with `/models/tts/manifest.json` (url, sha256, sizes). The app downloads it on request, verifies the hash and unpacks safely. For big uploads use `python3` chunked TUS (single 120 MB PATCH times out).
* Engine: sherpa-onnx 2.0.1 Android libs in `app/src/main/jniLibs` (its ONNX Runtime is renamed `libonnxruntime_tts.so` with patchelf so it never clashes with our minimal `libonnxruntime.so`). Kotlin API: `com.k2fsa.sherpa.onnx` (Tts.kt, Apache 2.0).
* Hinglish: `voice/Hinglish.kt` converts Roman Hindi to Devanagari using `app/src/main/assets/hinglish/*` (built by `tools/tts/build_lex.py`, prototype + evaluation in `tools/tts/`). Kotlin and Python versions are kept identical by `HinglishTest`.
