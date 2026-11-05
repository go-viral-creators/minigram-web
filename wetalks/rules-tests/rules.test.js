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
  await assertFails(remove(ref(db(B), `messages/${AB}/m1`)));
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
