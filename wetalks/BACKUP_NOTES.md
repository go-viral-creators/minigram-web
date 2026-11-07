# Chat backup and restore (team/backup)

Status: built, compiled and unit tested on a computer. NOT run on a phone, NOT run against Firebase or Google Drive.

## What it does
Me -> Data & storage -> **Chat backup** (`screens/BackupScreens.kt`, code in `data/Backup*.kt`).
- The phone reads the person's chats (direct chats and groups; not channels, not locked chats), decrypts the messages on the phone and writes one backup file `.wtbk` into app-private storage (`filesDir/backups`). Messages that are deleted, unreadable or still sending are left out.
- Optional: photos and voice notes that this phone already holds as plain files (the media cache).
- Per backup file: "Save a copy" (Storage Access Framework, no storage permission), "Share", "Delete". The last N sets are kept (default 2).
- Schedule Off / Daily / Weekly, "only on Wi-Fi", "only while charging", WorkManager periodic work. "Back up now" is expedited work with a progress notification (channel `background`) and a Cancel button.
- Restore: pick one or more backup files (SAF), enter the passphrase if needed, progress and Cancel.

## File format (version 1)
`WTBK` | version | flags | [header JSON if encrypted] | payload.
Payload = ZIP: `chats/<sha256(chatId)>.jsonl` (one message per line), `media/<sha256(mediaId)>`, `settings.json`, and last `manifest.json` (size + SHA-256 of every other entry, per-chat marks, set/sequence/base, account tag).
Encryption (optional): AES-256-GCM in 64 KiB chunks (constant memory, a 500 MB backup needs no RAM), key = PBKDF2-HMAC-SHA256 (600 000 rounds, random 16 byte salt; the same `Crypto.passKey` as the account key backup), KDF parameters in the header. Each chunk is bound to the file header, its position and a "last" flag (no reordering, dropping or truncation). The first chunk is a fixed marker: if it does not open the passphrase is wrong; failure later means a damaged file. The passphrase is separate from the key-backup passphrase (the screen says so; the app cannot check it).
For automatic backups the derived key is kept wrapped by the Android Keystore (`Crypto.wrapForDevice`), so the passphrase is never stored and nobody is asked at 3 a.m. If the phone cannot unlock it, the backup stops with a message asking for the passphrase again. Changing the passphrase does not re-encrypt older files.

## Incremental, sets, resume
- Marks: per chat the last message id (Firebase push ids sort by time) that is in a backup. The next backup fetches only newer ids. The mark stays one day behind (a message written offline gets its id before it reaches the server) and never moves over a message that could not be opened, so nothing is skipped for ever. Cost: the last day is saved again.
- A **set** = one full backup + small update backups. A new full backup starts when there is none, after 10 updates, after 35 days, when a part of the set is missing, or on request. Retention deletes whole old sets only, never part of one and never the newest set.
- Marks come from the stored manifests of the files that exist. Deleting a backup file therefore never loses data silently: the next backup becomes a full one.
- Resume: while building, each finished chat is written to a part file and a journal is saved (`noBackupFilesDir/backup-work`). After the app was killed or cancelled, the next run continues with the chats not done (journal younger than 24 h and for the same kind of backup). The final file is written under a temporary name and renamed when complete.
- Nothing new since the last backup: no file is made.
- Disk full: checked before writing and when writing; shows "Not enough free space on this phone". Signed out: "Sign in to WeTalks to back up your chats." Neither crashes.

