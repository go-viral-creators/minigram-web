'use strict';
const test = require('node:test');
const fs = require('fs');
const path = require('path');
const { initializeTestEnvironment, assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const { ref, set, get, update, remove, serverTimestamp, increment } = require('firebase/database');

let env;
const A = 'alice', B = 'bob', C = 'carol', AB = 'alice_bob';
const db = (u) => env.authenticatedContext(u).database();
const anon = () => env.unauthenticatedContext().database();
const msg = (from) => ({ from, ts: Date.now(), type: 'text', body: 'ct' });

test.before(async () => {
  env = await initializeTestEnvironment({ projectId: 'demo-wetalks',
    database: { rules: fs.readFileSync(path.join(__dirname, '..', 'database.rules.json'), 'utf8'), host: '127.0.0.1', port: 9000 } });
});
test.after(async () => { await env.cleanup(); });
test.beforeEach(async () => {
  await env.clearDatabase();
  await env.withSecurityRulesDisabled(async (ctx) => {
    const d = ctx.database();
    await set(ref(d, 'users/alice'), { name: 'Alice', username: 'alice', usernameLower: 'alice', pk: 'x' });
    await set(ref(d, 'users/bob'), { name: 'Bob', username: 'bob', usernameLower: 'bob', pk: 'y' });
    await set(ref(d, 'groups/g1'), { name: 'G', owner: A, kv: 1, members: { alice: 'admin', bob: 'member' } });
    await set(ref(d, 'channels/c1'), { name: 'News', handle: 'news', handleLower: 'news', owner: A, admins: { alice: true }, subCount: 1 });
  });
});

test('profile: own yes, others no, anon no', async () => {
  await assertSucceeds(update(ref(db(A), 'users/alice'), { about: 'hi' }));
  await assertFails(update(ref(db(B), 'users/alice'), { about: 'hax' }));
  await assertFails(get(ref(anon(), 'users/alice')));
  await assertFails(set(ref(db(A), 'users/alice/username'), 'a!'));
});

test('usernames are unique and only the owner can release', async () => {
  await assertSucceeds(set(ref(db(C), 'usernames/carol'), C));
  await assertFails(set(ref(db(A), 'usernames/carol'), A));
  await assertFails(remove(ref(db(A), 'usernames/carol')));
  await assertSucceeds(remove(ref(db(C), 'usernames/carol')));
  await assertFails(set(ref(db(C), 'usernames/Bad Name'), C));
});

test('DM: only the two people read/write, no forged sender', async () => {
  await assertSucceeds(set(ref(db(A), `messages/${AB}/m1`), msg(A)));
  await assertSucceeds(get(ref(db(B), `messages/${AB}`)));
  await assertFails(get(ref(db(C), `messages/${AB}`)));
  await assertFails(set(ref(db(C), `messages/${AB}/m2`), msg(C)));
  await assertFails(set(ref(db(A), `messages/${AB}/m3`), msg(B)));
  await assertSucceeds(remove(ref(db(B), `messages/${AB}/m1`)));   // either person may delete (also delete for the other side)
  await assertSucceeds(set(ref(db(A), `messages/${AB}/m1`), msg(A)));
  await assertSucceeds(remove(ref(db(A), `messages/${AB}/m1`)));
});

test('chat list: private, peers may update the shared entry only', async () => {
  await assertSucceeds(update(ref(db(A), `user_chats/bob/${AB}`), { lastTs: Date.now(), unread: increment(1) }));
  await assertFails(update(ref(db(C), `user_chats/bob/${AB}`), { unread: 5 }));
  await assertFails(get(ref(db(C), 'user_chats/bob')));
  await assertSucceeds(get(ref(db(B), 'user_chats/bob')));
});

test('reactions: own uid only, members only', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => set(ref(ctx.database(), `messages/${AB}/m1`), msg(A)));
  await assertSucceeds(set(ref(db(B), `messages/${AB}/m1/react/bob`), '👍'));
  await assertFails(set(ref(db(B), `messages/${AB}/m1/react/alice`), '👍'));
  await assertFails(set(ref(db(C), `messages/${AB}/m1/react/carol`), '👍'));
});

test('groups: members only, admins manage, members leave themselves', async () => {
  await assertSucceeds(get(ref(db(B), 'messages/g1')));
  await assertFails(get(ref(db(C), 'messages/g1')));
  await assertSucceeds(set(ref(db(B), 'messages/g1/m1'), msg(B)));
  await assertFails(set(ref(db(C), 'messages/g1/m2'), msg(C)));
  await assertFails(get(ref(db(C), 'groups/g1')));
  await assertSucceeds(set(ref(db(A), 'groups/g1/members/carol'), 'member'));
  await assertFails(set(ref(db(B), 'groups/g1/members/dave'), 'member'));
  await assertFails(set(ref(db(B), 'groups/g1/members/alice'), 'member'));
  await assertSucceeds(remove(ref(db(B), 'groups/g1/members/bob')));
  await assertFails(update(ref(db(B), 'groups/g1'), { name: 'X' }));
});

test('group keys: written by admins, read only by the recipient', async () => {
  await assertSucceeds(set(ref(db(A), 'group_keys/g1/1/bob'), { by: A, ct: 'zz' }));
  await assertSucceeds(get(ref(db(B), 'group_keys/g1/1/bob')));
  await assertFails(get(ref(db(A), 'group_keys/g1/1/bob')));
  await assertFails(set(ref(db(B), 'group_keys/g1/1/bob'), { by: B, ct: 'zz' }));
});

test('group creation: creator only, as owner', async () => {
  await assertSucceeds(set(ref(db(C), 'groups/g2'), { name: 'N', owner: C, kv: 1, members: { carol: 'admin' } }));
  await assertFails(set(ref(db(C), 'user_chats/alice/g2/type'), 'group'));            // alice is not a member yet: no stub entries for outsiders
  await assertSucceeds(set(ref(db(C), 'groups/g2/members/alice'), 'member'));
  await assertFails(set(ref(db(C), 'groups/g3'), { name: 'N', owner: A, kv: 1, members: { carol: 'admin' } }));
  await assertSucceeds(set(ref(db(C), 'user_chats/carol/g2/type'), 'group'));
  await assertSucceeds(set(ref(db(C), 'user_chats/alice/g2/type'), 'group'));   // creator is admin and alice is a member now
  await assertSucceeds(update(ref(db(C), 'user_chats/alice/g2'), { lastTs: Date.now(), unread: 0 }));
});

