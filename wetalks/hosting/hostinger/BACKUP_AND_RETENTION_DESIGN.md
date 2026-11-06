# Google Drive chat backup + server media retention – design (DRAFT for review, nothing is implemented or deployed)

Priority order: **Data safety > Reliability > Storage efficiency > Performance > New features.**
Nothing here deletes anything. Every deletion step needs the owner's explicit approval first.

## 0. What exists today (inspected)
| Area | Today |
|---|---|
| Chat history | Only in Firebase RTDB, **encrypted** (`messages/<chat>`), decrypted on the phone per message. There is **no local message database**; RTDB disk persistence is a cache, not a backup. |
| Media | Encrypted on the phone, stored on Hostinger (`wt_store/f`). Message payload carries the media id and (files) the file key. The phone now also keeps opened/sent photos and voice as plain files in private storage (cache-limited, `DataPrefs`). |
| Identity key | X25519 key in Android Keystore wrap. **Key backup already exists**: private key sealed with a passphrase (PBKDF2-SHA256, 210 000 iterations) in `key_backups/<uid>`. |
| Google | Sign-in only (Credential Manager, `GetGoogleIdOption`) -> Firebase Auth. **No Drive scope, no `play-services-auth`, no WorkManager** in the app. |
| Server | PHP, flat-file store, 19 websites share one Hostinger account/20 GB (see usage report). |

## 1. Product behaviour (WhatsApp-like, simpler)
Settings -> **Chat backup**: one screen.
- Google account: shows the account used for sign-in as a one-tap choice ("Use name@gmail.com"), or "Choose another account". Connecting asks Google for Drive permission **separately** (login does not grant it).
- Frequency: **Never / Daily / Weekly** (default Never until the user turns it on).
- Include: Photos, Voice, **Videos, Audio, Documents** (toggles; videos off by default on mobile data).
- Network: Wi-Fi only (default) or Wi-Fi + mobile.
- **Back up now** button, live progress (files done / size), cancel.
- Status line: "Last backup 6 Nov, 9:41 pm · 1.3 GB" or a clear warning ("Not backed up for 12 days", "Reconnect Google account", "Not enough Drive space").
- One **backup password** (with an optional printed **recovery key**). Shown once at setup with a clear "if you lose both, the backup cannot be opened by anyone, including us".
- Restore: shown after login on a new phone, and under the same screen.

## 2. Google Drive authorization
- Scope: **`https://www.googleapis.com/auth/drive.appdata`** only (hidden app folder). This is a *non-sensitive* scope: no Google OAuth verification/security review is needed (only normal app info). It cannot see any other Drive file.
- Android: `Identity.getAuthorizationClient(context).authorize(AuthorizationRequest(drive.appdata, account))` (Google Play services, `play-services-auth`). Returns a short-lived access token; if consent is already granted it returns silently, so scheduled background work works. No refresh token or client secret is ever stored on the phone or the server. If consent was revoked the job stops and shows "Reconnect Google account".
- Account choice: reuse the Firebase Google account's e-mail when present; otherwise `AccountPicker`. The chosen account is stored locally; the Drive account may differ from the login account.
- Drive REST over plain HTTPS (`files.list/create/update/get`, resumable upload) – no `google-api-client` library, so the APK stays small.
- One-time owner setup (Google Cloud project `wetalks-78e5a`): enable **Google Drive API**; OAuth consent screen with that scope and publishing status **In production** (in "Testing" tokens expire after 7 days and only 100 users are allowed); an **Android OAuth client** for the package + SHA-1 of the release signing key (and the Play App Signing key if used). The SHA-1 item is already on the owner's pending list.

### appDataFolder – fit for our use case
| Property | Effect |
|---|---|
| Hidden, only our app can read/write | Good for privacy. The user cannot browse or download it from the Drive website. |
| Counts against the user's Drive quota (15 GB free, shared with Gmail/Photos) | Must be shown to the user before the first backup (size estimate + free space). |
| Survives app uninstall, is deleted when the user removes "hidden app data" in Drive settings | Restore works on a new phone with the same account. |
| Accessible to apps of the same Google Cloud project with the same OAuth client | Restore requires our app (same signing config). |
Verdict: **fits**. A visible "WeTalks Backup" folder (`drive.file`) is the alternative; it adds user-visible files without any benefit here.

