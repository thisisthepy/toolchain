from toolchain.common.environ import Environment, EnvType
from toolchain.settings import Settings
from os import remove, makedirs
from os.path import exists
import requests

import tarfile
from toolchain.mingw.extract_zst import extract_zst


class MinGW(Environment):
    """MinGW Build Environment"""
    env_type = EnvType.MINGW

    build_dir = ""
    dist_dir = Settings.work_dir + "/mingw"

    @classmethod
    def is_initialized(cls):
        return exists(cls.dist_dir)

    @classmethod
    def init(cls, *args, **kwargs):
        def task(*_, **__):
            result = None
            name = ""

            for rev in range(1, 10):
                url, filename, ext = Settings.binary_url.mingw
                respond = requests.get(url + filename + f"-{rev}" + ext)
                if respond.status_code == 200:
                    result = respond
                    name = filename + f"-{rev}" + ext
                    continue
                else:
                    break
            if result is None:
                raise FileNotFoundError("ERROR: Cannot fetch MinGW python binary from MSYS2 repository."
                                        + " Please check the url information.")

            file_path = cls.dist_dir + "/" + name
            makedirs(cls.dist_dir)
            with open(file_path, "wb+") as file:
                file.write(result.content)

            extract_zst(file_path, cls.dist_dir)
            remove(file_path)

        super().init(task)