test('channels: owner posts, others only read; sub count moves by one', async () => {
  await assertSucceeds(set(ref(db(A), 'messages/c1/p1'), msg(A)));
  await assertFails(set(ref(db(B), 'messages/c1/p2'), msg(B)));
  await assertSucceeds(get(ref(db(B), 'messages/c1')));
  await assertSucceeds(set(ref(db(B), 'channel_subs/c1/bob'), true));
  await assertFails(set(ref(db(B), 'channel_subs/c1/carol'), true));
  await assertSucceeds(set(ref(db(B), 'channels/c1/subCount'), 2));
  await assertFails(set(ref(db(B), 'channels/c1/subCount'), 99));
  await assertFails(set(ref(db(B), 'channels/c1/name'), 'hijack'));
  await assertSucceeds(set(ref(db(B), 'messages/c1/p1/react/bob'), '🔥'));
});

test('channel handles are unique', async () => {
  await assertSucceeds(set(ref(db(C), 'channel_handles/tech'), 'cX'));
  await assertFails(set(ref(db(B), 'channel_handles/tech'), 'cY'));
  await assertFails(set(ref(db(B), 'channel_handles/x'), 'cY'));
});

test('media: new files can no longer be written to the database (they go to the media server); old ones stay readable', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => { await set(ref(ctx.database(), 'media/m1'), { from: A, mime: 'image/jpeg', data: 'abc' }); });
  await assertFails(set(ref(db(A), 'media/m9'), { from: A, mime: 'image/jpeg', data: 'abc' }));
  await assertSucceeds(get(ref(db(B), 'media/m1')));
  await assertFails(get(ref(anon(), 'media/m1')));
});

test('blocks are private to their owner', async () => {
  await assertSucceeds(set(ref(db(A), 'blocks/alice/bob'), true));
  await assertFails(get(ref(db(B), 'blocks/alice')));
  await assertFails(set(ref(db(B), 'blocks/alice/carol'), true));
});

test('creating a channel works end to end (regression)', async () => {
  const d = db(C);
  await assertSucceeds(set(ref(d, 'channel_handles/wetalks'), 'cNew'));
  await assertSucceeds(set(ref(d, 'channels/cNew'), { name: 'WeTalks', handle: 'wetalks', handleLower: 'wetalks', nameLower: 'wetalks', about: 'Hello', photo: '', owner: C, createdAt: serverTimestamp(), subCount: 1, admins: { carol: true } }));
  await assertSucceeds(set(ref(d, 'channel_subs/cNew/carol'), true));
  await assertSucceeds(update(ref(d, 'user_chats/carol/cNew'), { type: 'channel', lastTs: serverTimestamp() }));
  await assertFails(set(ref(db(B), 'channels/cNew2'), { name: 'x', owner: C, subCount: 5 }));
});

test('pins: DM members and group admins only', async () => {
  await assertSucceeds(set(ref(db(A), `pins/${AB}/m1`), 1));
  await assertSucceeds(get(ref(db(B), `pins/${AB}`)));
  await assertFails(set(ref(db(C), `pins/${AB}/m2`), 1));
  await assertFails(get(ref(db(C), `pins/${AB}`)));
  await assertSucceeds(set(ref(db(A), 'pins/g1/m1'), 1));
  await assertFails(set(ref(db(B), 'pins/g1/m1'), 1));          // plain member
  await assertSucceeds(set(ref(db(A), 'pins/c1/p1'), 1));        // channel owner
  await assertFails(set(ref(db(B), 'pins/c1/p1'), 1));
});

test('phone discovery: one hash per account, no hijack', async () => {
  const h = 'a'.repeat(32);
  await assertSucceeds(set(ref(db(A), `phones/${h}`), A));
  await assertFails(set(ref(db(B), `phones/${h}`), B));
  await assertFails(remove(ref(db(B), `phones/${h}`)));
  await assertSucceeds(get(ref(db(B), `phones/${h}`)));
  await assertFails(set(ref(db(B), 'phones/short'), B));
  await assertSucceeds(remove(ref(db(A), `phones/${h}`)));
});

test('saved messages (chat with yourself)', async () => {
  await assertSucceeds(set(ref(db(A), 'messages/alice_alice/m1'), msg(A)));
  await assertSucceeds(update(ref(db(A), 'user_chats/alice/alice_alice'), { type: 'dm', peer: A, lastTs: Date.now() }));
  await assertFails(get(ref(db(B), 'messages/alice_alice')));
});

test('deleting messages removes them completely', async () => {
  await assertSucceeds(set(ref(db(A), `messages/${AB}/m1`), msg(A)));
  await assertSucceeds(set(ref(db(B), `messages/${AB}/m2`), msg(B)));
  // either person in a DM may remove any message ("also delete for X")
  await assertSucceeds(remove(ref(db(B), `messages/${AB}/m1`)));
  await assertSucceeds(remove(ref(db(A), `messages/${AB}/m2`)));
  // outsiders cannot
  await assertSucceeds(set(ref(db(A), `messages/${AB}/m3`), msg(A)));
  await assertFails(remove(ref(db(C), `messages/${AB}/m3`)));
  // groups: sender or admin only
  await assertSucceeds(set(ref(db(B), 'messages/g1/g1'), msg(B)));
  await assertSucceeds(set(ref(db(B), 'messages/g1/g2'), msg(B)));
  await assertSucceeds(remove(ref(db(B), 'messages/g1/g1')));
  await assertSucceeds(remove(ref(db(A), 'messages/g1/g2')));     // admin removes a member's message
  await assertSucceeds(set(ref(db(A), 'messages/g1/g3'), msg(A)));
  await assertFails(remove(ref(db(B), 'messages/g1/g3')));         // plain member cannot remove an admin's message
  // channels: admins
  await assertSucceeds(set(ref(db(A), 'messages/c1/p1'), msg(A)));
  await assertFails(remove(ref(db(B), 'messages/c1/p1')));
  await assertSucceeds(remove(ref(db(A), 'messages/c1/p1')));
});