## 3. Backup format (all encrypted before it leaves the phone)
Keys: a random 256-bit **Backup Master Key (BMK)** is generated on the phone. Sub-keys come from HKDF(BMK, label) for: content encryption, object-name HMAC, manifest.
- `keybag` (1 small object): `{v, kdf: PBKDF2-HMAC-SHA256, iterations >= 600 000, salt, wrapped BMK (AES-256-GCM)}` and optionally a second wrap of the BMK with the **recovery key**. The password/recovery key never leave the phone. The BMK is cached on the phone wrapped by the Android Keystore (like `Crypto.wrap`), so scheduled backups need no password.
- **Messages** (decrypted on the phone, then re-encrypted with the BMK): one object per chat per month, `c-<HMAC(chatId)>-<yyyymm>`, JSON lines with id, sender, time, type, text/payload, reply id, reactions, edit flag, media id, sizes. Past months are immutable (hash unchanged -> skipped); the current and previous month are re-checked on every run.
- **Media**: one object per media id, `m-<HMAC(mediaId)>`, Tink streaming AEAD (same pattern as `FileKit`), carrying mime/name. The name is a keyed hash, so Google (and anyone reading file names) sees no ids, chat ids or user ids.
- `manifest-<n>` (encrypted): backup id, time, app version, schema version, Firebase uid hash, device name, list of objects with size and Drive md5, counts (chats / messages / media), previous manifest id.
- Drive `appProperties` carry only opaque tags (`kind`, `schema`) to find objects quickly.
- **E2EE is unchanged**: Firebase messages and Hostinger media are encrypted exactly as today. The backup is a separate copy under a separate key; no key is ever uploaded in plaintext.

## 4. Incremental, resumable, verified
- State on the phone (small SQLite/JSON): per chat the last backed-up message key; per media id -> Drive file id + md5 + state (`queued | uploading | done`). Each run first lists the Drive folder (1 request per 1 000 objects) and reconciles, so a lost local state cannot cause duplicates (names are deterministic).
- Only missing media is uploaded; unchanged month files are skipped.
- **Resumable upload**: Drive resumable sessions (URI kept up to 7 days, 8 MB chunks); after a crash we ask the session for the received byte range and continue.
- **Commit rule**: the manifest is uploaded **last**. "Backed up" is shown only after every object's Drive `md5Checksum`/size equals the local ciphertext values, the manifest is stored, and it can be read back and decrypted. Failed/partial runs leave unreferenced objects, which a cleanup step removes after 7 days.
- Keep the last 2 committed manifests; restore uses the newest committed one.

## 5. Android background execution
- **WorkManager** periodic work (daily/weekly), constraints: unmetered network (or any, by user choice), battery not low, storage not low. Exponential backoff, unique work, resume from journal.
- "Back up now": user-initiated data-transfer work with a progress notification and Cancel (Android 14+ requires a declared foreground type / user-initiated job).
- Android Doze / OEM killers (MIUI "autostart"): documented in-app tip; a banner appears when the last success is older than 2x the chosen interval.
- Battery: media is read in 1-4 MB buffers, uploads are sequential, one run capped (default 40 min) and continued next time.

## 6. Restore
1. Sign in; if the identity key is missing the existing **key-backup restore** runs first (so DMs still in Firebase decrypt).
2. Chat backup -> connect Google account -> list backups from manifests: **date, size, device, app version, counts**.
3. Choose one; enter backup password (or recovery key).
4. **No overwrite, no duplicates, no writing into Firebase.** Restored messages go into a new **local Archive** (SQLite in private storage; media files in `filesDir/archive`, kept separate from the cache so "Clear cache" cannot delete them). The chat screen shows the union of live Firebase messages and Archive messages **by message id** (live wins). Messages that were removed from Firebase simply appear from the Archive.
5. Messages first, media in the background (or on tap). Backup uid != current uid -> restore is blocked with an explanation.
6. Media lookup order: local plain file -> Hostinger -> Archive file -> Drive object on demand.
Backup and cache are separate stores; only the backup/archive files are exempt from cache trimming.

## 7. Server media retention (Hostinger) – nothing is deleted until all of this holds
**Acknowledgements.** After a media object is verified in the user's Drive backup the app calls `POST media/<id>/ack` (member of the chat, authenticated). An ack proves two things at once: the object is referenced by a real message (the member only knows the id from a message) and that member has it safely backed up. A second ack type, `device`, exists for "Save to device" by explicit user action. The server records acks per member (hashed uid) in the metadata.

