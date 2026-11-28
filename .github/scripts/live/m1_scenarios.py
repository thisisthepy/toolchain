#!/usr/bin/env python3
"""Live connection M1 (toolchain docs/design/live-connection.md §5; SPEC §2.4): a Jupyter client drives the
packaged desktop app through toolchain-lite's proxy kernel.

    uv run --no-project --with ipykernel --with jupyter_client python .github/scripts/live/m1_scenarios.py <agent.json> [--android | --ios]

<agent.json> is the connection file (host, port, token) of an app started with PYTHON_MULTIPLATFORM_DEV_AGENT,
or, with --android (M2), the debug APK's `build/pythonDevAgent/android.json` after `connectPythonAndroid`; the
cell then must also report `sys.platform == 'android'`. With --ios (M3), the simulator app's
`build/pythonDevAgent/ios.json` (the simulator shares the Mac's loopback, so no forward), and `sys.platform == 'ios'`.
Fails (exit 1) unless, through the kernel: a cell's output and result come back; a name set in one cell is
there in the next; the app's own payload (example_py) imports from inside the app; an error is reported as
one; completion sees the app's namespace; and the pid is not this process's (the code ran in the app).
With no agent running, the kernel cannot start and this fails, which is the negative check the design asks for.
"""
from __future__ import annotations

import os
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
alias = Path(tempfile.mkdtemp(prefix="tcl-pkg-"))
os.symlink(ROOT / "toolchain/src/cliMain/python", alias / "toolchain_lite")
sys.path.insert(0, str(alias))
os.environ["PYTHONPATH"] = str(alias) + os.pathsep + os.environ.get("PYTHONPATH", "")

from jupyter_client.manager import start_new_kernel  # noqa: E402
from toolchain_lite import kernel  # noqa: E402


def run(client, code):
    outputs = []
    reply = client.execute_interactive(code, output_hook=outputs.append, timeout=60)
    text = "".join(m["content"].get("text", "") for m in outputs if m["msg_type"] == "stream")
    result = next((m["content"]["data"].get("text/plain") for m in outputs if m["msg_type"] == "execute_result"), None)
    return reply["content"]["status"], text, result


def check(label, ok, detail):
    print(("ok   " if ok else "FAIL ") + label + ("" if ok else f": {detail}"))
    if not ok:
        sys.exit(1)


def main() -> int:
    agent_file = Path(sys.argv[1]).resolve()
    platform = "android" if "--android" in sys.argv[2:] else "ios" if "--ios" in sys.argv[2:] else None
    label = {"android": "Android debug", "ios": "iOS simulator debug", None: "desktop debug"}[platform]
    prefix = Path(tempfile.mkdtemp(prefix="tcl-jupyter-"))
    kernel.install(agent_file, "tcl-m1", f"usage-example ({label})", prefix=str(prefix))
    os.environ["JUPYTER_PATH"] = str(prefix / "share" / "jupyter")
    manager, client = start_new_kernel(kernel_name="tcl-m1", startup_timeout=60)
    try:
        status, text, result = run(client, "import os\nprint('hello from the app')\nos.getpid()")
        check("a cell's stream and result come back", status == "ok" and text == "hello from the app\n" and result, (status, text, result))
        check("the cell ran in the app, not here", int(result) != os.getpid(), result)
        if platform:
            status, _, result = run(client, "import sys\nsys.platform")
            where = "the Android device" if platform == "android" else "the iOS simulator"
            check(f"the cell ran on {where}", (status, result) == ("ok", repr(platform)), (status, result))
        run(client, "counter = 41")
        status, _, result = run(client, "counter += 1\ncounter")
        check("state persists across cells", (status, result) == ("ok", "42"), (status, result))
        # The payload root python-multiplatform put on sys.path (its PythonPayload): the staged `python/`
        # directory itself, or, for a payload inside the app's jar, the copy it extracts to
        # `<tmp>/python-multiplatform-payload/<sha256>`.
        status, _, result = run(
            client,
            "import example_py, os, sys\n"
            "root = os.path.dirname(os.path.dirname(os.path.abspath(example_py.__file__)))\n"
            "(root in [os.path.abspath(p) for p in sys.path], os.path.basename(root) == 'python'"
            " or os.path.basename(os.path.dirname(root)) == 'python-multiplatform-payload')",
        )
        check("the app's payload imports inside the app", status == "ok" and result == "(True, True)", (status, result))
        status, _, _ = run(client, "raise ValueError('boom')")
        check("an error is reported as one", status == "error", status)
        client.complete("count", 5)
        matches = client.get_shell_msg(timeout=60)["content"]["matches"]
        check("completion sees the app's namespace", "counter" in matches, matches)
        print("live connection M1 scenarios passed")
        return 0
    finally:
        client.stop_channels()
        manager.shutdown_kernel(now=True)


if __name__ == "__main__":
    sys.exit(main())
