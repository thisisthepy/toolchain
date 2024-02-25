from os.path import join
from os import getcwd

from toolchain.buildtarget import version, target

if not version or not target:
    raise RuntimeError("ERROR: Could not find python build target version setting file."
                       " Please run 'toolchain_targetver' and 'toolchain_targetos' first.")


class Settings:
    target_version = version
    target_version_short = '.'.join(version.split('.')[0:2])
    target_os = target

    root_dir = getcwd()
    cache_dir = join(root_dir, ".cache")
    build_dir = join(join(root_dir, "build"), "toolchain")
    work_dir = join(join(root_dir, "dist"), "toolchain")

    source_url = f"https://www.python.org/ftp/python/{version}/Python-{version}.tgz"

    class BinaryURL:
        mingw = ("https://repo.msys2.org/mingw/mingw64/", f"mingw-w64-x86_64-python-{version}", "-any.pkg.tar.zst")
        linux = ("", )

    binary_url = BinaryURL
