<?php
// The developer API (authenticated with an API key):  GET /ping, GET /usage, POST /messages, POST /otp, POST /otp/verify
declare(strict_types=1);
/** @var string $ROOT @var string $path @var string $method */

header('Cache-Control: no-store');
$db = db($ROOT);

// ---- API key
$h = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
if ($h === '' && function_exists('getallheaders')) foreach (getallheaders() as $k => $v) if (strcasecmp($k, 'Authorization') === 0) $h = $v;
$key = preg_match('/^Bearer\s+(wt_live_[A-Za-z0-9]{40})$/', $h, $m) ? $m[1] : (preg_match('/^wt_live_[A-Za-z0-9]{40}$/', (string)($_SERVER['HTTP_X_API_KEY'] ?? ''), $m2) ? $m2[0] : '');
if ($key === '') fail(401, 'missing or malformed API key. Send  Authorization: Bearer wt_live_...');
$st = $db->prepare('SELECT k.id kid, a.* FROM keys k JOIN apps a ON a.id = k.app WHERE k.hash = ? AND k.revoked = 0'); $st->execute([hash('sha256', $key)]);
$app = $st->fetch();
if (!$app) { usleep(300000); fail(401, 'invalid API key'); }     // small delay makes guessing pointless
if ($app['status'] !== 'active') fail(403, 'this app is suspended');
if (!allow($db, 'key:' . $app['kid'], 120, 60)) { header('Retry-After: 30'); fail(429, 'rate limit: 120 requests per minute'); }
$db->prepare('UPDATE keys SET last_used = ? WHERE id = ?')->execute([time(), $app['kid']]);

function logit(PDO $db, array $app, string $kind, string $status, string $detail = ''): void {
    $db->prepare('INSERT INTO logs (app, ts, kind, status, detail) VALUES (?, ?, ?, ?, ?)')->execute([$app['id'], time(), $kind, $status, mb_substr($detail, 0, 120)]);
    $db->prepare("DELETE FROM logs WHERE app = ? AND id < (SELECT COALESCE(MIN(id), 0) FROM (SELECT id FROM logs WHERE app = ? ORDER BY id DESC LIMIT 200))")->execute([$app['id'], $app['id']]);
}
function count_use(PDO $db, array $app, string $col): void {
    $db->prepare("INSERT INTO usage (app, day, $col) VALUES (?, ?, 1) ON CONFLICT(app, day) DO UPDATE SET $col = $col + 1")->execute([$app['id'], gmdate('Y-m-d')]);
}
function today(PDO $db, array $app): array {
    $u = $db->prepare('SELECT msgs, otps, fails FROM usage WHERE app = ? AND day = ?'); $u->execute([$app['id'], gmdate('Y-m-d')]);
    return $u->fetch() ?: ['msgs' => 0, 'otps' => 0, 'fails' => 0];
}
function mask(string $u): string { return '@' . mb_substr($u, 0, 2) . str_repeat('*', max(1, mb_strlen($u) - 2)); }

function oops(PDO $db, array $app, string $kind, int $code, string $err, string $msg, string $who = ''): never {
    count_use($db, $app, 'fails'); logit($db, $app, $kind, (string)$code . ' ' . $err, $who);
    http_response_code($code); header('Content-Type: application/json');
    echo json_encode(['error' => ['code' => $err, 'message' => $msg]]); exit;
}

/** "@name" or "name" -> Firebase uid, or null. */
function resolve_user(string $root, string $to): ?array {
    $u = strtolower(ltrim(trim($to), '@'));
    if (!preg_match('/^[a-z0-9_]{3,20}$/', $u)) return null;
    $r = relay($root);
    $res = http_json('GET', RTDB . '/usernames/' . $u . '.json?auth=' . rawurlencode($r['token']));
    return (is_string($res['body']) && preg_match('/^[A-Za-z0-9]{10,128}$/', $res['body'])) ? ['uid' => $res['body'], 'name' => $u] : null;
}

