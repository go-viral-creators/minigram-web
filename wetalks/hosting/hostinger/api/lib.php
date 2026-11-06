<?php
// Shared helpers for the WeTalks API (media + developer API). See index.php for the router.
// WeTalks media API (runs on Hostinger).  Files are END-TO-END ENCRYPTED on the phone before they get here: this server only ever
// stores ciphertext it cannot read.  Security model:
//   * every request needs a Firebase ID token; it is verified here against Google's public certificates (no secret is stored on this server)
//   * who may read / write a chat's media is decided by the Firebase database rules themselves: we ask the database, with the caller's own token,
//     whether that person can read the chat (so the server never has its own idea of "membership")
//   * files live OUTSIDE the web root, under random 128-bit names, and are only ever served through this script
//   * size limits, per-user quota and per-user rate limit
declare(strict_types=1);

const PROJECT = 'wetalks-78e5a';
const RTDB = 'https://wetalks-78e5a-default-rtdb.firebaseio.com';
// what each kind of upload may be (the phone checks the same limits; the server never trusts the phone)
const KINDS = [
    'image' => ['max' => 12, 'mime' => '#^image/(jpeg|png|webp|gif)$#', 'ext' => ['jpg', 'jpeg', 'png', 'webp', 'gif']],
    'gif'   => ['max' => 12, 'mime' => '#^image/(jpeg|png|webp|gif)$#', 'ext' => ['jpg', 'jpeg', 'png', 'webp', 'gif']],
    'voice' => ['max' => 12, 'mime' => '#^audio/[a-z0-9.+-]+$#', 'ext' => ['m4a', 'mp4', 'aac', 'ogg', 'opus']],
    'video' => ['max' => 30, 'mime' => '#^video/[a-z0-9.+-]+$#', 'ext' => ['mp4', 'm4v', '3gp', 'mkv', 'webm', 'mov']],
    'audio' => ['max' => 30, 'mime' => '#^audio/[a-z0-9.+-]+$#', 'ext' => ['mp3', 'm4a', 'aac', 'wav', 'ogg', 'oga', 'opus', 'flac', 'amr']],
    'file'  => ['max' => 30, 'mime' => '#^[a-z]+/[a-z0-9.+-]+$#', 'ext' => ['pdf', 'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'txt', 'csv', 'rtf', 'odt', 'ods', 'odp', 'json', 'xml', 'md', 'epub', 'vcf', 'zip', 'rar', '7z', 'tar', 'gz']],
];
const OVERHEAD = 64 * 1024;                  // encryption adds a few hundred bytes to every file
const MAX_BYTES = 31 * 1024 * 1024;          // absolute cap on any request
const QUOTA_BYTES = 2 * 1024 * 1024 * 1024;  // per user
const MAX_UPLOADS_PER_HOUR = 200;

$ROOT = realpath(__DIR__ . '/../../..') . '/wt_store';   // sibling of public_html, NOT reachable from the web
if (getenv('WT_STORE')) $ROOT = (string)getenv('WT_STORE');   // tests
const WEB_API_KEY = 'AIzaSyA9k0-Z4SuY3BJ_RWlYA6Ac4d2tdBOIVMk';   // Firebase web key: public by design (it only identifies the project)

header('X-Content-Type-Options: nosniff');
header('Cache-Control: no-store');
header('Referrer-Policy: no-referrer');

function fail(int $code, string $msg): never {
    http_response_code($code);
    header('Content-Type: application/json');
    echo json_encode(['error' => $msg]);
    exit;
}

function bearer(): string {
    $h = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    if ($h === '' && function_exists('getallheaders')) foreach (getallheaders() as $k => $v) if (strcasecmp($k, 'Authorization') === 0) $h = $v;
    if ($h === '') $h = isset($_SERVER['HTTP_X_FIREBASE_TOKEN']) ? 'Bearer ' . $_SERVER['HTTP_X_FIREBASE_TOKEN'] : '';
    return preg_match('/^Bearer\s+([A-Za-z0-9._-]{20,4096})$/', $h, $m) ? $m[1] : '';
}

function b64u(string $s): string|false { return base64_decode(strtr($s, '-_', '+/') . str_repeat('=', (4 - strlen($s) % 4) % 4), true); }

/** Google's signing certificates, cached on disk for as long as Google says. */
function certs(string $root): array {
    $f = $root . '/_certs.json';
    if (is_file($f) && filemtime($f) > time() - 3600) { $c = json_decode((string)file_get_contents($f), true); if (is_array($c) && $c) return $c; }
    $src = getenv('WT_CERTS_FILE');
    $raw = $src ? file_get_contents($src) : @file_get_contents('https://www.googleapis.com/robot/v1/metadata/x509/securetoken@system.gserviceaccount.com',
        false, stream_context_create(['http' => ['timeout' => 5]]));
    $c = $raw ? json_decode($raw, true) : null;
    if (!is_array($c) || !$c) { if (is_file($f)) { $c = json_decode((string)file_get_contents($f), true); if (is_array($c)) return $c; } fail(503, 'auth unavailable'); }
    @file_put_contents($f, json_encode($c), LOCK_EX);
    return $c;
}

/** Returns the Firebase uid or ends the request with 401. */
function authenticate(string $root, string $token, bool $claims = false): string|array {
    $p = explode('.', $token);
    if (count($p) !== 3) fail(401, 'bad token');
    $h = json_decode((string)b64u($p[0]), true); $c = json_decode((string)b64u($p[1]), true); $sig = b64u($p[2]);
    if (!is_array($h) || !is_array($c) || $sig === false) fail(401, 'bad token');
    if (($h['alg'] ?? '') !== 'RS256' || empty($h['kid'])) fail(401, 'bad token');
    $cs = certs($root);
    $pem = $cs[$h['kid']] ?? null;
    if (!$pem) fail(401, 'unknown key');
    if (openssl_verify($p[0] . '.' . $p[1], $sig, $pem, OPENSSL_ALGO_SHA256) !== 1) fail(401, 'bad signature');
    $now = time();
    if (($c['aud'] ?? '') !== PROJECT || ($c['iss'] ?? '') !== 'https://securetoken.google.com/' . PROJECT) fail(401, 'wrong project');
    if (($c['exp'] ?? 0) < $now || ($c['iat'] ?? PHP_INT_MAX) > $now + 60) fail(401, 'expired');
    $uid = (string)($c['sub'] ?? '');
    if (!preg_match('/^[A-Za-z0-9]{1,128}$/', $uid)) fail(401, 'bad subject');
    return $claims ? ['uid' => $uid, 'email' => (string)($c['email'] ?? ''), 'verified' => ($c['email_verified'] ?? false) === true] : $uid;
}

/** Can this person read the chat?  The database rules answer (pins/<chat> is readable by exactly the chat's people). */
function canUseChat(string $root, string $token, string $cid): bool {
    if (!preg_match('/^[A-Za-z0-9_-]{3,128}$/', $cid)) return false;
    if (getenv('WT_SKIP_RTDB')) return true;
    $f = $root . '/_acl/' . sha1($token . $cid);
    if (is_file($f) && filemtime($f) > time() - 120) return true;
    $ctx = stream_context_create(['http' => ['timeout' => 5, 'ignore_errors' => true]]);
    @file_get_contents(RTDB . '/pins/' . rawurlencode($cid) . '.json?shallow=true&auth=' . rawurlencode($token), false, $ctx);
    $ok = isset($http_response_header[0]) && str_contains($http_response_header[0], ' 200');
    if ($ok) { @mkdir($root . '/_acl', 0700, true); @file_put_contents($f, '1'); }
    return $ok;
}

function locked(string $file, callable $fn): mixed {
    $h = fopen($file, 'c+'); if (!$h) fail(500, 'storage');
    flock($h, LOCK_EX);
    try { return $fn($h); } finally { flock($h, LOCK_UN); fclose($h); }
}



/** Reads the JSON body of a request (max 64 KB). */
function json_in(): array {
    $raw = file_get_contents('php://input', false, null, 0, 65537);
    if ($raw === false || strlen($raw) > 65536) fail(413, 'body too large');
    if (trim($raw) === '') return [];
    $d = json_decode($raw, true);
    if (!is_array($d)) fail(400, 'invalid json');
    return $d;
}

function out(mixed $data, int $code = 200): never {
    http_response_code($code);
    header('Content-Type: application/json');
    echo json_encode($data, JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    exit;
}

/** SQLite database for developer accounts, apps, keys and usage (a file outside the web root). */
function db(string $root): PDO {
    static $pdo = null;
    if ($pdo) return $pdo;
    @mkdir($root, 0700, true);
    $pdo = new PDO('sqlite:' . $root . '/dev.sqlite', null, null, [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC]);
    $pdo->exec('PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000; PRAGMA foreign_keys=ON;');
    $pdo->exec("CREATE TABLE IF NOT EXISTS apps (id TEXT PRIMARY KEY, owner TEXT NOT NULL, name TEXT NOT NULL, descr TEXT NOT NULL DEFAULT '', verified INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'active', created INTEGER NOT NULL);
        CREATE INDEX IF NOT EXISTS apps_owner ON apps(owner);
        CREATE TABLE IF NOT EXISTS keys (id TEXT PRIMARY KEY, app TEXT NOT NULL REFERENCES apps(id) ON DELETE CASCADE, label TEXT NOT NULL DEFAULT '', prefix TEXT NOT NULL, hash TEXT NOT NULL UNIQUE, created INTEGER NOT NULL, last_used INTEGER, revoked INTEGER NOT NULL DEFAULT 0);
        CREATE TABLE IF NOT EXISTS usage (app TEXT NOT NULL, day TEXT NOT NULL, msgs INTEGER NOT NULL DEFAULT 0, otps INTEGER NOT NULL DEFAULT 0, fails INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (app, day));
        CREATE TABLE IF NOT EXISTS logs (id INTEGER PRIMARY KEY AUTOINCREMENT, app TEXT NOT NULL, ts INTEGER NOT NULL, kind TEXT NOT NULL, status TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '');
        CREATE INDEX IF NOT EXISTS logs_app ON logs(app, id);
        CREATE TABLE IF NOT EXISTS otps (id TEXT PRIMARY KEY, app TEXT NOT NULL, uid TEXT NOT NULL, hash TEXT NOT NULL, expires INTEGER NOT NULL, tries INTEGER NOT NULL DEFAULT 0, used INTEGER NOT NULL DEFAULT 0, created INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS hits (k TEXT NOT NULL, ts INTEGER NOT NULL);
        CREATE INDEX IF NOT EXISTS hits_k ON hits(k, ts);");
    return $pdo;
}

/** Sliding-window rate limit. Returns true when the call is allowed (and counts it). */
function allow(PDO $db, string $key, int $max, int $windowSecs): bool {
    $now = time();
    $db->prepare('DELETE FROM hits WHERE ts < ?')->execute([$now - 86400]);
    $st = $db->prepare('SELECT COUNT(*) c FROM hits WHERE k = ? AND ts > ?'); $st->execute([$key, $now - $windowSecs]);
    if ((int)$st->fetch()['c'] >= $max) return false;
    $db->prepare('INSERT INTO hits (k, ts) VALUES (?, ?)')->execute([$key, $now]);
    return true;
}

function secret(string $root, string $name): string {
    $f = $root . '/' . $name;
    if (!is_file($f)) { @mkdir($root, 0700, true); file_put_contents($f, bin2hex(random_bytes(32)), LOCK_EX); chmod($f, 0600); }
    return trim((string)file_get_contents($f));
}

function http_json(string $method, string $url, ?array $body = null, array $headers = []): array {
    if (getenv('WT_FAKE_NET')) return fake_net($method, $url, $body);   // tests only: the variable is never set on the server
    $opts = ['http' => ['method' => $method, 'timeout' => 8, 'ignore_errors' => true, 'header' => implode("\r\n", array_merge(['Content-Type: application/json'], $headers))]];
    if ($body !== null) $opts['http']['content'] = json_encode($body, JSON_UNESCAPED_UNICODE);
    $raw = @file_get_contents($url, false, stream_context_create($opts));
    $code = 0; if (isset($http_response_header[0]) && preg_match('/ (\d{3})/', $http_response_header[0], $m)) $code = (int)$m[1];
    return ['code' => $code, 'body' => $raw === false ? null : json_decode($raw, true)];
}

/**
 * The relay is an ordinary Firebase account that the API signs in as, to put messages into people's service inbox.
 * It holds NO admin power: the database rules decide what it may write (see service_inbox), and the account's password lives
 * only in a file outside the web root. If it does not exist yet it is created on first use.
 */
function relay(string $root): array {
    $cache = $root . '/_relay_token.json';
    if (is_file($cache)) { $c = json_decode((string)file_get_contents($cache), true); if (is_array($c) && ($c['exp'] ?? 0) > time() + 120) return $c; }
    $f = $root . '/relay.json';
    $cred = is_file($f) ? json_decode((string)file_get_contents($f), true) : null;
    $key = rawurlencode(WEB_API_KEY);
    if (!is_array($cred)) {
        $cred = ['email' => 'relay-' . bin2hex(random_bytes(6)) . '@services.wetalks.invalid', 'password' => bin2hex(random_bytes(24))];
        $r = http_json('POST', "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=$key", $cred + ['returnSecureToken' => true]);
        if ($r['code'] !== 200) fail(503, 'delivery service unavailable');
        $cred['uid'] = $r['body']['localId'];
        file_put_contents($f, json_encode($cred), LOCK_EX); chmod($f, 0600);
    }
    $r = http_json('POST', "https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=$key", ['email' => $cred['email'], 'password' => $cred['password'], 'returnSecureToken' => true]);
    if ($r['code'] !== 200) fail(503, 'delivery service unavailable');
    $c = ['token' => $r['body']['idToken'], 'uid' => $cred['uid'] ?? $r['body']['localId'], 'exp' => time() + (int)($r['body']['expiresIn'] ?? 3600)];
    file_put_contents($cache, json_encode($c), LOCK_EX); chmod($cache, 0600);
    return $c;
}

const LIMIT_MSGS_DAY = 200;      // messages per app per day (free tier)
const LIMIT_OTP_DAY = 100;       // verification codes per app per day

/** Test double for Google / Firebase calls (used by the local tests only). */
function fake_net(string $method, string $url, ?array $body): array {
    $dir = (string)getenv('WT_FAKE_NET');
    if (str_contains($url, 'accounts:signUp')) return ['code' => 200, 'body' => ['localId' => 'relayuid0001', 'idToken' => 'fake']];
    if (str_contains($url, 'accounts:signInWithPassword')) return ['code' => 200, 'body' => ['localId' => 'relayuid0001', 'idToken' => 'faketoken', 'expiresIn' => '3600']];
    if ($method === 'GET' && preg_match('#/usernames/([a-z0-9_]+)\.json#', $url, $m)) { $known = ['alice' => 'aliceuid0001', 'bob' => 'bobuid00002', 'blocked' => 'blockeduid03']; return ['code' => 200, 'body' => $known[$m[1]] ?? null]; }
    if ($method === 'PUT' && preg_match('#/service_inbox/([A-Za-z0-9]+)/([a-f0-9]+)\.json#', $url, $m)) {
        if ($m[1] === 'blockeduid03') return ['code' => 401, 'body' => ['error' => 'Permission denied']];
        file_put_contents($dir . '/inbox_' . $m[1] . '_' . $m[2] . '.json', json_encode($body));
        return ['code' => 200, 'body' => $body];
    }
    return ['code' => 404, 'body' => null];
}
