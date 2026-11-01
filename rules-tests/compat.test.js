'use strict';
// Runs the app's real write patterns against the LIVE rules + the minimal additions (database.rules.compat.json).
const test = require('node:test');
const fs = require('fs'); const path = require('path');
const { initializeTestEnvironment, assertFails, assertSucceeds } = require('@firebase/rules-unit-testing');
const { ref, set, get, update } = require('firebase/database');
let env; const db = (u) => env.authenticatedContext(u).database(); const now = () => Date.now();
test.before(async () => { env = await initializeTestEnvironment({ projectId: 'demo-minigram', database: { rules: fs.readFileSync(path.join(__dirname, '..', process.env.RULES_FILE || 'database.rules.compat.json'), 'utf8'), host: '127.0.0.1', port: 9000 } }); });
test.after(async () => env.cleanup());
test.beforeEach(async () => { await env.clearDatabase(); });

test('new user: CompleteProfile multi-path write', async () => {
  await assertSucceeds(update(ref(db('carol')), {
    'users/carol/fullname': 'Carol', 'users/carol/fullname_lower': 'carol', 'users/carol/bio': 'Hello',
    'user_private/carol/phone': '+919625006163', 'user_private/carol/phone_cc': '+91', 'user_private/carol/phone_number': '9625006163',
    'phone_index/919625006163': 'carol', 'handles/baddevil': { type: 'user', id: 'carol' }, 'usernames/baddevil': 'carol',
    'users/carol/username': 'baddevil', 'users/carol/username_lower': 'baddevil', 'users/carol/handle': 'baddevil',
    'user_private/carol/email': 'c@x.com', 'users/carol/photo_url': '', 'users/carol/status': 'offline', 'users/carol/created_at': now() }));
});
test('existing user: change username + add phone, old handle entry missing', async () => {
  await env.withSecurityRulesDisabled(async (c) => { await set(ref(c.database(), 'users/dave'), { fullname: 'Dave', handle: 'old' }); });
  await assertSucceeds(update(ref(db('dave')), {
    'users/dave/fullname': 'Bad Devil', 'user_private/dave/phone': '+918532081568', 'phone_index/918532081568': 'dave',
    'handles/old': null, 'handles/baddevil': { type: 'user', id: 'dave' }, 'usernames/baddevil': 'dave', 'users/dave/handle': 'baddevil' }));
});
test('cannot steal someone else\'s handle or phone', async () => {
  await env.withSecurityRulesDisabled(async (c) => { await set(ref(c.database(), 'handles/taken'), { type: 'user', id: 'alice' }); await set(ref(c.database(), 'phone_index/911'), 'alice'); });
  await assertFails(set(ref(db('bob'), 'handles/taken'), { type: 'user', id: 'bob' }));
  await assertFails(set(ref(db('bob'), 'phone_index/911'), 'bob'));
});
test('new nodes: private profile, push token, key bundle', async () => {
  await assertSucceeds(set(ref(db('alice'), 'user_private/alice/phone'), '+91'));
  await assertFails(get(ref(db('bob'), 'user_private/alice')));
  await assertSucceeds(set(ref(db('alice'), 'fcm_tokens/alice'), 'tok'));
  await assertFails(get(ref(db('bob'), 'fcm_tokens/alice')));
  const b = { ik: 'a', sik: 'b', spk: 'c', spk_sig: 'd', spk_id: 1, ts: now() };
  await assertSucceeds(set(ref(db('alice'), 'e2ee_keys/alice'), b));
  await assertSucceeds(get(ref(db('bob'), 'e2ee_keys/alice')));
  await assertFails(set(ref(db('bob'), 'e2ee_keys/alice'), b));
});
test('encrypted chat message passes the live message rules', async () => {
  await env.withSecurityRulesDisabled(async (c) => { await set(ref(c.database(), 'user_chats/alice/alice_bob'), { peer_uid: 'bob' }); });
  await assertSucceeds(set(ref(db('alice'), 'chats/alice_bob/m1'), { sender_uid: 'alice', type: 'text', text: '', timestamp: now(), e2ee: 1, ct: 'xxxx', x_ik: 'k', x_ek: 'k', x_spk: 1 }));
});
test('channel create with tags + handle, discover query', async () => {
  await assertSucceeds(update(ref(db('carol')), { 'channels/c1': { owner_uid: 'carol', name: 'T', privacy: 'public', subscriber_count: 1, tags: { Tech: true } }, 'handles/tch': { type: 'channel', id: 'c1' } }));
  await assertSucceeds(get(ref(db('bob'), 'channels')));
});
