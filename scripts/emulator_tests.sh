#!/usr/bin/env bash
# Runs inside the Android emulator job: installs the app + tests, runs the merge tests on the
# device, pulls the merged videos and checks them with ffprobe. Fails if anything failed.
set -uo pipefail
mkdir -p test-output/outputs
status=0

./gradlew --no-daemon installDebug installDebugAndroidTest || exit 1
adb logcat -c
adb shell am instrument -w -r \
  -e class io.github.kaustubhowmick.clipjoiner.MergeEngineTest \
  io.github.kaustubhowmick.clipjoiner.test/androidx.test.runner.AndroidJUnitRunner \
  | tee test-output/instrument.txt
if grep -qE "FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|INSTRUMENTATION_CODE: 0" test-output/instrument.txt \
   || ! grep -q "^OK (" test-output/instrument.txt; then
  echo "::error::Instrumented merge tests failed"
  status=1
fi
adb logcat -d > test-output/logcat.txt
grep "RESULT" test-output/logcat.txt | sed 's/.*ClipJoinerTest: //' | tee test-output/results.txt

for f in $(adb shell ls "/sdcard/Movies/Clip\ Joiner/" | tr -d '\r'); do
  adb pull "/sdcard/Movies/Clip Joiner/$f" "test-output/outputs/$f"
done
ls -l test-output/outputs

python3 scripts/verify_outputs.py app/src/androidTest/assets/clips test-output/outputs | tee test-output/verify.txt
[ "${PIPESTATUS[0]}" = 0 ] || status=1
exit $status