/** Puts the message into the person's service inbox. The database rules refuse it if they switched services off or blocked this app. */
function deliver(string $root, array $app, string $uid, string $kind, string $title, string $text, string $link, bool $otp): string {
    for ($try = 0; $try < 2; $try++) {
        if ($try === 1 && is_file($root . '/_relay_token.json')) @unlink($root . '/_relay_token.json');
        $r = relay($root);
        $mid = bin2hex(random_bytes(8));
        $data = ['a' => $app['id'], 'an' => $app['name'], 'v' => (int)$app['verified'], 'k' => $kind, 'x' => $text, 'ts' => ['.sv' => 'timestamp']];
        if ($title !== '') $data['t'] = $title;
        if ($link !== '') $data['l'] = $link;
        if ($otp) $data['o'] = 1;
        $res = http_json('PUT', RTDB . '/service_inbox/' . $uid . '/' . $mid . '.json?auth=' . rawurlencode($r['token']), $data);
        if ($res['code'] === 200) return $mid;
        if ($res['code'] !== 401) break;
    }
    return '';
}

// ---- GET /ping
if ($path === 'ping' && $method === 'GET') out(['ok' => true, 'app' => ['id' => $app['id'], 'name' => $app['name'], 'verified' => (bool)$app['verified']]]);

// ---- GET /usage
if ($path === 'usage' && $method === 'GET') {
    $t = today($db, $app);
    out(['date' => gmdate('Y-m-d'), 'messages' => ['used' => (int)$t['msgs'], 'limit' => LIMIT_MSGS_DAY], 'otps' => ['used' => (int)$t['otps'], 'limit' => LIMIT_OTP_DAY], 'errors' => (int)$t['fails']]);
}

if ($method !== 'POST') fail(405, 'use POST');
$b = json_in();

// ---- POST /messages
if ($path === 'messages') {
    $to = (string)($b['to'] ?? '');
    $text = trim(preg_replace('/[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]/u', '', (string)($b['text'] ?? '')) ?? '');
    $title = trim(preg_replace('/[\x00-\x1F\x7F]/u', '', (string)($b['title'] ?? '')) ?? '');
    $kind = (string)($b['kind'] ?? 'info'); $link = trim((string)($b['link'] ?? ''));
    if ($text === '' || mb_strlen($text) > 500) oops($db, $app, 'message', 422, 'invalid_text', 'text is required and may be at most 500 characters');
    if (mb_strlen($title) > 60) oops($db, $app, 'message', 422, 'invalid_title', 'title may be at most 60 characters');
    if (!in_array($kind, ['info', 'alert', 'followup'], true)) oops($db, $app, 'message', 422, 'invalid_kind', 'kind must be info, alert or followup');
    if ($link !== '' && (!preg_match('#^https://[A-Za-z0-9.-]+(/[^\s]*)?$#', $link) || strlen($link) > 200)) oops($db, $app, 'message', 422, 'invalid_link', 'link must be an https:// address (max 200 characters)');
    $t = today($db, $app);
    if ((int)$t['msgs'] >= LIMIT_MSGS_DAY) { header('Retry-After: 3600'); oops($db, $app, 'message', 429, 'daily_limit', 'daily message limit reached (' . LIMIT_MSGS_DAY . ')'); }
    $who = resolve_user($ROOT, $to);
    if (!$who) oops($db, $app, 'message', 404, 'user_not_found', 'no WeTalks user with that username', 'bad recipient');
    if (!allow($db, 'rcpt:' . $app['id'] . ':' . $who['uid'], 10, 86400)) oops($db, $app, 'message', 429, 'recipient_limit', 'at most 10 messages per person per day from one app', mask($who['name']));
    if (!allow($db, 'rcptall:' . $who['uid'], 30, 86400)) oops($db, $app, 'message', 429, 'recipient_busy', 'this person has received too many service messages today', mask($who['name']));
    $mid = deliver($ROOT, $app, $who['uid'], $kind, $title, $text, $link, false);
    if ($mid === '') oops($db, $app, 'message', 403, 'recipient_not_accepting', 'this person has turned off service messages or blocked your app', mask($who['name']));
    count_use($db, $app, 'msgs'); logit($db, $app, 'message', '201 delivered', mask($who['name']));
    out(['id' => $mid, 'status' => 'delivered', 'to' => '@' . $who['name']], 201);
}

