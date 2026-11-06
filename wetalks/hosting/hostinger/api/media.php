<?php
// Media routes: photos, voice, video, audio and documents (all end-to-end encrypted before they arrive). Included by index.php after sign-in.
declare(strict_types=1);
/** @var string $ROOT @var string $token @var string $uid @var string $path @var string $method */

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
    if (in_array($kind, ['video', 'audio', 'file'], true) && !FILES_ENABLED && !getenv('WT_FILES')) fail(403, 'coming_soon: video, audio and documents are not available yet');
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
