try:
    from toolchain.buildtarget import version, target
except ImportError:
    raise FileNotFoundError("ERROR: Could not find python build target version setting file."
                            + " Please run 'toolchain_targetver' and 'toolchain_targetos' first.")

from os.path import join
from os import getcwd


class Settings:
    target_version = version
    target_version_short = '.'.join(version.split('.')[0:2])
    target_os = target

    work_dir = join(join(getcwd(), "dist"), "toolchain")

    source_url = f"https://www.python.org/ftp/python/{version}/Python-{version}.tgz"

    class BinaryURL:
        mingw = ("https://repo.msys2.org/mingw/mingw64/", f"mingw-w64-x86_64-python-{version}", "-any.pkg.tar.zst")
        linux = ("", )

    binary_url = BinaryURL
