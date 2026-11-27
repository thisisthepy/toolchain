#!/usr/bin/env bash
# Hot reload over the live channel (SPEC §1.14, toolchain#150 M2 and M3), run from usage-example/ against an
# installed, running debug app: edit a module, push it with hotReloadPython, read the new value back from the
# app, then check that a second run pushes nothing. The module is restored on exit.
#     bash hot_reload_check.sh <hotReloadTarget> <agent.json>
set -euo pipefail
target=$1
connection=$2
here=$(cd "$(dirname "$0")" && pwd)
module=src/commonMain/python/src/main/example_py/__init__.py
cp "$module" build/hot-reload-original.py
trap 'cp build/hot-reload-original.py "$module"' EXIT
sed -i.bak 's/^VERSION = "0.1.0"$/VERSION = "0.1.0-hot-reload"/' "$module" && rm -f "$module.bak"
grep -q '0.1.0-hot-reload' "$module" || { echo "could not edit $module"; exit 1; }
../gradlew hotReloadPython -Ppython.hotReloadTarget="$target" --console=plain > build/hot-reload-1.log 2>&1 \
  || { cat build/hot-reload-1.log; exit 1; }
cat build/hot-reload-1.log
grep -q 'pushed example_py/__init__.py$\|pushed example_py/__init__.py;' build/hot-reload-1.log \
  || { echo "hotReloadPython did not push the edited module alone"; exit 1; }
python3 "$here/agent_check.py" eval "$connection" $'import example_py\nexample_py.VERSION' "'0.1.0-hot-reload'"
../gradlew hotReloadPython -Ppython.hotReloadTarget="$target" --console=plain > build/hot-reload-2.log 2>&1 \
  || { cat build/hot-reload-2.log; exit 1; }
grep -q 'nothing pushed' build/hot-reload-2.log || { cat build/hot-reload-2.log; echo "a second hotReloadPython pushed again"; exit 1; }
echo "hot reload ($target): edited module pushed and re-imported; an unchanged payload pushes nothing"
