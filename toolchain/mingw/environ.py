from toolchain.common.environ import Environment, EnvType
from toolchain.mingw.extract_zst import extract_zst
from toolchain.settings import Settings
from toolchain.logger import info

from os import makedirs, replace
from os.path import exists
from glob import glob
import requests


class MinGW(Environment):
    """MinGW Build Environment"""
    env_type = EnvType.MINGW

    build_dir = ""
    dist_dir = Settings.work_dir + "/mingw"
    cache_dir = Settings.cache_dir

    @classmethod
    def is_initialized(cls):
        return exists(cls.dist_dir)

    @classmethod
    def init(cls, *args, **kwargs):
        def task(*_, **__):
            url, filename, ext = Settings.binary_url.mingw
            result = None
            rev_v = 0

            def name():
                return filename + f"-{rev_v}" + ext

            makedirs(cls.cache_dir, exist_ok=True)
            listings = glob(f"{cls.cache_dir}/{filename}*{ext}")
            rev_list = [int(li.replace(f"{cls.cache_dir}/{filename}-", "").replace(ext, "")) for li in listings]
            rev_list.sort()
            if rev_list:
                rev_v = rev_list[-1]
                info(f"Found {name()} from download cache directory.")

            for rev in range(rev_v+1, 10):
                respond = requests.get(url + filename + f"-{rev}" + ext)
                if respond.status_code == 200:
                    result = respond
                    rev_v = rev
                    info(f"Revision {rev} of Python {Settings.target_version} is found."
                         " Continue the iteration for searching newer python revision version.")
                    continue
                else:
                    info("404 Found. Stop iteration for searching python revision version."
                         f" The latest version of Python is {Settings.target_version}-{rev_v}.")
                    break

            file_path = cls.cache_dir + "/" + name()
            if rev_list:
                pass
            elif result is None:
                raise FileNotFoundError("ERROR: Cannot fetch MinGW python binary from MSYS2 repository."
                                        + " Please check the url information.")
            else:
                info("Downloading minGW binary from MSYS2 repository...")
                with open(file_path, "wb+") as file:
                    file.write(result.content)
                info(f"Download complete: {file_path}")

            makedirs(cls.dist_dir, exist_ok=True)
            extract_zst(file_path, cls.dist_dir)
            replace(cls.dist_dir+"/mingw64", cls.dist_dir+"/python3")

        super().init(task)
