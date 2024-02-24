# toolchain

### Description

Toolchain for compiling Python & Other Libraries for Python Multiplatform

A lot of thanks to the contribution of the [Kivy](https://github.com/kivy) team to python build system, this toolchain can work.
The python package build operations on Android and iOS work using the Kivy team's tools as they are.
In addition to that, I would like to thank the people who produced the libraries that this tool depends on.


#### The toolchain supports:

- Android (arm64, arm32, x86, x86_64) originated by [Kivy Android ToolChain](https://github.com/thisisthepy/toolchain-android)
- iOS (arm64) originated by [Kivy ios Toolchain](https://github.com/thisisthepy/toolchain-ios)
- masOS (universal) originated by [Kivy ios Toolchain](https://github.com/thisisthepy/toolchain-ios)
- Linux (x86_64) originated by [25077667](https://github.com/thisisthepy/toolchain-linux)
- Windows (mingw_x64) originated by [MSYS2](https://github.com/thisisthepy/toolchain-mingw)

** Because Xcode only runs on macOS, you need macOS build macOS and iOS side packages.

___

## Installation

While describing the installation process of this toolchain, I'm going to suppose that you are using the [PMM Application Project Template](https://github.com/thisisthepy/pmm-app-template).

Then, the project directory seems like below.

      - project
          - app
              - src
              - build.gradle.kts
              - proguard-rules.pro
          - gradle
          - build.gradle.kts
          - gradle.properties
          - gradlew
          - gradlew.bat
          - settings.gradle.kts
          - ...

Using python 3.9 version or above as a project management tool is recommended.
Whatever version you use, it has nothing related to the version of python that being built by this tool.

Please create the project management environment at the project root directory.
Pip packages that your python application uses will not be installed in this .venv folder.

      cd project
      python3 -m venv .venv
      . .venv/bin/activate

Install this toolchain using the following command. (You can change the python version at the end of the git url)

      pip3 install git+https://github.com/thisisthepy/toolchain.git@python3.11.8

Additionally, you would need a few system dependencies and configuration when you are on macOS.

- Xcode 13 or above, with an iOS SDK and command line tools installed:

      xcode-select --install

- Using brew, you can install the following dependencies:

      brew install autoconf automake libtool pkg-config
      brew link libtool

---

## Usage
Then, start the compilation with:
(python3 and openssl will be built automatically with this)

    $ toolchain init

You can build recipes at the same time by adding them as parameters:

    $ toolchain build kivy numpy

Recipe builds can be removed via:

    $ toolchain clean kivy

You can install/uninstall packages that don't require compilation with pip:

    $ toolchain pip install plyer
    $ toolchain pip uninstall plyer

or specify a target platform with:

    $ toolchain android pip install plyer
    $ toolchain ios pip install plyer
    $ toolchain host pip install plyer

### Available Packages Compilation Recipes:
Please reference the recipe documentation of each platform's toolchain

- [Android](https://github.com/thisisthepy/toolchain-android/tree/master/pythonforandroid/recipes)
- [iOS](https://github.com/thisisthepy/toolchain-ios?tab=readme-ov-file#using-the-toolchain)
- [Mingw](https://github.com/thisisthepy/toolchain-mingw)
