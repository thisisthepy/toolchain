from __future__ import annotations

from toolchain.common.environ import EnvType, envs
from toolchain.android.environ import Android
from toolchain.darwin.environ import Darwin
from toolchain.linux.environ import Linux
from toolchain.mingw.environ import MinGW

from toolchain.logger import info, error
import argparse
import sys


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
        info("a list of platforms [" + ", ".join(set([env.value for env in envs if env is not None])) + "] are found.\n")
        self.Host = envs[EnvType.HOST]
        self.Android = envs[EnvType.ANDROID.value]
        self.IOS = envs[EnvType.IOS.value]
        from toolchain.settings import Settings
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
            error("Unrecognized command. Exiting...")
            parser.print_help()
            exit(1)
        getattr(target_os, argv.command)()

    def ios(self):
        if not self.IOS:
            error("iOS target is not initialized yet. Exiting...")
            exit(1)
        self.override_args()
        self.run(target_os=self.IOS)

    def android(self):
        if not self.Android:
            error("Android target is not initialized yet. Exiting...")
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
