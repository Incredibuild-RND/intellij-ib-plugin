foreach ($install in $installs) {
    $pluginsDir = Join-Path $env:APPDATA "JetBrains\$($install.DataDirectoryName)\plugins"
    if (-not (Test-Path $pluginsDir)) {
        New-Item -ItemType Directory -Path $pluginsDir -Force | Out-Null
    }

    $targetPluginDir = Join-Path $pluginsDir "intellij-ib-plugin"
    if (Test-Path $targetPluginDir) {
        Remove-Item -Recurse -Force $targetPluginDir
    }

    Expand-Archive -Path $pluginZip -DestinationPath $pluginsDir -Force
    Write-Host "Installed into $($install.Name) ($pluginsDir)."
}

Write-Host "Install complete."
Stop-Transcript | Out-Null
