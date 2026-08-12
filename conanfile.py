#!/usr/bin/python3

################################################################################
##  Project intellij_ib_plugin                                              ##
##  Copyright 2026 Incredibuild Software Ltd.                                ##
################################################################################

import os
import re

from conans import ConanFile


def _read_version():
    """Reads the plugin version from gradle.properties, so it's the single
    source of truth rather than a separately maintained literal here."""
    gradle_properties_path = os.path.join(os.path.dirname(__file__), "gradle.properties")
    with open(gradle_properties_path, "r") as gradle_properties_file:
        contents = gradle_properties_file.read()
    match = re.search(r"^version\s*=\s*(\S+)", contents, re.MULTILINE)
    if not match:
        raise Exception("Could not find 'version' in gradle.properties")
    return match.group(1)


class IntellijIbPluginConan(ConanFile):
    name = "intellij_ib_plugin"
    version = _read_version()
    # _read_version() re-runs wherever Conan re-executes this recipe from
    # (e.g. its own cache after `conan export-pkg`), so gradle.properties
    # must be exported alongside conanfile.py, not just left as a sibling
    # file in the working directory.
    exports = "gradle.properties"
    description = "Incredibuild Build Acceleration plugin for RustRover / IntelliJ IDEA."
    author = "Incredibuild"
    url = "https://github.com/Incredibuild-RND/intellij-ib-plugin"
    license = "Apache-2.0"
    homepage = "https://github.com/Incredibuild-RND/intellij-ib-plugin"

    # No settings: this package is a single, platform/compiler-agnostic
    # plugin zip (JVM bytecode), not a native binary with ABI variance.

    def package(self):
        self.copy("intellij-ib-plugin.zip", src="build/distributions", dst="bin", keep_path=False)

    def package_info(self):
        self.cpp_info.bindirs = ["bin"]
