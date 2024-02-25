from setuptools import setup, find_packages
from glob import glob


setup(
    name="toolchain",
    version="0.0.1",
    description="Toolchain for compiling Python & Other Libraries for Python Multiplatform",
    author="thisisthepy",
    author_email="",
    url="https://github.com/thisisthepy/toolchain",
    packages=list(set(['/'.join(path.removesuffix(".py").split('/')[:-1]) for path in glob("toolchain/**/*.py", recursive=True)])),
    install_requires=[
        "kivy-ios @ git+https://github.com/thisisthepy/toolchain-ios.git@python3.11",
        "setuptools",
        "requests"
        "zstandard"
    ],
    entry_points={
        'console_scripts': [
            "toolchain=toolchain:run_toolchain",
            "toolchain_targetver=toolchain:set_target_build_version",
            "toolchain_targetos=toolchain:set_target_os"
        ]
    },
    #package_data={'exampleproject': ['data/schema.json']}
)
