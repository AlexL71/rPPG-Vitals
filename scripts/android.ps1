<#
.SYNOPSIS
  Build, test and install the Android app with the portable toolchain in $ToolsDir.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File scripts\android.ps1 test      # JVM unit tests (incl. golden test)
  powershell -ExecutionPolicy Bypass -File scripts\android.ps1 build     # debug APK
  powershell -ExecutionPolicy Bypass -File scripts\android.ps1 install   # build + install on the USB phone + start
  powershell -ExecutionPolicy Bypass -File scripts\android.ps1 logs      # app log output
  powershell -ExecutionPolicy Bypass -File scripts\android.ps1 pull      # copy recorded session CSVs to data\phone_sessions
#>
param(
    [Parameter(Position = 0)][ValidateSet("test", "build", "install", "logs", "pull", "devices")]
    [string]$Task = "build",
    [string]$ToolsDir = "C:\dev\tools"
)
$ErrorActionPreference = "Stop"
$repo = Split-Path $PSScriptRoot -Parent
$androidDir = Join-Path $repo "android"
$appId = "com.rppg.vitals"

$jdk = Get-ChildItem $ToolsDir -Directory -Filter "jdk-17*" -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jdk) { throw "JDK not found in $ToolsDir - run scripts\setup_android_tools.ps1 first" }
$env:JAVA_HOME = $jdk.FullName
$env:ANDROID_HOME = Join-Path $ToolsDir "android-sdk"
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"

# local.properties tells Gradle where the SDK is (machine-specific, git-ignored)
$lp = Join-Path $androidDir "local.properties"
$sdkEscaped = $env:ANDROID_HOME -replace '\\', '\\' -replace ':', '\:'
Set-Content -Path $lp -Value "sdk.dir=$sdkEscaped" -Encoding ascii

function Invoke-Gradle([string[]]$gradleArgs) {
    Push-Location $androidDir
    try {
        & .\gradlew.bat @gradleArgs
        if ($LASTEXITCODE -ne 0) { throw "gradle $gradleArgs failed" }
    } finally { Pop-Location }
}

switch ($Task) {
    "test"    { Invoke-Gradle @("testDebugUnitTest", "--console=plain") }
    "build"   { Invoke-Gradle @("assembleDebug", "--console=plain")
                Write-Host "APK: $androidDir\app\build\outputs\apk\debug\app-debug.apk" }
    "install" { Invoke-Gradle @("assembleDebug", "--console=plain")
                & $adb install -r -g "$androidDir\app\build\outputs\apk\debug\app-debug.apk"
                if ($LASTEXITCODE -ne 0) { throw "adb install failed - is the phone connected with USB debugging allowed?" }
                & $adb shell am start -n "$appId/.MainActivity" }
    "logs"    { & $adb logcat -d -s "FrameAnalyzer:*" "MainActivity:*" "AndroidRuntime:E" }
    "devices" { & $adb devices -l }
    "pull"    { $dest = Join-Path $repo "data\phone_sessions"
                New-Item -ItemType Directory -Force $dest | Out-Null
                & $adb pull "/sdcard/Android/data/$appId/files/sessions/." $dest
                Write-Host "sessions copied to $dest" }
}
