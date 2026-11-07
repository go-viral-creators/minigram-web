#!/usr/bin/env bash
# Deploys ONLY the WeTalks database rules to the WeTalks Firebase project (wetalks-78e5a), after the emulator tests pass.
# Run it from anywhere; it always uses this folder and the explicit project, never the old Minigram config in the repo root (mim-324d3).
#   firebase login            (once, on the machine where you run this)
#   tools/deploy_rules.sh
set -euo pipefail
cd "$(dirname "$0")/.."
PROJECT=wetalks-78e5a
echo "project: $PROJECT   rules: $(pwd)/database.rules.json"
./rules-tests/run.sh
firebase deploy --only database --project "$PROJECT"
echo "deployed. Check: firebase database:get /config --project $PROJECT   (reads work), then try a label / 2-step PIN in the app."
