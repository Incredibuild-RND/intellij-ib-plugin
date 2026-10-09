import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Zip
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

kotlin {
    jvmToolchain(25)
    // Without this, Kotlin generates a synthetic delegating override in each implementing
    // class for every default-bodied interface member (e.g. ToolWindowFactory.isApplicable,
    // .manage, .getIcon, .getAnchor) even when the class never touches them - which the
    // Plugin Verifier then reports as OUR code overriding/invoking deprecated/experimental
    // platform API. -Xjvm-default=all compiles them as real JVM 8 default methods instead,
    // matching how the IntelliJ Platform's own Kotlin interfaces are compiled. (This flag is
    // marked deprecated in favor of -jvm-default, but that replacement's accepted value set
    // differs in the Kotlin compiler version this project builds with and rejects "all".)
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdeaUltimate("2026.2")
        plugin("com.jetbrains.rust", "262.8665.323")
        // CMake ("CMake") and Native Build Tools ("com.intellij.clion") give us
        // CMakeAppRunConfiguration/CMakeWorkspace/CMakeConfiguration and the
        // CidrBuildTargetAction/CPPToolchains/CPPEnvironment/CPPBuildUtil family the CLion
        // side of this plugin builds its cmake invocations with. Both are bundled in CLion but,
        // like the Rust plugin, published separately on the Marketplace for other IDEs (here,
        // installable into IntelliJ IDEA Ultimate via the "CLion C and C++" plugin) - pinned to
        // the same 262.8665 branch as the Rust plugin dependency above for one consistent
        // compile-time IDE line.
        plugin("com.intellij.cmake", "262.8665.176")
        plugin("com.intellij.clion", "262.8665.176")
        // com.intellij.clion's own plugin.xml mandatorily depends on this (native debugging
        // base classes, e.g. CidrRunConfiguration/CidrToolEnvironment/CidrBuildTarget that
        // CMakeAppRunConfiguration/CPPEnvironment/CMakeTarget extend) - the Gradle plugin
        // dependency mechanism does not pull a marketplace plugin's own transitive plugin
        // dependencies onto the compile classpath automatically, so it has to be listed here
        // too. Same 262.8665 branch as the other two now that one's been released for it -
        // keep this in step with them rather than drifting onto a newer build 262 line, which
        // previously kept the CLion half of this plugin from loading under runIde/the Plugin
        // Verifier against a plain 2026.2 target (only 262.8665+ builds have it).
        plugin("com.intellij.nativeDebug", "262.8665.176")
        bundledPlugin("intellij.testRunner.plugin")
        // The Rider-only .NET adapter (see rider/build.gradle.kts), merged into this plugin's
        // own jar rather than shipped as a separate one - still a single plugin to install.
        pluginComposedModule(implementation(project(":rider")))
        testFramework(TestFrameworkType.Platform)
    }
}

tasks.named("buildPlugin", Zip::class) {
    archiveFileName.set("intellij-ib-plugin.zip")
}

// Gradle's Jar/Zip tasks default every entry to a fixed 1980-02-01 placeholder
// timestamp instead of the file's real modification time, so identical source
// produces byte-identical archives (reproducible builds/build caching). That
// makes every .class/.jar entry inside the plugin zip show that placeholder
// date instead of when it was actually built - preserve real timestamps instead.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = true
}