test('deleting a whole DM for both people', async () => {
  await assertSucceeds(set(ref(db(A), `messages/${AB}/m1`), msg(A)));
  await assertSucceeds(set(ref(db(B), `messages/${AB}/m2`), msg(B)));
  await assertSucceeds(set(ref(db(A), `pins/${AB}/m1`), 1));
  await assertFails(remove(ref(db(C), `messages/${AB}`)));
  await assertSucceeds(update(ref(db(A)), { [`messages/${AB}`]: null, [`pins/${AB}`]: null, [`user_chats/alice/${AB}`]: null, [`user_chats/bob/${AB}`]: null }));
  const s = await get(ref(db(B), `messages/${AB}`));
  if (s.exists()) throw new Error('chat should be gone');
  await assertFails(remove(ref(db(A), 'messages/g1')));            // not for groups
});

test('media: only the uploader can delete an old file', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => { await set(ref(ctx.database(), 'media/x1'), { from: A, mime: 'image/jpeg', data: 'abc' }); });
  await assertFails(remove(ref(db(B), 'media/x1')));
  await assertSucceeds(remove(ref(db(A), 'media/x1')));
});

test('calls: only the two people see the signalling, nobody can ring as someone else', async () => {
  const call = { from: A, to: B, video: true, ts: Date.now(), state: 'ringing', offer: 'sealed' };
  await assertSucceeds(set(ref(db(A), 'calls/c1'), call));
  await assertFails(set(ref(db(C), 'calls/c2'), { ...call, from: A }));                 // forged caller
  await assertSucceeds(set(ref(db(A), 'incoming_calls/bob/c1'), { from: A, video: true, ts: 1 }));
  await assertFails(set(ref(db(C), 'incoming_calls/bob/c9'), { from: A, video: true, ts: 1 }));
  await assertFails(get(ref(db(C), 'incoming_calls/bob')));
  await assertSucceeds(get(ref(db(B), 'incoming_calls/bob')));
  await assertSucceeds(get(ref(db(B), 'calls/c1')));
  await assertFails(get(ref(db(C), 'calls/c1')));
  await assertSucceeds(update(ref(db(B), 'calls/c1'), { state: 'accepted', answer: 'sealed' }));
  await assertSucceeds(set(ref(db(B), 'calls/c1/ice_callee/k1'), 'sealed'));
  await assertFails(set(ref(db(C), 'calls/c1/ice_callee/k2'), 'x'));
  await assertFails(update(ref(db(C), 'calls/c1'), { state: 'ended' }));
  await assertSucceeds(remove(ref(db(B), 'incoming_calls/bob/c1')));
  await assertSucceeds(remove(ref(db(A), 'calls/c1')));
});

test('group permissions: members obey what admins switch off, admins always can', async () => {
  await assertSucceeds(set(ref(db(B), 'messages/g1/a'), { from: B, ts: 1, type: 'text', body: 'x' }));
  await assertSucceeds(update(ref(db(A), 'groups/g1'), { 'perms/image': false, 'perms/voice': false }));
  await assertFails(set(ref(db(B), 'messages/g1/b'), { from: B, ts: 2, type: 'image', body: 'x' }));
  await assertFails(set(ref(db(B), 'messages/g1/b2'), { from: B, ts: 2, type: 'voice', body: 'x' }));
  await assertSucceeds(set(ref(db(B), 'messages/g1/c'), { from: B, ts: 3, type: 'text', body: 'x' }));
  await assertSucceeds(set(ref(db(A), 'messages/g1/d'), { from: A, ts: 4, type: 'image', body: 'x' }));
  await assertFails(update(ref(db(B), 'groups/g1'), { 'perms/image': true }));          // members cannot change the rules
  await assertSucceeds(set(ref(db(A), 'groups/g1/perms/send'), false));
  await assertFails(set(ref(db(B), 'messages/g1/e'), { from: B, ts: 5, type: 'text', body: 'x' }));
  await assertSucceeds(set(ref(db(A), 'messages/g1/f'), { from: A, ts: 6, type: 'text', body: 'x' }));
});

test('group slow mode and member pinning', async () => {
  await assertSucceeds(set(ref(db(A), 'groups/g1/slow'), 60));
  await assertSucceeds(update(ref(db(B)), { 'messages/g1/s1': { from: B, ts: 1, type: 'text', body: 'x' }, 'slow/g1/bob': Date.now() + 1000 }));
  await assertFails(set(ref(db(B), 'messages/g1/s2'), { from: B, ts: 2, type: 'text', body: 'x' }));
  await assertSucceeds(set(ref(db(A), 'messages/g1/s3'), { from: A, ts: 3, type: 'text', body: 'x' }));   // admin is exempt
  await assertFails(set(ref(db(B), 'pins/g1/s1'), 1));
  await assertSucceeds(set(ref(db(A), 'groups/g1/perms/pin'), true));
  await assertSucceeds(set(ref(db(B), 'pins/g1/s1'), 1));
});

test('channel: followers can only post when the owner allows it', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => { await set(ref(ctx.database(), 'channel_subs/c1/bob'), true); });
  await assertFails(set(ref(db(B), 'messages/c1/f1'), { from: B, ts: 1, type: 'text', body: 'x' }));
  await assertFails(set(ref(db(B), 'channels/c1/perms/followers'), true));                 // not the owner
  await assertSucceeds(update(ref(db(A), 'channels/c1'), { 'perms/followers': true, 'perms/image': false, priv: true }));
  await assertSucceeds(update(ref(db(B)), { 'messages/c1/f2': { from: B, ts: 2, type: 'text', body: 'x' }, 'channels/c1/lastTs': Date.now(), 'channels/c1/lastPreview': 'x' }));
  await assertFails(set(ref(db(B), 'messages/c1/f3'), { from: B, ts: 3, type: 'image', body: 'x' }));
  await assertFails(set(ref(db(C), 'messages/c1/f4'), { from: C, ts: 4, type: 'text', body: 'x' }));   // not a follower
  await assertSucceeds(set(ref(db(A), 'channels/c1/admins/bob'), true));                      // owner promotes
  await assertSucceeds(set(ref(db(B), 'messages/c1/f5'), { from: B, ts: 5, type: 'image', body: 'x' }));
});

test('a group member with the pin permission still cannot delete other people\'s messages', async () => {
  await assertSucceeds(set(ref(db(A), 'messages/g1/x1'), { from: A, ts: 1, type: 'text', body: 'x' }));
  await assertSucceeds(set(ref(db(A), 'groups/g1/perms/pin'), true));
  await assertFails(remove(ref(db(B), 'messages/g1/x1')));
});

