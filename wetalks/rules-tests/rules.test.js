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

// ---- 2-step verification: the rules check the PIN hash, nobody can read it, attempts are rate-limited ----
const H1 = 'a1'.repeat(32), H2 = 'b2'.repeat(32), SALT = 's'.repeat(24);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const enable = (u, did, h = H1) => update(ref(db(u)), {
  [`two_step_pub/${u}`]: { salt: SALT, iter: 300000, ts: Date.now() }, [`two_step_secret/${u}/h`]: h, [`trusted_devices/${u}`]: { [did]: { ts: Date.now() } } });
async function seedEnabled(u = 'alice', h = H1, trusted = 'Trusted1234abcd') {
  await env.withSecurityRulesDisabled(async (ctx) => { const d = ctx.database();
    await set(ref(d, `two_step_pub/${u}`), { salt: SALT, iter: 300000, ts: 1 }); await set(ref(d, `two_step_secret/${u}`), { h }); await set(ref(d, `trusted_devices/${u}/${trusted}`), { ts: 1 }); });
}
const attempt = (u, h) => set(ref(db(u), `two_step_try/${u}`), { h, n: 1, ts: serverTimestamp() });

test('2SV: turning it on trusts this phone only, and the hash can never be read', async () => {
  await assertSucceeds(enable(A, 'Thisphone1234ab'));
  await assertSucceeds(get(ref(db(A), 'two_step_pub/alice')));
  await assertFails(get(ref(db(A), 'two_step_secret/alice')));                       // not even the owner
  await assertFails(get(ref(db(A), 'two_step_secret/alice/h')));
  await assertFails(get(ref(db(B), 'two_step_pub/alice')));
  await assertSucceeds(get(ref(db(A), 'trusted_devices/alice')));
  await assertFails(get(ref(db(B), 'trusted_devices/alice')));
  await assertFails(enable(B, 'Bobphone1234abcd', H2).then(() => update(ref(db(B)), { 'two_step_secret/alice/h': H2 })));  // bob cannot touch alice's
  await assertFails(set(ref(db(A), 'two_step_secret/alice/h'), 'nothex'));
});

test('2SV: a new phone cannot trust itself or replace the PIN without the right PIN', async () => {
  await seedEnabled();
  await assertFails(set(ref(db(A), 'trusted_devices/alice/Newphone1234abcd'), { ts: 5 }));          // no proof
  await assertFails(set(ref(db(A), 'two_step_secret/alice/h'), H2));                                  // cannot swap the PIN
  await assertFails(remove(ref(db(A), 'two_step_secret/alice')));                                     // cannot switch it off
  await assertFails(remove(ref(db(A), 'trusted_devices/alice/Trusted1234abcd')));                     // cannot un-trust the real phone
  await attempt(A, H2);                                                                               // wrong PIN: the attempt is recorded...
  await assertFails(set(ref(db(A), 'trusted_devices/alice/Newphone1234abcd'), { ts: 5 }));           // ...but does not unlock anything
});

test('2SV: the right PIN trusts the phone for one minute of proof, then the counter resets', async () => {
  await seedEnabled();
  await assertSucceeds(attempt(A, H1));
  await assertSucceeds(set(ref(db(A), 'trusted_devices/alice/Newphone1234abcd'), { ts: Date.now() }));
  await assertSucceeds(remove(ref(db(A), 'two_step_try/alice')));                                      // success clears the attempt record
  // an old proof is worthless
  await env.withSecurityRulesDisabled(async (ctx) => { await set(ref(ctx.database(), 'two_step_try/alice'), { h: H1, n: 1, ts: Date.now() - 120000 }); });
  await assertFails(set(ref(db(A), 'trusted_devices/alice/Other1234abcdef'), { ts: Date.now() }));
});

test('2SV: change PIN and turn off both need a fresh correct PIN', async () => {
  await seedEnabled();
  await assertSucceeds(attempt(A, H1));
  // the old attempt record is cleared in the same step (it matches the OLD secret, which is what the rules compare against)
  await assertSucceeds(update(ref(db(A)), { 'two_step_secret/alice/h': H2, 'two_step_pub/alice': { salt: 't'.repeat(24), iter: 300000, ts: Date.now() }, 'trusted_devices/alice': { Thisphone1234ab: { ts: Date.now() } }, 'two_step_try/alice': null }));
  await assertFails(update(ref(db(A)), { 'two_step_secret/alice': null, 'two_step_pub/alice': null }));  // off without proof
  await assertSucceeds(attempt(A, H2));                                                                 // the new PIN is now the right one
  await assertSucceeds(update(ref(db(A)), { 'two_step_secret/alice': null, 'two_step_pub/alice': null, 'trusted_devices/alice': null, 'two_step_reset/alice': null, 'two_step_try/alice': null }));
});

