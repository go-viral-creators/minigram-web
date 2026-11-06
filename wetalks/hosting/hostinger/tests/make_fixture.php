<?php
// Builds a synthetic wt_store (SIMULATED data) for testing the usage report. usage: php make_fixture.php <dir>
declare(strict_types=1);
$d = rtrim($argv[1], '/'); mt_srand(7);
foreach (['f', 'q', 'r', '_acl'] as $s) @mkdir("$d/$s", 0700, true);
$now = time(); $users = ['uidAAAAAAAAAAAA1' => 40, 'uidBBBBBBBBBBBB2' => 6, 'uidCCCCCCCCCCCC3' => 2]; $truth = ['files' => 0, 'bytes' => 0, 'users' => []];
foreach ($users as $uid => $n) {
    for ($i = 0; $i < $n; $i++) {
        $id = 'x' . bin2hex(random_bytes(16)); $size = mt_rand(2000, 90000); $age = mt_rand(0, 120) * 86400; $kind = $i % 7 === 0 ? 'voice' : 'image';
        $dir = ($i % 5 === 0) ? "$d/f/" . substr($id, 1, 2) : "$d/f";                    // some files in a shard folder, like the new layout
        @mkdir($dir, 0700, true);
        file_put_contents("$dir/$id", random_bytes($size));
        file_put_contents("$dir/$id.json", json_encode(['uid' => $uid, 'cid' => 'chat1', 'size' => $size, 'ts' => $now - $age, 'kind' => $kind, 'mime' => 'x']));
        $truth['files']++; $truth['bytes'] += $size; $truth['users'][$uid] = ($truth['users'][$uid] ?? 0) + $size;
    }
    file_put_contents("$d/q/$uid", (string)($truth['users'][$uid] + ($uid === 'uidCCCCCCCCCCCC3' ? 5000 : 0)));    // one counter deliberately drifts
}
// integrity cases: data without meta, meta without data, .part file, unreadable meta
$o = 'x' . bin2hex(random_bytes(16)); file_put_contents("$d/f/$o", str_repeat('a', 1234));
file_put_contents("$d/f/x" . bin2hex(random_bytes(16)) . ".json", json_encode(['uid' => 'ghost']));
file_put_contents("$d/f/x" . bin2hex(random_bytes(16)) . ".part", str_repeat('b', 777));
$b = 'x' . bin2hex(random_bytes(16)); file_put_contents("$d/f/$b", str_repeat('c', 500)); file_put_contents("$d/f/$b.json", "{not json");
foreach (range(1, 5) as $i) { file_put_contents("$d/_acl/" . sha1((string)$i), '1'); touch("$d/_acl/" . sha1((string)$i), $now - ($i > 2 ? 3600 : 5)); }
file_put_contents("$d/_certs.json", '{}');
file_put_contents("$d/truth.json", json_encode($truth));
echo json_encode($truth), "\n";
