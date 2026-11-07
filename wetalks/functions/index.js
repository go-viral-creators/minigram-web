// WeTalks push (FCM). Deploy needs the Blaze plan.
//
// SECURITY MODEL
//  * Messages are end-to-end encrypted, so the server never sees text. The push is "data only": { t: "msg"|"call", cid }.
//    No name, no text, no media. The phone wakes up, reads the chat from the database and decrypts locally.
//  * Functions run with the project's own service account inside Google Cloud. There is no key file, no secret
//    and no HTTP endpoint (only database triggers), so there is nothing for an outsider to call or steal.
//  * A user cannot make the server push to someone else: triggers fire only after the database rules accepted the write.
const { onValueCreated } = require("firebase-functions/v2/database");
const { setGlobalOptions } = require("firebase-functions/v2");
const admin = require("firebase-admin");

admin.initializeApp();
setGlobalOptions({ region: "us-central1", maxInstances: 20 });
const db = admin.database();

async function tokensOf(uid) {
  const s = await db.ref(`fcm_tokens/${uid}`).get();
  return s.exists() ? Object.keys(s.val()) : [];
}

async function push(uid, data, ttlMs) {
  const tokens = await tokensOf(uid);
  if (!tokens.length) return;
  const res = await admin.messaging().sendEachForMulticast({
    tokens,
    data,
    android: { priority: "high", ttl: ttlMs },
  });
  // forget tokens that no longer exist (uninstalled app, signed out)
  const dead = [];
  res.responses.forEach((r, i) => {
    const c = r.error && r.error.code;
    if (c === "messaging/registration-token-not-registered" || c === "messaging/invalid-registration-token") dead.push(tokens[i]);
  });
  await Promise.all(dead.map((t) => db.ref(`fcm_tokens/${uid}/${t}`).remove()));
}

// new chat message -> wake the other members (not channels: followers just see them on open, like before)
exports.onMessage = onValueCreated({ ref: "/messages/{cid}/{mid}" }, async (event) => {
  const { cid } = event.params;
  const m = event.data.val();
  if (!m || !m.from) return;
  // ignore old messages being re-written (forward/restore) - only fresh ones
  if (typeof m.ts === "number" && Date.now() - m.ts > 2 * 60 * 1000) return;
  let members = [];
  if (cid.includes("_")) members = cid.split("_");
  else {
    const g = await db.ref(`groups/${cid}/members`).get();
    if (!g.exists()) return; // channel or unknown
    members = Object.keys(g.val());
  }
  await Promise.all(members.filter((u) => u && u !== m.from).map((u) => push(u, { t: "msg", cid }, 60 * 60 * 1000).catch(() => {})));
});

// incoming call -> ring now; a call push is worthless after ~45 seconds
exports.onCall = onValueCreated({ ref: "/incoming_calls/{uid}/{cid}" }, async (event) => {
  const { uid, cid } = event.params;
  await push(uid, { t: "call", cid }, 45 * 1000).catch(() => {});
});

// missed call -> tell the person who called, even though their app is closed. The caller (or the person's own app) writes the "missed" line
// of the call history; this wakes the phone with a high-priority push: who, voice/video, when. The phone builds the notification and its Call back button.
exports.onMissedCall = onValueCreated({ ref: "/call_log/{uid}/{cid}" }, async (event) => {
  const { uid, cid } = event.params;
  const e = event.data.val();
  if (!e || e.dir !== "in" || e.st !== "missed" || !e.peer) return;
  if (typeof e.ts === "number" && Date.now() - e.ts > 10 * 60 * 1000) return;           // an old line being re-written: no push
  await push(uid, { t: "missed", cid, from: String(e.peer), v: e.video ? "1" : "0", ts: String(e.ts || Date.now()) }, 24 * 60 * 60 * 1000).catch(() => {});
});
