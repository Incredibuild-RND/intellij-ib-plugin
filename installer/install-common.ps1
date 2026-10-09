# Shared by both installer/standalone-footer.ps1 (assembled into the
# self-contained scripts/install-intellij-ib-plugin.ps1) and
# installer/sfx-footer.ps1 (assembled into the SFX installer's own
# install.ps1) via installer/build-installer.ps1 - edit this ONE file rather
# than both generated scripts.

# RR = RustRover, IU = IntelliJ IDEA Ultimate, RD = Rider (the .NET actions -
# see rider-support.xml). Community editions (IC) don't carry
# com.intellij.modules.ultimate, which the Rust plugin requires there.
$targetProductCodes = @("RR", "IU", "RD")

# Installs can land in quite a few places, so several roots are searched
# recursively rather than assuming one fixed layout:
#  - "C:\Program Files\JetBrains\<Product>" - the standalone installer's
#    default when run elevated.
#  - "%LOCALAPPDATA%\JetBrains\..." - covers both JetBrains Toolbox (which
#    nests several levels deeper, under "Toolbox\apps\<Product>\ch-0\<build>")
#    and the standalone installer's own default when run WITHOUT admin
#    rights, which silently falls back to a per-user install here instead
#    of Program Files.
#  - Whatever the Windows registry's own Uninstall entries report as the
#    InstallLocation for a matching product - the authoritative source for
#    a custom install path (e.g. a different drive), which no fixed-path
#    guess can anticipate.
$registryInstallLocations = @(
    "HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall",
    "HKLM:\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall",
    "HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall"
) | Where-Object { Test-Path $_ } | ForEach-Object {
    Get-ChildItem -Path $_ -ErrorAction SilentlyContinue
} | ForEach-Object {
    Get-ItemProperty -Path $_.PSPath -ErrorAction SilentlyContinue
} | Where-Object {
    $_.DisplayName -match "RustRover|IntelliJ IDEA|Rider" -and $_.InstallLocation
} | ForEach-Object { $_.InstallLocation } | Select-Object -Unique

$searchRoots = @(
    "C:\Program Files\JetBrains",
    (Join-Path $env:LOCALAPPDATA "JetBrains")
) + $registryInstallLocations

# Every JetBrains product found (any productCode), so a "not found" error can
# report what actually IS here - e.g. a Community edition, which is filtered
# out below but is useful to see rather than a bare "nothing found".
$allProducts = $searchRoots | Where-Object { Test-Path $_ } | ForEach-Object {
    Get-ChildItem -Path $_ -Recurse -Filter "product-info.json" -ErrorAction SilentlyContinue
} | ForEach-Object {
    $infoPath = $_.FullName
    try {
        $info = Get-Content $infoPath -Raw | ConvertFrom-Json
    } catch {
        return
    }
    [PSCustomObject]@{
        InfoPath          = $infoPath
        ProductName       = $info.name
        ProductCode       = $info.productCode
        DataDirectoryName = $info.dataDirectoryName
        Launch            = $info.launch
    }
}

$installs = $allProducts | Where-Object { $targetProductCodes -contains $_.ProductCode } | ForEach-Object {
    $product = $_
    $winLaunch = $product.Launch | Where-Object { $_.os -eq "Windows" } | Select-Object -First 1
    if (-not $winLaunch) { return }

    [PSCustomObject]@{
        Name              = Split-Path -Leaf (Split-Path -Parent $product.InfoPath)
        DataDirectoryName = $product.DataDirectoryName
        ProcessName       = [System.IO.Path]::GetFileNameWithoutExtension($winLaunch.launcherPath)
    }
} | Sort-Object -Property DataDirectoryName -Unique

# A no-op if no transcript is running (Start-Transcript is only called in the
# SFX installer's copy of this logic, not the standalone script's), safe to
# call unconditionally from both under $ErrorActionPreference = "Stop".
function Stop-InstallerTranscriptIfRunning {
    try { Stop-Transcript | Out-Null } catch {}
}

if (-not $installs) {
    $searched = ($searchRoots | Select-Object -Unique) -join "; "
    $message = "No supported JetBrains IDE (RustRover, IntelliJ IDEA Ultimate or Rider) found. Searched: $searched."
    if ($allProducts) {
        $found = ($allProducts | ForEach-Object { "$($_.ProductName) [$($_.ProductCode)] at $($_.InfoPath)" }) -join "; "
        $message += " Found (but not supported by this plugin): $found."
    }
    Write-Error $message
    Stop-InstallerTranscriptIfRunning
    exit 1
}

$anyRunning = $false
foreach ($install in $installs) {
    if (Get-Process -Name $install.ProcessName -ErrorAction SilentlyContinue) {
        Write-Error "$($install.Name) is currently running. Close it, then re-run this installer."
        $anyRunning = $true
    }
}
if ($anyRunning) {
    Stop-InstallerTranscriptIfRunning
    exit 1
}
