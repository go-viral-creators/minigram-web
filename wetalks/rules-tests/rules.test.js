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
  await assertFails(set(ref(db(C), 'groups/g3'), { name: 'N', owner: A, kv: 1, members: { carol: 'admin' } }));
  await assertSucceeds(set(ref(db(C), 'user_chats/carol/g2/type'), 'group'));
  await assertSucceeds(set(ref(db(C), 'user_chats/alice/g2/type'), 'group'));   // creator is admin => member
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

test('media: write once as yourself, size capped', async () => {
  await assertSucceeds(set(ref(db(A), 'media/m1'), { from: A, mime: 'image/jpeg', data: 'abc' }));
  await assertFails(set(ref(db(A), 'media/m1'), { from: A, mime: 'image/jpeg', data: 'zzz' }));
  await assertFails(set(ref(db(A), 'media/m2'), { from: B, mime: 'image/jpeg', data: 'abc' }));
  await assertFails(set(ref(db(A), 'media/m3'), { from: A, mime: 'image/jpeg', data: 'a'.repeat(1900000) }));
  await assertSucceeds(get(ref(db(B), 'media/m1')));
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

test('media: only the uploader can delete it', async () => {
  await assertSucceeds(set(ref(db(A), 'media/x1'), { from: A, mime: 'image/jpeg', data: 'abc' }));
  await assertFails(remove(ref(db(B), 'media/x1')));
  await assertSucceeds(remove(ref(db(A), 'media/x1')));
  await assertSucceeds(set(ref(db(A), `messages/${AB}/big`), { from: A, ts: 1, type: 'link', body: 'x'.repeat(50000) }));
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
