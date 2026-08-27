# Windows desktop packaging

Run the pinned wrapper-driven packaging pipeline from the repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\package-desktop.ps1
```

The script builds the reactor offline, assembles a `jpackage` application image, runs the
packaged launcher's clean-start health check, creates a portable ZIP and Windows EXE installer,
and writes `target/desktop-dist/SHA256SUMS.txt`.

Use `-SkipInstaller` only for local portable-image diagnostics. Formal phase gates require both
the portable ZIP and installer.