test('2SV: attempts are limited to one per 1.5 s and five per hour', async () => {
  await seedEnabled();
  await assertSucceeds(attempt(A, H2));
  await assertFails(set(ref(db(A), 'two_step_try/alice'), { h: H2, n: 2, ts: serverTimestamp() }));      // too fast
  await sleep(1700);
  await assertSucceeds(set(ref(db(A), 'two_step_try/alice'), { h: H2, n: 2, ts: serverTimestamp() }));
  await assertFails(set(ref(db(A), 'two_step_try/alice'), { h: H2, n: 5, ts: serverTimestamp() }));      // cannot skip the counter
  await env.withSecurityRulesDisabled(async (ctx) => { await set(ref(ctx.database(), 'two_step_try/alice'), { h: H2, n: 5, ts: Date.now() - 2000 }); });
  await assertFails(set(ref(db(A), 'two_step_try/alice'), { h: H2, n: 6, ts: serverTimestamp() }));      // sixth try in the hour
  await env.withSecurityRulesDisabled(async (ctx) => { await set(ref(ctx.database(), 'two_step_try/alice'), { h: H2, n: 5, ts: Date.now() - 3700000 }); });
  await assertSucceeds(set(ref(db(A), 'two_step_try/alice'), { h: H2, n: 1, ts: serverTimestamp() })); // an hour later it starts over
  await assertFails(get(ref(db(A), 'two_step_try/alice')));                                              // attempts are not readable either
  await assertFails(set(ref(db(B), 'two_step_try/alice'), { h: H2, n: 1, ts: serverTimestamp() }));
});

test('2SV: forgot PIN waits seven days, and the PIN can cancel it', async () => {
  await seedEnabled();
  await assertSucceeds(set(ref(db(A), 'two_step_reset/alice'), { ts: serverTimestamp() }));
  await assertFails(set(ref(db(A), 'two_step_reset/alice'), { ts: serverTimestamp() }));                 // cannot restart the clock
  await assertFails(update(ref(db(A)), { 'two_step_secret/alice': null, 'two_step_pub/alice': null, 'trusted_devices/alice': null }));   // too early
  await assertFails(remove(ref(db(A), 'two_step_reset/alice')));                                          // cancelling needs the PIN
  await assertSucceeds(attempt(A, H1));
  await assertSucceeds(remove(ref(db(A), 'two_step_reset/alice')));                                       // owner with the PIN cancels
  await env.withSecurityRulesDisabled(async (ctx) => { const d = ctx.database(); await remove(ref(d, 'two_step_try/alice')); await set(ref(d, 'two_step_reset/alice'), { ts: Date.now() - 8 * 86400000 }); });
  await assertSucceeds(update(ref(db(A)), { 'two_step_secret/alice': null, 'two_step_pub/alice': null, 'trusted_devices/alice': null, 'two_step_reset/alice': null }));  // after 7 days
});

test('2SV: the app can read how many tries are used (never the hash); a stale record can be cleared once 2-step is off', async () => {
  await seedEnabled();
  await assertSucceeds(attempt(A, H2));
  await assertSucceeds(get(ref(db(A), 'two_step_try/alice/n')));
  await assertSucceeds(get(ref(db(A), 'two_step_try/alice/ts')));
  await assertFails(get(ref(db(A), 'two_step_try/alice/h')));
  await assertFails(get(ref(db(B), 'two_step_try/alice/n')));
  await assertFails(remove(ref(db(A), 'two_step_try/alice')));                       // wrong-PIN record cannot be wiped to reset the counter
  await env.withSecurityRulesDisabled(async (ctx) => { await remove(ref(ctx.database(), 'two_step_secret/alice')); });
  await assertSucceeds(remove(ref(db(A), 'two_step_try/alice')));                    // 2-step is off: nothing to protect
});

