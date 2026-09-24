import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Zip
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
        // too. No matching 262.8665 release exists for this one; the closest available build
        // is used instead - fine for compiling against (Gradle just needs the class files),
        // even though its own since-build is newer than the other two.
        plugin("com.intellij.nativeDebug", "262.10315.125")
        bundledPlugin("intellij.testRunner.plugin")
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
}

// buildSearchableOptions launches a sandboxed IDE to index Settings UI labels for search.
// That launcher doesn't support ARM64 hosts ("Unsupported JVM architecture: aarch64") - it's a
// build-time search-indexing convenience only, not required for the plugin to function.
tasks.named("buildSearchableOptions") {
    enabled = false
}
