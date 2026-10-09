# Build the JBC B·IRON Android app on Windows and (optionally) install it.
# Last modified: 2026-10-09--0107
#
# Windows twin of build.sh. Gradle can't build directly on the SMB/NAS path, so
# this mirrors the source to %USERPROFILE%\builds\jbc-android, builds there with
# JDK 17+ and the pinned Gradle 8.11.1, copies the APK back to .\out, and
# installs to the connected phone.
#
#   pwsh -NoProfile -File build.ps1                    # build + copy APK to .\out
#   pwsh -NoProfile -File build.ps1 install            # build + adb install (auto-picks the phone)
#   pwsh -NoProfile -File build.ps1 install -Serial X  # pick the device explicitly
#
# Signing: android\debug.keystore (gitignored) is the Mac's debug key, so Windows-
# and Mac-built APKs share one signature and `install -r` upgrades in place.
param([string]$Action = "", [string]$Serial = "")
$ErrorActionPreference = "Stop"

$Src       = $PSScriptRoot
$Build     = Join-Path $env:USERPROFILE "builds\jbc-android"
$Tools     = Join-Path $env:USERPROFILE "builds\tools"
$GradleVer = "8.11.1"
$Sdk       = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$Adb       = Join-Path $Sdk "platform-tools\adb.exe"

# --- JDK: AGP 8.7.3 needs 17+ (Temurin 17 is installed on this box) -----------
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $jdk = Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-*" -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending | Select-Object -First 1
    if (-not $jdk) { throw "No JDK found: set JAVA_HOME to a JDK 17-21." }
    $env:JAVA_HOME = $jdk.FullName
}

# --- Gradle 8.11.1: builds\tools, else the wrapper cache, else fetch once ------
$gradle = Join-Path $Tools "gradle-$GradleVer\bin\gradle.bat"
if (-not (Test-Path $gradle)) {
    $cached = Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-$GradleVer-*\*\gradle-$GradleVer\bin\gradle.bat" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($cached) {
        $gradle = $cached.FullName
    } else {
        New-Item -ItemType Directory -Force $Tools | Out-Null
        $zip = Join-Path $Tools "gradle-$GradleVer-bin.zip"
        Invoke-WebRequest "https://services.gradle.org/distributions/gradle-$GradleVer-bin.zip" -OutFile $zip
        Expand-Archive $zip -DestinationPath $Tools -Force
        Remove-Item $zip
    }
}

if (-not (Test-Path (Join-Path $Src "debug.keystore"))) {
    Write-Warning "android\debug.keystore missing - signing with this machine's own debug key; installing over a Mac-built APK will fail (copy it from macmini:~/.android/debug.keystore)."
}

# --- Mirror source to the local build dir (keep build/ + .gradle/ for incremental builds)
robocopy $Src $Build /MIR /XD build .gradle out /XF local.properties "._*" /NFL /NDL /NJH /NJS /NP | Out-Null
if ($LASTEXITCODE -ge 8) { throw "robocopy mirror failed (exit $LASTEXITCODE)" }
[IO.File]::WriteAllText((Join-Path $Build "local.properties"), "sdk.dir=$($Sdk -replace '\\','/')`n")

# Unit tests (ModelTest: parseStatus parity with jbc_biron.py) gate every build.
& $gradle -p $Build :app:testDebugUnitTest :app:assembleDebug --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { throw "gradle build failed (exit $LASTEXITCODE)" }

$Apk = Join-Path $Build "app\build\outputs\apk\debug\app-debug.apk"
New-Item -ItemType Directory -Force (Join-Path $Src "out") | Out-Null
Copy-Item $Apk (Join-Path $Src "out\jbc-biron-debug.apk") -Force
Write-Host "APK -> $(Join-Path $Src 'out\jbc-biron-debug.apk')"

if ($Action -eq "install") {
    if (-not $Serial) {
        # adb may also list non-Android gadgets (e.g. the Luckfox board) - pick API 28+ phones only.
        $attached = @(& $Adb devices | Select-String "`tdevice$" | ForEach-Object { ($_.Line -split "`t")[0] })
        $phones = @($attached | Where-Object {
            $api = "$(& $Adb -s $_ shell getprop ro.build.version.sdk 2>$null)".Trim()
            $api -match '^\d+$' -and [int]$api -ge 28
        })
        if ($phones.Count -eq 0) { throw "No Android (API 28+) phone on adb. Attached: $($attached -join ', ')" }
        if ($phones.Count -gt 1) { throw "Several phones on adb - pass -Serial. Candidates: $($phones -join ', ')" }
        $Serial = $phones[0]
    }
    & $Adb -s $Serial install -r $Apk
    if ($LASTEXITCODE -ne 0) { throw "adb install failed (signature mismatch? see debug.keystore note above)" }
    Write-Host "installed on $Serial."
}
