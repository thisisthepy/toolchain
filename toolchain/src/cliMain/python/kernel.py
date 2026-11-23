"""The proxy kernel: an ordinary Jupyter kernel on the laptop that runs every cell inside the running app.

toolchain docs/design/live-connection.md (B) and SPEC §2.4. JupyterLab, VS Code or PyCharm talk to this
kernel as to any other; it forwards `execute_request`, `complete_request` and `inspect_request` to the app's
dev agent (python-multiplatform SPEC L-12) over one loopback socket and relays the agent's streams, results
and errors back as Jupyter messages. Needs `ipykernel` (the `kernel` extra: `uv tool install
'toolchain-lite[kernel]'`).

    python -m toolchain_lite.kernel install <connection file> [--name NAME] [--display-name TEXT]
    python -m toolchain_lite.kernel run --agent <connection file> -f <jupyter connection file>
    python -m toolchain_lite.kernel console <connection file>      (a plain REPL; stdlib only)

`tcl kernel install` and `tcl connect` run the first and the last.
"""
from __future__ import annotations

import argparse
import json
import sys
import tempfile
from pathlib import Path

from .agent import AgentClient, AgentError


def _kernel_class():
    from ipykernel.kernelbase import Kernel

    class ProxyKernel(Kernel):
        implementation = "toolchain-lite-proxy"
        implementation_version = "1"
        language = "python"
        language_version = "3"
        language_info = {"name": "python", "mimetype": "text/x-python", "file_extension": ".py"}
        banner = "toolchain-lite proxy kernel: cells run inside the running app (python-multiplatform dev agent)"
        agent_file: str = ""

        def __init__(self, **kwargs):
            super().__init__(**kwargs)
            self.agent = AgentClient.from_connection_file(self.agent_file)
            hello = self.agent.connect()
            self.banner += f"\nconnected to pid {hello.get('pid')}, Python {hello.get('python')}"

        def do_execute(self, code, silent, store_history=True, user_expressions=None, allow_stdin=False):
            done = {"status": "ok", "count": self.execution_count}
            try:
                for message in self.agent.execute(code):
                    kind = message.get("type")
                    if kind == "stream" and not silent:
                        self.send_response(self.iopub_socket, "stream", {"name": message["name"], "text": message["text"]})
                    elif kind == "result" and not silent:
                        self.send_response(
                            self.iopub_socket,
                            "execute_result",
                            {"execution_count": self.execution_count, "data": message["data"], "metadata": {}},
                        )
                    elif kind == "done":
                        done = message
            except AgentError as error:
                done = {"status": "error", "ename": "AgentError", "evalue": str(error), "traceback": [str(error)]}
            if done.get("status") == "error":
                content = {"ename": done.get("ename", ""), "evalue": done.get("evalue", ""), "traceback": done.get("traceback", [])}
                if not silent:
                    self.send_response(self.iopub_socket, "error", content)
                return {"status": "error", "execution_count": self.execution_count, **content}
            return {"status": "ok", "execution_count": self.execution_count, "payload": [], "user_expressions": {}}

        def do_complete(self, code, cursor_pos):
            try:
                reply = self.agent.complete(code, cursor_pos)
            except AgentError:
                return {"status": "ok", "matches": [], "cursor_start": cursor_pos, "cursor_end": cursor_pos, "metadata": {}}
            return {
                "status": "ok",
                "matches": reply.get("matches", []),
                "cursor_start": reply.get("cursor_start", cursor_pos),
                "cursor_end": reply.get("cursor_end", cursor_pos),
                "metadata": {},
            }

        def do_inspect(self, code, cursor_pos, detail_level=0, omit_sections=()):
            # The agent has no inspect op (L-12): answer from `help()`-style text produced in the app.
            name = _name_at(code, cursor_pos)
            if not name:
                return {"status": "ok", "found": False, "data": {}, "metadata": {}}
            text = []
            try:
                for message in self.agent.execute(f"import pydoc as __tcl_pydoc\nprint(__tcl_pydoc.render_doc({name}, renderer=__tcl_pydoc.plaintext))"):
                    if message.get("type") == "stream" and message.get("name") == "stdout":
                        text.append(message["text"])
                    elif message.get("type") == "done" and message.get("status") != "ok":
                        return {"status": "ok", "found": False, "data": {}, "metadata": {}}
            except AgentError:
                return {"status": "ok", "found": False, "data": {}, "metadata": {}}
            return {"status": "ok", "found": True, "data": {"text/plain": "".join(text)}, "metadata": {}}

        def do_shutdown(self, restart):
            self.agent.close()
            return {"status": "ok", "restart": restart}

    return ProxyKernel


