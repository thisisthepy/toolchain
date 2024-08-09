from kivy_ios.recipes.hostpython3 import Hostpython3Recipe
import kivy_ios.toolchain as tc
from toolchain.logger import error
from os.path import join
from toolchain.settings import Settings


Hostpython3Recipe.version = Settings.target_version
Hostpython3Recipe.url = Settings.source_url


class Context(tc.Context):
    def __init__(self):
        super(Context, self).__init__()
        initial_working_directory = Settings.work_dir
        # root of the toolchain
        self.build_dir = f"{Settings.build_dir}/darwin"
        self.cache_dir = Settings.cache_dir
        self.dist_dir = f"{initial_working_directory}/darwin"
        self.install_dir = f"{initial_working_directory}/darwin/root"
        self.include_dir = f"{initial_working_directory}/darwin/include"


tc.Context = Context
ctx = Context()


class ToolchainCL(tc.ToolchainCL):
    @staticmethod
    def find_xcodeproj(filename):
        if not filename.endswith(".xcodeproj"):
            # try to find the xcodeproj
            from glob import glob
            xcodeproj = glob(join(filename+"**", "*.xcodeproj"), recursive=True)
            if not xcodeproj:
                error("Unable to find a xcodeproj in {}".format(filename))
                exit(1)
            filename = xcodeproj[0]
        return filename

    def __init__(self):
        pass
