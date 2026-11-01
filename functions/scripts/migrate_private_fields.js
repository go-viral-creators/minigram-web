#!/usr/bin/env node
'use strict';
/**
 * One-off migration (run ONCE after deploying the new rules, with admin credentials):
 *
 *   GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json \
 *   node functions/scripts/migrate_private_fields.js [--dry-run]
 *
 * Moves PII that used to sit in the publicly readable users/{uid} node:
 *   users/{uid}/phone, phone_cc, phone_number, email   -> user_private/{uid}/...
 *   users/{uid}/fcm_token                              -> fcm_tokens/{uid}
 * and then deletes the legacy copies. Safe to re-run.
 */
const admin = require('firebase-admin');
admin.initializeApp({ databaseURL: process.env.DATABASE_URL || 'https://mim-324d3-default-rtdb.firebaseio.com' });
const dry = process.argv.includes('--dry-run');

(async () => {
  const db = admin.database();
  const snap = await db.ref('users').get();
  const updates = {};
  let n = 0;
  snap.forEach((u) => {
    const v = u.val() || {};
    const uid = u.key;
    for (const f of ['phone', 'phone_cc', 'phone_number', 'email']) {
      if (typeof v[f] === 'string' && v[f]) { updates[`user_private/${uid}/${f}`] = v[f]; }
      if (f in v) updates[`users/${uid}/${f}`] = null;
    }
    if (typeof v.fcm_token === 'string' && v.fcm_token) updates[`fcm_tokens/${uid}`] = v.fcm_token;
    if ('fcm_token' in v) updates[`users/${uid}/fcm_token`] = null;
    if ('token_updated_at' in v) updates[`users/${uid}/token_updated_at`] = null;
    n++;
  });
  console.log(`${n} users scanned, ${Object.keys(updates).length} writes${dry ? ' (dry run, nothing written)' : ''}`);
  if (!dry && Object.keys(updates).length) await db.ref().update(updates);
  process.exit(0);
})().catch((e) => { console.error(e); process.exit(1); });
