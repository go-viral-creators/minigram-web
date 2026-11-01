#!/usr/bin/env bash
# Copies the real rules next to the emulator config (the CLI refuses paths outside the project) and runs the tests.
set -e
cd "$(dirname "$0")"
cp ../${RULES_FILE:-database.rules.json} ./.rules.copy.json
cat > firebase.json <<JSON
{ "database": { "rules": ".rules.copy.json" }, "emulators": { "database": { "port": 9000 }, "ui": { "enabled": false } } }
JSON
trap 'rm -f .rules.copy.json firebase.json' EXIT
npx firebase emulators:exec --only database --project demo-minigram "node --test ${TEST_FILE:-rules.test.js}"
