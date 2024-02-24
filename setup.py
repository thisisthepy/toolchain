from setuptools import setup, find_packages


setup(
    name="toolchain",
    version="0.0.1",
    description="Toolchain for compiling Python & Other Libraries for Python Multiplatform",
    author="thisisthepy",
    author_email="",
    url="https://github.com/thisisthepy/toolchain",
    packages=find_packages(include=['toolchain', 'toolchain.*']),
    install_requires=[
        "git+https://github.com/thisisthepy/toolchain-ios.git@python3.11",
        "setuptools",
        "requests"
    ],
    entry_points={
        'console_scripts': [
            "toolchain=toolchain:main",
            "toolchain_targetver=toolchain:set_target_build_version",
            "toolchain_targetos=toolchain:set_target_os"
        ]
    },
    #package_data={'exampleproject': ['data/schema.json']}
)
