from __future__ import annotations

import os
import sys
import platform
from subprocess import run
from os.path import join, exists
from enum import Enum
from toolchain.logger import info

_SYSTEM = platform.system().lower()


class EnvType(Enum):
    IOS = 'ios'
    DARWIN = 'darwin'
    LINUX = 'linux'
    ANDROID = 'android'
    MINGW = 'windows'
    WASM = 'wasm'

    @staticmethod
    def get_platform():
        for sys in (EnvType.DARWIN, EnvType.LINUX, EnvType.MINGW):
            if sys.value == _SYSTEM:
                EnvType.HOST = _SYSTEM
                return sys
        raise NotImplementedError(f"ERROR: Current system <{_SYSTEM}> is not supported by this toolchain yet.")


envs = {
    EnvType.IOS: None,
    EnvType.DARWIN: None,
    EnvType.LINUX: None,
    EnvType.ANDROID: None,
    EnvType.MINGW: None,
    EnvType.WASM: None
}


class Environment:
    """Build Environment"""
    env_type = None

    @classmethod
    def register_env(cls):
        if cls.is_initialized():
            envs[cls.env_type] = cls

    work_path = os.getcwd()

    @classmethod
    def get_abs_path(cls, path: str):
        return join(cls.work_path, path)

    build_dir = ""
    dist_dir = ""

    pip_path = ""
    lib_dir = ""
    site_dir = ""

    @classmethod
    def is_initialized(cls):
        return exists(cls.get_abs_path(cls.build_dir)) and exists(cls.dist_dir)

    @classmethod
    def init(cls, task=lambda: None, init_package=("python3", "openssl")):
        if cls.is_initialized():
            info(f"Build Environment for {cls.env_type.value} is found.")
        else:
            argv_backup = sys.argv
            sys.argv = [sys.argv[0], "build"] + list(init_package)
            result = task()
            sys.argv = argv_backup
            return result

    @classmethod
    def build(cls, task=None):
        if task is None:
            task = cls.pip
        argv_backup = sys.argv
        sys.argv = [sys.argv[0], "pip", "install"] + sys.argv[2:]
        result = task()
        sys.argv = argv_backup
        return result

    @classmethod
    def clean(cls, task=None):
        if task is None:
            task = cls.pip
        argv_backup = sys.argv
        sys.argv = [sys.argv[0], "pip", "uninstall"] + sys.argv[2:]
        result = task()
        sys.argv = argv_backup
        return result

    @classmethod
    def pip(cls):
        args = [cls.pip_path] + sys.argv[2:] + ["--isolated", "--prefix", cls.dist_dir]
        return run(" ".join(args), check=True, shell=True).returncode

    @classmethod
    def pip3(cls):
        return cls.pip()
