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
