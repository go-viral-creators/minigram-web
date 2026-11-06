<?php
/**
 * WeTalks media storage USAGE REPORT  (READ-ONLY, command line only)
 *
 *   php usage_report.php --store=/path/to/wt_store [--json] [--top=15] [--plan-gb=20] [--other-used-gb=X] [--reserve-gb=Y]
 *
 * What it does:   walks wt_store/f (and wt_store/f/<xx> shards) and reads the small <id>.json files, then prints totals.
 * What it NEVER does: write, create, rename, delete, touch, lock or change anything. No fopen() with a write mode, no flock, no mkdir,
 *                     no network. It only uses scandir / stat / file_get_contents. Output goes to stdout.
 * Where to keep it: OUTSIDE the web root (it refuses to run from a web request).
 */
declare(strict_types=1);
if (PHP_SAPI !== 'cli') { http_response_code(404); exit; }

$o = getopt('', ['store:', 'json', 'top::', 'plan-gb::', 'other-used-gb::', 'reserve-gb::', 'help']);
if (isset($o['help']) || empty($o['store'])) { fwrite(STDERR, "usage: php usage_report.php --store=/path/to/wt_store [--json] [--top=15] [--plan-gb=20 --other-used-gb=X --reserve-gb=Y]\n"); exit(isset($o['help']) ? 0 : 2); }
$store = rtrim((string)$o['store'], '/');
if (basename($store) !== 'wt_store') { fwrite(STDERR, "refusing: the folder must be named wt_store (safety check)\n"); exit(2); }
if (!is_dir($store . '/f')) { fwrite(STDERR, "no media folder at $store/f\n"); exit(2); }
$top = max(1, (int)($o['top'] ?? 15));
$now = time();

$R = ['files' => 0, 'bytes' => 0, 'kinds' => [], 'users' => [], 'age' => ['<7d' => [0, 0], '7-30d' => [0, 0], '30-90d' => [0, 0], '>90d' => [0, 0]],
      'perday' => [], 'data_without_meta' => [0, 0], 'meta_without_data' => 0, 'part_files' => [0, 0], 'bad_meta' => 0, 'dirs' => 0, 'entries' => 0, 'layout' => ['flat' => 0, 'sharded' => 0]];

/** one media folder: data files + <id>.json */
function mask_uid(string $u): string { return strlen($u) > 10 ? substr($u, 0, 6) . '…' . substr($u, -3) : $u; }
function scan_dir(string $dir, bool $sharded, array &$R, int $now): void {
    $names = @scandir($dir);
    if ($names === false) return;
    $set = array_flip($names);
    foreach ($names as $n) {
        if ($n === '.' || $n === '..') continue;
        $R['entries']++;
        $p = $dir . '/' . $n;
        if (is_link($p)) continue;                                     // never follow links out of the store
        if (is_dir($p)) { $R['dirs']++; if (!$sharded && preg_match('/^[0-9a-f]{2}$/', $n)) scan_dir($p, true, $R, $now); continue; }
        if (str_ends_with($n, '.part')) { $R['part_files'][0]++; $R['part_files'][1] += (int)@filesize($p); continue; }
        if (str_ends_with($n, '.json')) {
            $id = substr($n, 0, -5);
            if (!isset($set[$id])) $R['meta_without_data']++;
            continue;
        }
        if (!preg_match('/^x[a-f0-9]{32}$/', $n)) continue;
        $size = (int)@filesize($p);
        $meta = @json_decode((string)@file_get_contents($p . '.json', false, null, 0, 65536), true);   // metadata is tiny: a hard cap on what is read
        $R['layout'][$sharded ? 'sharded' : 'flat']++;
        if (!is_array($meta)) {
            if (isset($set[$n . '.json'])) $R['bad_meta']++; else { $R['data_without_meta'][0]++; $R['data_without_meta'][1] += $size; }
            $uid = '(unknown)'; $kind = '(unknown)'; $ts = (int)@filemtime($p);
        } else {
            $uid = (string)($meta['uid'] ?? '(unknown)'); $kind = (string)($meta['kind'] ?? 'image'); $ts = (int)($meta['ts'] ?? @filemtime($p));
        }
        $R['files']++; $R['bytes'] += $size;
        $R['kinds'][$kind][0] = ($R['kinds'][$kind][0] ?? 0) + 1; $R['kinds'][$kind][1] = ($R['kinds'][$kind][1] ?? 0) + $size;
        $R['users'][$uid][0] = ($R['users'][$uid][0] ?? 0) + 1; $R['users'][$uid][1] = ($R['users'][$uid][1] ?? 0) + $size;
        $d = ($now - $ts) / 86400; $b = $d < 7 ? '<7d' : ($d < 30 ? '7-30d' : ($d < 90 ? '30-90d' : '>90d'));
        $R['age'][$b][0]++; $R['age'][$b][1] += $size;
        if ($d < 14) { $day = gmdate('Y-m-d', $ts); $R['perday'][$day] = ($R['perday'][$day] ?? 0) + $size; }
    }
}
scan_dir($store . '/f', false, $R, $now);