// ---- call log: one entry per user per call; a missed call reaches an offline receiver ----
const CID = '-Ncall0001abcdef';
const entry = (o) => ({ peer: B, dir: 'out', st: 'noanswer', ts: Date.now(), video: false, dur: 0, ...o });

test('call log: each person writes their own entry once per call id; nobody else can read it', async () => {
  await assertSucceeds(set(ref(db(A), `call_log/alice/${CID}`), entry({})));
  await assertSucceeds(set(ref(db(A), `call_log/alice/${CID}`), entry({ st: 'ended', dur: 65 })));   // same call id = same single entry (updates it, no second row)
  await assertSucceeds(get(ref(db(A), 'call_log/alice')));
  await assertFails(get(ref(db(B), 'call_log/alice')));
  await assertFails(set(ref(db(B), `call_log/alice/${CID}`), entry({ peer: B, dir: 'in', st: 'declined' })));   // bob cannot rewrite alice's history
  await assertFails(set(ref(db(A), 'call_log/alice/-Ncall0002'), entry({ st: 'whatever' })));
  await assertFails(set(ref(db(A), 'call_log/alice/-Ncall0003'), entry({ peer: A })));                 // not a call with yourself
  await assertFails(set(ref(db(A), 'call_log/alice/-Ncall0004'), entry({ dur: 999999 })));
  await assertFails(set(ref(db(A), 'call_log/alice/-Ncall0005'), { ...entry({}), extra: 1 }));
  await assertSucceeds(remove(ref(db(A), `call_log/alice/${CID}`)));                                   // delete one entry
});

test('call log, receiver app closed or offline: the caller leaves the Missed call in their log, only while the ring request is still waiting', async () => {
  // the caller's ring request, as the app writes it before the receiver is online
  await assertSucceeds(set(ref(db(A), `incoming_calls/bob/${CID}`), { from: A, video: true, ts: serverTimestamp() }));
  await assertSucceeds(set(ref(db(A), `call_log/bob/${CID}`), { peer: A, dir: 'in', st: 'missed', ts: Date.now(), video: true, dur: 0 }));
  await assertSucceeds(get(ref(db(B), 'call_log/bob')));                                               // bob sees it when he opens the app
  await assertFails(get(ref(db(A), 'call_log/bob')));
  await assertFails(set(ref(db(A), `call_log/bob/${CID}`), { peer: A, dir: 'in', st: 'declined', ts: Date.now() }));   // cannot overwrite it, nor claim another state
  await assertSucceeds(set(ref(db(B), `call_log/bob/${CID}`), { peer: A, dir: 'in', st: 'missed', ts: Date.now(), video: true, dur: 0 }));   // bob's own app can always write his entry
});

test('call log: nobody can plant fake missed calls', async () => {
  await assertFails(set(ref(db(A), 'call_log/bob/-Ncall0100'), { peer: A, dir: 'in', st: 'missed', ts: Date.now() }));      // no ring request exists
  await assertSucceeds(set(ref(db(A), 'incoming_calls/bob/-Ncall0101'), { from: A, video: false, ts: serverTimestamp() }));
  await assertFails(set(ref(db(C), 'call_log/bob/-Ncall0101'), { peer: C, dir: 'in', st: 'missed', ts: Date.now() }));      // carol is not the caller
  await assertFails(set(ref(db(C), 'call_log/bob/-Ncall0101'), { peer: A, dir: 'in', st: 'missed', ts: Date.now() }));      // nor can she name alice
  await assertFails(set(ref(db(A), 'call_log/bob/-Ncall0101'), { peer: A, dir: 'out', st: 'missed', ts: Date.now() }));     // only an incoming missed entry
  await assertFails(set(ref(db(A), 'call_log/bob/-Ncall0101'), { peer: A, dir: 'in', st: 'ended', ts: Date.now(), dur: 500 })); // cannot invent a long call
});

test('call log: declined and cancelled-before-answer, both sides own their line', async () => {
  // bob declined while online: his app removed the ring request and wrote his own entry; alice writes hers
  await assertSucceeds(set(ref(db(B), `call_log/bob/${CID}`), { peer: A, dir: 'in', st: 'declined', ts: Date.now(), video: false, dur: 0 }));
  await assertSucceeds(set(ref(db(A), `call_log/alice/${CID}`), { peer: B, dir: 'out', st: 'declined', ts: Date.now(), video: false, dur: 0 }));
  // alice cancels another call before bob answers
  await assertSucceeds(set(ref(db(A), 'call_log/alice/-Ncall0200'), { peer: B, dir: 'out', st: 'cancelled', ts: Date.now() }));
  await assertSucceeds(set(ref(db(A), 'call_log/alice/-Ncall0201'), { peer: B, dir: 'out', st: 'unavailable', ts: Date.now() }));
  // clearing the whole log is one delete
  await assertSucceeds(remove(ref(db(A), 'call_log/alice')));
});

