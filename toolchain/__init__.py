from logging import info, error
import sys
import os


def run_toolchain():
    from toolchain.main import Toolchain
    if sys.argv[1] == "init":
        Toolchain.init()
    else:
        toolchain = Toolchain()
        toolchain.run()


def set_target_build_version():
    version = sys.argv[1].lower().removeprefix("python")
    if version.count('.') != 2:
        error("ERROR: Version string should be like '3.11.8'")
        exit(1)
    proj_path = os.path.abspath(os.path.dirname(__file__))
    file_path = os.path.join(proj_path, "buildtarget.py")
    if os.path.exists(file_path):
        with open(file_path, "r") as file:
            lines = file.readlines()
            for line in lines:
                if "version" in line:
                    error("ERROR: Build target version is already set.")
                    exit(2)
    with open(file_path, "a+") as file:
        file.write(f"version = {version}")
        info(f"INFO: Build target version is set to {version}")


def set_target_os():
    supported_os = ("host", "android", "ios")
    args = sys.argv[1:]
    targets = set([tg for tg in args if tg.lower() in supported_os])
    if len(targets) != len(args):
        error(f"ERROR: Unknown target os detected. This tool only support for package build for os type {supported_os}")
        exit(1)
    proj_path = os.path.abspath(os.path.dirname(__file__))
    file_path = os.path.join(proj_path, "buildtarget.py")
    with open(file_path, "a+") as file:
        file.write(f"target = [{targets}]")
        info(f"INFO: Build target version is set to [{targets}]")
