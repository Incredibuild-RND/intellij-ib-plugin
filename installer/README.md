# Installer build pipeline

Produces both distributable installers from `build/distributions/intellij-ib-plugin.zip`:

- `scripts/install-intellij-ib-plugin.ps1` - a single, self-contained PowerShell
  script (the plugin zip is embedded as base64) for anyone who'd rather not run
  an unsigned `.exe`.
- `build/distributions/intellij-ib-plugin.exe` - a self-extracting installer
  with zero visible windows, for double-click installs.

## Usage

```
powershell -ExecutionPolicy Bypass -File .\installer\build-installer.ps1
```

This runs `.\gradlew.bat buildPlugin` itself first, so
`build/distributions/intellij-ib-plugin.zip` is always rebuilt from current
source before either installer is packaged - it can never silently package a
stale zip left over from an earlier build.

Prerequisites (both are standard on a Windows dev machine, neither is bundled):

- **7-Zip** (`7z.exe` on `PATH`, or installed at `C:\Program Files\7-Zip`) - used
  to package the `.exe`'s internal payload archive.
- **.NET Framework 4.x** (`csc.exe` under `%WINDIR%\Microsoft.NET`, present on
  every Windows install) - used to compile `Launcher.cs`.

## How the pieces fit together

Both generated scripts share one detection/validation block,
**`install-common.ps1`** - edit that single file (not either generated script)
for anything about how installs are found or validated, then re-run
`build-installer.ps1`. The two installers differ only in how they package the
plugin zip and report progress:

| | `install-common.ps1` sandwiched between... | plugin zip | output |
|---|---|---|---|
| Standalone script | `standalone-header.ps1` / `standalone-footer.ps1` | embedded as base64 | `scripts/install-intellij-ib-plugin.ps1` (committed to the repo) |
| SFX installer | `sfx-header.ps1` / `sfx-footer.ps1` | packaged alongside as a sibling file | assembled at build time, only shipped inside the `.exe` |

### The `.exe`'s pieces

- **`Launcher.cs`** - the SFX's `RunProgram` target (see `config.txt`). A
  GUI-subsystem executable (compiled with `/target:winexe`, so it never
  allocates a console), which stages the payload to a stable
  `%TEMP%\ib-plugin-staging` folder (the SFX deletes its own extraction temp
  folder as soon as `RunProgram` exits) and runs `install.ps1` there via a
  child PowerShell process started with `CreateNoWindow=true` - this, not
  `-WindowStyle Hidden` alone or a `.cmd`/`.vbs` wrapper, is what avoids a
  console flash. On a non-zero exit code it shows a native, guaranteed
  -foreground message box (`MB_TOPMOST | MB_SYSTEMMODAL`) pointing at the log
  `install.ps1` writes via `Start-Transcript`.
- **`config.txt`** - the 7-Zip SFX config block (`;!@Install@!...`) naming
  `install-launcher.exe` as `RunProgram`.
- **`installer.manifest`** / **`7zSD-manifested.sfx`** - `7zSD-manifested.sfx`
  is the LZMA SDK's `7zSD.sfx` stub (the only SFX module that supports
  `RunProgram`) with `installer.manifest` embedded via the Windows SDK's
  `mt.exe`, which is what suppresses a Windows Program Compatibility
  Assistant nag on an unsigned installer-shaped executable. This embedding is
  **not** part of the routine build (`mt.exe`/Windows SDK isn't assumed to be
  installed) - `7zSD-manifested.sfx` is committed pre-built. Regenerate it only
  if `installer.manifest` itself changes, via a pristine `7zSD.sfx` from the
  [LZMA SDK](https://www.7-zip.org/sdk.html):
  ```
  mt.exe -manifest installer.manifest -outputresource:7zSD.sfx;#1
  ```
  (must be run on a pristine stub *before* `build-installer.ps1` concatenates
  the payload onto it - `mt.exe` truncates any data appended after the PE's
  own declared end).

`build-installer.ps1` compiles `Launcher.cs`, assembles the SFX's
`install.ps1` from the shared pieces above, packages
`install-launcher.exe` + `install.ps1` + the plugin zip into a `.7z` archive,
then concatenates `7zSD-manifested.sfx` + `config.txt` + that archive into the
final `.exe` - exactly what a plain SFX archive is, just with a `RunProgram`
directive prepended per the 7-Zip SFX config format.
