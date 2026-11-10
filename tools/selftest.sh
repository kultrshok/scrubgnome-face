#!/usr/bin/env bash
# Self-test for the pure-Java core: generate photos stuffed with fake private
# metadata, scrub them, re-scan, and check independently (pixels + exiftool).
# Needs: Java 17+, python3 with pillow + pillow-heif, exiftool.
set -euo pipefail
cd "$(dirname "$0")/.."
T=$(mktemp -d)
python3 tools/make_samples.py "$T/in"
javac -d "$T/cls" app/src/main/java/com/kultrshok/scrubgnome/face/core/*.java tools/SelfTest.java
java -cp "$T/cls" SelfTest "$T/in" "$T/java"
mkdir -p "$T/javaimg" && cp "$T/java"/*.* "$T/javaimg"/ && rm -f "$T/javaimg"/*.findings.txt
python3 tools/verify.py "$T/in" "$T/javaimg"
echo "$T" > /tmp/sgface_selftest_dir
echo "SELF-TEST PASSED"
