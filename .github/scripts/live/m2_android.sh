#!/usr/bin/env bash
# Live connection M2 (toolchain#150, docs/design/live-connection.md; SPEC §1.14 and §2.4), run inside the
# android-e2e emulator step from the repository root, after the debug APK is installed.
# The APK's payload marker carries the agent's port and token (Decision 15 (a)); connectPythonAndroid forwards
# the port; the M1 scenarios then run through the proxy kernel against build/pythonDevAgent/android.json.
set -euo pipefail
cd usage-example
connection=build/pythonDevAgent/android.json
[ -f "$connection" ] || { echo "no $connection: the debug build did not stage the dev-agent request"; exit 1; }
unzip -p "$(find build/outputs/apk/debug -name '*.apk' | head -n 1)" assets/python/python_multiplatform_devagent.enable \
  | grep -Eq '^port=[0-9]+;token=[A-Za-z0-9_-]{43,}$' || { echo "the APK's marker carries no agent request"; exit 1; }
../gradlew connectPythonAndroid --console=plain
adb shell am force-stop org.thisisthepy.python.multiplatform.toolchain.example
adb logcat -c
adb shell am start -W -n org.thisisthepy.python.multiplatform.toolchain.example/.MainActivity
# The forward accepts a connection before the app listens, so wait for the agent's own hello.
ready=0
for i in $(seq 1 90); do
  if python3 - "$connection" <<'PY'
import json, socket, sys
c = json.load(open(sys.argv[1]))
try:
    with socket.create_connection((c["host"], c["port"]), timeout=2) as s:
        s.sendall((json.dumps({"op": "hello", "id": 1, "token": c["token"]}) + "\n").encode())
        reply = s.makefile().readline()
    sys.exit(0 if '"hello"' in reply else 1)
except OSError:
    sys.exit(1)
PY
  then ready=1; break; fi
  sleep 1
done
if [ "$ready" != 1 ]; then
  echo "the app's dev agent never answered through the forward"
  adb logcat -d -t 300 || true
  exit 1
fi
uv run --no-project --python 3.13 --with ipykernel --with jupyter_client python ../.github/scripts/live/m1_scenarios.py "$connection" --android \
  || { adb logcat -d -t 300 || true; exit 1; }
