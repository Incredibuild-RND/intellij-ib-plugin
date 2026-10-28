<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Incredibuild Build Acceleration Changelog

## [Unreleased]

## [1.1.0]

### Added

- CLion support for CMake projects: **Build**, **Rebuild** and **Build All** in the **Incredibuild** menu run
  `cmake --build` through Incredibuild, against the build directory, toolchain and generator the active CMake profile
  is already configured with.
- Rider support for .NET solutions: **Build Solution with Incredibuild**, **Rebuild Solution with Incredibuild** and
  **Build Selected Projects with Incredibuild** in the **Build** menu, the Solution Explorer context menu and the
  **Incredibuild** menu, bindable to shortcuts in **Settings/Preferences > Keymap**. They build the solution as it is
  configured, in its active configuration and platform, through MSBuild accelerated by Incredibuild. While one runs,
  the status bar shows the accelerated build in progress, and a notification reports the result.
- **Settings/Preferences > Tools > Incredibuild** can set the MSBuild that Rider builds use, when the automatically
  detected one isn't the right one.
- The installers now also install the plugin into CLion and Rider.

### Changed

- Only one Incredibuild build runs at a time, in every IDE. Starting a build - from the **Incredibuild** menu or,
  with IDE build acceleration on, from the IDE's own build actions - while another is still running offers to stop
  the running one, then starts the new build as soon as it has stopped. Previously a second build started alongside
  the first, replacing its output in the **Incredibuild** tool window.
- The Windows installers (`install-intellij-ib-plugin.ps1` and `intellij-ib-plugin.exe`) are now attached to each
  GitHub release, built by the release workflow from the published plugin, instead of the script being committed to
  the repository.

## [1.0.3]

### Added

- The IDE's own build actions can now be accelerated too. Turn on **Accelerate the IDE's own build actions** in
  **Settings/Preferences > Tools > Incredibuild**, and **Build Project** - along with the build that runs before
  **Run** and **Test** - goes through Incredibuild, keeping the standard commands and shortcuts where they are.
  Off by default; while it is off, those actions behave exactly as they do without this plugin.

### Changed

- **Build** and **Rebuild** now run the same cargo command the IDE itself would have run, so the selected run
  configuration, build profile and target selection are all respected. Previously they always built every target in
  the workspace, which compiled far more than intended.
- Renamed **Build All** and **Rebuild All** back to **Build** and **Rebuild**, since they no longer build everything.
- The configured parallel job count now replaces any `-j` already present in the command instead of being passed
  alongside it.
- Minimum recommended Incredibuild version is now 10.37.1 on Windows and 4.29.3 on Linux.

### Removed

- **Build Selected Run Configuration** is temporarily hidden while its behaviour is reworked.

## [1.0.2]

### Added

- Linux support: builds run through `ib_console` from an Incredibuild installation in `/opt/incredibuild`.
- Build output keeps cargo's own colouring instead of being shown as plain red text.

### Changed

- **Build** and **Rebuild** build every target in the whole workspace, and were renamed **Build All** and
  **Rebuild All**.
- The default parallel job count is now 300.

### Fixed

- **Rebuild** no longer shows the "version may be too old" warning twice.
- Cargo is now located correctly on non-Windows machines.

## [1.0.1]

### Added

- Windows builds pass a dedicated Rust BuildCache profile to BuildConsole.

### Changed

- Raised the minimum recommended Incredibuild version for Rust build support.

## [1.0.0]

### Added

- **Incredibuild** menu (next to **Build** in the main menu) with:
  - **Build** - builds the whole Cargo workspace through Incredibuild
  - **Rebuild** - cleans the workspace, then builds it through Incredibuild
  - **Build Selected Run Configuration** - runs the currently selected Cargo run configuration through Incredibuild
  - **Stop Build** - stops whichever build or clean process is currently running
- Configurable parallel job count (`-j`), under **Settings/Preferences > Tools > Incredibuild**
- Automatic detection of the Incredibuild installation, with a prompt to open [incredibuild.com](https://www.incredibuild.com/) if it isn't found
