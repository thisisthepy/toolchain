#!/usr/bin/env python3
"""The TypedPython gate's wheels for the toolchain test job (docs/SPEC.md §1.18; team lead 2026-11-27).

    python3 .github/scripts/typedpython/gate_wheels.py <out dir>

The gate tests run with `-Ptypedpython.wheelDir=<out dir>`, which needs the typedpython wheel and the pyrefly wheel
for this machine. Both come from the pinned gate, not from a second source of truth:
- the coordinates are `DEFAULT_TYPEDPYTHON_GATE` in toolchain's TypedpythonGate.kt;
- its `typedpython-gate` zip is in ~/.m2 (the job publishes python-multiplatform-ksp first). The zip holds the
  typedpython wheel and `typedpython-gate.json`;
- pyrefly is downloaded from PyPI at the manifest's version, and only a file name and SHA-256 the manifest lists is
  accepted.
Exit 1, naming what is missing, if any of these fails. Stdlib only.
"""
from __future__ import annotations

import hashlib
import json
import platform
import re
import sys
import urllib.request
import zipfile
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
GATE_SOURCE = REPO / "toolchain/src/main/kotlin/org/thisisthepy/python/multiplatform/toolchain/typedpython/TypedpythonGate.kt"
MANIFEST = "typedpython-gate.json"


def fail(message: str) -> None:
    print(f"gate_wheels: {message}", file=sys.stderr)
    sys.exit(1)


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def pyrefly_matches(name: str) -> bool:
    """Whether the pyrefly wheel [name] is the one for this machine (the CI runners: Linux x86_64, macOS arm64)."""
    machine = platform.machine().lower()
    if sys.platform.startswith("linux"):
        return "manylinux" in name and (machine in name or (machine == "amd64" and "x86_64" in name))
    if sys.platform == "darwin":
        return "macosx" in name and ("arm64" in name if machine == "arm64" else "x86_64" in name)
    return False


def main() -> None:
    if len(sys.argv) != 2:
        fail(__doc__)
    out = Path(sys.argv[1]).resolve()
    out.mkdir(parents=True, exist_ok=True)

    match = re.search(r'DEFAULT_TYPEDPYTHON_GATE\s*=\s*"([^":]+):([^":]+):([^"]+)"', GATE_SOURCE.read_text(encoding="utf-8"))
    if not match:
        fail(f"no DEFAULT_TYPEDPYTHON_GATE in {GATE_SOURCE}")
    group, artifact, version = match.groups()
    zip_path = Path.home() / ".m2/repository" / group.replace(".", "/") / artifact / version / f"{artifact}-{version}-typedpython-gate.zip"
    if not zip_path.is_file():
        fail(f"{zip_path} is missing: publish python-multiplatform-ksp to mavenLocal first")

    with zipfile.ZipFile(zip_path) as gate:
        manifest = json.loads(gate.read(MANIFEST))
        if str(manifest.get("format")) != "1":
            fail(f"{MANIFEST} format {manifest.get('format')!r}, expected 1")
        wheel_name = manifest["typedpython"]["wheel"]
        wheel = gate.read(wheel_name)
    if sha256(wheel) != manifest["typedpython"]["sha256"].lower():
        fail(f"{wheel_name} in {zip_path} does not match the manifest's SHA-256")
    (out / Path(wheel_name).name).write_bytes(wheel)

    pyrefly_version = manifest["pyrefly"]["version"]
    allowed = {name: digest.lower() for name, digest in manifest["pyrefly"]["sha256"].items() if pyrefly_matches(name)}
    if len(allowed) != 1:
        fail(f"the manifest lists {len(allowed)} pyrefly {pyrefly_version} wheels for {sys.platform}/{platform.machine()}: {sorted(allowed)}")
    (name, digest), = allowed.items()
    release = json.load(urllib.request.urlopen(f"https://pypi.org/pypi/pyrefly/{pyrefly_version}/json", timeout=60))
    url = next((u["url"] for u in release["urls"] if u["filename"] == name), None)
    if url is None:
        fail(f"PyPI has no {name}")
    data = urllib.request.urlopen(url, timeout=300).read()
    if sha256(data) != digest:
        fail(f"{name} from PyPI does not match the manifest's SHA-256")
    (out / name).write_bytes(data)
    print(f"gate_wheels: {group}:{artifact}:{version} -> {Path(wheel_name).name} and {name} in {out}")


if __name__ == "__main__":
    main()
