$tempZip = Join-Path $env:TEMP "intellij-ib-plugin-install.zip"
$pluginZipBytes = [System.Convert]::FromBase64String(($pluginZipBase64 -replace '\s', ''))
[System.IO.File]::WriteAllBytes($tempZip, $pluginZipBytes)

foreach ($install in $installs) {
    $pluginsDir = Join-Path $env:APPDATA "JetBrains\$($install.DataDirectoryName)\plugins"
    if (-not (Test-Path $pluginsDir)) {
        New-Item -ItemType Directory -Path $pluginsDir -Force | Out-Null
    }

    $targetPluginDir = Join-Path $pluginsDir "intellij-ib-plugin"
    if (Test-Path $targetPluginDir) {
        Remove-Item -Recurse -Force $targetPluginDir
    }

    Expand-Archive -Path $tempZip -DestinationPath $pluginsDir -Force
    Write-Host "Installed into $($install.Name) ($pluginsDir)."
}

Remove-Item $tempZip -Force
Write-Host "Install complete. Start the IDE to use the Incredibuild plugin."