def _name_at(code: str, cursor_pos: int) -> str:
    """The dotted name under the cursor (`os.path.jo|in` -> `os.path.join`), or empty."""
    left = code[:cursor_pos]
    right = code[cursor_pos:]
    allowed = set("abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.")
    start = len(left)
    while start > 0 and left[start - 1] in allowed:
        start -= 1
    end = 0
    while end < len(right) and right[end] in allowed and right[end] != ".":
        end += 1
    return (left[start:] + right[:end]).strip(".")


def kernel_spec(agent_file: Path, display_name: str) -> dict:
    """The `kernel.json` for an agent connection file: this interpreter runs the proxy kernel."""
    return {
        "argv": [sys.executable, "-m", "toolchain_lite.kernel", "run", "--agent", str(agent_file.resolve()), "-f", "{connection_file}"],
        "display_name": display_name,
        "language": "python",
        "metadata": {"toolchain-lite": {"agent": str(agent_file.resolve())}},
    }


def install(agent_file: Path, name: str, display_name: str, user: bool = True, prefix: str | None = None) -> str:
    """Registers the kernelspec with Jupyter and returns where it went."""
    from jupyter_client.kernelspec import KernelSpecManager

    with tempfile.TemporaryDirectory() as tmp:
        spec_dir = Path(tmp) / name
        spec_dir.mkdir()
        (spec_dir / "kernel.json").write_text(json.dumps(kernel_spec(agent_file, display_name), indent=2) + "\n", encoding="utf-8")
        return KernelSpecManager().install_kernel_spec(str(spec_dir), kernel_name=name, user=user and prefix is None, prefix=prefix)


def console(agent_file: Path, stdin=sys.stdin, stdout=sys.stdout) -> int:
    """`tcl connect`: a line REPL into the app (a blank line runs a multi-line cell). Stdlib only."""
    with AgentClient.from_connection_file(agent_file) as agent:
        print(f"connected to pid {agent.hello.get('pid')}, Python {agent.hello.get('python')}. Ctrl-D quits.", file=stdout)
        buffer: list[str] = []
        while True:
            print("... " if buffer else ">>> ", end="", file=stdout, flush=True)
            line = stdin.readline()
            if not line:
                return 0
            line = line.rstrip("\n")
            if buffer:
                if line:
                    buffer.append(line)
                    continue
                code, buffer = "\n".join(buffer), []
            elif line.rstrip().endswith(":"):
                buffer = [line]
                continue
            else:
                code = line
            if not code.strip():
                continue
            for message in agent.execute(code):
                if message.get("type") == "stream":
                    print(message["text"], end="", file=sys.stderr if message["name"] == "stderr" else stdout)
                elif message.get("type") == "result":
                    print(message["data"].get("text/plain", ""), file=stdout)
                elif message.get("type") == "done" and message.get("status") == "error":
                    print("".join(message.get("traceback", [])) or f"{message.get('ename')}: {message.get('evalue')}", file=stdout)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m toolchain_lite.kernel")
    sub = parser.add_subparsers(dest="command", required=True)
    p_install = sub.add_parser("install", help="register a kernelspec for a running app's dev agent")
    p_install.add_argument("agent_file", type=Path)
    p_install.add_argument("--name")
    p_install.add_argument("--display-name")
    p_install.add_argument("--prefix", help="install under this prefix instead of the user's Jupyter directory")
    p_run = sub.add_parser("run", help="run the proxy kernel (Jupyter starts this from the kernelspec)")
    p_run.add_argument("--agent", dest="agent_file", required=True, type=Path)
    p_run.add_argument("-f", dest="connection_file", required=True)
    p_console = sub.add_parser("console", help="a plain REPL into the app")
    p_console.add_argument("agent_file", type=Path)
    args = parser.parse_args(argv)

    try:
        if args.command == "install":
            stem = args.agent_file.stem
            name = args.name or f"tcl-{stem}"
            where = install(args.agent_file, name, args.display_name or f"{stem} (running app)", prefix=args.prefix)
            print(f"installed kernelspec '{name}' in {where}")
            return 0
        if args.command == "console":
            return console(args.agent_file)
        from ipykernel.kernelapp import IPKernelApp

        kernel = _kernel_class()
        kernel.agent_file = str(args.agent_file)
        IPKernelApp.launch_instance(argv=["-f", args.connection_file], kernel_class=kernel)
        return 0
    except AgentError as error:
        print(f"tcl: {error}", file=sys.stderr)
        return 1
    except ImportError as error:
        print(f"tcl: {error.name or error} is missing; install the kernel extra: uv tool install 'toolchain-lite[kernel]'", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
