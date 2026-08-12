<#
.SYNOPSIS
Builds the plugin and publishes it to the org's JFrog Artifactory Conan
remote, so ib_win_core_dev's installer can pull it in as a Conan dependency
(the same way it already does for ib_visual_studio_extensions).

.DESCRIPTION
Run it with:
    powershell -ExecutionPolicy Bypass -File .\scripts\publish-conan.ps1

Prompts for your JFrog Artifactory username and password. If you normally
log into Artifactory via SSO (no separate Artifactory password), generate a
personal API key or Identity Token from your JFrog user profile page instead
and use that as the password here - Artifactory accepts either. This is a
personal credential, distinct from the JFROG_CONAN_USERNAME/JFROG_CONAN_PASSWORD
service-account secrets used by the "Publish to Conan" GitHub Actions workflow.
Requires Conan 1.x on PATH.

The published version always comes from gradle.properties (via
conanfile.py), never from a parameter here - bump the version there first.

.PARAMETER Force
Also removes any existing LOCAL Conan cache entry for the current version
before exporting. Note this does not affect Artifactory: Conan treats a
version already uploaded there as immutable, so re-uploading the same
version will still fail server-side regardless of this flag - bump the
version in gradle.properties instead if you need to publish again.
#>

param(
    [switch]$Force
)

# Deliberately not using $ErrorActionPreference = "Stop": in PowerShell 5.1,
# any stderr output from a native .exe (conan.exe here) becomes a terminating
# error under "Stop" before a "2>$null" redirect can suppress it - e.g. the
# harmless "remote already exists" message below would abort the whole
# script. Every call whose failure should actually stop the script already
# checks $LASTEXITCODE explicitly instead.

$repoRoot = Split-Path -Parent $PSScriptRoot
# ib-general-conan (not the "-local" variant) is what ib_win_core_dev's own
# README and local upload scripts document for publishing proprietary Conan
# recipes - personal developer accounts appear to have deploy permission
# there but not on ib-general-conan-local, which is likely reserved for the
# CI service account.
$conanRemoteName = "ib-general-conan"
$conanRemoteUrl = "https://incredibuild.jfrog.io/artifactory/api/conan/ib-general-conan"

Push-Location $repoRoot
try {
    Write-Host "Building plugin..."
    & "$repoRoot\gradlew.bat" buildPlugin
    if ($LASTEXITCODE -ne 0) {
        Write-Error "gradlew buildPlugin failed (exit $LASTEXITCODE)."
        exit 1
    }

    $versionLine = & "$repoRoot\gradlew.bat" properties --property version --quiet --console=plain | Select-Object -Last 1
    $version = $versionLine.Substring($versionLine.IndexOf(' ') + 1).Trim()
    Write-Host "Publishing intellij_ib_plugin/$version"

    conan remote add $conanRemoteName $conanRemoteUrl 2>$null

    $jfrogUser = Read-Host "JFrog Artifactory username (e.g. your email)"
    $jfrogPasswordSecure = Read-Host "JFrog Artifactory password or API key/Identity Token (if you log in via SSO, generate one from your JFrog user profile page)" -AsSecureString
    $jfrogPasswordBstr = [System.Runtime.InteropServices.Marshal]::SecureStringToBSTR($jfrogPasswordSecure)
    try {
        $jfrogPassword = [System.Runtime.InteropServices.Marshal]::PtrToStringBSTR($jfrogPasswordBstr)
    } finally {
        [System.Runtime.InteropServices.Marshal]::ZeroFreeBSTR($jfrogPasswordBstr)
    }

    conan user -r $conanRemoteName -p $jfrogPassword $jfrogUser
    if ($LASTEXITCODE -ne 0) {
        Write-Error "conan user (authentication) failed (exit $LASTEXITCODE)."
        exit 1
    }

    if ($Force) {
        conan remove "intellij_ib_plugin/$version" -f 2>$null
    }

    conan export-pkg . "intellij_ib_plugin/$version@"
    if ($LASTEXITCODE -ne 0) {
        Write-Error "conan export-pkg failed (exit $LASTEXITCODE)."
        exit 1
    }

    conan upload intellij_ib_plugin --remote $conanRemoteName --all --confirm
    if ($LASTEXITCODE -ne 0) {
        Write-Error "conan upload failed (exit $LASTEXITCODE)."
        exit 1
    }

    conan search intellij_ib_plugin --remote $conanRemoteName

    Write-Host "Done: published intellij_ib_plugin/$version to $conanRemoteName."
} finally {
    Pop-Location
}
