#!/usr/bin/env python3
"""Small checks against python-multiplatform's dev agent for the live-connection e2e scripts (toolchain#150).

    python3 agent_check.py wait <agent.json> [seconds]    # until the agent answers hello (exit 1 on timeout)
    python3 agent_check.py eval <agent.json> <code> <expected repr>

`wait` exists because a forward (adb) or a just-launched app accepts nothing useful before the agent listens.
`eval` runs <code> in the app's `__main__` and exits 0 only if its result is exactly <expected repr>.
Stdlib only.
"""
from __future__ import annotations

import json
import socket
import sys
import time


def _ask(stream, reader, message):
    stream.sendall((json.dumps(message) + "\n").encode())
    replies = []
    while True:
        line = reader.readline()
        if not line:
            raise OSError("the agent closed the connection")
        reply = json.loads(line)
        if reply.get("id") == message["id"]:
            replies.append(reply)
            if reply.get("type") in ("hello", "done", "error"):
                return replies


def _hello(connection, timeout):
    stream = socket.create_connection((connection["host"], connection["port"]), timeout=timeout)
    reader = stream.makefile()
    replies = _ask(stream, reader, {"op": "hello", "id": 1, "token": connection["token"]})
    if replies[-1].get("type") != "hello":
        raise OSError(f"the agent refused hello: {replies[-1]}")
    return stream, reader


def main() -> int:
    command, connection = sys.argv[1], json.load(open(sys.argv[2]))
    if command == "wait":
        deadline = time.monotonic() + (float(sys.argv[3]) if len(sys.argv) > 3 else 90)
        while time.monotonic() < deadline:
            try:
                stream, _ = _hello(connection, timeout=2)
                stream.close()
                return 0
            except (OSError, ValueError):
                time.sleep(1)
        print("the app's dev agent never answered hello")
        return 1
    if command == "eval":
        code, expected = sys.argv[3], sys.argv[4]
        stream, reader = _hello(connection, timeout=30)
        with stream:
            replies = _ask(stream, reader, {"op": "execute", "id": 2, "code": code})
        values = [r["data"]["text/plain"] for r in replies if r.get("type") == "result"]
        print(f"{code!r} in the app: {values}")
        return 0 if values == [expected] else 1
    print(__doc__)
    return 2


if __name__ == "__main__":
    sys.exit(main())
