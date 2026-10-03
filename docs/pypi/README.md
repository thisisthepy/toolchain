# toolchain-lite

**`tcl`: add Python packages to a project from the command line, no Gradle project needed.**

toolchain-lite is the command-line side of [toolchain](https://github.com/thisisthepy/toolchain), the
Gradle plugin that declares the Python half of a Kotlin Multiplatform app. `tcl` gives Python users
the same dependency step without a Gradle build.

## Install

```shell
uv tool install toolchain-lite    # puts tcl on PATH
tcl --help
uvx --from toolchain-lite tcl --version   # or run it without installing
```

The wheel carries `tcl` as a native binary (GraalVM Native Image), one wheel per platform: macOS 11+
on Apple silicon, Linux x86_64 and aarch64 (glibc), Windows x86_64. There is no source distribution:
building needs a JDK and GraalVM.

`uv` is needed at run time. When none is on `PATH`, `tcl` downloads one into `~/.pypackpack/uv`.

## Use

```shell
mkdir myapp && cd myapp
tcl install six         # creates pyproject.toml (uv init --bare) if there is none, then adds six
```

`tcl install <package>` finds the nearest `pyproject.toml` at or above the current directory, creates
one if there is none, and adds the package with uv. It exits 0 on success and 1 with a message
otherwise.

## Status

`tcl install` is implemented and tested. Everything else in the plan for `tcl`
([issue #1](https://github.com/thisisthepy/toolchain/issues/1)) is not built yet.

## Links

- [Guide: tcl for Python users](https://thisisthepy.github.io/toolchain/guide-tcl.html)
- [Source](https://github.com/thisisthepy/toolchain/tree/develop/tcl)
- [Licence: Apache-2.0](https://github.com/thisisthepy/toolchain/blob/develop/LICENSE)