test('encryption key backups are private to their owner', async () => {
  const bk = { s: 'salt', i: 210000, c: 'sealed', pub: 'pub' };
  await assertSucceeds(set(ref(db(A), 'key_backups/alice'), bk));
  await assertSucceeds(get(ref(db(A), 'key_backups/alice')));
  await assertFails(get(ref(db(B), 'key_backups/alice')));
  await assertFails(set(ref(db(B), 'key_backups/alice'), bk));
  await assertFails(set(ref(db(A), 'key_backups/alice'), { ...bk, c: 'x'.repeat(500) }));
});

test('privacy: last seen, online status and phone number follow the owner\'s choices', async () => {
  await assertSucceeds(set(ref(db(A), 'presence/alice'), { online: true, lastSeen: 5 }));
  await assertSucceeds(get(ref(db(B), 'presence/alice/lastSeen')));                    // default = everybody
  await assertSucceeds(set(ref(db(A), 'privacy/alice/lastSeen'), 'nobody'));
  await assertFails(get(ref(db(B), 'presence/alice/lastSeen')));
  await assertSucceeds(get(ref(db(B), 'presence/alice/online')));                      // a separate setting
  await assertSucceeds(get(ref(db(A), 'presence/alice/lastSeen')));                    // owners always see their own
  await assertSucceeds(set(ref(db(A), 'privacy/alice/online'), 'contacts'));
  await assertFails(get(ref(db(B), 'presence/alice/online')));                         // no chat yet
  await env.withSecurityRulesDisabled(async (ctx) => { await set(ref(ctx.database(), 'contacts/alice/bob'), true); });
  await assertSucceeds(get(ref(db(B), 'presence/alice/online')));                      // now a contact
  await assertFails(get(ref(db(C), 'presence/alice/online')));
  await assertFails(set(ref(db(B), 'presence/alice/online'), false));
  await assertSucceeds(set(ref(db(A), 'contact_info/alice/phone'), '+919876543210'));
  await assertFails(get(ref(db(B), 'contact_info/alice/phone')));                      // default = nobody
  await assertSucceeds(set(ref(db(A), 'privacy/alice/phone'), 'all'));
  await assertSucceeds(get(ref(db(B), 'contact_info/alice/phone')));
  await assertFails(set(ref(db(A), 'privacy/alice/phone'), 'everyone'));              // only the three allowed values
  await assertFails(get(ref(db(B), 'privacy/alice')));
});

test('call flow: candidates only after the call exists, callee marks ringing, stale entries can be cleaned', async () => {
  await assertFails(set(ref(db(A), 'calls/k1/ice_caller/x'), 'sealed'));              // too early: why the app now queues candidates
  await assertSucceeds(set(ref(db(A), 'calls/k1'), { from: A, to: B, video: false, ts: 1, state: 'ringing', offer: 's' }));
  await assertSucceeds(set(ref(db(A), 'calls/k1/ice_caller/x'), 'sealed'));
  await assertSucceeds(set(ref(db(A), 'incoming_calls/bob/k1'), { from: A, video: false, ts: 1 }));
  await assertSucceeds(set(ref(db(B), 'calls/k1/ringing'), true));
  await assertSucceeds(update(ref(db(B), 'calls/k1'), { answer: 'a', state: 'accepted' }));
  await assertSucceeds(set(ref(db(B), 'calls/k1/ice_callee/y'), 'sealed'));
  await assertSucceeds(remove(ref(db(B), 'incoming_calls/bob/k1')));
});

test('maintenance switch is public to read and impossible to change from the app; push tokens are private to their owner', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => { await set(ref(ctx.database(), 'config/maintenance'), { on: true, title: 'x' }); });
  await assertSucceeds(get(ref(anon(), 'config/maintenance')));
  await assertFails(set(ref(db(A), 'config/maintenance/on'), false));
  await assertFails(set(ref(anon(), 'config/maintenance/on'), false));
  await assertFails(get(ref(db(A), 'config')));
  const tok = 'x'.repeat(40);
  await assertSucceeds(set(ref(db(A), 'fcm_tokens/alice/' + tok), serverTimestamp()));
  await assertFails(set(ref(db(B), 'fcm_tokens/alice/' + tok), serverTimestamp()));   // nobody can plant a token on someone else
  await assertFails(get(ref(db(A), 'fcm_tokens/alice')));                              // tokens can't be read back, even by the owner
  await assertFails(get(ref(db(B), 'fcm_tokens/alice')));
  await assertFails(set(ref(db(A), 'fcm_tokens/alice/short'), serverTimestamp()));
  await assertFails(set(ref(db(A), 'fcm_tokens/alice/' + tok), 'text'));
});

test('web cards: public to read, only the owner writes, unknown fields rejected', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => { await set(ref(ctx.database(), 'usernames/alice'), 'alice'); await set(ref(ctx.database(), 'channel_handles/news'), 'c1'); });
  await assertSucceeds(set(ref(db(A), 'web/u/alice'), { n: 'Alice', a: 'hi', p: '' }));
  await assertSucceeds(get(ref(anon(), 'web/u/alice')));
  await assertFails(set(ref(db(B), 'web/u/alice'), { n: 'Fake' }));
  await assertFails(set(ref(db(A), 'web/u/alice'), { n: 'Alice', phone: '123' }));
  await assertFails(get(ref(anon(), 'web/u')));
  await assertSucceeds(set(ref(db(A), 'web/c/news'), { n: 'News', a: '', s: 1, p: '' }));
  await assertSucceeds(get(ref(anon(), 'web/c/news')));
  await assertFails(set(ref(db(B), 'web/c/news'), { n: 'Hacked' }));
  await assertSucceeds(remove(ref(db(A), 'web/u/alice')));
});

