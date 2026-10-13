<#
.SYNOPSIS
  Installs a portable Android build toolchain (no Android Studio, no system-wide changes).

.DESCRIPTION
  Downloads into $ToolsDir and verifies checksums:
    - Eclipse Temurin JDK 17 (Adoptium)
    - Android command-line tools (Google)
    - Gradle (used once to generate the Gradle wrapper)
  Then installs SDK packages with sdkmanager.
  Nothing is written outside $ToolsDir; no environment variables are changed permanently.
  To uninstall, delete $ToolsDir.

.PARAMETER AcceptAndroidSdkLicense
  Required to install SDK packages. Passing it means you accept the Android SDK License
  Agreement: https://developer.android.com/studio/terms

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\setup_android_tools.ps1 -AcceptAndroidSdkLicense
#>
param(
    [string]$ToolsDir = "C:\dev\tools",
    [string]$JdkMajor = "17",
    [string]$CmdlineToolsZip = "commandlinetools-win-16111833_latest.zip",
    [string]$CmdlineToolsSha1 = "57d04f2d75eb8e8fffc5000a987e5de4b5a63e9d",
    [string]$GradleVersion = "8.9",
    [string[]]$SdkPackages = @("platform-tools", "platforms;android-35", "build-tools;34.0.0"),
    [switch]$AcceptAndroidSdkLicense
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

function Write-Step($msg) { Write-Host "[setup] $msg" }

function Get-File($url, $dest) {
    if (Test-Path $dest) { Write-Step "already downloaded: $dest"; return }
    Write-Step "downloading $url"
    & curl.exe -L --fail --retry 3 --retry-delay 5 -o "$dest.part" $url
    if ($LASTEXITCODE -ne 0) { throw "download failed: $url" }
    Move-Item "$dest.part" $dest
}

function Assert-Hash($file, $expected, $algo) {
    $actual = (Get-FileHash $file -Algorithm $algo).Hash.ToLower()
    if ($actual -ne $expected.ToLower()) {
        Remove-Item $file -Force
        throw "$algo mismatch for $file (expected $expected, got $actual). File deleted, rerun to retry."
    }
    Write-Step "$algo OK: $(Split-Path $file -Leaf)"
}

New-Item -ItemType Directory -Force -Path $ToolsDir, "$ToolsDir\downloads" | Out-Null
$dl = "$ToolsDir\downloads"

# ---------------- JDK ----------------
$jdkDir = Get-ChildItem $ToolsDir -Directory -Filter "jdk-$JdkMajor*" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jdkDir) {
    $meta = Invoke-RestMethod "https://api.adoptium.net/v3/assets/latest/$JdkMajor/hotspot?architecture=x64&image_type=jdk&os=windows&vendor=eclipse"
    $pkg = ($meta | Select-Object -First 1).binary.package
    $zip = Join-Path $dl $pkg.name
    Get-File $pkg.link $zip
    Assert-Hash $zip $pkg.checksum "SHA256"
    Write-Step "extracting JDK"
    & tar.exe -xf $zip -C $ToolsDir
    $jdkDir = Get-ChildItem $ToolsDir -Directory -Filter "jdk-$JdkMajor*" | Select-Object -First 1
}
$javaHome = $jdkDir.FullName
Write-Step "JAVA_HOME = $javaHome"

# ---------------- Android command-line tools ----------------
$sdkRoot = Join-Path $ToolsDir "android-sdk"
$sdkManager = Join-Path $sdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
if (-not (Test-Path $sdkManager)) {
    $zip = Join-Path $dl $CmdlineToolsZip
    Get-File "https://dl.google.com/android/repository/$CmdlineToolsZip" $zip
    Assert-Hash $zip $CmdlineToolsSha1 "SHA1"
    $tmp = Join-Path $ToolsDir "_cmdline_tmp"
    if (Test-Path $tmp) { Remove-Item $tmp -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $tmp, "$sdkRoot\cmdline-tools" | Out-Null
    & tar.exe -xf $zip -C $tmp
    # sdkmanager requires the layout <sdk>/cmdline-tools/latest/bin
    Move-Item (Join-Path $tmp "cmdline-tools") (Join-Path $sdkRoot "cmdline-tools\latest")
    Remove-Item $tmp -Recurse -Force
}
Write-Step "ANDROID_HOME = $sdkRoot"

# ---------------- Gradle (only to generate the wrapper) ----------------
$gradleHome = Join-Path $ToolsDir "gradle-$GradleVersion"
if (-not (Test-Path "$gradleHome\bin\gradle.bat")) {
    $zip = Join-Path $dl "gradle-$GradleVersion-bin.zip"
    Get-File "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip" $zip
    $sha = (& curl.exe -sL "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip.sha256").Trim()
    Assert-Hash $zip $sha "SHA256"
    & tar.exe -xf $zip -C $ToolsDir
}
Write-Step "GRADLE_HOME = $gradleHome"

# ---------------- SDK packages ----------------
$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $sdkRoot
if (-not $AcceptAndroidSdkLicense) {
    Write-Step "SDK packages NOT installed: rerun with -AcceptAndroidSdkLicense to accept the Android SDK License Agreement."
    exit 0
}
Write-Step "accepting SDK licenses (user passed -AcceptAndroidSdkLicense)"
("y`n" * 40) | & $sdkManager "--sdk_root=$sdkRoot" --licenses | Out-Null
Write-Step "installing: $($SdkPackages -join ', ')"
# Package names contain ';' which cmd.exe (sdkmanager.bat) treats as a separator,
# so pass them through a package file instead of the command line.
$pkgFile = Join-Path $ToolsDir "sdk_packages.txt"
Set-Content -Path $pkgFile -Value $SdkPackages -Encoding ascii
& $sdkManager "--sdk_root=$sdkRoot" "--package_file=$pkgFile"
if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed" }
foreach ($p in $SdkPackages) {
    if (-not (Test-Path (Join-Path $sdkRoot ($p -replace ';', '\')))) { throw "package missing after install: $p" }
}

Write-Step "done"
Write-Step "adb: $sdkRoot\platform-tools\adb.exe"
