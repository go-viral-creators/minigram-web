'use strict';
/**
 * Minigram Cloud Functions.
 *
 * Replaces the old shared-secret push endpoints (Vercel /api/send-notification and the
 * PHP admin sender). The Android app no longer ships ANY server credential: it calls the
 * callable below with its Firebase Auth identity + a Firebase App Check token.
 *
 *   sendNotification  - chat push (caller must be a participant of the chat)
 *                     - channel push (caller must own / administer the channel)
 *
 * Secrets / service accounts live only in Firebase (Admin SDK runs with the function's own
 * identity, so no key file is needed at all).
 */
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { setGlobalOptions } = require('firebase-functions/v2');
const admin = require('firebase-admin');

admin.initializeApp();
setGlobalOptions({ region: 'us-central1', maxInstances: 20 });

const db = () => admin.database();

const LIMITS = { title: 100, body: 400, image: 500, extra: 128, channel_name: 100 };
const RATE = { windowMs: 60 * 1000, max: 60 };          // 60 pushes / user / minute

function clean(v, max) {
  if (v === undefined || v === null) return '';
  const s = String(v);
  return s.length > max ? s.slice(0, max) : s;
}

function isHttpsUrl(u) {
  if (!u) return true;
  try { return new URL(u).protocol === 'https:'; } catch (_) { return false; }
}

/** Mirrors FcmTokenManager.topicFor() on the client. */
function topicFor(channelId) {
  return 'channel_' + String(channelId).replace(/[^a-zA-Z0-9\-_.~%]/g, '_');
}

/** chatId is "<uidA>_<uidB>" (sorted). Firebase uids never contain '_'. */
function chatIdFor(a, b) { return a < b ? `${a}_${b}` : `${b}_${a}`; }

/** Per-user sliding window in RTDB (admin SDK bypasses rules). */
async function enforceRateLimit(uid) {
  const ref = db().ref(`rate_limits/${uid}/push`);
  const now = Date.now();
  const res = await ref.transaction((cur) => {
    if (!cur || now - cur.start > RATE.windowMs) return { start: now, count: 1 };
    if (cur.count >= RATE.max) return;            // abort -> uncommitted
    return { start: cur.start, count: cur.count + 1 };
  });
  if (!res.committed) throw new HttpsError('resource-exhausted', 'Too many notifications, slow down.');
}

exports.sendNotification = onCall(
  { enforceAppCheck: true, consumeAppCheckToken: false, cors: false },
  async (request) => {
    if (!request.auth) throw new HttpsError('unauthenticated', 'Sign in required.');
    const uid = request.auth.uid;
    const d = request.data || {};
    const type = clean(d.type, 16);

    await enforceRateLimit(uid);

    const title = clean(d.title, LIMITS.title);
    const body = clean(d.body, LIMITS.body);
    const image = clean(d.image, LIMITS.image);
    const extra = clean(d.extra, LIMITS.extra);
    if (!isHttpsUrl(image)) throw new HttpsError('invalid-argument', 'image must be https');

    const data = {
      title, body, image, type, extra,
      channel_name: clean(d.channel_name, LIMITS.channel_name),
      sender_uid: uid,                               // never trust a client-supplied sender
      to_uid: '',
    };

    if (type === 'chat') {
      const toUid = clean(d.to_uid, 128);
      if (!toUid || toUid === uid) throw new HttpsError('invalid-argument', 'to_uid required');
      // Caller must really be in this conversation.
      if (extra !== chatIdFor(uid, toUid)) throw new HttpsError('permission-denied', 'Not your chat.');
      // Receiver blocked the sender -> silently drop (don't reveal the block).
      const blocked = await db().ref(`blocked_users/${toUid}/${uid}`).get();
      if (blocked.val() === true) return { ok: true, delivered: false };

      const tokSnap = await db().ref(`fcm_tokens/${toUid}`).get();
      const token = tokSnap.val();
      if (!token || typeof token !== 'string') return { ok: true, delivered: false };
      data.to_uid = toUid;
      try {
        await admin.messaging().send({ token, data, android: { priority: 'high' } });
      } catch (e) {
        if (e.code === 'messaging/registration-token-not-registered' ||
            e.code === 'messaging/invalid-registration-token') {
          await db().ref(`fcm_tokens/${toUid}`).remove();   // stale token cleanup
          return { ok: true, delivered: false };
        }
        console.error('chat push failed', e.code);
        throw new HttpsError('internal', 'Push failed.');
      }
      return { ok: true, delivered: true };
    }

    if (type === 'channel') {
      const channelId = clean(d.channel_id || extra, 128);
      if (!channelId) throw new HttpsError('invalid-argument', 'channel_id required');
      const [ownerSnap, adminSnap] = await Promise.all([
        db().ref(`channels/${channelId}/owner_uid`).get(),
        db().ref(`channel_admins/${channelId}/${uid}`).get(),
      ]);
      if (ownerSnap.val() !== uid && !adminSnap.exists()) {
        throw new HttpsError('permission-denied', 'Only the owner or an admin can notify subscribers.');
      }
      data.extra = channelId;
      try {
        await admin.messaging().send({ topic: topicFor(channelId), data, android: { priority: 'high' } });
      } catch (e) {
        console.error('channel push failed', e.code);
        throw new HttpsError('internal', 'Push failed.');
      }
      return { ok: true, delivered: true };
    }

    throw new HttpsError('invalid-argument', 'Unknown type.');
  }
);

// exported for unit tests
exports._test = { topicFor, chatIdFor, clean, isHttpsUrl };