test('service inbox: only the relay writes, people can switch services off or block an app, strangers cannot read or write', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => { await set(ref(ctx.database(), 'config/relay'), 'relayuid'); });
  const msg = { a: 'app_0123456789ab', an: 'Acme', v: 0, k: 'otp', x: 'Your code is 123456', ts: Date.now(), o: 1 };
  await assertSucceeds(set(ref(db('relayuid'), 'service_inbox/alice/m1'), msg));
  await assertSucceeds(get(ref(db(A), 'service_inbox/alice')));
  await assertFails(get(ref(db(B), 'service_inbox/alice')));
  await assertFails(get(ref(anon(), 'service_inbox/alice')));
  await assertFails(set(ref(db(B), 'service_inbox/alice/m2'), msg));                        // a normal user can't send service messages
  await assertFails(set(ref(db(A), 'service_inbox/alice/m3'), msg));                        // not even to themselves
  await assertFails(set(ref(db('relayuid'), 'service_inbox/alice/m1'), { ...msg, x: 'changed' })); // no editing sent messages
  await assertFails(set(ref(db('relayuid'), 'service_inbox/alice/m4'), { ...msg, x: 'x'.repeat(501) }));
  await assertFails(set(ref(db('relayuid'), 'service_inbox/alice/m5'), { ...msg, k: 'spam' }));
  await assertFails(set(ref(db('relayuid'), 'service_inbox/alice/m6'), { ...msg, extra: 'field' }));
  await assertFails(set(ref(db('relayuid'), 'service_inbox/alice/m7'), { ...msg, l: 'http://evil.example' }));
  await assertSucceeds(set(ref(db('relayuid'), 'service_inbox/alice/m8'), { ...msg, l: 'https://example.com/a?b=1' }));
  // block this app
  await assertSucceeds(set(ref(db(A), 'service_blocks/alice/app_0123456789ab'), true));
  await assertFails(set(ref(db('relayuid'), 'service_inbox/alice/m9'), msg));
  await assertSucceeds(set(ref(db('relayuid'), 'service_inbox/alice/m10'), { ...msg, a: 'app_aaaaaaaaaaaa' })); // other apps still fine
  await assertFails(set(ref(db(B), 'service_blocks/alice/app_bbbbbbbbbbbb'), true));        // only you manage your block list
  await assertSucceeds(remove(ref(db(A), 'service_blocks/alice/app_0123456789ab')));
  // switch all services off
  await assertSucceeds(set(ref(db(A), 'privacy/alice/services'), 'nobody'));
  await assertFails(set(ref(db('relayuid'), 'service_inbox/alice/m11'), msg));
  await assertSucceeds(set(ref(db(A), 'privacy/alice/services'), 'all'));
  await assertSucceeds(set(ref(db('relayuid'), 'service_inbox/alice/m12'), msg));
  // delete: owner only
  await assertFails(remove(ref(db(B), 'service_inbox/alice/m1')));
  await assertSucceeds(remove(ref(db(A), 'service_inbox/alice/m1')));
  await assertFails(set(ref(db(A), 'config/relay'), 'alice'));                              // nobody can make themselves the relay
});

// ---- remediation H3: the chat list can no longer be forged, "contacts" cannot be faked ----
test('H3: old-client write shapes keep working (send, refreshLast, clearHistory, createGroup, remove member)', async () => {
  // DM send fan-out by alice: own entry + bob's entry in one atomic update
  const t = Date.now();
  await assertSucceeds(update(ref(db(A)), {
    [`messages/${AB}/m1`]: msg(A),
    [`user_chats/alice/${AB}/type`]: 'dm', [`user_chats/alice/${AB}/peer`]: B, [`user_chats/alice/${AB}/lastTs`]: t, [`user_chats/alice/${AB}/lastBody`]: 'ct', [`user_chats/alice/${AB}/lastType`]: 'text', [`user_chats/alice/${AB}/lastFrom`]: A, [`user_chats/alice/${AB}/unread`]: 0,
    [`user_chats/bob/${AB}/type`]: 'dm', [`user_chats/bob/${AB}/peer`]: A, [`user_chats/bob/${AB}/lastTs`]: t, [`user_chats/bob/${AB}/lastBody`]: 'ct', [`user_chats/bob/${AB}/lastType`]: 'text', [`user_chats/bob/${AB}/lastFrom`]: A, [`user_chats/bob/${AB}/unread`]: increment(1) }));
  await assertSucceeds(update(ref(db(A)), { [`user_chats/bob/${AB}/lastFrom`]: A, [`user_chats/bob/${AB}/unread`]: increment(1) }));
  // group fan-out: bob (member) writes alice's entry
  await assertSucceeds(update(ref(db(B)), { 'user_chats/alice/g1/type': 'group', 'user_chats/alice/g1/lastTs': t, 'user_chats/alice/g1/lastFrom': B, 'user_chats/alice/g1/unread': increment(1) }));
  // refreshLast: peer entry gets peer = writer; lastFrom may be the other person
  await assertSucceeds(update(ref(db(A)), { [`user_chats/bob/${AB}/peer`]: A, [`user_chats/bob/${AB}/lastFrom`]: B, [`user_chats/bob/${AB}/lastBody`]: '' }));
  // clearHistory resets both unread counters
  await assertSucceeds(update(ref(db(A)), { [`user_chats/bob/${AB}/unread`]: 0, [`user_chats/alice/${AB}/unread`]: 0 }));
  // owner settings
  await assertSucceeds(set(ref(db(B), `user_chats/bob/${AB}/pinned`), true));
  await assertSucceeds(set(ref(db(B), `user_chats/bob/${AB}/muted`), true));
  // delete for both
  await assertSucceeds(update(ref(db(A)), { [`user_chats/alice/${AB}`]: null, [`user_chats/bob/${AB}`]: null }));
  // admin removes a member (the member's entry is deleted after they left the group)
  await assertSucceeds(set(ref(db(A), 'groups/g1/members/bob'), null));
  await assertSucceeds(set(ref(db(A), 'user_chats/bob/g1'), null));
});

test('H3: attacker cannot plant stubs, fake pins/mutes, spam unread or lie about the peer', async () => {
  await assertFails(set(ref(db(C), 'user_chats/alice/carol_dave/type'), 'dm'));            // chat that does not involve alice
  await assertFails(set(ref(db(C), 'user_chats/alice/g1/type'), 'group'));                  // group carol is not in
  await assertFails(set(ref(db(B), 'user_chats/alice/g1x/type'), 'group'));                 // group that does not exist
  await assertFails(set(ref(db(B), `user_chats/alice/${AB}/pinned`), true));                // pinned/muted are owner-only
  await assertFails(set(ref(db(B), `user_chats/alice/${AB}/muted`), true));
  await assertFails(set(ref(db(B), `user_chats/alice/${AB}/junk`), 'x'));                   // unknown fields
  await assertSucceeds(set(ref(db(B), `user_chats/alice/${AB}/type`), 'dm'));
  await assertFails(set(ref(db(B), `user_chats/alice/${AB}/peer`), 'someone_else'));        // peer must be the writer
  await assertFails(set(ref(db(B), `user_chats/alice/${AB}/unread`), 999));                 // no inflating
  await assertSucceeds(set(ref(db(B), `user_chats/alice/${AB}/unread`), 1));
  await assertFails(set(ref(db(B), `user_chats/alice/${AB}/unread`), 50));
  await assertSucceeds(set(ref(db(B), `user_chats/alice/${AB}/unread`), 2));
  await assertFails(set(ref(db(B), `user_chats/alice/${AB}/lastBody`), 'x'.repeat(60001)));
  await assertSucceeds(set(ref(db(A), `user_chats/alice/${AB}/unread`), 40));              // the owner decides her own counter
});

