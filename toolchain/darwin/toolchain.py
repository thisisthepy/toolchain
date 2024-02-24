from kivy_ios.recipes.hostpython3 import Hostpython3Recipe
import kivy_ios.toolchain as tc
from logging import error
from os.path import join
from ..settings import Settings


Hostpython3Recipe.version = Settings.target_version
Hostpython3Recipe.url = Settings.source_url


class Context(tc.Context):
    def __init__(self):
        super(Context, self).__init__()
        initial_working_directory = Settings.work_dir
        # root of the toolchain
        self.build_dir = "{}/build".format(initial_working_directory)
        self.cache_dir = "{}/.cache".format(initial_working_directory)
        self.dist_dir = "{}".format(initial_working_directory)
        self.install_dir = "{}/ios".format(initial_working_directory)
        self.include_dir = "{}/ios/include".format(initial_working_directory)


tc.Context = Context


class ToolchainCL(tc.ToolchainCL):
    ctx = Context()

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
