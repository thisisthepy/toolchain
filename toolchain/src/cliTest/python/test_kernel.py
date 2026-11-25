"""toolchain-lite's live-connection client, REPL and proxy kernel (SPEC §2.4), against a fake dev agent.

The fake speaks python-multiplatform's agent protocol (its SPEC L-12): hello with the token, then execute
(streams, a result, done), complete. The CI job `desktop-e2e` runs the same client against the real agent in
the packaged app. Run with ipykernel and jupyter_client available:

    uv run --no-project --with ipykernel --with jupyter_client python -m unittest discover -s toolchain/src/cliTest/python
"""
from __future__ import annotations

import ast
import contextlib
import io
import json
import os
import socket
import sys
import tempfile
import threading
import unittest
from pathlib import Path

HERE = Path(__file__).resolve()
PACKAGE_SOURCE = HERE.parents[2] / "cliMain" / "python"
TOKEN = "t" * 43

_alias = Path(tempfile.mkdtemp(prefix="tcl-pkg-"))
os.symlink(PACKAGE_SOURCE, _alias / "toolchain_lite")
sys.path.insert(0, str(_alias))
os.environ["PYTHONPATH"] = str(_alias) + os.pathsep + os.environ.get("PYTHONPATH", "")

from toolchain_lite.agent import AgentClient, AgentError  # noqa: E402
from toolchain_lite import kernel  # noqa: E402


class FakeAgent:
    """One-connection-at-a-time loopback agent with the L-12 message shapes."""

    def __init__(self):
        self.namespace: dict = {"__name__": "__main__"}
        self.count = 0
        self.server = socket.socket()
        self.server.bind(("127.0.0.1", 0))
        self.server.listen()
        self.port = self.server.getsockname()[1]
        threading.Thread(target=self._serve, daemon=True).start()

    def connection_file(self, directory: Path, token: str = TOKEN) -> Path:
        path = directory / "run.json"
        path.write_text(json.dumps({"host": "127.0.0.1", "port": self.port, "token": token}), encoding="utf-8")
        return path

    def _serve(self):
        while True:
            try:
                conn, _ = self.server.accept()
            except OSError:
                return
            threading.Thread(target=self._handle, args=(conn,), daemon=True).start()

    def _handle(self, conn):
        reader = conn.makefile("r", encoding="utf-8")
        send = lambda m: conn.sendall((json.dumps(m) + "\n").encode())  # noqa: E731
        first = json.loads(reader.readline() or "{}")
        if first.get("op") != "hello" or first.get("token") != TOKEN:
            # As the real agent: an AuthError, then the connection closes.
            send({"id": first.get("id"), "type": "error", "ename": "AuthError", "evalue": "hello with the token first"})
            conn.close()
            return
        send({"id": first["id"], "type": "hello", "protocol": 1, "python": "3.15.0", "pid": 4242})
        for line in reader:
            req = json.loads(line)
            rid = req["id"]
            if req["op"] == "execute":
                self.count += 1
                out = io.StringIO()
                try:
                    tree = ast.parse(req["code"])
                    last = tree.body[-1] if tree.body and isinstance(tree.body[-1], ast.Expr) else None
                    body = tree.body[:-1] if last else tree.body
                    with contextlib.redirect_stdout(out):
                        exec(compile(ast.Module(body, []), "<cell>", "exec"), self.namespace)
                        value = eval(compile(ast.Expression(last.value), "<cell>", "eval"), self.namespace) if last else None
                    if out.getvalue():
                        send({"id": rid, "type": "stream", "name": "stdout", "text": out.getvalue()})
                    if value is not None:
                        send({"id": rid, "type": "result", "data": {"text/plain": repr(value)}})
                    send({"id": rid, "type": "done", "status": "ok", "count": self.count})
                except Exception as error:  # noqa: BLE001
                    send({"id": rid, "type": "done", "status": "error", "count": self.count, "ename": type(error).__name__,
                          "evalue": str(error), "traceback": [f"{type(error).__name__}: {error}"]})
            elif req["op"] == "complete":
                prefix = req["code"][: req["cursor_pos"]].split()[-1] if req["code"].strip() else ""
                matches = sorted(n for n in self.namespace if n.startswith(prefix))
                send({"id": rid, "type": "done", "status": "ok", "matches": matches,
                      "cursor_start": req["cursor_pos"] - len(prefix), "cursor_end": req["cursor_pos"]})

    def close(self):
        self.server.close()