test('H3: "contacts" privacy cannot be faked with a stub, and blocked people see nothing', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => { const d = ctx.database();
    await set(ref(d, 'presence/alice'), { online: true, lastSeen: 5 }); await set(ref(d, 'privacy/alice/online'), 'contacts'); await set(ref(d, 'privacy/alice/phone'), 'contacts');
    await set(ref(d, 'contact_info/alice/phone'), '+910000000000'); });
  await assertSucceeds(set(ref(db(C), 'user_chats/alice/alice_carol/type'), 'dm'));         // carol can create a chat with alice (that's how DMs start)...
  await assertFails(get(ref(db(C), 'presence/alice/online')));                               // ...but that does not make her a contact
  await assertFails(get(ref(db(C), 'contact_info/alice/phone')));
  await assertFails(set(ref(db(C), 'contacts/alice/carol'), true));                          // the marker is alice's alone
  await assertSucceeds(set(ref(db(A), 'contacts/alice/bob'), true));
  await assertSucceeds(get(ref(db(B), 'presence/alice/online')));
  await assertSucceeds(get(ref(db(B), 'contact_info/alice/phone')));
  await assertSucceeds(set(ref(db(A), 'reads/alice_dave/alice'), 1));                       // fallback for old clients: alice opened the chat
  await assertSucceeds(get(ref(db('dave'), 'presence/alice/online')));
  await assertSucceeds(set(ref(db(A), 'blocks/alice/bob'), true));
  await assertFails(get(ref(db(B), 'presence/alice/online')));
  await assertFails(get(ref(db(B), 'contact_info/alice/phone')));
  await assertFails(get(ref(db(B), 'contacts/alice')));
});

// ---- remediation H2: private channels need an invite, and only participants read their content ----
const TOK = 'Abcdefgh12345678XY';
async function makePrivate() {
  await env.withSecurityRulesDisabled(async (ctx) => { const d = ctx.database();
    await set(ref(d, 'channels/c1/priv'), true);
    await set(ref(d, 'channel_subs/c1/alice'), true); await set(ref(d, 'channel_subs/c1/bob'), true);   // existing followers (legacy value)
    await set(ref(d, 'messages/c1/p1'), msg(A)); await set(ref(d, 'pins/c1/p1'), 1); await set(ref(d, 'reads/c1/bob'), 5); await set(ref(d, 'typing/c1/alice'), true); });
}

test('H2: outsiders cannot read a private channel; followers and admins can', async () => {
  await makePrivate();
  await assertFails(get(ref(db(C), 'messages/c1')));
  await assertFails(get(ref(db(C), 'pins/c1')));
  await assertFails(get(ref(db(C), 'reads/c1')));
  await assertFails(get(ref(db(C), 'typing/c1')));
  await assertFails(set(ref(db(C), 'messages/c1/p1/react/carol'), '👍'));
  await assertFails(get(ref(db(C), 'channel_priv/c1')));
  await assertSucceeds(get(ref(db(B), 'messages/c1')));                       // legacy follower keeps access
  await assertSucceeds(get(ref(db(B), 'pins/c1')));
  await assertSucceeds(get(ref(db(B), 'reads/c1')));
  await assertSucceeds(set(ref(db(B), 'messages/c1/p1/react/bob'), '🔥'));
  await assertSucceeds(get(ref(db(A), 'messages/c1')));                       // owner
});

test('H2: public channels stay open (join with true, read, react)', async () => {
  await assertSucceeds(get(ref(db(C), 'messages/c1')));
  await assertSucceeds(set(ref(db(C), 'channel_subs/c1/carol'), true));
  await assertSucceeds(get(ref(db(C), 'reads/c1')));
});

test('H2: joining a private channel needs a valid invite token', async () => {
  await makePrivate();
  await assertFails(set(ref(db(C), 'channel_subs/c1/carol'), true));                                  // old-client join
  await assertFails(set(ref(db(C), 'channel_subs/c1/carol'), { t: TOK, ts: 1 }));                    // token does not exist
  await assertFails(set(ref(db(C), `channel_invites/c1/${TOK}`), { by: C, exp: 0, created: 1 }));    // outsider cannot mint tokens
  await assertSucceeds(set(ref(db(A), `channel_invites/c1/${TOK}`), { by: A, exp: Date.now() + 86400000, created: Date.now() }));
  await assertFails(get(ref(db(C), 'channel_invites/c1')));                                           // tokens are not listable by outsiders
  await assertSucceeds(get(ref(db(A), 'channel_invites/c1')));
  await assertFails(set(ref(db(C), 'channel_subs/c1/dave'), { t: TOK, ts: 1 }));                      // only for yourself
  await assertSucceeds(set(ref(db(C), 'channel_subs/c1/carol'), { t: TOK, ts: Date.now() }));
  await assertSucceeds(get(ref(db(C), 'messages/c1')));                                               // now a participant
  await assertSucceeds(get(ref(db(C), 'channel_priv/c1')));
  await assertSucceeds(remove(ref(db(C), 'channel_subs/c1/carol')));                                  // leaving always works
  await assertFails(get(ref(db(C), 'messages/c1')));
  await assertSucceeds(remove(ref(db(A), `channel_invites/c1/${TOK}`)));                              // revoke
  await assertFails(set(ref(db(C), 'channel_subs/c1/carol'), { t: TOK, ts: Date.now() }));
});

