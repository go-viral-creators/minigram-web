# Security remediation status (branch `remediation/security-phase1`)

Nothing here is deployed. Rules: `database.rules.json`, tests: `rules-tests/` (`./rules-tests/run.sh`, emulator only).

| Item | What | Where | Old APKs |
|---|---|---|---|
| H3 | Field-level `user_chats`, owner-only `contacts` marker, privacy uses contacts/blocks | rules + `Repo.fanOut/markRead` | keep working; "contacts" visibility returns once the owner opens the chat |
| M1 | Notifier skips blocked senders | `Notifier.handle` | n/a |
| H2 | Private channels: `channel_invites`, token join, participant-only reads, `channel_priv` previews | rules + `Repo.inviteLink/subscribe`, channel screens | old APKs cannot join *private* channels (public unchanged) |
| H1 | Phone hash moved to owner-only `phone_keys` | rules + `Repo.myPhoneKey` | legacy field still writable until `config/phoneKeyLegacyOff = true` |
| L1 | Edits cannot change type/ts/from/kv | rules | unaffected |
| L2 | Slow-mode stamp only increases | rules | unaffected |
| M6a | Profile username must be claimed in `usernames` | rules | unaffected (claim already comes first) |
| M2 | "Add me to groups" (all / contacts / nobody) enforced | rules + one-by-one adds + Settings row | old APKs: adding a protected person fails for that person |
| M7 | `subCount` ±1 only with matching follow state | rules | unaffected; **still advisory** (a non-follower can step it down) |

## Rollout order (needs explicit approval, none done)
1. Ship the new APK (additive; works with old and new rules).
2. Wait for adoption (`config/maintenance.minVersion` can gate old APKs).
3. Deploy `database.rules.json`.
4. Set `config/phoneKeyLegacyOff = true`, then purge `users/*/phoneKey` (migration, dry-run first).
5. Optionally recount `channels/*/subCount` from `channel_subs` (not written yet).

## Still open
Channel metadata of private channels is readable by any account; `phones/<hash>` is guessable (server HMAC lookup planned); M5 provider allow-list needs live Auth checks.