// ---- POST /otp  (WeTalks generates the code, so your server never sees it until the user types it back)
if ($path === 'otp') {
    $len = (int)($b['length'] ?? 6); $ttl = (int)($b['ttl'] ?? 300);
    if ($len < 4 || $len > 8) oops($db, $app, 'otp', 422, 'invalid_length', 'length must be 4 to 8');
    if ($ttl < 60 || $ttl > 600) oops($db, $app, 'otp', 422, 'invalid_ttl', 'ttl must be 60 to 600 seconds');
    $t = today($db, $app);
    if ((int)$t['otps'] >= LIMIT_OTP_DAY) { header('Retry-After: 3600'); oops($db, $app, 'otp', 429, 'daily_limit', 'daily code limit reached (' . LIMIT_OTP_DAY . ')'); }
    $who = resolve_user($ROOT, (string)($b['to'] ?? ''));
    if (!$who) oops($db, $app, 'otp', 404, 'user_not_found', 'no WeTalks user with that username', 'bad recipient');
    if (!allow($db, 'otp:' . $app['id'] . ':' . $who['uid'], 5, 3600)) oops($db, $app, 'otp', 429, 'recipient_limit', 'at most 5 codes per person per hour', mask($who['name']));
    $code = ''; for ($i = 0; $i < $len; $i++) $code .= (string)random_int(0, 9);
    $id = 'otp_' . bin2hex(random_bytes(10));
    $pepper = secret($ROOT, 'pepper');
    $mins = max(1, intdiv($ttl, 60));
    $text = 'Your ' . $app['name'] . ' verification code is ' . $code . '. It expires in ' . $mins . ' minute' . ($mins > 1 ? 's' : '') . '. Never share this code with anyone.';
    $mid = deliver($ROOT, $app, $who['uid'], 'otp', 'Verification code', $text, '', true);
    if ($mid === '') oops($db, $app, 'otp', 403, 'recipient_not_accepting', 'this person has turned off service messages or blocked your app', mask($who['name']));
    $db->prepare('INSERT INTO otps (id, app, uid, hash, expires, created) VALUES (?, ?, ?, ?, ?, ?)')->execute([$id, $app['id'], $who['uid'], hash_hmac('sha256', $id . ':' . $code, $pepper), time() + $ttl, time()]);
    count_use($db, $app, 'otps'); logit($db, $app, 'otp', '201 sent', mask($who['name']));
    out(['otp_id' => $id, 'to' => '@' . $who['name'], 'length' => $len, 'expires_at' => gmdate('c', time() + $ttl)], 201);
}

// ---- POST /otp/verify
if ($path === 'otp/verify') {
    $id = (string)($b['otp_id'] ?? ''); $code = (string)($b['code'] ?? '');
    if (!preg_match('/^otp_[a-f0-9]{20}$/', $id) || !preg_match('/^[0-9]{4,8}$/', $code)) oops($db, $app, 'verify', 422, 'invalid_input', 'otp_id and a 4 to 8 digit code are required');
    $st = $db->prepare('SELECT * FROM otps WHERE id = ? AND app = ?'); $st->execute([$id, $app['id']]);
    $o = $st->fetch();
    if (!$o) oops($db, $app, 'verify', 404, 'otp_not_found', 'unknown otp_id');
    $bad = function (string $why) use ($db, $app): never { logit($db, $app, 'verify', '200 ' . $why); out(['valid' => false, 'reason' => $why]); };
    if ((int)$o['used'] === 1) $bad('used');
    if ((int)$o['expires'] < time()) $bad('expired');
    if ((int)$o['tries'] >= 5) $bad('too_many_attempts');
    $db->prepare('UPDATE otps SET tries = tries + 1 WHERE id = ?')->execute([$id]);
    $good = hash_equals($o['hash'], hash_hmac('sha256', $id . ':' . $code, secret($ROOT, 'pepper')));
    if (!$good) $bad('invalid');
    $db->prepare('UPDATE otps SET used = 1 WHERE id = ?')->execute([$id]);
    logit($db, $app, 'verify', '200 valid');
    out(['valid' => true]);
}
fail(404, 'unknown endpoint');
