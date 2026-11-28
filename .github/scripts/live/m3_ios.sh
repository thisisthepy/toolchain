#!/usr/bin/env bash
# Live connection M3 (toolchain#150, docs/design/live-connection.md; SPEC §1.14 and §2.4), run inside the
# ios-e2e job from the repository root, after the debug .app is installed on a booted simulator.
#     UDID=<simulator> APP=<path to the .app> bash .github/scripts/live/m3_ios.sh
# The .app's payload marker carries the agent's port and token (Decision 15 (a)); the simulator shares the
# Mac's loopback, so build/pythonDevAgent/ios.json is reached as it is, with no forward. The M1 scenarios then
# run through the proxy kernel, and hotReloadPython pushes an edited module to the running app.
set -euo pipefail
: "${UDID:?UDID must name the booted simulator}" "${APP:?APP must be the installed .app}"
cd usage-example
connection=build/pythonDevAgent/ios.json
[ -f "$connection" ] || { echo "no $connection: the debug build did not stage the dev-agent request"; exit 1; }
grep -Eq '^port=[0-9]+;token=[A-Za-z0-9_-]{43,}$' "$APP/python/python_multiplatform_devagent.enable" \
  || { echo "the .app's marker carries no agent request"; exit 1; }
bundle=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$APP/Info.plist")
logs() { xcrun simctl spawn "$UDID" log show --last 5m --style compact --predicate "process CONTAINS 'iosApp' OR process CONTAINS 'app'" 2>/dev/null | tail -n 200 || true; }
xcrun simctl launch --terminate-running-process "$UDID" "$bundle"
python3 ../.github/scripts/live/agent_check.py wait "$connection" 120 || { logs; exit 1; }
uv run --no-project --python 3.13 --with ipykernel --with jupyter_client python ../.github/scripts/live/m1_scenarios.py "$connection" --ios \
  || { logs; exit 1; }
bash ../.github/scripts/live/hot_reload_check.sh ios "$connection" || { logs; exit 1; }
