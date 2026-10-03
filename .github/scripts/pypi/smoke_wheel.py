#!/usr/bin/env python3
"""Install a built wheel into a fresh venv and check that what a PyPI user runs works.

    python3 .github/scripts/pypi/smoke_wheel.py dist/toolchain_lite-<v>-<tag>.whl

Adapted from pypackpack's smoke_wheel.py (pypackpack #53). Fails (exit 1) unless, from that venv:
  1. `tcl --help` exits 0 and prints the usage line;
  2. `tcl --version` and `python -m toolchain_lite --version` print "tcl <pyproject.toml's version>";
  3. `tcl install six` in an empty directory creates a pyproject.toml that depends on six (a real
     install, which needs uv: tcl finds it on PATH or pypackpack downloads it into ~/.pypackpack/uv).
"""
from __future__ import annotations

import os
import subprocess
import sys
import tempfile
import tomllib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


def check(cmd: list[str], expect: str | None, cwd: Path | None = None) -> None:
    result = subprocess.run(cmd, cwd=cwd, capture_output=True, text=True)
    shown = " ".join(Path(c).name if i == 0 else c for i, c in enumerate(cmd))
    if result.returncode != 0 or (expect and expect not in result.stdout):
        print(f"FAIL {shown}: exit {result.returncode}\n{result.stdout}{result.stderr}")
        sys.exit(1)
    print(f"ok   {shown}")


def main() -> int:
    wheel = Path(sys.argv[1]).resolve()
    version = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))["project"]["version"]
    with tempfile.TemporaryDirectory() as tmp:
        venv = Path(tmp) / "venv"
        subprocess.run([sys.executable, "-m", "venv", str(venv)], check=True)
        bin_dir = venv / ("Scripts" if os.name == "nt" else "bin")
        exe = ".exe" if os.name == "nt" else ""
        python = bin_dir / f"python{exe}"
        subprocess.run([str(python), "-m", "pip", "install", "--quiet", "--no-index", str(wheel)], check=True)

        tcl = str(bin_dir / f"tcl{exe}")
        check([tcl, "--help"], "Usage: tcl install <package>")
        expected = f"tcl {version}"
        check([tcl, "--version"], expected)
        check([str(python), "-m", "toolchain_lite", "--version"], expected)

        work = Path(tmp) / "work"
        work.mkdir()
        check([tcl, "install", "six"], None, cwd=work)
        pyproject = work / "pyproject.toml"
        if not pyproject.is_file() or "six" not in pyproject.read_text(encoding="utf-8"):
            print("FAIL tcl install six: no pyproject.toml that depends on six")
            return 1
        print("ok   tcl install six wrote pyproject.toml")
    return 0


if __name__ == "__main__":
    sys.exit(main())