/** counters kept by the server (q/<uid>) versus what is really on disk */
$drift = []; $counterTotal = 0; $counterUsers = 0;
foreach ((array)@scandir($store . '/q') as $n) {
    if ($n === '.' || $n === '..' || $n === false) continue;
    $v = (int)trim((string)@file_get_contents($store . '/q/' . $n, false, null, 0, 32)); $counterTotal += $v; $counterUsers++;
    $real = $R['users'][$n][1] ?? 0;
    if ($v !== $real) $drift[$n] = [$v, $real];
}
$aclFiles = 0; $aclBytes = 0; $aclOld = 0;
foreach ((array)@scandir($store . '/_acl') as $n) { if ($n === '.' || $n === '..' || $n === false) continue; $aclFiles++; $aclBytes += (int)@filesize($store . '/_acl/' . $n); if ((int)@filemtime($store . '/_acl/' . $n) < $now - 600) $aclOld++; }
$rateFiles = count(array_diff((array)@scandir($store . '/r'), ['.', '..']));

function tree_size(string $dir, int &$files): int {
    $t = 0; $names = @scandir($dir); if ($names === false) return 0;
    foreach ($names as $n) { if ($n === '.' || $n === '..') continue; $p = $dir . '/' . $n; if (is_link($p)) continue; if (is_dir($p)) $t += tree_size($p, $files); else { $t += (int)@filesize($p); $files++; } }
    return $t;
}
$nonMedia = ['_acl', 'q', 'r', '_certs.json', 'dev.sqlite', 'dev.sqlite-wal', 'dev.sqlite-shm'];
$other = 0; $otherFiles = 0;
foreach ((array)@scandir($store) as $n) { if (in_array($n, ['.', '..', 'f'], true)) continue; $p = $store . '/' . $n; if (is_dir($p)) $other += tree_size($p, $otherFiles); else { $other += (int)@filesize($p); $otherFiles++; } }

// user distribution
$sizes = array_map(fn($u) => $u[1], $R['users']); sort($sizes);
$pct = function (float $q) use ($sizes) { $n = count($sizes); return $n ? $sizes[(int)min($n - 1, floor($q * ($n - 1)))] : 0; };
uasort($R['users'], fn($a, $b) => $b[1] <=> $a[1]);
$MB = 1048576;
$over = fn(int $mb) => count(array_filter($sizes, fn($s) => $s >= $mb * $MB));
ksort($R['perday']); $days = max(1, count($R['perday']));
$perDayAvg = $R['perday'] ? array_sum($R['perday']) / $days : 0;
$inodes = $R['files'] * 2 + $R['dirs'] + $otherFiles + $aclFiles + $rateFiles + $counterUsers;     // data + .json per media file, plus the rest

$diskTotal = @disk_total_space($store); $diskFree = @disk_free_space($store);
$budget = null;
if (isset($o['plan-gb'])) {
    $plan = (float)$o['plan-gb'] * 1073741824; $oth = (float)($o['other-used-gb'] ?? 0) * 1073741824;
    $res = isset($o['reserve-gb']) ? (float)$o['reserve-gb'] * 1073741824 : max(2 * 1073741824, 0.10 * $plan);
    $avail = $plan - $oth - $other - $res;
    $budget = ['plan_bytes' => (int)$plan, 'other_sites_and_site_files_bytes' => (int)$oth, 'store_non_media_bytes' => $other, 'safety_reserve_bytes' => (int)$res,
               'media_budget_bytes' => (int)max(0, $avail), 'media_used_bytes' => $R['bytes'], 'media_free_in_budget_bytes' => (int)($avail - $R['bytes']),
               'runway_days_at_recent_rate' => $perDayAvg > 0 ? round(max(0, $avail - $R['bytes']) / $perDayAvg, 1) : null];
}

$report = [
    'generated_utc' => gmdate('c'), 'store' => $store, 'read_only' => true,
    'media' => ['files' => $R['files'], 'bytes' => $R['bytes'], 'by_kind' => $R['kinds'], 'layout_files' => $R['layout'], 'age_buckets_files_bytes' => $R['age']],
    'users' => ['count' => count($R['users']), 'p50' => $pct(.5), 'p90' => $pct(.9), 'p99' => $pct(.99), 'max' => $sizes ? end($sizes) : 0,
                'over_100MB' => $over(100), 'over_300MB' => $over(300), 'over_1GB' => $over(1024), 'over_2GB' => $over(2048), 'top' => (function () use ($R, $top) { $o = []; foreach (array_slice($R['users'], 0, $top, true) as $u => $v) $o[mask_uid((string)$u)] = $v; return $o; })()],
    'growth' => ['bytes_per_day_last_14d_avg' => (int)$perDayAvg, 'days_with_uploads' => count($R['perday']), 'by_day' => $R['perday']],
    'integrity' => ['data_without_meta_files_bytes' => $R['data_without_meta'], 'meta_without_data' => $R['meta_without_data'], 'unreadable_meta' => $R['bad_meta'],
                    'part_files_count_bytes' => $R['part_files'], 'counter_users' => $counterUsers, 'counter_total_bytes' => $counterTotal, 'counter_drift_users' => count($drift), 'counter_drift_sample' => (function () use ($drift) { $o = []; foreach (array_slice($drift, 0, 10, true) as $u => $v) $o[mask_uid((string)$u)] = $v; return $o; })()],
    'inodes_estimate' => $inodes, 'acl_cache' => ['files' => $aclFiles, 'bytes' => $aclBytes, 'older_than_10min' => $aclOld], 'rate_files' => $rateFiles,
    'store_non_media_bytes' => $other,
    'server_disk_NOTE' => 'disk_total/free describe the whole server disk on many shared hosts, NOT your plan quota: do not use them as the plan limit',
    'server_disk' => ['total' => $diskTotal === false ? null : (int)$diskTotal, 'free' => $diskFree === false ? null : (int)$diskFree],
    'budget_calculator' => $budget ?? 'pass --plan-gb=20 --other-used-gb=<site files + other sites + databases + logs from hPanel Disk usage> [--reserve-gb=<n>] to calculate',
];