// Compiled against com.jetbrains.rust 262.8665.323 (2026.2 line), but the specific APIs this
// plugin uses (CargoCommandConfiguration.parametersHolder, RustProjectSettingsService.toolchain,
// RsToolchainBase.pathToCargoExecutable, etc.) are verified identical in the 261.x (2026.1) line
// too, so declare compatibility down to build 261 rather than the auto-derived 262.
intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild.set("261")
            // Capped at the 262 line because com.jetbrains.rust - which this plugin declares
            // as a mandatory dependency, and without which the IDE will not load it at all -
            // ships pinned to one IDE line at a time (the build we compile against declares
            // since-build="262.8665" until-build="262.*"). Leaving this open-ended claimed
            // compatibility with IDEs where that plugin does not exist, which is what made
            // the Plugin Verifier report "com.jetbrains.rust: Unavailable" against IU-263 and
            // then, as a consequence, every org.rust class as missing. Raise this in step with
            // a Rust plugin release for the newer line, not before.
            untilBuild.set("262.*")
        }
    }

    // This plugin's Rust and CMake halves each only ever load in one specific IDE
    // (RustRover/IntelliJ IDEA+Rust, and CLion respectively - see plugin.xml's optional
    // <depends>), so verifying only against the intellijIdeaUltimate target it's compiled
    // against would never actually load the CMake half at all, silently missing any
    // classloading problem in it (e.g. the nativeDebug branch mismatch this once had). Verify
    // against both real IDEs instead.
    pluginVerification {
        // Verified against CLion 2026.2: fully compatible, no compatibility problems.
        // Verified against RustRover 2026.2: 4 compatibility problems, all of them exactly
        // the CMake-only symbols CMakeBuildCommand.kt references
        // (com.jetbrains.cidr.cpp.toolchains.*, com.jetbrains.cidr.cpp.cmake.*) - the
        // Verifier's own report explains why ("Missing dependencies: com.intellij.cmake
        // (optional): Unavailable" / "compatibility problems, some of which may be caused
        // by absence of optional dependency"). That's the optional-dependency split from
        // the comment above working as intended - RustRover genuinely can't and shouldn't
        // resolve CMake-only classes - not a bug. Rather than dropping COMPATIBILITY_PROBLEMS
        // from failureLevel entirely (which would also hide a real, future compatibility
        // break, e.g. a 2026.x IDE removing an API this plugin uses), those 4 specific,
        // already-reviewed problems are listed in ignoredProblemsFile instead - everything
        // else in that category still fails the task. failureLevel is left at its default
        // (COMPATIBILITY_PROBLEMS + INTERNAL_API_USAGES; confirmed by observing which
        // categories actually failed the task even though deprecated/experimental/missing-
        // optional-dependency problems were also present and reported) - it already caught a
        // real, fixable internal-API usage (PluginManagerCore.getPlugin(), replaced with the
        // public isLoaded() instead) and should keep gating future ones the same way.
        ignoredProblemsFile.set(rootProject.layout.projectDirectory.file("gradle/pluginVerifier-ignoredProblems.txt"))
        ides {
            create(IntelliJPlatformType.CLion, "2026.2")
            create(IntelliJPlatformType.RustRover, "2026.2")
            // The only IDE that ever loads the .NET half (rider-support.xml).
            // Rider is only published as a Maven artifact, not a regular installer - see
            // https://github.com/JetBrains/intellij-platform-gradle-plugin/issues/1852.
            create(IntelliJPlatformType.Rider, "2026.2") {
                useInstaller = false
            }
        }
    }
}

// Plain `runIde` launches intellijIdeaUltimate (the platform this plugin's dependencies block
// above declares), which never loads the CMake half at all (see plugin.xml's optional
// <depends config-file="cmake-support.xml">) - there's nothing to manually exercise there. Add a
// second target that launches CLion instead, for manually trying the CMake/CLion actions.
intellijPlatformTesting {
    runIde {
        register("runIdeForClion") {
            type = IntelliJPlatformType.CLion
            version = "2026.2"
        }
        // Likewise for the .NET/Rider actions (rider-support.xml).
        register("runIdeForRider") {
            type = IntelliJPlatformType.Rider
            version = "2026.2"
            useInstaller = false
        }
    }
}

// buildSearchableOptions launches a sandboxed IDE to index Settings UI labels for search.
// That launcher doesn't support ARM64 hosts ("Unsupported JVM architecture: aarch64") - it's a
// build-time search-indexing convenience only, not required for the plugin to function.
// Switched off through the plugin's own flag rather than by disabling just that one task: the
// follow-up prepareJarSearchableOptions task still expects the output folder buildSearchableOptions
// would have created, so after a `clean` the task-only approach fails the build.
intellijPlatform {
    buildSearchableOptions = false
}
