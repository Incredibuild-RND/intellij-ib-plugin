# Incredibuild Build Acceleration

![Build](https://github.com/Incredibuild-RND/intellij-ib-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

Build your Rust/Cargo project through [Incredibuild](https://www.incredibuild.com/) directly from RustRover
(or IntelliJ IDEA Ultimate with the Rust plugin), via a dedicated **Incredibuild** menu.

Incredibuild is a build acceleration tool that distributes compilation work across the free cores of
multiple machines on your network (or in the cloud), cutting build times without requiring any changes
to your code or project structure.

**Currently supports Windows only.**

## Features

The **Incredibuild** menu (next to **Build** in the main menu bar) provides:

- **Build** - builds the whole Cargo workspace through Incredibuild
- **Rebuild** - cleans the workspace, then builds it through Incredibuild
- **Build Selected Run Configuration** - runs the currently selected Cargo run configuration through Incredibuild
- **Stop Build** - stops whichever build or clean process is currently running

The number of parallel jobs (`-j`) Incredibuild uses is configurable under
**Settings/Preferences > Tools > Incredibuild**.

If Incredibuild isn't installed on the machine, the menu commands offer to open
[incredibuild.com](https://www.incredibuild.com/) to download it.

## Requirements

- RustRover, or IntelliJ IDEA Ultimate with the Rust plugin (build 261 / 2026.1 or later)
- [Incredibuild](https://www.incredibuild.com/) installed on Windows

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "Incredibuild"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID) and install it by clicking the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID/versions) from JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/Incredibuild-RND/intellij-ib-plugin/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

## License

Licensed under the [Apache License, Version 2.0](./LICENSE). See [CONTRIBUTING.md](./CONTRIBUTING.md) for contribution terms.
