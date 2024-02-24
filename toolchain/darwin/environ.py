from ..common.environ import Environment, EnvType
from toolchain import ToolchainCL
from ..settings import Settings


toolchainCL = ToolchainCL()


class Darwin(Environment):
    """Macos Build Environment"""
    env_type = EnvType.DARWIN

    build_dir = toolchainCL.ctx.build_dir
    dist_dir = toolchainCL.ctx.dist_dir

    pip_path = f"{dist_dir}/hostpython3/bin/pip3"
    site_dir = f"{dist_dir}/hostpython3/lib/python{Settings.target_version_short}/site-packages"

    @classmethod
    def init(cls, task=toolchainCL.build, init_package=("python3", "openssl, pyobjus")):
        return super().init(task, init_package)


class IOS(Darwin):
    """IOS Build Environment"""
    env_type = EnvType.IOS

    dist_dir = toolchainCL.ctx.install_dir
    site_dir = f"{dist_dir}/python3/lib/python{Settings.target_version_short}/site-packages"

    recipes = toolchainCL.recipes
    distclean = toolchainCL.distclean
    status = toolchainCL.status
    create = toolchainCL.create
    build_info = toolchainCL.build_info
    pip = toolchainCL.pip
    pip3 = toolchainCL.pip
    launchimage = toolchainCL.launchimage
    icon = toolchainCL.icon
    xcode = toolchainCL.xcode

    @classmethod
    def init(cls, *args, **kwargs):
        return False

    @classmethod
    def build(cls, *_, **__):
        toolchainCL.build_info()
        result = toolchainCL.build()
        return result

    @classmethod
    def clean(cls, *_, **__):
        result = toolchainCL.clean()
        cls.status()
        return result