test('H2: expired invites and malformed tokens are rejected', async () => {
  await makePrivate();
  await assertSucceeds(set(ref(db(A), `channel_invites/c1/${TOK}`), { by: A, exp: Date.now() - 1000, created: 1 }));
  await assertFails(set(ref(db(C), 'channel_subs/c1/carol'), { t: TOK, ts: 1 }));
  await assertFails(set(ref(db(A), 'channel_invites/c1/short'), { by: A, exp: 0, created: 1 }));
  await assertFails(set(ref(db(A), `channel_invites/c1/${TOK}x`), { by: B, exp: 0, created: 1 }));   // `by` must be the writer
  await assertFails(set(ref(db(B), 'channel_invites/c1/Zbcdefgh12345678XY'), { by: B, exp: 0, created: 1 })); // followers cannot invite
});

test('H2: private previews live in channel_priv, written by admins (or followers when allowed)', async () => {
  await makePrivate();
  await assertSucceeds(update(ref(db(A)), { 'channel_priv/c1/lastTs': Date.now(), 'channel_priv/c1/lastPreview': 'hello' }));
  await assertSucceeds(get(ref(db(B), 'channel_priv/c1/lastPreview')));
  await assertFails(set(ref(db(B), 'channel_priv/c1/lastPreview'), 'x'));                              // followers cannot, unless the owner allows
  await assertFails(set(ref(db(A), 'channel_priv/c1/lastPreview'), 'x'.repeat(201)));
  await assertFails(set(ref(db(A), 'channel_priv/c1/other'), 1));
});

// ---- remediation H1: my phone-number hash is private (it used to sit in users/<uid>, readable by every account) ----
test('H1: phone_keys is owner-only and validated', async () => {
  const h = 'ab'.repeat(16);
  await assertSucceeds(set(ref(db(A), 'phone_keys/alice'), h));
  await assertSucceeds(get(ref(db(A), 'phone_keys/alice')));
  await assertFails(get(ref(db(B), 'phone_keys/alice')));
  await assertFails(get(ref(db(B), 'phone_keys')));
  await assertFails(get(ref(anon(), 'phone_keys/alice')));
  await assertFails(set(ref(db(B), 'phone_keys/alice'), h));
  await assertFails(set(ref(db(A), 'phone_keys/alice'), 'not-a-hash'));
  await assertSucceeds(remove(ref(db(A), 'phone_keys/alice')));
});

test('H1: legacy users/<uid>/phoneKey still works for old APKs until the owner closes it with config/phoneKeyLegacyOff', async () => {
  const h = 'cd'.repeat(16);
  await assertSucceeds(set(ref(db(A), 'users/alice/phoneKey'), h));                        // old APK before the cutover
  await assertSucceeds(remove(ref(db(A), 'users/alice/phoneKey')));                        // the new app moves it away
  await env.withSecurityRulesDisabled(async (ctx) => { await set(ref(ctx.database(), 'config/phoneKeyLegacyOff'), true); });
  await assertFails(set(ref(db(A), 'users/alice/phoneKey'), h));                           // closed: no new copies in the public profile
  await assertSucceeds(update(ref(db(A), 'users/alice'), { about: 'still editable' }));
  await assertFails(set(ref(db(A), 'config/phoneKeyLegacyOff'), false));                   // a client can't re-open it
});

// ---- remediation L1 / L2 / M6a / M2 / M7 ----
test('L1: editing a message cannot change its type, time, sender or key version', async () => {
  await assertSucceeds(set(ref(db(A), `messages/${AB}/e1`), { ...msg(A), kv: 1 }));
  await assertSucceeds(update(ref(db(A), `messages/${AB}/e1`), { body: 'new', edited: true }));          // what the app does
  await assertFails(update(ref(db(A), `messages/${AB}/e1`), { type: 'image' }));
  await assertFails(update(ref(db(A), `messages/${AB}/e1`), { ts: 1 }));
  await assertFails(update(ref(db(A), `messages/${AB}/e1`), { kv: 9 }));
  await assertFails(update(ref(db(A), `messages/${AB}/e1`), { from: B }));
  await assertFails(update(ref(db(B), `messages/${AB}/e1`), { body: 'hax' }));                           // the other person cannot edit
});

test('L2: slow-mode stamp only moves forward and cannot be erased', async () => {
  await assertSucceeds(set(ref(db(B), 'slow/g1/bob'), 1000));
  await assertFails(set(ref(db(B), 'slow/g1/bob'), 10));
  await assertFails(remove(ref(db(B), 'slow/g1/bob')));
  await assertSucceeds(set(ref(db(B), 'slow/g1/bob'), 2000));
  await assertFails(set(ref(db(B), 'slow/g1/bob'), 'x'));
});

test('M6a: a profile can only show a username its owner has claimed', async () => {
  await assertFails(update(ref(db(A), 'users/alice'), { username: 'Newname', usernameLower: 'newname' }));      // not claimed
  await assertSucceeds(set(ref(db(A), 'usernames/newname'), A));
  await assertSucceeds(update(ref(db(A), 'users/alice'), { username: 'Newname', usernameLower: 'newname' }));
  await assertSucceeds(update(ref(db(A), 'users/alice'), { username: 'Newname', about: 'same name again' }));     // unchanged value is fine
  await assertFails(update(ref(db(A), 'users/alice'), { username: 'bob' }));                                      // someone else's
  await env.withSecurityRulesDisabled(async (ctx) => { await set(ref(ctx.database(), 'usernames/bob'), B); });
  await assertFails(update(ref(db(A), 'users/alice'), { username: 'bob', usernameLower: 'bob' }));
  await assertFails(update(ref(db(A), 'users/alice'), { usernameLower: 'zzz' }));                                 // must match username
});

test('M2: "who can add me to groups" is enforced', async () => {
  await env.withSecurityRulesDisabled(async (ctx) => { await set(ref(ctx.database(), 'privacy/carol/groups'), 'nobody'); await set(ref(ctx.database(), 'privacy/dave/groups'), 'contacts'); });
  await assertFails(set(ref(db(A), 'groups/g1/members/carol'), 'member'));
  await assertFails(set(ref(db(A), 'groups/g1/members/dave'), 'member'));
  await assertSucceeds(set(ref(db('dave'), 'contacts/dave/alice'), true));
  await assertSucceeds(set(ref(db(A), 'groups/g1/members/dave'), 'member'));
  await assertSucceeds(set(ref(db(A), 'groups/g1/members/erin'), 'member'));                                      // default = everybody
  await assertSucceeds(set(ref(db(A), 'groups/g1/members/bob'), 'admin'));                                        // existing members can be promoted
  await assertFails(set(ref(db(A), 'groups/g1/members/erin'), 'boss'));
  await assertSucceeds(set(ref(db('carol'), 'groups/g9'), { name: 'Mine', owner: 'carol', kv: 1, members: { carol: 'admin' } }));  // creator is always allowed
});

