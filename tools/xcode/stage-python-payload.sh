#!/usr/bin/env bash
# Xcode build-phase helper: stage the iOS Python payload with Gradle and copy it into the .app.
# Usage:  stage-python-payload.sh [:app:stagePythonBundleIosForXcode]
# Env:    GRADLEW (default ./gradlew); TARGET_BUILD_DIR and UNLOCALIZED_RESOURCES_FOLDER_PATH (Xcode sets both).
# Use it only WITHOUT python-multiplatform. With python-multiplatform, its tools/xcode/install-python.sh is
# the one phase that copies python/ (and the stdlib): give it PYTHON_PAYLOAD_TASK=:app:stagePythonBundleIosForXcode
# and do not add this script too -- both rsync --delete into the same <app>/python/.
set -euo pipefail
: "${TARGET_BUILD_DIR:?Xcode must set TARGET_BUILD_DIR}" "${UNLOCALIZED_RESOURCES_FOLDER_PATH:?Xcode must set UNLOCALIZED_RESOURCES_FOLDER_PATH}"
task="${1:-:app:stagePythonBundleIosForXcode}"
out=$("${GRADLEW:-./gradlew}" -q "$task")
dir=$(printf '%s\n' "$out" | sed -n 's/^PYTHON_PAYLOAD_DIR=//p')
[ -n "$dir" ] && [ -d "$dir" ] || { echo "error: no Python payload dir from Gradle" >&2; exit 1; }
rsync -a --delete "$dir/" "$TARGET_BUILD_DIR/$UNLOCALIZED_RESOURCES_FOLDER_PATH/python/"
