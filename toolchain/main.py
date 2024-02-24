from __future__ import annotations

from toolchain.common.environ import EnvType, envs
from toolchain.android.environ import Android
from toolchain.darwin.environ import Darwin
from toolchain.linux.environ import Linux
from toolchain.mingw.environ import MinGW

from logging import info, error
import argparse
import sys
import os


parser = argparse.ArgumentParser(
    description="Python package build toolchain",
    usage="""toolchain <command> [<args>]

Available commands:
init          Initialize the build toolchain (build python3, openssl
              and necessary libraries)
build         Build a recipe (compile a library for the required target
              architecture)
clean         Clean the build of the specified recipe
pip           Install a pip dependency into the distribution

Available platforms:
host          Run command only for host platform
android       Run command only for android target platform
ios           Run command only for iOS target platform
""")
parser.add_argument("command", help="Command to run")


class Toolchain:
    """Toolchain CLI"""

    platforms = (Android, Darwin, Linux, MinGW)
    platform = EnvType.get_platform()
    print("\n\nINFO: You are running toolchain on", platform.value, "system.")

    def __init__(self):
        for platform in self.platforms:
            platform.register_env()
        print("INFO: a list of platforms [" + ", ".join([env.value for env in envs if env is not None]) + "] are found.\n")
        self.Host = envs[EnvType.HOST]
        self.Android = envs[EnvType.ANDROID]
        self.IOS = envs[EnvType.IOS]
        from settings import Settings
        self.platforms = []
        for os_name in Settings.target_os:
            if os_name == "host":
                self.platforms.append(self.Host)
            elif os_name == self.Android.value and self.Android is not None:
                self.platforms.append(self.Android)
            elif os_name == self.IOS.value and self.IOS is not None:
                self.platforms.append(self.IOS)

    @classmethod
    def init(cls):
        for platform in cls.platforms:
            platform.init()

    original_args = sys.argv[:]
    args = original_args[2:].insert(0, original_args[0])

    @classmethod
    def override_args(cls):
        sys.argv = cls.args

    def run(self, target_os=None):
        if target_os is None:
            target_os = self
        argv = parser.parse_args(sys.argv[1:2])
        if not hasattr(target_os, argv.command):
            error("ERROR: Unrecognized command. Exiting...")
            parser.print_help()
            exit(1)
        getattr(target_os, argv.command)()

    def ios(self):
        if not self.IOS:
            error("ERROR: iOS target is not initialized yet. Exiting...")
            exit(1)
        self.override_args()
        self.run(target_os=self.IOS)

    def android(self):
        if not self.Android:
            error("ERROR: Android target is not initialized yet. Exiting...")
            exit(1)
        self.override_args()
        self.run(target_os=self.Android)

    def host(self):
        self.override_args()
        self.run(target_os=self.Host)

    def build(self):
        for platform in self.platforms:
            platform.build()

    def clean(self):
        for platform in self.platforms:
            platform.clean()

    def pip(self):
        for platform in self.platforms:
            platform.pip()

    def pip3(self):
        self.pip()


def run_toolchain():
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