test('M7: follower count only moves with the caller\'s own follow state', async () => {
  await assertFails(set(ref(db(C), 'channels/c1/subCount'), 2));                  // carol does not follow: no +1
  await assertSucceeds(set(ref(db(C), 'channel_subs/c1/carol'), true));
  await assertSucceeds(set(ref(db(C), 'channels/c1/subCount'), 2));
  await assertFails(set(ref(db(C), 'channels/c1/subCount'), 1));                  // still following: no -1
  await assertSucceeds(remove(ref(db(C), 'channel_subs/c1/carol')));
  await assertSucceeds(set(ref(db(C), 'channels/c1/subCount'), 1));
  await assertFails(set(ref(db(C), 'channels/c1/subCount'), 50));                 // no jumps
  // known limit: a non-follower can still step the count down by one at a time, so subCount stays advisory (recount tool/cron planned)
});

// ---- chat organisation: archive, labels (pin and mute already existed) ----
test('archive and labels: owner-only fields on the chat entry, peers cannot set them', async () => {
  await assertSucceeds(update(ref(db(A), `user_chats/alice/${AB}`), { type: 'dm', peer: B, archived: true, 'labels/abc123': true }));
  await assertSucceeds(set(ref(db(A), `user_chats/alice/${AB}/archived`), false));
  await assertFails(set(ref(db(A), `user_chats/alice/${AB}/archived`), 'yes'));
  await assertFails(set(ref(db(A), `user_chats/alice/${AB}/labels/BAD!`), true));
  await assertFails(set(ref(db(A), `user_chats/alice/${AB}/labels/abc123`), 'x'));
  await assertFails(set(ref(db(B), `user_chats/alice/${AB}/archived`), true));              // bob cannot archive alice's chat
  await assertFails(set(ref(db(B), `user_chats/alice/${AB}/labels/abc123`), true));
  await assertSucceeds(set(ref(db(B), `user_chats/alice/${AB}/unread`), 1));                 // normal send fan-out still works
});

test('chat_labels: private to the owner, validated', async () => {
  await assertSucceeds(set(ref(db(A), 'chat_labels/alice/work01'), { name: 'Work', color: 3 }));
  await assertSucceeds(get(ref(db(A), 'chat_labels/alice')));
  await assertFails(get(ref(db(B), 'chat_labels/alice')));
  await assertFails(set(ref(db(B), 'chat_labels/alice/work02'), { name: 'x', color: 1 }));
  await assertFails(set(ref(db(A), 'chat_labels/alice/work02'), { name: '', color: 1 }));
  await assertFails(set(ref(db(A), 'chat_labels/alice/work02'), { name: 'x'.repeat(25), color: 1 }));
  await assertFails(set(ref(db(A), 'chat_labels/alice/work02'), { name: 'ok', color: 99 }));
  await assertFails(set(ref(db(A), 'chat_labels/alice/work02'), { name: 'ok', color: 1, extra: 1 }));
  await assertFails(set(ref(db(A), 'chat_labels/alice/W'), { name: 'ok', color: 1 }));
  await assertSucceeds(update(ref(db(A), 'chat_labels/alice/work01'), { name: 'Office' }));
  await assertSucceeds(remove(ref(db(A), 'chat_labels/alice/work01')));
});

// ---- linked devices ----
test('devices: private to the account, validated', async () => {
  const d = { name: 'Pixel 8', model: 'Pixel 8', added: Date.now(), last: Date.now(), ver: '1.20.0' };
  await assertSucceeds(set(ref(db(A), 'devices/alice/AbCd1234efGh5678'), d));
  await assertSucceeds(get(ref(db(A), 'devices/alice')));
  await assertFails(get(ref(db(B), 'devices/alice')));
  await assertFails(set(ref(db(B), 'devices/alice/ZZZZ1234zzzz5678'), d));
  await assertFails(set(ref(db(A), 'devices/alice/short'), d));
  await assertFails(set(ref(db(A), 'devices/alice/AbCd1234efGh5678'), { ...d, extra: 1 }));
  await assertFails(set(ref(db(A), 'devices/alice/AbCd1234efGh5678'), { model: 'x' }));
  await assertSucceeds(update(ref(db(A), 'devices/alice/AbCd1234efGh5678'), { revoked: true }));       // another phone logs this one out
  await assertFails(update(ref(db(A), 'devices/alice/AbCd1234efGh5678'), { revoked: 'yes' }));
  await assertSucceeds(remove(ref(db(A), 'devices/alice/AbCd1234efGh5678')));
});

test('link requests: only the account, the key hand-over is sealed and expires after 10 minutes', async () => {
  const epk = 'e'.repeat(44), sid = 'Sess1234abcd5678';
  await assertSucceeds(set(ref(db(A), `link_requests/alice/${sid}`), { epk, name: 'New phone', did: 'AbCd1234efGh5678', ts: serverTimestamp() }));
  await assertFails(get(ref(db(B), `link_requests/alice/${sid}`)));
  await assertFails(set(ref(db(B), `link_requests/alice/${sid}/grant`), { ct: 'x', pub: epk }));          // nobody else can hand out or read keys
  await assertFails(set(ref(db(A), 'link_requests/alice/Sess9999abcd5678'), { epk, ts: 5 }));              // fake old timestamp
  await assertFails(set(ref(db(A), 'link_requests/alice/Sess8888abcd5678'), { epk: 'short', ts: serverTimestamp() }));
  await assertSucceeds(set(ref(db(A), `link_requests/alice/${sid}/grant`), { ct: 'c'.repeat(100), pub: epk }));
  await assertFails(set(ref(db(A), `link_requests/alice/${sid}/grant`), { ct: 'c'.repeat(301), pub: epk }));
  await assertSucceeds(get(ref(db(A), `link_requests/alice/${sid}`)));
  await assertSucceeds(remove(ref(db(A), `link_requests/alice/${sid}`)));
  // an old request can no longer receive a key
  await env.withSecurityRulesDisabled(async (ctx) => { await set(ref(ctx.database(), 'link_requests/alice/Old01234abcd5678'), { epk, ts: Date.now() - 3600000 }); });
  await assertFails(set(ref(db(A), 'link_requests/alice/Old01234abcd5678/grant'), { ct: 'c', pub: epk }));
});