## Restore: design choice and limits
Chats are end-to-end encrypted and other people's messages belong to a shared database. A restore therefore does **not** write messages back to Firebase (that would duplicate them for the other person, fake timestamps, and fight the rules). Instead the verified messages go into a local **archive** (`filesDir/archive`, separate from the cache, "Clear cache" does not touch it). `ArchiveStore.union` merges it into the chat screen: a live message always wins; an archived message appears only when the server has no message with that id. A message the sender deleted for everyone (a tombstone on the server) stays hidden; "clear chat" and "delete for me" cutoffs still apply because the merge happens before them (one local edit in `ChatScreen.kt`).
Limits, honestly:
- There was no local message database before, so there was nothing else to restore into. The archive is only read in the chat screen; the chat list preview, global search and notifications do not use it.
- Restored messages look like normal ones. The long-press menu (reply, react, edit) on an archived message is not disabled yet: a reaction on a message the server does not have would create a stub node (the merge ignores such stubs, but the lead should disable actions for archive-only ids; `ArchiveStore.get().messages(chat)` ids minus live ids).
- Edits, reactions and deletions made after a message was backed up are not in the backup. A message deleted for everyone AFTER the backup stays in the backup file and in a later restore only if the server no longer has the tombstone.
- Disappearing messages (team/disappearing): the backup does not know about expiry yet. When that feature lands, expiring messages must be excluded in `BackupMsgCodec.keep`, otherwise a backup keeps what the sender wanted gone.
- Calls use the call id as message id; those ids can sort before the mark, so call lines may be missed. The call log is separate.
- Restoring needs the same account (account tag in the manifest); other accounts are refused. A backup file is verified completely (every hash) before anything is added; damaged, cut-off, wrong-passphrase and wrong-account files change nothing. Restoring the same file twice adds nothing twice; the files of one set can be restored in any order.
- Media is restored into the archive folder (`ArchiveStore.mediaFile(id)`); the image/voice loaders do not look there yet (one line in the media loader: `DataPrefs.getPlain(id) ?: ArchiveStore.get().mediaFile(id)?.readBytes()`).
- Settings restored (additively, never overwriting): per-chat notification settings of this phone, labels (only if the account has none), starred ids through `BackupExtras.restoreStarred` if some feature registers it. Locked chat ids are never written.
- An UNENCRYPTED backup contains the messages as readable text. The screen warns about it. The temporary part files in `backup-work` are also plain while a backup is being built (app-private, deleted afterwards).

## Cloud backup (Google Drive): NOT working yet, untested
`data/BackupCloud.kt`: interface `CloudBackup`; `DriveRest` implements Drive REST v3 on the `appDataFolder` (list, download with Range resume, delete, quota, **resumable upload**: `uploadType=resumable`, 8 MiB chunks, status query `bytes */total`, session address kept so an upload continues after the app was killed, retry with backoff on 5xx/429). Tested only against a small fake server in `DriveRestTest`, which proves the client follows the documented protocol, not that Google accepts it.
`DriveAuth` asks Google Play services for a `drive.appdata` access token (`Identity.getAuthorizationClient`, `play-services-auth` 21.1.1, resolves offline), but `DriveAuth.SETUP_DONE = false`, so it answers **"Google Drive is not set up for this app yet"** without calling Google. The toggle in the screen says the same. Nothing about consent screens, token refresh or background silent authorisation was tried.
Owner steps (Google Cloud project of the app, `wetalks-78e5a`):
1. Enable the **Google Drive API**.
2. OAuth consent screen: add the scope `https://www.googleapis.com/auth/drive.appdata` (a non-sensitive scope, no Google review), set publishing status to **In production** (in "Testing" tokens expire after 7 days and only 100 users are allowed).
3. Create an **Android OAuth client**: package `com.kafisir.wetalks` + SHA-1 of the release signing key (and of the Play App Signing key if used).
4. Then set `DriveAuth.SETUP_DONE = true`, wire the consent dialog (`DriveAuth.needsConsent`, a PendingIntent for `StartIntentSenderForResult`) into the toggle, and test on a phone with a real account.
Quota: backups live in the person's Drive quota (15 GB free, shared with Gmail and Photos). Estimates from `hosting/hostinger/BACKUP_AND_RETENTION_DESIGN.md` on `phase1/storage-reliability` section 8 (text a few MB a year; photos/voice 0.1 to 2 GB a year) still apply; the screen does not show a size estimate or free Drive space yet. Drive limits (about 750 GB/day upload per user) are no concern.

## Added dependencies
`androidx.work:work-runtime-ktx:2.9.1` and `com.google.android.gms:play-services-auth:21.1.1` (already in the cache as a transitive dependency; both resolved offline).

## Not needed / not done
- No Firebase rules or database nodes: a backup is a file built on the phone. The `key_backups` anchor was not used.
- No Cloud Functions, no Hostinger changes.
