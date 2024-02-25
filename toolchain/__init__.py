from logger import info, error
import sys
import os


def run_toolchain():
    from toolchain.main import Toolchain
    if sys.argv[1] == "init":
        Toolchain.init()
    else:
        toolchain = Toolchain()
        toolchain.run()


def set_build_target(tag: str, content=""):
    proj_path = os.path.abspath(os.path.dirname(__file__))
    file_path = os.path.join(proj_path, "buildtarget.py")

    f = open(file_path, "r")
    lines = f.readlines()
    f.close()

    new_lines = []
    for line in lines:
        if tag + " = " in line:
            new_lines.append(f"{tag} = {content}")
        else:
            new_lines.append(line)

    with open(file_path, "w+") as f:
        f.writelines(new_lines)


def set_target_build_version():
    v = sys.argv[1].lower().removeprefix("python")
    if v.count('.') != 2:
        error(" Version string should be like '3.11.8'")
        exit(1)
    from toolchain.buildtarget import version
    if version is not None:
        error(" Build target version is already set.")
        exit(2)
    set_build_target("version", f"\"{v}\"")
    info(f" Build target version is set to {version}")


def set_target_os():
    supported_os = ("host", "android", "ios")
    args = sys.argv[1:]
    targets = list(set([tg for tg in args if tg.lower() in supported_os]))
    if len(targets) != len(args):
        error(f" Unknown target os detected. This tool only support for package build for os type {supported_os}")
        exit(1)
    set_build_target("target", str(targets))
    info(f" Build target version is set to {targets}")
