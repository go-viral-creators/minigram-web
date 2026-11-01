'use strict';
const test = require('node:test');
const fs = require('fs');
const path = require('path');
const { initializeTestEnvironment, assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const { ref, set, get, update, remove, push, serverTimestamp, increment } = require('firebase/database');

let env;
const A = 'alice', B = 'bob', C = 'carol';
const chatAB = 'alice_bob';
const db = (uid) => env.authenticatedContext(uid).database();
const anon = () => env.unauthenticatedContext().database();
const now = () => Date.now();

test.before(async () => {
  env = await initializeTestEnvironment({
    projectId: 'demo-minigram',
    database: { rules: fs.readFileSync(path.join(__dirname, '..', 'database.rules.json'), 'utf8'), host: '127.0.0.1', port: 9000 },
  });
});
test.after(async () => { await env.cleanup(); });
test.beforeEach(async () => {
  await env.clearDatabase();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const d = ctx.database();
    await set(ref(d, 'users/alice'), { fullname: 'Alice', handle: 'alice' });
    await set(ref(d, 'users/bob'), { fullname: 'Bob', handle: 'bob' });
    await set(ref(d, 'channels/ch1'), { owner_uid: A, name: 'News', subscriber_count: 3 });
    await set(ref(d, 'channel_posts/ch1/p1'), { text: 'hi', timestamp: 1, view_count: 2 });
    await set(ref(d, 'channel_admins/ch1/bob'), true);
  });
});

test('anonymous users get nothing', async () => {
  await assertFails(get(ref(anon(), 'users/alice')));
  await assertFails(set(ref(anon(), 'users/alice/bio'), 'x'));
});

test('profile: own write ok, others and is_banned denied', async () => {
  await assertSucceeds(update(ref(db(A), 'users/alice'), { bio: 'hello', photo_url: 'https://x/y.jpg' }));
  await assertFails(set(ref(db(B), 'users/alice/bio'), 'hacked'));
  await assertFails(set(ref(db(A), 'users/alice/is_banned'), false));
  await assertFails(set(ref(db(A), 'users/alice/photo_url'), 'http://insecure'));
  await assertSucceeds(get(ref(db(B), 'users/alice')));            // public profile readable by signed-in users
});

test('private fields + fcm token are not readable by others', async () => {
  await assertSucceeds(set(ref(db(A), 'user_private/alice/phone'), '+911234567890'));
  await assertFails(get(ref(db(B), 'user_private/alice')));
  await assertSucceeds(get(ref(db(A), 'user_private/alice')));
  await assertSucceeds(set(ref(db(A), 'fcm_tokens/alice'), 'tok'));
  await assertFails(get(ref(db(A), 'fcm_tokens/alice')));          // not even the owner: server-only
  await assertFails(set(ref(db(B), 'fcm_tokens/alice'), 'evil'));
});

test('chat: only participants read/write, sender cannot be forged', async () => {
  const m = { sender_uid: A, text: 'hello', type: 'text', timestamp: now(), status: 'sent' };
  await assertSucceeds(set(ref(db(A), `chats/${chatAB}/m1`), m));
  await assertSucceeds(get(ref(db(B), `chats/${chatAB}`)));
  await assertFails(get(ref(db(C), `chats/${chatAB}`)));
  await assertFails(set(ref(db(C), `chats/${chatAB}/m2`), { ...m, sender_uid: C }));
  await assertFails(set(ref(db(B), `chats/${chatAB}/m3`), { ...m, sender_uid: A }));      // B pretending to be A
  await assertFails(set(ref(db(A), `chats/${chatAB}/m4`), { ...m, timestamp: now() - 3600e3 })); // back-dated
  // peer may update delivery status / reaction on an existing message
  await assertSucceeds(update(ref(db(B), `chats/${chatAB}/m1`), { status: 'read' }));
  await assertSucceeds(set(ref(db(B), `chats/${chatAB}/m1/reactions/bob`), '👍'));
});