// ---- scenario: the full data flow of a call to a receiver whose app is closed / offline, then back online ----
test('scenario: receiver app killed, then back online: log, chat event, no duplicates', async () => {
  const id = '-NscenarioCall001';
  const call = { from: A, to: B, video: true, ts: serverTimestamp(), state: 'ringing', offer: 'sealed' };
  // alice calls: the call node and the ring request (what Calls.start writes)
  await assertSucceeds(set(ref(db(A), `calls/${id}`), call));
  await assertSucceeds(set(ref(db(A), `incoming_calls/bob/${id}`), { from: A, video: true, ts: serverTimestamp() }));
  // bob's phone never reports ringing. After ~50 s alice's app finishes the call as unanswered and writes: her own line, bob's Missed line, the chat event
  await assertSucceeds(update(ref(db(A)), { ['calls/' + id + '/state']: 'missed' }));
  await assertSucceeds(set(ref(db(A), `call_log/alice/${id}`), { peer: B, dir: 'out', st: 'unavailable', ts: Date.now(), video: true, dur: 0 }));
  await assertSucceeds(set(ref(db(A), `call_log/bob/${id}`), { peer: A, dir: 'in', st: 'missed', ts: Date.now(), video: true, dur: 0 }));
  const body = JSON.stringify({ v: 1, d: 0, s: 'missed' });
  const chatEvent = { [`messages/${AB}/${id}`]: { from: A, ts: serverTimestamp(), type: 'call', body, kv: 0 },
    [`user_chats/alice/${AB}/type`]: 'dm', [`user_chats/alice/${AB}/peer`]: B, [`user_chats/alice/${AB}/lastTs`]: serverTimestamp(), [`user_chats/alice/${AB}/lastFrom`]: A, [`user_chats/alice/${AB}/unread`]: 0,
    [`user_chats/bob/${AB}/type`]: 'dm', [`user_chats/bob/${AB}/peer`]: A, [`user_chats/bob/${AB}/lastTs`]: serverTimestamp(), [`user_chats/bob/${AB}/lastFrom`]: A, [`user_chats/bob/${AB}/unread`]: increment(1) };
  await assertSucceeds(update(ref(db(A)), chatEvent));
  // a second finish (retry) cannot add a second chat line or a second unread: the fixed id makes the whole write fail
  await assertFails(update(ref(db(A)), chatEvent));
  await assertSucceeds(set(ref(db(A), `call_log/alice/${id}`), { peer: B, dir: 'out', st: 'unavailable', ts: Date.now(), video: true, dur: 0 }));   // same key: still one line
  await assertSucceeds(remove(ref(db(A), `calls/${id}`)));                                                  // alice's app cleans the call node 4 s later
  // bob opens the app much later: the ring request is still waiting, the call node is gone, his Missed line and the chat event are there
  const bob = db(B);
  const inc = await get(ref(bob, 'incoming_calls/bob'));  if (!inc.exists() || !inc.val()[id]) throw new Error('ring request missing');
  await assertFails(get(ref(bob, `calls/${id}`)));       // a deleted call cannot be read at all: the app must treat that as "gone" (this was the bug)
  const mine = await get(ref(bob, 'call_log/bob'));      if (!mine.exists() || mine.val()[id].st !== 'missed' || mine.val()[id].peer !== A) throw new Error('missed line missing');
  const chat = await get(ref(bob, `messages/${AB}`));    if (Object.keys(chat.val()).length !== 1) throw new Error('chat should hold exactly one call line');
  await assertSucceeds(remove(ref(bob, `incoming_calls/bob/${id}`)));                                         // bob's app consumes the request
  const entries = await get(ref(bob, 'call_log/bob'));   if (Object.keys(entries.val()).length !== 1) throw new Error('exactly one log line expected');
});

