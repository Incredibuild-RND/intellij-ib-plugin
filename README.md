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

### Rider (.NET)

In Rider, the **Build** menu gets dedicated Incredibuild actions right after the standard ones (they are
also in the **Incredibuild** menu):

- **Build Solution with Incredibuild** - builds the solution in its active configuration and platform
- **Rebuild Solution with Incredibuild** - cleans and rebuilds it
- **Build Selected Projects with Incredibuild** - builds the projects selected in the Solution Explorer,
  along with the projects they reference

The same actions are in the Solution Explorer's context menu on the solution, solution folders and
projects, and each can be bound to a shortcut under **Settings/Preferences > Keymap** (search for
"Incredibuild"). They have no default shortcuts, so none of Rider's own are taken over.

They run MSBuild on the solution exactly as it is configured: the active configuration and platform
are respected (for selected projects, through the solution's own project configuration mapping), and
an open solution filter (`.slnf`) limits the build the same way it limits Rider's own. Nothing about the
solution or its projects is changed. While an accelerated build runs, the status bar shows
*Building <solution> with Incredibuild*, the **Incredibuild** tool window shows its output under a banner
naming the configuration and MSBuild used, and a notification reports the result.

MSBuild is detected automatically: the newest Visual Studio / Build Tools `MSBuild.exe` with the .NET SDK component on Windows,
otherwise the .NET SDK's `dotnet msbuild`. Rider's own MSBuild selection lives in its backend settings,
which plugins can't read, so a different one can be set under **Settings/Preferences > Tools > Incredibuild**.

### Settings

**Settings/Preferences > Tools > Incredibuild** configures the number of parallel jobs (`-j`)
accelerated Cargo/CMake builds request, the opt-in above, and (in Rider) the MSBuild to use.

If Incredibuild isn't installed on the machine, the menu commands offer to open
[incredibuild.com](https://www.incredibuild.com/) to download it.

## Requirements

- RustRover, or IntelliJ IDEA Ultimate with the Rust plugin - 2026.1 or 2026.2 (builds 261-262).
  The Rust plugin is released for one IDE line at a time, and this plugin depends on it, so support
  for a newer line follows a Rust plugin release for it.
- Or Rider 2026.2, with MSBuild (Visual Studio / Build Tools) or the .NET SDK installed.
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