if (isset($o['json'])) { echo json_encode($report, JSON_PRETTY_PRINT | JSON_UNESCAPED_SLASHES), "\n"; exit(0); }

function h(int|float $b): string { foreach (['B', 'KB', 'MB', 'GB', 'TB'] as $i => $u) { if ($b < 1024 || $u === 'TB') return ($i ? number_format($b, 1) : (string)(int)$b) . ' ' . $u; $b /= 1024; } return ''; }
function mask(string $u): string { return mask_uid($u); }
echo "WeTalks media usage report (READ-ONLY)  ", $report['generated_utc'], "\nstore: $store\n\n";
echo "MEDIA   files: ", number_format($R['files']), "   total: ", h($R['bytes']), "   layout: flat ", $R['layout']['flat'], " / sharded ", $R['layout']['sharded'], "\n";
foreach ($R['kinds'] as $k => $v) echo sprintf("  %-10s %8s files  %10s\n", $k, number_format($v[0]), h($v[1]));
echo "\nAGE     "; foreach ($R['age'] as $k => $v) echo "$k: ", number_format($v[0]), " files/", h($v[1]), "   "; echo "\n";
echo "\nUSERS   ", count($R['users']), " with media.  p50 ", h($report['users']['p50']), "  p90 ", h($report['users']['p90']), "  p99 ", h($report['users']['p99']), "  max ", h($report['users']['max']), "\n";
echo "        over 100 MB: ", $over(100), "   over 300 MB: ", $over(300), "   over 1 GB: ", $over(1024), "   over 2 GB: ", $over(2048), "\n";
echo "  top $top (uid masked):\n"; $i = 0; foreach ($R['users'] as $u => $v) { if (++$i > $top) break; echo sprintf("   %2d. %-14s %7s files  %10s\n", $i, mask((string)$u), number_format($v[0]), h($v[1])); }
echo "\nGROWTH  last 14 days average: ", h((int)$perDayAvg), "/day over ", count($R['perday']), " active days\n";
echo "\nINTEGRITY  data without metadata: ", $R['data_without_meta'][0], " (", h($R['data_without_meta'][1]), ")   metadata without data: ", $R['meta_without_data'], "   unreadable metadata: ", $R['bad_meta'], "   .part files: ", $R['part_files'][0], " (", h($R['part_files'][1]), ")\n";
echo "           quota counters: ", $counterUsers, " users, ", h($counterTotal), " vs disk ", h($R['bytes']), "   users whose counter differs from disk: ", count($drift), "\n";
echo "\nINODES  about ", number_format($inodes), " used by this store (plan limit is 400,000 for everything)\n";
echo "ACL cache files: ", $aclFiles, " (", $aclOld, " older than 10 min)   rate-limit files: ", $rateFiles, "   other store data: ", h($other), "\n";
echo "\nSERVER DISK (whole server, NOT plan quota): total ", $diskTotal === false ? 'n/a' : h((int)$diskTotal), "  free ", $diskFree === false ? 'n/a' : h((int)$diskFree), "\n";
if ($budget) {
    echo "\nBUDGET (you supplied the plan numbers; nothing here is fixed at a percentage)\n";
    echo "  plan ", h($budget['plan_bytes']), " - site files/other sites/db/logs ", h($budget['other_sites_and_site_files_bytes']), " - store non-media ", h($budget['store_non_media_bytes']), " - safety reserve ", h($budget['safety_reserve_bytes']), "\n";
    echo "  = media budget ", h($budget['media_budget_bytes']), "   used ", h($budget['media_used_bytes']), "   free in budget ", h($budget['media_free_in_budget_bytes']), "   runway ", $budget['runway_days_at_recent_rate'] === null ? 'n/a (no recent uploads)' : $budget['runway_days_at_recent_rate'] . ' days at recent rate', "\n";
} else echo "\nBUDGET  add --plan-gb=20 --other-used-gb=<from hPanel> to calculate the media budget.\n";
