# Phase 1 – storage reliability: revised design (DRAFT for review, nothing here is deployed)

Priority order: **Data safety > Reliability > Backward compatibility > Performance > New features.**
Branch: `phase1/storage-reliability`. No production deploy, no push to stable branches, cleanup is dry-run by default.

## 0. Hard rules
1. No automatic deletion of user media (a message may reference it). Not by age, not by "pending".
2. Existing APKs keep working unchanged (upload, download, delete, E2EE bytes are never inspected or modified).
3. Every feature has its own kill switch in `wt_store/config.json` (all OFF on first deploy).
4. Tests first; each step reports results; SIMULATED and STAGING results are never mixed.
5. Staging only: separate subdomain, folder, `wt_store`, and Firebase TEST project. No production data or credentials.

## 1. Cleanup – what may be removed, and what may NOT
| Class | Examples | Action |
|---|---|---|
| A. Never user media | `.part` of an upload that never finished (no id was ever returned), `_acl/*` cache (TTL 120 s), `r/*` rate files, expired idempotency records, expired reservations | Removable by cleanup (only in `apply` mode, only if no live lock) |
| B. User media | any `x<32hex>` file that was returned to a client | **Never deleted by cleanup.** Only reported (dry-run) and optionally *moved* to quarantine (reversible, flag OFF) |
| C. Legacy media | uploaded by existing APKs (no upload-id) | Treated as committed forever. Not even reported as orphan |

### 1.1 Reliable reference verification (the crash-between-send-and-commit problem)
The server cannot read messages (E2EE), so it cannot decide by itself. The reliable mechanism lives where the data is readable: **the uploader's phone**.
1. Before the RTDB write the app stores a durable journal entry `{uploadId, mediaId, cid, mid}`. `mid` is generated before sending, so it is known even if the app dies.
2. After the write succeeds the app calls `POST media/<id>/commit`, then removes the entry.
3. If the app dies at any point: on next start it replays the journal. For each entry it reads `messages/<cid>/<mid>` (allowed: it is a member).
   - exists -> `commit` (media referenced, safe forever)
   - does not exist and the user cancelled / the entry is a failed send the user dropped -> `DELETE media/<id>` (owner delete, the existing endpoint)
   - does not exist but the send may still be retried -> keep.
4. Cancelling an outgoing item after its upload finished must call `DELETE media/<id>` (today it does not). This removes most orphans at the source.

Unresolved cases (app data cleared, uninstall, phone lost): the file stays `pending` **forever**. It costs space, it is never lost. They are listed in the dry-run report so the owner can decide later.
**Server-side permanent deletion of unverified media is not implemented in Phase 1.** `orphan_delete` does not exist until a verification mechanism is approved and proven on staging.

Also safe: **commit-on-read.** A successful GET of a pending file by a chat member other than the uploader proves a message references it (ids are random secrets only delivered inside messages) -> mark committed. The uploader's own GET does not commit.

## 2. Quota reservation: no premature expiry of active uploads
Problem with a plain 15-minute expiry: a slow upload could lose its reservation and another upload would be allowed over quota.

Design = liveness by OS lock + heartbeat, time is only a last resort:
1. Reserve (inside one short critical section on `lock/reserve`): check `used + sum(live reservations) + len <= quota` (user) and the same for the global cap; write `res/<uid>/<resId>` with `bytes`, `started`, `heartbeat`.
2. The upload request holds `flock(LOCK_EX)` on `res/<uid>/<resId>.lock` for its whole life. The OS releases it if the process dies for any reason (crash, kill, timeout).
3. During the body loop the process touches the reservation heartbeat every ~5 s.
4. A reservation counts as **live** if its lock is held OR its heartbeat is newer than 120 s. It is reclaimed only when BOTH are false (owner dead and silent). A slow-but-alive upload is never reclaimed, however long it takes.
5. Strict limits so nothing hangs forever: per-read stall timeout 60 s (`stream_set_timeout`), absolute upload cap `max_upload_seconds` (config, default 900) -> abort with 408 and release.
6. **Finalize re-validates.** Before the rename the process re-checks its own reservation still exists and that `used + len <= quota` (and the global cap). If not (e.g. flock is unreliable on the host) it deletes its own `.part` and answers 507. The failure mode is "reject an upload", never "exceed the quota".
7. Counters stay backward compatible: `q/<uid>` remains a plain integer (old code can still read it). Reservations are in separate files, so rollback loses nothing.
8. Staging test T8 must prove flock semantics on the real host. If they fail, the design falls back to heartbeat-only (rule 4 second half) and a SQLite transaction option is evaluated.

## 3. Global disk protection (not "85% of 20 GB")
The plan's 20 GB is shared by website files, other sites, databases, logs, backups and this media store. The media budget is therefore:

`media_budget = plan_limit − non_media_usage − safety_reserve`

- `non_media_usage` = website files + other sites on the plan + databases + logs + store non-media data (measured; hPanel *Disk usage* gives the account figure, the report script measures the store part).
- `safety_reserve` = max(2 GB, 10 % of the plan) by default, chosen by the owner after the report.
- The cap is a **number in config.json (`media_cap_bytes`) that a human sets from the report**, not a hard-coded percentage.
- Runtime check: `media_used (counter) + reservations + len <= media_cap_bytes`. A cron `status` job also measures account usage periodically and writes `usage.json`; if it is fresh and `account_used + len > plan − reserve` the upload is refused (second, independent guard).
- `disk_free_space()` is only informational on shared hosting (often the whole server disk); it is never used as the plan limit.
- At the cap: 507 "storage temporarily full" (old APKs already map 507 to "Your media storage is full"). Nothing is deleted.
- If the global counter is not initialised the cap is OFF and `health` says so (fail-open for users, loud for the operator).

