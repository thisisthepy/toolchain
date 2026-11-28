"""A client for python-multiplatform's dev agent (its SPEC L-12; toolchain docs/design/live-connection.md, B).

The agent runs inside a debug build of the app and listens on loopback. This side speaks its protocol:
one JSON object per line, each carrying the request's `id`; the first message is `hello` with the token.

    with AgentClient.from_connection_file("build/pythonDevAgent/run.json") as agent:
        for message in agent.execute("print(1 + 1)"):
            ...

Stdlib only: `tcl connect` needs nothing else. The proxy kernel (`toolchain_lite.kernel`) builds on it.
"""
from __future__ import annotations

import itertools
import json
import socket
from pathlib import Path
from typing import Iterator

PROTOCOL = 1


class AgentError(Exception):
    """The agent refused the connection, closed it, or answered something this client does not understand."""


class AgentClient:
    def __init__(self, host: str, port: int, token: str, timeout: float = 30.0):
        self.host = host
        self.port = port
        self.token = token
        self.timeout = timeout
        self._ids = itertools.count(1)
        self._socket: socket.socket | None = None
        self._reader = None
        self.hello: dict | None = None

    @classmethod
    def from_connection_file(cls, path: str | Path, timeout: float = 30.0) -> "AgentClient":
        """The file toolchain writes for a desktop run (`build/pythonDevAgent/<task>.json`: host, port, token)."""
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        try:
            return cls(data.get("host", "127.0.0.1"), int(data["port"]), data["token"], timeout)
        except (KeyError, ValueError) as error:
            raise AgentError(f"{path} is not a dev agent connection file (host, port, token): {error}") from error

    def connect(self) -> dict:
        try:
            self._socket = socket.create_connection((self.host, self.port), timeout=self.timeout)
        except OSError as error:
            raise AgentError(
                f"no dev agent at {self.host}:{self.port} ({error}). Is the app running from a debug build "
                "with enableHotReload, started by its Gradle run task or with PYTHON_MULTIPLATFORM_DEV_AGENT set?"
            ) from error
        self._reader = self._socket.makefile("r", encoding="utf-8", newline="\n")
        replies = list(self._request({"op": "hello", "token": self.token}, until="hello"))
        self.hello = replies[-1]
        if self.hello.get("protocol") != PROTOCOL:
            raise AgentError(f"the agent speaks protocol {self.hello.get('protocol')}, this client {PROTOCOL}")
        return self.hello

    def close(self) -> None:
        for closable in (self._reader, self._socket):
            if closable is not None:
                try:
                    closable.close()
                except OSError:
                    pass
        self._reader = self._socket = None

    def __enter__(self) -> "AgentClient":
        self.connect()
        return self

    def __exit__(self, *exc) -> None:
        self.close()

    def execute(self, code: str) -> Iterator[dict]:
        """Every `stream` and `result` message, then the `done` message (status, count, and on error the traceback)."""
        return self._request({"op": "execute", "code": code})

    def complete(self, code: str, cursor_pos: int) -> dict:
        return list(self._request({"op": "complete", "code": code, "cursor_pos": cursor_pos}))[-1]

    def push(self, files: dict[str, str]) -> dict:
        """Write files into the app's overlay and re-import the modules among them (hot reload)."""
        return list(self._request({"op": "push", "files": files}))[-1]

    def _request(self, message: dict, until: str = "done") -> Iterator[dict]:
        if self._socket is None:
            raise AgentError("not connected")
        request_id = next(self._ids)
        self._socket.sendall((json.dumps({**message, "id": request_id}) + "\n").encode("utf-8"))
        while True:
            line = self._reader.readline()
            if not line:
                raise AgentError("the agent closed the connection (a wrong token closes it at once)")
            reply = json.loads(line)
            if reply.get("id") != request_id:
                continue
            if reply.get("type") == "error":
                # The agent's refusal (a wrong token: AuthError), sent before it closes the connection.
                raise AgentError(f"the agent refused the request: {reply.get('ename')}: {reply.get('evalue')}")
            yield reply
            if reply.get("type") == until:
                return
