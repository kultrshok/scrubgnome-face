#!/usr/bin/env bash
# Self-test for the web (Safari/iPhone) version: the JS scrubber must clean every
# sample AND match the Java core byte-for-byte. Runs the Java test first.
# Needs: Node 22+ plus everything selftest.sh needs.
set -euo pipefail
cd "$(dirname "$0")/.."
bash tools/selftest.sh
T=$(cat /tmp/sgface_selftest_dir)
node tools/web-selftest.mjs "$T/in" "$T/java" "$T/web"
python3 tools/verify.py "$T/in" "$T/web"
echo "WEB SELF-TEST PASSED"
