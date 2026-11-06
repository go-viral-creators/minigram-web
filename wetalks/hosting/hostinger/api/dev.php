<?php
// Developer portal API: apps, API keys, usage, logs.  The caller is a signed-in developer (Firebase ID token).
declare(strict_types=1);
/** @var string $ROOT @var string $token @var string $path @var string $method */

const MAX_APPS = 3;
const MAX_KEYS = 3;
const RESERVED = ['wetalks', 'we talks', 'official', 'support', 'security', 'admin', 'verify', 'verification', 'bank', 'payment', 'google', 'microsoft', 'apple', 'meta', 'whatsapp', 'telegram'];

$me = authenticate($ROOT, $token, true);
$owner = $me['uid'];
$db = db($ROOT);
$parts = explode('/', $path);               // dev / apps / {id} / keys / {kid}

function app_row(PDO $db, string $id, string $owner): array {
    if (!preg_match('/^app_[a-f0-9]{12}$/', $id)) fail(404, 'app not found');
    $st = $db->prepare('SELECT * FROM apps WHERE id = ? AND owner = ?'); $st->execute([$id, $owner]);
    $a = $st->fetch();
    if (!$a) fail(404, 'app not found');
    return $a;
}

function app_json(PDO $db, array $a): array {
    $k = $db->prepare('SELECT COUNT(*) c FROM keys WHERE app = ? AND revoked = 0'); $k->execute([$a['id']]);
    $u = $db->prepare('SELECT msgs, otps, fails FROM usage WHERE app = ? AND day = ?'); $u->execute([$a['id'], gmdate('Y-m-d')]);
    $t = $u->fetch() ?: ['msgs' => 0, 'otps' => 0, 'fails' => 0];
    return ['id' => $a['id'], 'name' => $a['name'], 'description' => $a['descr'], 'verified' => (bool)$a['verified'], 'status' => $a['status'], 'created' => (int)$a['created'],
        'active_keys' => (int)$k->fetch()['c'], 'today' => ['messages' => (int)$t['msgs'], 'otps' => (int)$t['otps'], 'errors' => (int)$t['fails']]];
}

function clean_name(string $n): string {
    $n = trim(preg_replace('/\s+/u', ' ', $n) ?? '');
    if (mb_strlen($n) < 2 || mb_strlen($n) > 32) fail(422, 'name must be 2 to 32 characters');
    if (!preg_match('/^[\p{L}\p{N} .&_-]+$/u', $n)) fail(422, 'name may contain letters, numbers, spaces and . & _ -');
    $l = mb_strtolower($n);
    foreach (RESERVED as $r) if (str_contains($l, $r)) fail(422, 'that name is reserved, pick another');
    return $n;
}

if (!allow($db, 'dev:' . $owner, 120, 60)) fail(429, 'slow down');

// GET dev/me
if ($path === 'dev/me' && $method === 'GET') {
    $c = $db->prepare('SELECT COUNT(*) c FROM apps WHERE owner = ?'); $c->execute([$owner]);
    out(['uid' => $owner, 'email' => $me['email'], 'email_verified' => $me['verified'], 'apps' => (int)$c->fetch()['c'],
        'limits' => ['apps' => MAX_APPS, 'keys_per_app' => MAX_KEYS, 'messages_per_day' => LIMIT_MSGS_DAY, 'otps_per_day' => LIMIT_OTP_DAY]]);
}

if ($path === 'dev/apps' && $method === 'GET') {
    $st = $db->prepare('SELECT * FROM apps WHERE owner = ? ORDER BY created DESC'); $st->execute([$owner]);
    out(['apps' => array_map(fn($a) => app_json($db, $a), $st->fetchAll())]);
}

if ($path === 'dev/apps' && $method === 'POST') {
    if (!$me['verified']) fail(403, 'verify your email address first');
    $b = json_in();
    $name = clean_name((string)($b['name'] ?? ''));
    $descr = trim((string)($b['description'] ?? '')); if (mb_strlen($descr) > 200) fail(422, 'description is too long (max 200)');
    $c = $db->prepare('SELECT COUNT(*) c FROM apps WHERE owner = ?'); $c->execute([$owner]);
    if ((int)$c->fetch()['c'] >= MAX_APPS) fail(409, 'you can create up to ' . MAX_APPS . ' apps');
    $d = $db->prepare('SELECT 1 FROM apps WHERE lower(name) = lower(?)'); $d->execute([$name]);
    if ($d->fetch()) fail(409, 'an app with that name already exists');
    if (!allow($db, 'mk:' . $owner, 10, 3600)) fail(429, 'too many apps created, try later');
    $id = 'app_' . bin2hex(random_bytes(6));
    $db->prepare('INSERT INTO apps (id, owner, name, descr, created) VALUES (?, ?, ?, ?, ?)')->execute([$id, $owner, $name, $descr, time()]);
    out(app_json($db, app_row($db, $id, $owner)), 201);
}

