"""A package whose directory starts with `_`, like numpy's `_core` or torch's `_dynamo`.

AGP's default `ignoreAssetsPatterns` drops such directories from the APK; the toolchain plugin removes that
entry (toolchain#166), and android-e2e checks this file reaches `assets/python/`.
"""

MARKER = "underscore package packaged"