test('user_chats: peer meta writes allowed only within a shared chat', async () => {
  await assertSucceeds(update(ref(db(A), `user_chats/bob/${chatAB}`), { peer_uid: A, last_msg: 'x', last_time: now(), unread: increment(1) }));
  await assertSucceeds(set(ref(db(A), `user_chats/alice/${chatAB}/last_msg`), 'x'));
  await assertFails(set(ref(db(C), `user_chats/bob/${chatAB}/unread`), 99));
  await assertFails(get(ref(db(B), `user_chats/alice`)));
  await assertSucceeds(get(ref(db(A), `user_chats/alice`)));
});

test('channels: owner/admin write posts, others only interact', async () => {
  await assertSucceeds(set(ref(db(A), 'channel_posts/ch1/p2'), { text: 'new', timestamp: 2 }));
  await assertSucceeds(set(ref(db(B), 'channel_posts/ch1/p3'), { text: 'by admin', timestamp: 3 }));
  await assertFails(set(ref(db(C), 'channel_posts/ch1/p4'), { text: 'spam', timestamp: 4 }));
  await assertFails(set(ref(db(C), 'channel_posts/ch1/p1/text'), 'edited'));
  await assertSucceeds(set(ref(db(C), 'channel_posts/ch1/p1/likes/carol'), true));
  await assertFails(set(ref(db(C), 'channel_posts/ch1/p1/likes/alice'), true));
  // view recording (multi-path, as ChannelActivity does)
  await assertSucceeds(update(ref(db(C)), { 'channel_post_views/ch1/p1/carol': true, 'channel_posts/ch1/p1/view_count': increment(1) }));
  await assertFails(set(ref(db(C), 'channel_posts/ch1/p1/view_count'), 1));                 // cannot decrease
});

test('channels: subscribe flow and counter tampering', async () => {
  await assertSucceeds(update(ref(db(C)), {
    'channel_subscribers/ch1/carol': true, 'user_channels/carol/ch1': true, 'channels/ch1/subscriber_count': increment(1) }));
  await assertFails(set(ref(db(C), 'channels/ch1/subscriber_count'), 100000));
  await assertFails(set(ref(db(C), 'channels/ch1/name'), 'Hijacked'));
  await assertFails(set(ref(db(C), 'channel_subscribers/ch1/alice'), true));
  await assertSucceeds(set(ref(db(A), 'channel_subscribers/ch1/dave'), true));              // owner adds subscriber
  await assertFails(get(ref(db(C), 'user_channels/alice')));
});

test('channel creation requires self as owner', async () => {
  await assertSucceeds(set(ref(db(C), 'channels/ch2'), { owner_uid: C, name: 'Mine', subscriber_count: 0 }));
  await assertFails(set(ref(db(C), 'channels/ch3'), { owner_uid: A, name: 'Fake', subscriber_count: 0 }));
  await assertFails(set(ref(db(C), 'channel_admins/ch1/carol'), true));
});

test('handles / phone_index cannot be stolen', async () => {
  await assertSucceeds(set(ref(db(A), 'handles/alice'), A));
  await assertFails(set(ref(db(B), 'handles/alice'), B));
  await assertSucceeds(set(ref(db(A), 'phone_index/911234567890'), A));
  await assertFails(set(ref(db(B), 'phone_index/911234567890'), B));
  await assertFails(get(ref(db(B), 'phone_index')));                                           // no enumeration
  await assertSucceeds(get(ref(db(B), 'phone_index/911234567890')));
});

test('server-only nodes', async () => {
  await assertFails(set(ref(db(A), 'rate_limits/alice/push'), { start: 0, count: 0 }));
  await assertFails(set(ref(db(A), 'updates_feed/x'), { t: 1 }));
  await assertFails(get(ref(db(A), 'channel_reports')));
  await assertSucceeds(push(ref(db(A), 'channel_reports'), { reason: 'spam' }));
});

