<?php
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
function authenticate(string $root, string $token): string {
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
    return $uid;
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

$path = trim((string)parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH), '/');
$path = preg_replace('#^.*?api/v1/?#', '', $path) ?? '';
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

if ($path === 'health') { header('Content-Type: application/json'); echo json_encode(['ok' => true, 'fopen' => (bool)ini_get('allow_url_fopen'), 'openssl' => function_exists('openssl_verify')]); exit; }
if (!is_dir($ROOT)) @mkdir($ROOT, 0700, true);
if (!is_dir($ROOT . '/f')) { @mkdir($ROOT . '/f', 0700, true); @mkdir($ROOT . '/q', 0700, true); @mkdir($ROOT . '/r', 0700, true); }

$token = bearer();
if ($token === '') fail(401, 'sign in required');
$uid = authenticate($ROOT, $token);

if ($method === 'POST' && $path === 'media') {
    $cid = (string)($_GET['cid'] ?? '');
    if (!canUseChat($ROOT, $token, $cid)) fail(403, 'not allowed in this chat');
    $len = (int)($_SERVER['CONTENT_LENGTH'] ?? 0);
    if ($len <= 0) fail(411, 'length required');
    if ($len > MAX_BYTES) fail(413, 'file too large');
    // declared kind / type / extension must be one we allow, and the size must fit that kind (older apps send no kind: treated as a small image-class blob)
    $kind = strtolower((string)($_SERVER['HTTP_X_KIND'] ?? 'image'));
    if (!isset(KINDS[$kind])) fail(400, 'unknown kind');
    $K = KINDS[$kind];
    if ($len > $K['max'] * 1024 * 1024 + OVERHEAD) fail(413, 'file too large for this type (max ' . $K['max'] . ' MB)');
    $mimeIn = strtolower(trim((string)($_SERVER['HTTP_X_MIME'] ?? '')));
    if ($mimeIn !== '' && !preg_match($K['mime'], $mimeIn)) fail(415, 'type not allowed');
    $extIn = strtolower(trim((string)($_SERVER['HTTP_X_EXT'] ?? '')));
    if ($extIn !== '' && !in_array($extIn, $K['ext'], true)) fail(415, 'file type not allowed');
    // rate limit
    $ok = locked($ROOT . '/r/' . $uid, function ($h) {
        $d = json_decode((string)stream_get_contents($h), true) ?: ['t' => time(), 'n' => 0];
        if ($d['t'] < time() - 3600) $d = ['t' => time(), 'n' => 0];
        $d['n']++;
        ftruncate($h, 0); rewind($h); fwrite($h, json_encode($d));
        return $d['n'] <= MAX_UPLOADS_PER_HOUR;
    });
    if (!$ok) fail(429, 'too many uploads, try later');
    // quota
    $used = locked($ROOT . '/q/' . $uid, fn($h) => (int)stream_get_contents($h));
    if ($used + $len > QUOTA_BYTES) fail(507, 'storage full');
    $id = 'x' . bin2hex(random_bytes(16));
    $tmp = $ROOT . '/f/' . $id . '.part';
    $in = fopen('php://input', 'rb'); $out = fopen($tmp, 'wb'); $n = 0;
    $cap = $K['max'] * 1024 * 1024 + OVERHEAD;
    while (!feof($in)) { $b = fread($in, 65536); if ($b === false) break; $n += strlen($b); if ($n > $cap) { fclose($out); @unlink($tmp); fail(413, 'file too large'); } fwrite($out, $b); }
    fclose($in); fclose($out);
    if ($n <= 0 || $n !== $len) { @unlink($tmp); fail(400, 'incomplete upload'); }
    chmod($tmp, 0600);
    rename($tmp, $ROOT . '/f/' . $id);
    file_put_contents($ROOT . '/f/' . $id . '.json', json_encode(['uid' => $uid, 'cid' => $cid, 'size' => $n, 'ts' => time(), 'kind' => $kind, 'mime' => preg_replace('/[^a-z0-9.\/+-]/i', '', $mimeIn)]), LOCK_EX);
    locked($ROOT . '/q/' . $uid, function ($h) use ($n) { $v = (int)stream_get_contents($h) + $n; ftruncate($h, 0); rewind($h); fwrite($h, (string)$v); });
    http_response_code(201); header('Content-Type: application/json');
    echo json_encode(['id' => $id, 'size' => $n]);
    exit;
}

if (preg_match('#^media/(x[a-f0-9]{32})$#', $path, $m)) {
    $id = $m[1];
    $meta = @json_decode((string)@file_get_contents($ROOT . '/f/' . $id . '.json'), true);
    if (!is_array($meta) || !is_file($ROOT . '/f/' . $id)) fail(404, 'not found');
    if ($method === 'GET') {
        if (!canUseChat($ROOT, $token, (string)$meta['cid'])) fail(403, 'not allowed');
        header('Content-Type: application/octet-stream');
        header('Content-Length: ' . filesize($ROOT . '/f/' . $id));
        header('Cache-Control: private, max-age=86400, immutable');
        readfile($ROOT . '/f/' . $id);
        exit;
    }
    if ($method === 'DELETE') {
        if (($meta['uid'] ?? '') !== $uid) fail(403, 'only the sender can delete');
        $size = (int)($meta['size'] ?? 0);
        @unlink($ROOT . '/f/' . $id); @unlink($ROOT . '/f/' . $id . '.json');
        locked($ROOT . '/q/' . $uid, function ($h) use ($size) { $v = max(0, (int)stream_get_contents($h) - $size); ftruncate($h, 0); rewind($h); fwrite($h, (string)$v); });
        http_response_code(204);
        exit;
    }
}
fail(404, 'unknown endpoint');
