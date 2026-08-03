<#
Installs (or updates) the Incredibuild Build Acceleration plugin into every
supported JetBrains IDE found on this machine (RustRover, and IntelliJ IDEA
Ultimate). Meant to be run from inside the self-extracting installer,
alongside intellij-ib-plugin.zip.

Detects installs by reading each product's own product-info.json rather than
guessing the config folder name from the install folder's display name -
that heuristic does NOT generalize across products (e.g. RustRover's config
folder is "RustRover2026.2", a simple space-strip of its install folder name,
but IntelliJ IDEA Ultimate's is "IntelliJIdea2026.2", which does not follow
the same pattern at all).

Runs hidden (invoked via install-launcher.exe), so all output is logged to
%TEMP%\ib-plugin-install.log rather than a visible console - install-launcher.exe
pops up a message box on failure with a pointer to this log.
#>

$ErrorActionPreference = "Stop"
Start-Transcript -Path "$env:TEMP\ib-plugin-install.log" -Force | Out-Null

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$pluginZip = Join-Path $scriptDir "intellij-ib-plugin.zip"