**Eligibility** of a server copy (all must be true):
1. age >= 30 days;
2. **every** member of the chat acked it (DM: both people, the two uids are in the chat id; groups: member list, needs a rules change, so groups come in a later step);
3. the owner has switched the cleanup flag on and approved the dry-run report;
4. not a legacy-uncommitted file of an old APK, not pending/active upload.
Members who have not backed up: the media stays. Their screen shows a gentle notice at day 90/150 ("Back up to keep old media") with one tap to enable backup or save to device; the notice itself is recorded as an "acknowledged notice" by the member. **Silence never counts as consent**, so a member who never opens the app keeps their media on the server (cost: storage; bounded by quotas and the global cap).
Proposed product policy for people **without backup**: media stays on the server **at least 180 days**; deletion after that happens only when every member either acked or tapped "I understand" on the notice. Otherwise it is kept.

**After removal** the message stays; the bubble says "Media removed from server" and loads from the Archive/Drive if the member has a backup, otherwise offers "Restore from Google Drive".
**Deleting a message for everyone** keeps today's behaviour (media removed at once). The next backup rewrites that month's file, so it disappears from the backup too.
**Rollback:** before any removal the cleanup moves objects to `quarantine/` (reversible, `orphan_quarantine` flag, 30 days). Permanent delete is a separate flag that I will not implement or enable without your explicit approval.

## 8. Drive storage estimate and API limits (estimates, verify with real data)
Assumptions: text ~200 B/message; photo ~300 KB; voice ~40 KB; AEAD overhead < 0.1 %.
| User type | Media per day | Per year (photos+voice) | 3 years |
|---|---|---|---|
| Light | ~1 | ~0.1 GB | ~0.3 GB |
| Typical | ~5 | ~0.5 GB | ~1.5 GB |
| Heavy | ~20 | ~2 GB | ~6 GB |
Text history is a few MB per year. Videos/documents dominate when enabled (30 MB max each). Free Drive is 15 GB shared with Gmail/Photos: the app shows size and free space before the first backup.
API (Drive v3, defaults – please confirm in Cloud Console): about 12 000 requests/min per project/user, 750 GB/day upload per user. A first backup of 2 000 files is roughly 2-4 k requests; later runs a few dozen. No limit problem is expected. Rate-limit (403/429) is retried with backoff.

## 9. Affected files
Android: new `backup/` package (`DriveAuth`, `DriveApi` (REST + resumable), `BackupCrypto` (BMK, keybag, Tink streaming), `BackupEngine`, `BackupWorker`, `ArchiveStore` (SQLite), `RestoreEngine`), new screens (`BackupScreens.kt`: settings, progress, restore), `ChatScreen.kt` / `Repo.kt` (union of live+archive, media lookup chain), `Repo.send/loadMedia` (ack hook), `MediaApi.kt` (`ack`), `build.gradle.kts` (`play-services-auth`, `work-runtime-ktx`), `AndroidManifest.xml` (foreground service type, WorkManager), `proguard-rules.pro`.
Server: `media.php` (+ `ack`, `status`), `lib.php` (config/flags), `tools/cleanup.php` (dry-run default), Phase 1 items (reservation, global cap, shards, idempotency).
Firebase rules: none needed for DM-only phase; group membership read for cleanup is a later, separately approved change.
Not changed: E2EE code (`Crypto`, `FileKit`), message format.

## 10. Backward compatibility, migration, risks
- Old APKs never ack: their media is treated as `legacy-committed` and is **never** touched by retention.
- Backup is opt-in; nothing changes for users who do not turn it on.
- Archive is additive; if it is empty the chat screen behaves exactly as today.
- Risks: (1) lost password + recovery key = unreadable backup (stated clearly, same as WhatsApp); (2) OEM background kills (retries + banner); (3) Drive quota full (clear message, no partial "success"); (4) first-backup time/data on large histories (Wi-Fi default, resumable); (5) Google consent/verification state (non-sensitive scope; must be set to Production); (6) group retention needs member lists (deferred); (7) Archive adds a new local database (tests + migration versioning).
- Rollout order: Phase 1 storage fixes -> report -> backup engine + restore (no server deletion) -> acks recorded only -> dry-run cleanup reports -> owner approval -> quarantine -> (optionally, later) permanent delete.

## 11. Approvals needed before coding
1. This design (esp. Archive-union restore, password + recovery key, no silent expiry).
2. Google Cloud: Drive API on, consent screen In production, Android OAuth client + SHA-1.
3. Retention numbers (30-day minimum, 180-day no-backup minimum, notice days).
4. Permission to run the read-only usage report on production (temporary upload + one-shot cron, details in chat).
