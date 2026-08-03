<#
.SYNOPSIS
Builds both distributable installers from the current build/distributions/intellij-ib-plugin.zip:
  - scripts/install-intellij-ib-plugin.ps1 (self-contained, base64-embedded script)
  - build/distributions/intellij-ib-plugin.exe (self-extracting installer, zero visible windows)

.DESCRIPTION
Just run this script - it runs `.\gradlew buildPlugin` itself first, so
build/distributions/intellij-ib-plugin.zip is always current:
    powershell -ExecutionPolicy Bypass -File .\installer\build-installer.ps1

Both generated scripts share the IDE-detection/validation logic in
install-common.ps1 (edit that ONE file for both, rather than either generated
script directly - regenerate by re-running this script). See installer/README.md
for the full pipeline explanation and prerequisites.
#>

$ErrorActionPreference = "Stop"

$installerDir = $PSScriptRoot
$repoRoot = Split-Path -Parent $installerDir
$pluginZip = Join-Path $repoRoot "build\distributions\intellij-ib-plugin.zip"
$standaloneScriptOut = Join-Path $repoRoot "scripts\install-intellij-ib-plugin.ps1"
$exeOut = Join-Path $repoRoot "build\distributions\intellij-ib-plugin.exe"

# --- 0. Rebuild the plugin so the zip can never be stale -------------------

$gradlew = Join-Path $repoRoot "gradlew.bat"
if (-not (Test-Path $gradlew)) {
    Write-Error "$gradlew not found - expected at the repo root."
    exit 1
}

Write-Host "Running '.\gradlew.bat buildPlugin' ..."
Push-Location $repoRoot
try {
    & $gradlew buildPlugin --console=plain
    if ($LASTEXITCODE -ne 0) {
        Write-Error "gradlew buildPlugin failed (exit $LASTEXITCODE)."
        exit 1
    }
} finally {
    Pop-Location
}

if (-not (Test-Path $pluginZip)) {
    Write-Error "$pluginZip still not found after 'gradlew buildPlugin' - check the buildPlugin task's output archive name/path."
    exit 1
}

# --- Locate prerequisites ---------------------------------------------------

$sevenZip = Get-Command "7z.exe" -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
if (-not $sevenZip) {
    $candidate = "C:\Program Files\7-Zip\7z.exe"
    if (Test-Path $candidate) { $sevenZip = $candidate }
}
if (-not $sevenZip) {
    Write-Error "7z.exe not found (checked PATH and 'C:\Program Files\7-Zip\7z.exe'). Install 7-Zip: https://www.7-zip.org/"
    exit 1
}

$csc = @(
    "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe",
    "C:\Windows\Microsoft.NET\Framework\v4.0.30319\csc.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $csc) {
    Write-Error "csc.exe not found under %WINDIR%\Microsoft.NET - .NET Framework 4.x is required to compile installer/Launcher.cs."
    exit 1
}

Write-Host "Using 7z: $sevenZip"
Write-Host "Using csc: $csc"

# --- Working directory -------------------------------------------------------

$work = Join-Path ([System.IO.Path]::GetTempPath()) ("ib-plugin-installer-build-" + [System.Guid]::NewGuid().ToString("N"))
New-Item -ItemType Directory -Path $work | Out-Null
try {
    # --- 1. Compile the SFX launcher ----------------------------------------

    $launcherExe = Join-Path $work "install-launcher.exe"
    & $csc /nologo /target:winexe /platform:anycpu ("/out:" + $launcherExe) (Join-Path $installerDir "Launcher.cs")
    if ($LASTEXITCODE -ne 0) {
        Write-Error "csc.exe failed to compile Launcher.cs (exit $LASTEXITCODE)."
        exit 1
    }

    # --- 2. Assemble scripts/install-intellij-ib-plugin.ps1 (standalone) ---

    Write-Host "Assembling $standaloneScriptOut ..."
    $pluginZipBase64 = [System.Convert]::ToBase64String([System.IO.File]::ReadAllBytes($pluginZip))
    $standaloneParts = @(
        (Get-Content (Join-Path $installerDir "standalone-header.ps1") -Raw),
        (Get-Content (Join-Path $installerDir "install-common.ps1") -Raw),
        "`$pluginZipBase64 = @'`n$pluginZipBase64`n'@`n",
        (Get-Content (Join-Path $installerDir "standalone-footer.ps1") -Raw)
    )
    Set-Content -Path $standaloneScriptOut -Value ($standaloneParts -join "`n") -NoNewline -Encoding utf8

    # --- 3. Assemble the SFX's own install.ps1 ------------------------------

    $sfxInstallPs1 = Join-Path $work "install.ps1"
    $sfxParts = @(
        (Get-Content (Join-Path $installerDir "sfx-header.ps1") -Raw),
        (Get-Content (Join-Path $installerDir "install-common.ps1") -Raw),
        (Get-Content (Join-Path $installerDir "sfx-footer.ps1") -Raw)
    )
    Set-Content -Path $sfxInstallPs1 -Value ($sfxParts -join "`n") -NoNewline -Encoding utf8

    # --- 4. Package the SFX payload -----------------------------------------

    $payloadZip = Join-Path $work "intellij-ib-plugin.zip"
    Copy-Item -Path $pluginZip -Destination $payloadZip -Force

    $payload7z = Join-Path $work "payload.7z"
    & $sevenZip a -t7z -mx=9 $payload7z $launcherExe $sfxInstallPs1 $payloadZip | Out-Null
    if ($LASTEXITCODE -ne 0) {
        Write-Error "7z.exe failed to package the payload (exit $LASTEXITCODE)."
        exit 1
    }

    # --- 5. Concatenate the manifested SFX stub + config + payload ---------

    Write-Host "Assembling $exeOut ..."
    $sfxStub = Join-Path $installerDir "7zSD-manifested.sfx"
    $configTxt = Join-Path $installerDir "config.txt"

    $outStream = [System.IO.File]::Create($exeOut)
    try {
        foreach ($part in @($sfxStub, $configTxt, $payload7z)) {
            $bytes = [System.IO.File]::ReadAllBytes($part)
            $outStream.Write($bytes, 0, $bytes.Length)
        }
    } finally {
        $outStream.Close()
    }

    Write-Host "Done:"
    Write-Host "  $standaloneScriptOut"
    Write-Host "  $exeOut"
} finally {
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}
