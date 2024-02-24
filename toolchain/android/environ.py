from ..common.environ import Environment, EnvType
from ..settings import Settings


class Android(Environment):
    """Android Build Environment"""
    env_type = EnvType.ANDROID

    build_dir = Settings.work_dir + "/android/builid"
    dist_dir = Settings.work_dir + "/android"

    @classmethod
    def init(cls, *args, **kwargs):
        def task(*_, **__):
            pass
        super().init(task)
