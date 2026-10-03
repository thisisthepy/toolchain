#!/usr/bin/env python3
"""Assemble the platform wheel that carries the native `tcl` binary (publish-pypi.yml).

    python3 .github/scripts/pypi/build_wheel.py --binary toolchain/build/native/nativeCompile/tcl --out dist

Adapted from pypackpack's .github/scripts/pypi/build_wheel.py (pypackpack #53). The wheel holds:
  toolchain_lite/__init__.py, __main__.py    toolchain/src/cliMain/python/ (`python -m toolchain_lite`)
  toolchain_lite-<v>.data/scripts/tcl        the native binary (tcl.exe on Windows)
  dist-info: METADATA from pyproject.toml's [project], LICENSE, WHEEL, RECORD

The platform tag is read from the binary, never assumed: the highest GLIBC_x.y symbol it needs on
Linux (manylinux_x_y_<arch>), its LC_BUILD_VERSION minos on macOS (macosx_<maj>_<min>_<arch>),
win_amd64 / win_arm64 on Windows. On Linux every NEEDED library must be in ALLOWED_LINUX_LIBS.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import platform
import re
import subprocess
import sys
import tomllib
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
PACKAGE_SRC = ROOT / "toolchain/src/cliMain/python"
IMPORT_NAME = "toolchain_lite"
BINARY = "tcl"
# glibc's own libraries plus libz, which every glibc distribution ships and Native Image links.
ALLOWED_LINUX_LIBS = {
    "libc.so.6", "libm.so.6", "libdl.so.2", "libpthread.so.0", "librt.so.1", "libz.so.1",
    "ld-linux-x86-64.so.2", "ld-linux-aarch64.so.1",
}
FIXED_DATE = (1980, 1, 1, 0, 0, 0)


def run(*cmd: str) -> str:
    return subprocess.run(cmd, check=True, capture_output=True, text=True).stdout


def linux_tag(binary: Path) -> str:
    needed = set(re.findall(r"\(NEEDED\)\s+Shared library: \[([^\]]+)\]", run("readelf", "-d", str(binary))))
    extra = needed - ALLOWED_LINUX_LIBS
    if extra:
        sys.exit(f"{binary} needs libraries a manylinux wheel may not assume: {sorted(extra)}")
    versions = {tuple(map(int, v)) for v in re.findall(r"GLIBC_(\d+)\.(\d+)", run("objdump", "-T", str(binary)))}
    major, minor = max(versions) if versions else (2, 17)
    machine = platform.machine().lower()
    arch = {"amd64": "x86_64", "arm64": "aarch64"}.get(machine, machine)
    return f"manylinux_{major}_{minor}_{arch}"


def macos_tag(binary: Path) -> str:
    match = re.search(r"cmd LC_BUILD_VERSION.*?minos (\d+)\.(\d+)", run("otool", "-l", str(binary)), re.S)
    if not match:
        sys.exit(f"{binary} has no LC_BUILD_VERSION; cannot tell its minimum macOS")
    major, minor = int(match[1]), int(match[2])
    if major >= 11:
        minor = 0  # pip matches macosx_11+ tags on the major version only
    arch = "arm64" if platform.machine() == "arm64" else "x86_64"
    return f"macosx_{major}_{minor}_{arch}"


def windows_tag() -> str:
    return "win_arm64" if platform.machine().lower() in ("arm64", "aarch64") else "win_amd64"


def platform_tag(binary: Path) -> str:
    if sys.platform.startswith("linux"):
        return linux_tag(binary)
    if sys.platform == "darwin":
        return macos_tag(binary)
    if sys.platform == "win32":
        return windows_tag()
    sys.exit(f"no wheel platform tag for {sys.platform}")


def metadata(project: dict) -> str:
    lines = [
        "Metadata-Version: 2.4",
        f"Name: {project['name']}",
        f"Version: {project['version']}",
        f"Summary: {project['description']}",
    ]
    for author in project.get("authors", []):
        lines.append(f"Author-email: {author['name']} <{author['email']}>")
    lines.append(f"License-Expression: {project['license']}")
    lines += [f"License-File: {f}" for f in project.get("license-files", [])]
    lines += [f"Classifier: {c}" for c in project.get("classifiers", [])]
    if project.get("keywords"):
        lines.append(f"Keywords: {','.join(project['keywords'])}")
    lines.append(f"Requires-Python: {project['requires-python']}")
    lines += [f"Project-URL: {label}, {url}" for label, url in project.get("urls", {}).items()]
    lines.append("Description-Content-Type: text/markdown")
    readme = (ROOT / project["readme"]).read_text(encoding="utf-8")
    return "\n".join(lines) + "\n\n" + readme


def record_line(arcname: str, data: bytes) -> str:
    digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode()
    return f"{arcname},sha256={digest},{len(data)}"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    project = tomllib.loads((ROOT / "pyproject.toml").read_text(encoding="utf-8"))["project"]
    name, version = project["name"], project["version"]
    dist = re.sub(r"[-_.]+", "_", name).lower()  # wheel and dist-info names use the normalized form
    tag = f"py3-none-{platform_tag(args.binary)}"
    dist_info = f"{dist}-{version}.dist-info"
    exe = f"{BINARY}.exe" if sys.platform == "win32" else BINARY

    files: list[tuple[str, bytes, int]] = []  # (arcname, data, unix mode)
    for source in sorted(PACKAGE_SRC.glob("*.py")):
        files.append((f"{IMPORT_NAME}/{source.name}", source.read_bytes(), 0o644))
    files.append((f"{dist}-{version}.data/scripts/{exe}", args.binary.read_bytes(), 0o755))
    files.append((f"{dist_info}/METADATA", metadata(project).encode(), 0o644))
    for license_file in project.get("license-files", []):
        files.append((f"{dist_info}/licenses/{license_file}", (ROOT / license_file).read_bytes(), 0o644))
    scripts = "".join(f"{k} = {v}\n" for k, v in project.get("scripts", {}).items())
    if scripts:
        files.append((f"{dist_info}/entry_points.txt", f"[console_scripts]\n{scripts}".encode(), 0o644))
    wheel = f"Wheel-Version: 1.0\nGenerator: toolchain build_wheel.py\nRoot-Is-Purelib: false\nTag: {tag}\n"
    files.append((f"{dist_info}/WHEEL", wheel.encode(), 0o644))
    record = "\n".join(record_line(a, d) for a, d, _ in files) + f"\n{dist_info}/RECORD,,\n"
    files.append((f"{dist_info}/RECORD", record.encode(), 0o644))

    args.out.mkdir(parents=True, exist_ok=True)
    path = args.out / f"{dist}-{version}-{tag}.whl"
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as archive:
        for arcname, data, mode in files:
            info = zipfile.ZipInfo(arcname, FIXED_DATE)
            info.external_attr = (0o100000 | mode) << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, data)
    print(path)
    return 0


if __name__ == "__main__":
    sys.exit(main())