test('scenario: receiver was offline and the caller could not write his line: his own app builds it from the ring request', async () => {
  const id = '-NscenarioCall002';
  await assertSucceeds(set(ref(db(A), `incoming_calls/bob/${id}`), { from: A, video: false, ts: serverTimestamp() }));
  // (alice's app was killed before finishing, so nobody wrote call_log/bob). Bob comes online, reads the request and writes his own Missed line.
  const inc = (await get(ref(db(B), `incoming_calls/bob/${id}`))).val();
  await assertSucceeds(set(ref(db(B), `call_log/bob/${id}`), { peer: inc.from, dir: 'in', st: 'missed', ts: inc.ts, video: inc.video, dur: 0 }));
  await assertSucceeds(remove(ref(db(B), `incoming_calls/bob/${id}`)));
  await assertSucceeds(set(ref(db(B), `call_log/bob/${id}`), { peer: A, dir: 'in', st: 'missed', ts: inc.ts, video: false, dur: 0 }));   // running it twice leaves one line
  const n = Object.keys((await get(ref(db(B), 'call_log/bob'))).val()).length; if (n !== 1) throw new Error('one line expected, got ' + n);
});

test('scenario: declined and cancelled-before-answer give each side exactly one line, nothing planted on the other side', async () => {
  const dec = '-NscenarioDecl001', can = '-NscenarioCanc001';
  // declined by bob (online): bob's app took the ring request, so alice can no longer write for him; each writes only his own line
  await assertSucceeds(set(ref(db(A), `incoming_calls/bob/${dec}`), { from: A, video: false, ts: serverTimestamp() }));
  await assertSucceeds(remove(ref(db(B), `incoming_calls/bob/${dec}`)));
  await assertFails(set(ref(db(A), `call_log/bob/${dec}`), { peer: A, dir: 'in', st: 'missed', ts: Date.now() }));
  await assertSucceeds(set(ref(db(B), `call_log/bob/${dec}`), { peer: A, dir: 'in', st: 'declined', ts: Date.now(), video: false, dur: 0 }));
  await assertSucceeds(set(ref(db(A), `call_log/alice/${dec}`), { peer: B, dir: 'out', st: 'declined', ts: Date.now(), video: false, dur: 0 }));
  // cancelled by alice while it rang on bob's phone: bob's app logs Missed for itself, alice logs Cancelled
  await assertSucceeds(set(ref(db(A), `incoming_calls/bob/${can}`), { from: A, video: true, ts: serverTimestamp() }));
  await assertSucceeds(remove(ref(db(B), `incoming_calls/bob/${can}`)));
  await assertSucceeds(set(ref(db(B), `call_log/bob/${can}`), { peer: A, dir: 'in', st: 'missed', ts: Date.now(), video: true, dur: 0 }));
  await assertSucceeds(set(ref(db(A), `call_log/alice/${can}`), { peer: B, dir: 'out', st: 'cancelled', ts: Date.now(), video: true, dur: 0 }));
  const b = Object.keys((await get(ref(db(B), 'call_log/bob'))).val()), a = Object.keys((await get(ref(db(A), 'call_log/alice'))).val());
  if (b.length !== 2 || a.length !== 2) throw new Error('two lines each expected');
});

// ---------------------------------------------------------------------------------------------- starred messages (references only)
test('starred: the owner stars, lists and unstars; the value must be the server time', async () => {
  const mine = db(A);
  await assertSucceeds(set(ref(mine, `starred/alice/${AB}/-Nstar0000000001`), serverTimestamp()));
  await assertSucceeds(set(ref(mine, 'starred/alice/-NgroupPushId001/-Nstar0000000002'), serverTimestamp()));
  const all = await get(ref(mine, 'starred/alice'));
  if (Object.keys(all.val()).length !== 2) throw new Error('two chats expected');
  await assertSucceeds(update(ref(mine), { [`starred/alice/${AB}/-Nstar0000000003`]: serverTimestamp(), [`starred/alice/${AB}/-Nstar0000000001`]: null }));   // several changes in one write
  await assertSucceeds(remove(ref(mine, `starred/alice/${AB}/-Nstar0000000003`)));
  await assertSucceeds(remove(ref(mine, 'starred/alice')));                                                                                       // clear all
});