class AgentClientTest(unittest.TestCase):
    def setUp(self):
        self.agent = FakeAgent()
        self.dir = Path(tempfile.mkdtemp())

    def tearDown(self):
        self.agent.close()

    def test_execute_streams_results_errors_and_a_persistent_namespace(self):
        with AgentClient.from_connection_file(self.agent.connection_file(self.dir)) as client:
            self.assertEqual(4242, client.hello["pid"])
            messages = list(client.execute("x = 20\nprint('hi')\nx + 22"))
            self.assertEqual(["stream", "result", "done"], [m["type"] for m in messages])
            self.assertEqual("hi\n", messages[0]["text"])
            self.assertEqual("42", messages[1]["data"]["text/plain"])
            self.assertEqual("ok", list(client.execute("x"))[-1]["status"])
            error = list(client.execute("1 / 0"))[-1]
            self.assertEqual(("error", "ZeroDivisionError"), (error["status"], error["ename"]))

    def test_a_wrong_token_or_no_agent_fails_with_a_reason(self):
        with self.assertRaisesRegex(AgentError, "refused the request: AuthError"):
            AgentClient.from_connection_file(self.agent.connection_file(self.dir, token="u" * 43)).connect()
        free = socket.socket()
        free.bind(("127.0.0.1", 0))
        port = free.getsockname()[1]
        free.close()
        with self.assertRaisesRegex(AgentError, "no dev agent"):
            AgentClient("127.0.0.1", port, TOKEN, timeout=2).connect()

    def test_console_runs_lines_and_blocks(self):
        stdin = io.StringIO("y = 2\nfor i in range(2):\n    print(i * y)\n\ny * 21\n")
        stdout = io.StringIO()
        self.assertEqual(0, kernel.console(self.agent.connection_file(self.dir), stdin=stdin, stdout=stdout))
        self.assertIn("0\n2\n", stdout.getvalue())
        self.assertIn("42", stdout.getvalue())

    def test_kernel_spec_runs_this_interpreter_with_the_agent_file(self):
        spec = kernel.kernel_spec(self.dir / "run.json", "app (running)")
        self.assertEqual([sys.executable, "-m", "toolchain_lite.kernel", "run", "--agent"], spec["argv"][:5])
        self.assertEqual("{connection_file}", spec["argv"][-1])


class ProxyKernelTest(unittest.TestCase):
    """The proxy kernel under a real Jupyter client: cells run in the (fake) app."""

    def test_jupyter_client_executes_completes_and_reports_errors_through_the_proxy(self):
        try:
            from jupyter_client.manager import start_new_kernel
        except ImportError:
            self.skipTest("jupyter_client and ipykernel are needed (uv run --with ipykernel --with jupyter_client)")
        agent = FakeAgent()
        directory = Path(tempfile.mkdtemp())
        prefix = directory / "jupyter"
        kernel.install(agent.connection_file(directory), "tcl-test", "test app", prefix=str(prefix))
        os.environ["JUPYTER_PATH"] = str(prefix / "share" / "jupyter")
        manager, client = start_new_kernel(kernel_name="tcl-test")
        try:
            outputs = []
            reply = client.execute_interactive("z = 6\nprint('in app')\nz * 7", output_hook=outputs.append, timeout=30)
            self.assertEqual("ok", reply["content"]["status"])
            kinds = [(m["msg_type"], m["content"].get("text") or m["content"].get("data", {}).get("text/plain")) for m in outputs
                     if m["msg_type"] in ("stream", "execute_result")]
            self.assertIn(("stream", "in app\n"), kinds)
            self.assertIn(("execute_result", "42"), kinds)
            self.assertEqual("error", client.execute_interactive("undefined_name", timeout=30)["content"]["status"])
            client.complete("z", 1)
            complete = client.get_shell_msg(timeout=30)
            self.assertIn("z", complete["content"]["matches"])
        finally:
            client.stop_channels()
            manager.shutdown_kernel(now=True)
            agent.close()


if __name__ == "__main__":
    unittest.main()
