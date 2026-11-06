<?php
// WeTalks API router.  /api/v1/...
//   media...      photos, voice, video, audio, documents       (Firebase sign-in token)
//   dev/...       developer portal: apps, keys, usage           (Firebase sign-in token of the developer)
//   messages, otp, otp/verify, usage, ping                      (developer API key)
declare(strict_types=1);
require __DIR__ . '/lib.php';

$path = trim((string)parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH), '/');
$path = preg_replace('#^.*?api/v1/?#', '', $path) ?? '';
$method = $_SERVER['REQUEST_METHOD'] ?? 'GET';

if ($method === 'OPTIONS') { http_response_code(204); header('Access-Control-Allow-Origin: https://wetalks.fireclashpro.com'); header('Access-Control-Allow-Headers: Authorization, Content-Type'); header('Access-Control-Allow-Methods: GET, POST, PATCH, DELETE'); header('Access-Control-Max-Age: 600'); exit; }
if ($path === 'health') {
    header('Content-Type: application/json');
    echo json_encode(['ok' => true, 'fopen' => (bool)ini_get('allow_url_fopen'), 'openssl' => function_exists('openssl_verify'), 'sqlite' => in_array('sqlite', PDO::getAvailableDrivers(), true), 'php' => PHP_MAJOR_VERSION . '.' . PHP_MINOR_VERSION]);
    exit;
}
if ($path === 'relay/uid') { header('Content-Type: application/json'); echo json_encode(['uid' => relay($ROOT)['uid']]); exit; }   // not secret: it is the id of the delivery account (needed once to allow it in the database rules)
if (!is_dir($ROOT)) @mkdir($ROOT, 0700, true);
if (!is_dir($ROOT . '/f')) { @mkdir($ROOT . '/f', 0700, true); @mkdir($ROOT . '/q', 0700, true); @mkdir($ROOT . '/r', 0700, true); }

// the developer API (API-key authentication) is handled first: those calls never carry a Firebase token
if (in_array($path, ['messages', 'otp', 'otp/verify', 'usage', 'ping'], true)) { require __DIR__ . '/service.php'; exit; }

$token = bearer();
if ($token === '') fail(401, 'sign in required');
if (str_starts_with($path, 'dev/') || $path === 'dev') {
    header('Access-Control-Allow-Origin: https://wetalks.fireclashpro.com');
    require __DIR__ . '/dev.php'; exit;
}
$uid = authenticate($ROOT, $token);
require __DIR__ . '/media.php';
