<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# Incredibuild Build Acceleration Changelog

## [Unreleased]

### Added

- **Incredibuild** menu (next to **Build** in the main menu) with:
  - **Build** - builds the whole Cargo workspace through Incredibuild
  - **Rebuild** - cleans the workspace, then builds it through Incredibuild
  - **Build Selected Run Configuration** - runs the currently selected Cargo run configuration through Incredibuild
  - **Stop Build** - stops whichever build or clean process is currently running
- Configurable parallel job count (`-j`), under **Settings/Preferences > Tools > Incredibuild**
- Automatic detection of the Incredibuild installation, with a prompt to open [incredibuild.com](https://www.incredibuild.com/) if it isn't found