test('starred: a stranger can neither read nor write someone else\'s stars; nobody signed out can either', async () => {
  await assertSucceeds(set(ref(db(A), `starred/alice/${AB}/-Nstar0000000001`), serverTimestamp()));
  await assertFails(get(ref(db(B), 'starred/alice')));
  await assertFails(get(ref(db(B), `starred/alice/${AB}`)));
  await assertFails(get(ref(db(B), `starred/alice/${AB}/-Nstar0000000001`)));
  await assertFails(get(ref(anon(), 'starred/alice')));
  await assertFails(set(ref(db(B), `starred/alice/${AB}/-Nstar0000000009`), serverTimestamp()));        // plant a star in alice's list
  await assertFails(remove(ref(db(B), `starred/alice/${AB}/-Nstar0000000001`)));                         // remove alice's star
  await assertFails(remove(ref(db(B), 'starred/alice')));
  await assertFails(update(ref(db(B)), { 'starred/alice/x/-Nstar0000000009': serverTimestamp() }));
  await assertFails(set(ref(anon(), `starred/alice/${AB}/-Nstar0000000009`), serverTimestamp()));
  await assertFails(get(ref(db(B), 'starred')));                                                          // no way to list everybody's stars
  const still = await get(ref(db(A), `starred/alice/${AB}`));  if (Object.keys(still.val()).length !== 1) throw new Error('alice\'s star must be untouched');
});

test('starred: only references are accepted (no text, no forged time, no odd ids, no extra fields)', async () => {
  const mine = db(A);
  await assertFails(set(ref(mine, `starred/alice/${AB}/-Nstar0000000001`), 'the message text'));         // text instead of a reference
  await assertFails(set(ref(mine, `starred/alice/${AB}/-Nstar0000000001`), true));
  await assertFails(set(ref(mine, `starred/alice/${AB}/-Nstar0000000001`), { ts: serverTimestamp(), text: 'hello' }));
  await assertFails(set(ref(mine, `starred/alice/${AB}/-Nstar0000000001`), 12345));                       // a made-up time
  await assertFails(set(ref(mine, `starred/alice/${AB}/-Nstar0000000001`), Date.now() + 86400000));       // a time in the future
  await assertFails(set(ref(mine, `starred/alice/${AB}/bad id!`), serverTimestamp()));
  await assertFails(set(ref(mine, `starred/alice/${AB}/${'m'.repeat(41)}`), serverTimestamp()));
  await assertFails(set(ref(mine, `starred/alice/bad chat!/-Nstar0000000001`), serverTimestamp()));
  await assertFails(set(ref(mine, `starred/alice/${'c'.repeat(301)}/-Nstar0000000001`), serverTimestamp()));
  await assertFails(set(ref(mine, 'starred/alice/justAString'), 'x'));                                    // a value directly under the person
  await assertFails(set(ref(mine, `starred/alice/${AB}/-Nstar0000000001/extra`), serverTimestamp()));     // a child below a star
  await assertSucceeds(set(ref(mine, `starred/alice/${AB}/-Nstar0000000001`), serverTimestamp()));
});

test('starred: a stranger cannot flood someone else\'s list; one bad key rejects a whole bulk write by the owner', async () => {
  const flood = {};
  for (let i = 0; i < 200; i++) flood[`starred/alice/${AB}/-Nflood${String(i).padStart(8, '0')}`] = serverTimestamp();
  await assertFails(update(ref(db(B)), flood));
  await assertFails(update(ref(anon()), flood));
  await assertFails(update(ref(db(A)), { ...flood, [`starred/alice/${AB}/bad id!`]: serverTimestamp() }));
  if ((await get(ref(db(A), 'starred/alice'))).exists()) throw new Error('nothing should have been written');
  await assertSucceeds(update(ref(db(A)), flood));
  await assertFails(update(ref(db(A)), { [`starred/alice/${AB}/-Nflood00000001`]: 5 }));                  // an existing star cannot be changed to a made-up time
});

test('starred: stars never change other people\'s data or the chat itself', async () => {
  await assertSucceeds(set(ref(db(A), `messages/${AB}/m1`), msg(A)));
  await assertSucceeds(set(ref(db(B), `starred/bob/${AB}/m1`), serverTimestamp()));                       // bob stars alice's message: his own list only
  const m = await get(ref(db(A), `messages/${AB}/m1`));  if (m.val().body !== 'ct' || m.val().starred) throw new Error('message must be untouched');
  await assertFails(get(ref(db(A), 'starred/bob')));
});
