# toolchain

![Build](https://github.com/thisisthepy/toolchain/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

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

** Because Xcode only runs on macOS, you need macOS to build macOS and iOS side packages.


### Template ToDo list
- [x] Create a new [IntelliJ Platform Plugin Template][template] project.
- [ ] Get familiar with the [template documentation][template].
- [ ] Adjust the [pluginGroup](./gradle.properties) and [pluginName](./gradle.properties), as well as the [id](./src/main/resources/META-INF/plugin.xml) and [sources package](./src/main/kotlin).
- [ ] Adjust the plugin description in `README` (see [Tips][docs:plugin-description])
- [ ] Review the [Legal Agreements](https://plugins.jetbrains.com/docs/marketplace/legal-agreements.html?from=IJPluginTemplate).
- [ ] [Publish a plugin manually](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html?from=IJPluginTemplate) for the first time.
- [ ] Set the `MARKETPLACE_ID` in the above README badges. You can obtain it once the plugin is published to JetBrains Marketplace.
- [ ] Set the [Plugin Signing](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html?from=IJPluginTemplate) related [secrets](https://github.com/JetBrains/intellij-platform-plugin-template#environment-variables).
- [ ] Set the [Deployment Token](https://plugins.jetbrains.com/docs/marketplace/plugin-upload.html?from=IJPluginTemplate).
- [ ] Click the <kbd>Watch</kbd> button on the top of the [IntelliJ Platform Plugin Template][template] to be notified about releases containing new features and fixes.

<!-- Plugin description -->
This Fancy IntelliJ Platform Plugin is going to be your implementation of the brilliant ideas that you have.

This specific section is a source for the [plugin.xml](/src/main/resources/META-INF/plugin.xml) file which will be extracted by the [Gradle](/build.gradle.kts) during the build process.

To keep everything working, do not remove `<!-- ... -->` sections. 
<!-- Plugin description end -->

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
      python3 -m venv build
      . build/bin/activate

Install this toolchain using the following command. (You can change the python version at the end of the git url)

      pip3 install git+https://github.com/thisisthepy/toolchain.git@python3.11

And set python build target version and target os system.

      toolchain_targetver 3.11.8
      toolchain_targetos host android ios

Additionally, you would need a few system dependencies and configuration when you are on macOS.

- Xcode 13 or above, with an iOS SDK and command line tools installed:

      xcode-select --install

- Using brew, you can install the following dependencies:

      brew install autoconf automake libtool pkg-config
      brew link libtool

---

- Using the IDE built-in plugin system:
  
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "toolchain"</kbd> >
  <kbd>Install</kbd>
  
- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/thisisthepy/toolchain/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


---

## Usage
Then, start the compilation with:
(python3 and openssl will be built automatically with this)

    $ toolchain init

You can build recipes at the same time by adding them as parameters:

    $ toolchain build kivy numpy

Recipe builds can be removed via:

    $ toolchain clean kivy

or specify a target platform with:

    $ toolchain android build kivy numpy
    $ toolchain ios build kivy numpy
    $ toolchain android clean kivy
    $ toolchain ios clean kivy

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


---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
