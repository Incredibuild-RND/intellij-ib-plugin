# Incredibuild Build Acceleration

![Build](https://github.com/Incredibuild-RND/intellij-ib-plugin/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/MARKETPLACE_ID.svg)](https://plugins.jetbrains.com/plugin/MARKETPLACE_ID)

Build your Rust/Cargo project through [Incredibuild](https://www.incredibuild.com/) directly from RustRover
(or IntelliJ IDEA Ultimate with the Rust plugin), via a dedicated **Incredibuild** menu.

Incredibuild is a build acceleration tool that distributes compilation work across the free cores of
multiple machines on your network (or in the cloud), cutting build times without requiring any changes
to your code or project structure.

## Features

The **Incredibuild** menu (next to **Build** in the main menu bar) provides:

- **Build** - builds whatever the IDE's own **Build Project** would build, through Incredibuild
- **Rebuild** - cleans the Cargo workspace first, then builds it
- **Stop Build** - cancels the build in progress

The accelerated build runs the same cargo command the IDE itself would have run, so the selected run
configuration, build profile and target selection are all respected.

### Accelerating the IDE's own build actions

The IDE's build actions can be accelerated too. Turn on **Accelerate the IDE's own build actions**
under **Settings/Preferences > Tools > Incredibuild**, and **Build Project** - along with the build
that runs before **Run** and **Test** - goes through Incredibuild, reporting into the IDE's own
**Build** tool window. The commands and keyboard shortcuts stay exactly where they are.

The setting is off by default. While it is off - or if Incredibuild isn't installed on the machine -
those actions behave exactly as they do without this plugin. The actions in the **Incredibuild** menu
always use Incredibuild, whichever way the setting is set.

### Settings

**Settings/Preferences > Tools > Incredibuild** configures the number of parallel jobs (`-j`)
accelerated builds request, and the opt-in above.

If Incredibuild isn't installed on the machine, the menu commands offer to open
[incredibuild.com](https://www.incredibuild.com/) to download it.

## Requirements

- RustRover, or IntelliJ IDEA Ultimate with the Rust plugin - 2026.1 or 2026.2 (builds 261-262).
  The Rust plugin is released for one IDE line at a time, and this plugin depends on it, so support
  for a newer line follows a Rust plugin release for it.
- [Incredibuild](https://www.incredibuild.com/) installed, on Windows or Linux:
  - Windows: 10.37.1 or later
  - Linux: 4.29.3 or later, installed under `/opt/incredibuild`

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
