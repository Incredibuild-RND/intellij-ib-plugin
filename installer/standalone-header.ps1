<#
.SYNOPSIS
Installs (or updates) the "Incredibuild Build Acceleration" plugin into every
supported JetBrains IDE found on this machine (RustRover, and IntelliJ IDEA
Ultimate).

.DESCRIPTION
Fully self-contained: the plugin zip is embedded below as base64, so this is
the only file needed. Run it with:
    powershell -ExecutionPolicy Bypass -File .\install-intellij-ib-plugin.ps1

Detects installs by reading each product's own product-info.json rather than
guessing the config folder name from the install folder's display name -
that heuristic does NOT generalize across products (e.g. RustRover's config
folder is "RustRover2026.2", a simple space-strip of its install folder name,
but IntelliJ IDEA Ultimate's is "IntelliJIdea2026.2", which does not follow
the same pattern at all).

All detected target IDEs must be closed before running this script - it will
refuse to proceed otherwise, since IntelliJ Platform IDEs load plugins at
startup and don't reliably pick up a plugin folder replaced while already
running.
#>

$ErrorActionPreference = "Stop"
