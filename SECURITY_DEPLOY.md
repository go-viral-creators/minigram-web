# Minigram – backend security setup

The Android app no longer contains any server secret. Everything below is done **once**, by you,
because it needs your Firebase project's credentials (nothing here can be deployed from the coding sandbox).

## 0. Rotate the old secret (do this first)
The old shared `APP_SECRET` (64 hex chars) shipped inside every previous APK. Treat it as public:
* Change/remove it in the PHP `config.php` (`upload_api` and `admin/api/send.php`) and delete `APP_SECRET`, `FIREBASE_SERVICE_ACCOUNT`, `GITHUB_TOKEN` from the Vercel project.
* Delete the old `/api` deployment (this repo no longer contains it).
* If the service-account key was ever in an APK/Git history, revoke it in Google Cloud Console → IAM → Service accounts.

## 1. Plan & services
* Upgrade the Firebase project to **Blaze** (Cloud Functions + Storage need it).
* Enable **Storage** (Build → Storage → Get started).
* Enable **App Check** → register the Android app with **Play Integrity**.

## 2. Deploy
```bash
npm i -g firebase-tools && firebase login
cd functions && npm install && cd ..
firebase deploy --only functions,database,storage        # uses firebase.json / .firebaserc (mim-324d3)
```
Then migrate PII out of the public `users/` node (once):
```bash
GOOGLE_APPLICATION_CREDENTIALS=sa.json node functions/scripts/migrate_private_fields.js --dry-run
GOOGLE_APPLICATION_CREDENTIALS=sa.json node functions/scripts/migrate_private_fields.js
```

## 3. App Check enforcement (order matters)
1. Ship the new app build to users first (it sends App Check tokens).
2. Firebase console → App Check → **Monitor** metrics for Realtime Database, Storage, Functions until verified traffic is ~100 %.
3. Then click **Enforce** for each. (`sendNotification` already enforces it in code.)
4. Side-loaded test builds fail Play Integrity. Build them with `-PappCheckDebug=true`, copy the debug token printed in logcat (`DebugAppCheckProvider`) into App Check → Manage debug tokens.

## 4. Tests
```bash
(cd functions && npm test)            # helper unit tests
(cd rules-tests && npm i && npm test) # 10 security-rule scenarios against the RTDB emulator
```

## Known limits (be aware)
* `is_pro` is still written by the client after a Play purchase (existing design). A user with a modified client could grant themselves Pro. Real fix = verify the purchase token server-side with the Play Developer API and write `is_pro` from a function.
* RTDB rules cannot rate-limit writes on their own; push fan-out is rate-limited in the function (60/min/user). Message-write abuse is bounded by auth + App Check + size/shape validation.
