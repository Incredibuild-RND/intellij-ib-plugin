// Launched by the self-extracting installer (config.txt's RunProgram) instead
// of running install.ps1 directly, so the install has zero visible windows:
// this is a GUI-subsystem executable (compiled with /target:winexe), so it
// never allocates a console of its own, and it runs the actual install.ps1
// via a child PowerShell process started with CreateNoWindow=true - hiding
// the window this way (rather than "-WindowStyle Hidden" alone, or wrapping
// it in a .cmd/.bat) is what avoids the console flash those approaches hit.
//
// The SFX deletes its own extraction temp folder as soon as this process
// exits, so the payload is copied to a stable "%TEMP%\ib-plugin-staging"
// folder first and run from there.
//
// On a non-zero exit code, shows a native, guaranteed-foreground message box
// (MB_TOPMOST | MB_SYSTEMMODAL) pointing at the log install.ps1 writes via
// Start-Transcript, since there is no console to show the failure in.
using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;

internal static class Launcher
{
    [DllImport("user32.dll", CharSet = CharSet.Unicode)]
    private static extern int MessageBoxW(IntPtr hWnd, string text, string caption, uint type);

    private const uint MB_OK = 0x00000000;
    private const uint MB_ICONERROR = 0x00000010;
    private const uint MB_TOPMOST = 0x00040000;
    private const uint MB_SYSTEMMODAL = 0x00001000;

    private static void Main()
    {
        string sourceDir = AppDomain.CurrentDomain.BaseDirectory;
        string stagingDir = Path.Combine(Path.GetTempPath(), "ib-plugin-staging");

        if (Directory.Exists(stagingDir))
        {
            Directory.Delete(stagingDir, true);
        }
        Directory.CreateDirectory(stagingDir);

        foreach (string fileName in new[] { "install.ps1", "intellij-ib-plugin.zip" })
        {
            File.Copy(Path.Combine(sourceDir, fileName), Path.Combine(stagingDir, fileName), true);
        }

        string scriptPath = Path.Combine(stagingDir, "install.ps1");

        var startInfo = new ProcessStartInfo
        {
            FileName = "powershell.exe",
            Arguments = "-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File \"" + scriptPath + "\"",
            UseShellExecute = false,
            CreateNoWindow = true,
        };

        int exitCode;
        using (Process process = Process.Start(startInfo))
        {
            process.WaitForExit();
            exitCode = process.ExitCode;
        }

        if (exitCode != 0)
        {
            string logPath = Path.Combine(Path.GetTempPath(), "ib-plugin-install.log");
            MessageBoxW(
                IntPtr.Zero,
                "The Incredibuild plugin installer failed (exit code " + exitCode + ").\n\nSee the log for details:\n" + logPath,
                "Incredibuild Installer",
                MB_OK | MB_ICONERROR | MB_TOPMOST | MB_SYSTEMMODAL);
        }

        Environment.Exit(exitCode);
    }
}