## 4. Backward compatibility (explicit)
- No `X-Upload-Id` header = old behaviour: `201 {"id","size"}`, file is committed, no commit call needed, no idempotency.
- GET/DELETE look in the sharded path first, then the legacy flat path.
- Server never reads or alters file bytes (E2EE intact). Message schema and Firebase rules are unchanged in Phase 1.
- Only visible change for old APKs: a possible 507 when the global cap is reached.
- Test T7 replays request fixtures recorded from the 1.15 / 1.16 `MediaApi` (with and without `X-Kind`).

## 5. Kill switches (`wt_store/config.json`, all false by default)
`reserve_enforce` · `global_cap_enforce` · `shard_writes` · `idempotency` · `state_tracking` (pending/commit) · `commit_on_read` · `cleanup_apply_class_a` · `orphan_quarantine` · (no `orphan_delete`)
Each can be turned off without a redeploy. An unreadable config = all switches off (old behaviour).

## 6. Affected files
Server (this branch): `api/lib.php` (config, paths, lock helpers), `api/media.php` (reserve, idempotency, shards, commit, state), new `tools/cleanup.php` (CLI, dry-run default), new `tools/usage_report.php` (done, read-only), `api/.htaccess` (deny CLI files), `tests/*`.
Client (separate step, new APK, optional for the server to work): `MediaApi.kt`, `Repo.kt` (journal + commit in `send`, delete on cancel).
Not touched: crypto (`Crypto`, `FileKit`), Firebase rules, message format.

## 7. Rollback
- Deploy order: (1) code that can READ both layouts and has every switch OFF, (2) switches on one by one on staging, (3) production only after approval.
- Rollback = switch off (instant). New folders (`res/`, `idx/`, shards) are invisible to old code and nothing is deleted.
- Shard writes are enabled last. If old code must be restored after shard writes were on, run `tools/unshard.php` first (moves files back, idempotent).
- Before any deploy the previous PHP files are copied to `*.bak-<timestamp>`.

## 8. Tests (written before the code)
| ID | What | Where |
|---|---|---|
| T0 | Usage report is read-only and numbers match | SIMULATED – **done, passing** |
| T1 | 40 parallel uploads cannot exceed quota (real HTTP; must FAIL on current code first) | SIMULATED, then STAGING |
| T2 | Slow upload (> 15 min equivalent, heartbeat) keeps its reservation; second upload is still refused at quota | SIMULATED, then STAGING |
| T3 | Interrupted upload + retry with the same upload-id gives one file, one quota charge | SIMULATED, STAGING |
| T4 | Cleanup safety: never touches active uploads, committed, legacy or sent-message media; dry-run changes nothing | SIMULATED |
| T5 | Global cap: refuse at cap, existing files untouched, recovery after a delete | SIMULATED; real quota behaviour STAGING |
| T6 | Duplicate / concurrent same upload-id | SIMULATED, STAGING |
| T7 | Old-APK request fixtures behave exactly as before | SIMULATED |
| T8 | Process killed at every step of an upload -> consistent state, no leaked reservation, no orphan metadata | SIMULATED |
| T9 | flock / timeouts / cron / fsync / `disk_free_space` semantics on the real host | **STAGING only** |

Simulated = sandbox PHP built-in server, local disk, locally signed test JWTs. Staging = real Hostinger. They are reported in separate tables.

## 9. Needed from the owner before implementation starts
1. Staging subdomain + folder + separate `wt_store` + Firebase TEST project (details in the chat message).
2. How the read-only usage report should be run on production (options in the chat message), and the plan numbers for the budget (hPanel *Disk usage*).
3. Approval of this revised design (especially §1.1: no server-side permanent deletion in Phase 1).

---
## 10. Changes after the second review (owner requirements)
1. **Usage report is run by Claude, not manually.** Audit done (static review: no write/exec/network/lock calls; symlinks never followed; metadata reads capped; ids masked; refuses a folder not named `wt_store`; 14/14 simulated checks). Execution path found: the Hostinger API has no shell, so the only way is *temporary upload of the script + one one-shot account cron job whose output is read back, then the cron is deleted*. This touches production (adds then removes a cron entry and leaves/blanks one inert file), so it waits for the owner's explicit go-ahead. Findings so far (read-only API): the account hosts **19 websites** (wetalks is a subdomain at `.../domains/fireclashpro.com/public_html/wetalks`), so the 20 GB is shared with all of them; `wt_store` is a sibling of `public_html` (not web reachable).
2. **Cleanup is driven by acknowledgements, not by age** (see BACKUP_AND_RETENTION_DESIGN.md §7). The Phase 1 `pending` / `committed` states stay; a new per-member `ack` is added later. User media is never deleted by Phase 1.
3. **Backup is Google Drive (appDataFolder), not local.** The earlier "local backup" idea is dropped. A local *Archive* is only the restore target.
4. **Global cap and quota stay unchanged in Phase 1** and are set from the usage report numbers, never a fixed percentage.
