from ..common.environ import Environment, EnvType
from ..settings import Settings
from os.path import exists


class Linux(Environment):
    """Linux Build Environment"""
    env_type = EnvType.LINUX

    build_dir = ""
    dist_dir = Settings.work_dir + "/linux"

    @classmethod
    def is_initialized(cls):
        return exists(cls.dist_dir)

    @classmethod
    def init(cls, *args, **kwargs):
        def task(*_, **__):
            pass
        super().init(task)