test('e2ee key bundles: public read, owner-only write, ciphertext messages validated', async () => {
  const bundle = { ik: 'a'.repeat(44), sik: 'b'.repeat(44), spk: 'c'.repeat(44), spk_sig: 'd'.repeat(88), spk_id: 1, ts: now() };
  await assertSucceeds(set(ref(db(A), 'e2ee_keys/alice'), bundle));
  await assertFails(set(ref(db(B), 'e2ee_keys/alice'), bundle));                 // cannot replace someone's keys
  await assertSucceeds(get(ref(db(B), 'e2ee_keys/alice')));                      // peers can fetch them
  await assertFails(set(ref(db(A), 'e2ee_keys/alice'), { ik: 'only' }));         // incomplete bundle rejected
  const enc = { sender_uid: A, type: 'text', text: '', timestamp: now(), status: 'sent', e2ee: 1, ct: 'x'.repeat(200), x_ik: 'k'.repeat(44), x_ek: 'k'.repeat(44), x_spk: 1 };
  await assertSucceeds(set(ref(db(A), `chats/${chatAB}/e1`), enc));
  await assertFails(set(ref(db(A), `chats/${chatAB}/e2`), { ...enc, ct: 'x'.repeat(50000) }));
  await assertFails(set(ref(db(C), `chats/${chatAB}/e3`), enc));                 // outsider cannot inject ciphertext
  await assertSucceeds(update(ref(db(A), `chats/${chatAB}/e1`), { ct: 'y'.repeat(100), is_edited: true })); // edit re-encrypts
});

test('channel tags (Discover): only owner/admin can set them', async () => {
  await assertSucceeds(set(ref(db(A), 'channels/ch1/tags'), { Tech: true, Cricket: true }));
  await assertFails(set(ref(db(C), 'channels/ch1/tags'), { Memes: true }));
  await assertFails(set(ref(db(A), 'channels/ch1/tags/Tech'), 'yes'));            // values must be true
});

// ---- Regression: the exact multi-path writes the app performs (profile screen showed "username or phone just taken" for everyone)
test('CompleteProfile: brand-new user claims object handle + phone + legacy username in one write', async () => {
  const w = {
    'users/carol/fullname': 'Carol', 'users/carol/fullname_lower': 'carol', 'users/carol/bio': 'Hello',
    'user_private/carol/phone': '+919625006163', 'user_private/carol/phone_cc': '+91', 'user_private/carol/phone_number': '9625006163',
    'phone_index/919625006163': C,
    'handles/baddevil123hh': { type: 'user', id: C }, 'usernames/baddevil123hh': C,
    'users/carol/username': 'baddevil123hh', 'users/carol/username_lower': 'baddevil123hh', 'users/carol/handle': 'baddevil123hh',
    'user_private/carol/email': 'c@x.com', 'users/carol/photo_url': '', 'users/carol/status': 'offline', 'users/carol/created_at': now(),
  };
  await assertSucceeds(update(ref(db(C)), w));
});
test('handles: cannot take a handle someone else owns (object or string form), owner can release/rename', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => { await set(ref(ctx.database(), 'handles/taken'), { type: 'user', id: A }); });
  await assertFails(set(ref(db(B), 'handles/taken'), { type: 'user', id: B }));
  await assertFails(set(ref(db(B), 'handles/taken'), null));
  await assertFails(set(ref(db(B), 'handles/new'), { type: 'user', id: A }));          // cannot register on behalf of another user
  await assertSucceeds(update(ref(db(A)), { 'handles/taken': null, 'handles/renamed': { type: 'user', id: A } }));
});
test('CreateChannel: channel + channel handle created atomically; only that owner can release it', async () => {
  await assertSucceeds(update(ref(db(C)), {
    'channels/chN': { owner_uid: C, name: 'N', subscriber_count: 1, privacy: 'public' },
    'handles/mychannel': { type: 'channel', id: 'chN' } }));
  await assertFails(set(ref(db(B), 'handles/mychannel'), null));
  await assertSucceeds(set(ref(db(C), 'handles/mychannel'), null));
  await assertFails(update(ref(db(B)), { 'handles/stolen': { type: 'channel', id: 'ch1' } }));   // ch1 is owned by alice
});
