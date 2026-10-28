// The Rider-only adapter for this plugin's .NET support: a thin layer over Rider's frontend
// solution model (which solution is open, its active configuration|platform, what's selected in
// the Solution Explorer), compiled against Rider itself because those classes only exist in the
// Rider product, never as a Marketplace plugin the root module could compile against alongside
// the Rust/CMake ones. It is merged into the root plugin's jar (see pluginComposedModule in the
// root build.gradle.kts), so this is still one plugin and one install - and only ever called
// from code that rider-support.xml registers, i.e. only when running inside Rider.
//
// Deliberately depends on nothing in the root module (that would be a dependency cycle - the
// root module depends on this one), and exposes only platform types in its API so the root
// module never needs Rider classes on its own compile classpath.

import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform.module")
}

kotlin {
    jvmToolchain(25)
    // Same reason as the root module's identical setting.
    compilerOptions {
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    intellijPlatform {
        // Same 2026.2 (262) line as the root module's other compile targets.
        rider("2026.2") {
            useInstaller = false
        }
        // Rider's solution-host extensions (project.solutionFile, isExistingSolution, ...) and its
        // Solution Explorer workspace model (ProjectModelEntity) live in this core content module,
        // which isn't on the default compile classpath. It's a "required" module of Rider's core
        // plugin, so it's always loaded at runtime in Rider.
        bundledModule("intellij.rider.rdclient.dotnet")
        bundledModule("intellij.rd.ide.model.generated")
    }
}

// Same as the root build.gradle.kts: keep real timestamps on this module's classes, which end up
// merged into the plugin's own jar, instead of Gradle's fixed 1980-02-01 placeholder.
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = true
}
