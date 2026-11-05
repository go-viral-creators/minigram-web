# WeTalks (Kotlin + Jetpack Compose)

Package `com.kafisir.wetalks`, Firebase project `wetalks-78e5a` (Auth + Realtime Database).

## Before first use
1. Deploy the database rules: Firebase console > Realtime Database > Rules, paste `database.rules.json` (tested: `cd rules-tests && ./run.sh`).
2. Firebase console > Project settings > your Android app > add the SHA-1 of the signing key (release key SHA-1 is printed by
   `keytool -list -v -keystore keystore/wetalks-release.jks`), then re-download `google-services.json` into `app/`.
   Needed for Google sign-in and phone OTP.
3. Authentication > Sign-in method: Email/Password, Phone and Google are already enabled.

## Build
`gradle :app:assembleRelease` (needs `keystore.properties`, not committed). Never commit keystores or service-account keys.
