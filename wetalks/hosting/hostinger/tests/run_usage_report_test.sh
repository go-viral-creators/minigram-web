#!/bin/sh
# SIMULATED test: builds a synthetic store, proves the report changes NOTHING in it, and that the numbers match what was generated.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
T=${TMPDIR:-/tmp}/wt_report_test_$$; mkdir -p "$T"
php "$HERE/make_fixture.php" "$T/store" > "$T/truth.txt"
snap() { (cd "$T/store" && find . -type f -o -type d | sort | while read -r p; do if [ -f "$p" ]; then echo "$p $(stat -c '%s %Y' "$p") $(sha1sum < "$p")"; else echo "$p dir"; fi; done) | sha1sum; }
BEFORE=$(snap)
php "$HERE/../tools/usage_report.php" --store="$T/store" --json --plan-gb=20 --other-used-gb=3 > "$T/report.json"
php "$HERE/../tools/usage_report.php" --store="$T/store" --top=3 --plan-gb=20 --other-used-gb=3 > "$T/report.txt"
AFTER=$(snap)
[ "$BEFORE" = "$AFTER" ] && echo "PASS read-only: store identical before and after (names, sizes, mtimes, contents)" || { echo "FAIL: the report changed the store"; exit 1; }
php -r '
$t = json_decode(file_get_contents($argv[1]."/store/truth.json"), true); $r = json_decode(file_get_contents($argv[1]."/report.json"), true);
$ok = true; $chk = function($name, $a, $b) use (&$ok) { $s = $a === $b; $ok = $ok && $s; echo ($s ? "PASS" : "FAIL"), " $name: expected ", json_encode($b), " got ", json_encode($a), "\n"; };
$chk("media file count (+1 data-without-meta +1 unreadable-meta)", $r["media"]["files"], $t["files"] + 2);
$chk("media bytes (+1234 +500)", $r["media"]["bytes"], $t["bytes"] + 1234 + 500);
$chk("users with media (+unknown)", $r["users"]["count"], 3 + 1);
$chk("data without meta", $r["integrity"]["data_without_meta_files_bytes"], [1, 1234]);
$chk("meta without data", $r["integrity"]["meta_without_data"], 1);
$chk("unreadable meta", $r["integrity"]["unreadable_meta"], 1);
$chk(".part files", $r["integrity"]["part_files_count_bytes"], [1, 777]);
$chk("counter drift users", $r["integrity"]["counter_drift_users"], 1);
$chk("acl cache files / old", [$r["acl_cache"]["files"], $r["acl_cache"]["older_than_10min"]], [5, 3]);
$chk("top user is the biggest", array_key_first($r["users"]["top"]), "uidAAAAAAAAAAAA1");
$chk("sharded files are counted too", $r["media"]["layout_files"]["sharded"] > 0, true);
$chk("budget present", is_array($r["budget_calculator"]) && $r["budget_calculator"]["media_budget_bytes"] > 0, true);
exit($ok ? 0 : 1);
' "$T"
echo "--- text report (first lines) ---"; head -14 "$T/report.txt"
rm -f "$T/truth.txt" "$T/report.json" "$T/report.txt" "$T/store/truth.json"
echo "(temp folder left in $T)"
