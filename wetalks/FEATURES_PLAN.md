# Chat organisation, linked devices and 2-step verification: plan

Branch `remediation/security-phase1` (later merged by the owner). Nothing here is deployed; the new rules must be deployed by the owner before the new features work for real.

## What exists today
- Pin: long-press a chat -> "Pin to top" (`user_chats/<me>/<cid>/pinned`). Works; only a cap is missing.
- App lock: fingerprint / screen lock for the whole app (`AppLock`, `androidx.biometric`).
- One account = one Firebase uid = one encryption key pair that lives on one phone. A second phone gets a "key conflict" and must restore the passphrase backup.

## A. Chat organisation
| Feature | Where it is stored | Behaviour |
|---|---|---|
| **Pin** | `user_chats/<me>/<cid>/pinned` (exists) | max 5 pinned, archiving a chat unpins it |
| **Archive** | `user_chats/<me>/<cid>/archived` (synced to all my devices) | archived chats leave the list and sit in an "Archived" row at the top of Chats/Groups/Channels; they stay archived when a message arrives (unread count shows on the row) |
| **Label** | `chat_labels/<me>/<lid>` = name + colour; `user_chats/<me>/<cid>/labels/<lid>` = true | create / rename / delete labels, assign from the long-press sheet, filter chips above the list |
| **Lock** | this phone only (`wt_locked_chats` prefs) | locked chats leave every list and live in a "Locked chats" row that needs fingerprint / screen lock; opening one from a notification asks first; notifications for it never show the text; stays unlocked until the app goes to the background |

Lock is per phone on purpose: it needs this phone's screen lock, and the server should not learn which chats are sensitive.

## B. Linked devices (one account on several phones)
- Every install has a random device id. `devices/<uid>/<did>` = name, model, added, last active, revoked flag. Owner-only.
- **Link a new phone**: the new phone signs in with the same number, then picks "Link from my other phone". It makes a one-time key pair and shows a QR (`wetalks-link:<uid>:<sid>`) after writing `link_requests/<uid>/<sid>` (public key + device name). The old phone scans it, shows "Link <name>?", and on OK writes the encryption private key sealed with the shared secret of (old phone key, new phone one-time key). The new phone opens it and installs the key. The server only ever sees ciphertext. Requests expire after 10 minutes.
- **Linked devices screen** (Me -> Privacy): list, "this phone", last active, "Log out" for another phone (sets `revoked`; that phone signs itself out when it sees the flag).
- Honest limit: logging out another phone is a flag the app honours. A stolen phone running a modified app could ignore it. True session revocation needs a server with the Firebase Admin API (not available here). After removing a lost phone, also use "Reset encryption key" (change key) in a later step.

## C. 2-step verification (a PIN on top of the SMS code)
- PIN is 6 digits. Salt/iterations are readable by me (`two_step_pub/<uid>`); the hash lives in `two_step_secret/<uid>` which **nobody can read**, not even the owner.
- The database rules are the checker: a PIN attempt is written to `two_step_try/<uid>` (hash + counter + server time). Rules allow max 1 attempt per 1.5 s and 5 per hour. A phone becomes trusted (`trusted_devices/<uid>/<did>`) only if the attempt hash equals the secret hash, so the hash can never be read back or brute-forced offline.
- Gate: after sign-in, if 2-step is on and this phone is not trusted, the app shows the PIN screen before anything else.
- Change PIN / turn off needs a fresh correct attempt (within 60 s) and writes the new values in the same step.
- Forgot PIN: request a reset (`two_step_reset/<uid>`), the PIN is removable after 7 days; trusted phones show a warning banner and the real owner can cancel it with the PIN.
- Honest limit: this stops someone who only has your SMS code from getting into your account in the app, and your messages stay unreadable without your encryption key anyway. It is enforced by the database rules for the PIN check, but a modified client with a valid login can still read non-secret data; hard enforcement of all reads needs a backend with custom claims.

## Rules and tests
All new nodes are owner-only and validated (`$other: false`). Each has emulator tests, and the old-client shapes (existing `user_chats` writes) are re-checked.

## Build order
1. Stage A (organisation) -> tests -> commit
2. Stage B (linked devices) -> tests -> commit
3. Stage C (2-step) -> tests -> commit
4. Release 1.20.0 APK. No production action: rules deploy and site/API changes need the owner's go.