if (preg_match('#^dev/apps/(app_[a-f0-9]{12})$#', $path, $m)) {
    $a = app_row($db, $m[1], $owner);
    if ($method === 'GET') {
        $k = $db->prepare('SELECT id, label, prefix, created, last_used, revoked FROM keys WHERE app = ? ORDER BY created DESC'); $k->execute([$a['id']]);
        $u = $db->prepare('SELECT day, msgs, otps, fails FROM usage WHERE app = ? ORDER BY day DESC LIMIT 14'); $u->execute([$a['id']]);
        out(app_json($db, $a) + ['keys' => array_map(fn($r) => ['id' => $r['id'], 'label' => $r['label'], 'prefix' => $r['prefix'], 'created' => (int)$r['created'],
            'last_used' => $r['last_used'] === null ? null : (int)$r['last_used'], 'revoked' => (bool)$r['revoked']], $k->fetchAll()),
            'usage' => array_map(fn($r) => ['day' => $r['day'], 'messages' => (int)$r['msgs'], 'otps' => (int)$r['otps'], 'errors' => (int)$r['fails']], $u->fetchAll()),
            'limits' => ['messages_per_day' => LIMIT_MSGS_DAY, 'otps_per_day' => LIMIT_OTP_DAY]]);
    }
    if ($method === 'PATCH') {
        $b = json_in();
        $name = isset($b['name']) ? clean_name((string)$b['name']) : $a['name'];
        $descr = isset($b['description']) ? trim((string)$b['description']) : $a['descr']; if (mb_strlen($descr) > 200) fail(422, 'description is too long (max 200)');
        $d = $db->prepare('SELECT 1 FROM apps WHERE lower(name) = lower(?) AND id <> ?'); $d->execute([$name, $a['id']]);
        if ($d->fetch()) fail(409, 'an app with that name already exists');
        $db->prepare('UPDATE apps SET name = ?, descr = ?, verified = CASE WHEN name <> ? THEN 0 ELSE verified END WHERE id = ?')->execute([$name, $descr, $name, $a['id']]);
        out(app_json($db, app_row($db, $a['id'], $owner)));
    }
    if ($method === 'DELETE') {
        $db->prepare('DELETE FROM apps WHERE id = ?')->execute([$a['id']]);
        $db->prepare('DELETE FROM usage WHERE app = ?')->execute([$a['id']]);
        $db->prepare('DELETE FROM logs WHERE app = ?')->execute([$a['id']]);
        http_response_code(204); exit;
    }
}

if (preg_match('#^dev/apps/(app_[a-f0-9]{12})/keys$#', $path, $m) && $method === 'POST') {
    if (!$me['verified']) fail(403, 'verify your email address first');
    $a = app_row($db, $m[1], $owner);
    $c = $db->prepare('SELECT COUNT(*) c FROM keys WHERE app = ? AND revoked = 0'); $c->execute([$a['id']]);
    if ((int)$c->fetch()['c'] >= MAX_KEYS) fail(409, 'an app can have up to ' . MAX_KEYS . ' active keys, revoke one first');
    $b = json_in(); $label = trim((string)($b['label'] ?? '')); if (mb_strlen($label) > 40) fail(422, 'label is too long (max 40)');
    $alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789';
    $key = 'wt_live_'; for ($i = 0; $i < 40; $i++) $key .= $alphabet[random_int(0, 61)];
    $id = 'key_' . bin2hex(random_bytes(6));
    $db->prepare('INSERT INTO keys (id, app, label, prefix, hash, created) VALUES (?, ?, ?, ?, ?, ?)')->execute([$id, $a['id'], $label, substr($key, 0, 12), hash('sha256', $key), time()]);
    // the only time the full key is ever shown: only its hash is stored
    out(['id' => $id, 'key' => $key, 'prefix' => substr($key, 0, 12), 'label' => $label, 'note' => 'Copy this key now. It will not be shown again.'], 201);
}

if (preg_match('#^dev/apps/(app_[a-f0-9]{12})/keys/(key_[a-f0-9]{12})$#', $path, $m) && $method === 'DELETE') {
    $a = app_row($db, $m[1], $owner);
    $db->prepare('UPDATE keys SET revoked = 1 WHERE id = ? AND app = ?')->execute([$m[2], $a['id']]);
    http_response_code(204); exit;
}

if (preg_match('#^dev/apps/(app_[a-f0-9]{12})/logs$#', $path, $m) && $method === 'GET') {
    $a = app_row($db, $m[1], $owner);
    $st = $db->prepare('SELECT ts, kind, status, detail FROM logs WHERE app = ? ORDER BY id DESC LIMIT 50'); $st->execute([$a['id']]);
    out(['logs' => array_map(fn($r) => ['time' => (int)$r['ts'], 'kind' => $r['kind'], 'status' => $r['status'], 'detail' => $r['detail']], $st->fetchAll())]);
}

fail(404, 'unknown endpoint');
