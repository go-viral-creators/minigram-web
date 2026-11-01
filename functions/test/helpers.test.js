'use strict';
const test = require('node:test');
const assert = require('node:assert');
// Load only the pure helpers (no Firebase init) by stubbing the heavy modules.
const Module = require('module');
const origLoad = Module._load;
Module._load = function (req, ...rest) {
  if (req === 'firebase-admin') return { initializeApp() {}, database() {}, messaging() {} };
  if (req.startsWith('firebase-functions')) {
    return { onCall: (_o, fn) => fn, HttpsError: class extends Error {}, setGlobalOptions() {} };
  }
  return origLoad.call(this, req, ...rest);
};
const { _test } = require('../index.js');

test('topicFor matches client sanitiser', () => {
  assert.strictEqual(_test.topicFor('ab c/d'), 'channel_ab_c_d');
});
test('chatIdFor is order independent', () => {
  assert.strictEqual(_test.chatIdFor('b', 'a'), 'a_b');
  assert.strictEqual(_test.chatIdFor('a', 'b'), 'a_b');
});
test('https only', () => {
  assert.ok(_test.isHttpsUrl('https://x.y/z.png'));
  assert.ok(!_test.isHttpsUrl('http://x.y/z.png'));
  assert.ok(_test.isHttpsUrl(''));
});
test('clean truncates', () => {
  assert.strictEqual(_test.clean('abcdef', 3), 'abc');
});
