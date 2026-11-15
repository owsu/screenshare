[CmdletBinding()]
param(
    [string]$JdkPath,
    [switch]$Lint
)

$ErrorActionPreference = 'Stop'
$projectDirectory = $PSScriptRoot
$candidates = [System.Collections.Generic.List[string]]::new()
if ($JdkPath) {
    $candidates.Add($JdkPath)
} else {
    foreach ($candidate in @($env:WISAFE_JAVA17_HOME, $env:JAVA_HOME)) {
        if ($candidate) { $candidates.Add($candidate) }
    }
    $searchRoots = @(
        (Join-Path $env:ProgramFiles 'Eclipse Adoptium'),
        (Join-Path $env:ProgramFiles 'Java'),
        (Join-Path $env:USERPROFILE '.jdks'),
        (Join-Path $projectDirectory '.tools/jdk17')
    )
    foreach ($directory in $searchRoots) {
        foreach ($item in @(Get-ChildItem -LiteralPath $directory -Directory -ErrorAction SilentlyContinue)) {
            $candidates.Add($item.FullName)
        }
    }
}

$selectedJavaHome = $null
foreach ($candidate in $candidates) {
    $release = Join-Path $candidate 'release'
    $javaExecutable = Join-Path $candidate 'bin/java.exe'
    if ((Test-Path -LiteralPath $release) -and (Test-Path -LiteralPath $javaExecutable)) {
        if (Select-String -LiteralPath $release -Pattern '^JAVA_VERSION="17(?:\.|\")' -Quiet) {
            $selectedJavaHome = (Resolve-Path -LiteralPath $candidate).Path
            break
        }
    }
}
if (-not $selectedJavaHome) {
    throw 'JDK 17 was not found. Install Eclipse Temurin 17, or run .\build-apk.ps1 -JdkPath "C:\path\to\jdk-17".'
}

$previousJavaHome = $env:JAVA_HOME
$previousGradleHome = $env:GRADLE_USER_HOME
try {
    $env:JAVA_HOME = $selectedJavaHome
    if (-not $env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle' }
    Write-Host "Building WiSafe using JDK 17: $selectedJavaHome"
    & (Join-Path $selectedJavaHome 'bin/java.exe') -version
    if ($LASTEXITCODE -ne 0) { throw 'Could not run the selected Java installation.' }
    $gradleArguments = @("-Dorg.gradle.java.home=$selectedJavaHome", ':app:assembleDebug', '--no-daemon', '--console=plain')
    if ($Lint) { $gradleArguments += ':app:lintDebug' }
    Push-Location (Join-Path $projectDirectory 'android')
    try {
        & .\gradlew.bat @gradleArguments
        if ($LASTEXITCODE -ne 0) { throw "APK build failed (Gradle exit code $LASTEXITCODE). See the error above." }
    } finally { Pop-Location }
    $apk = Join-Path $projectDirectory 'android/app/build/outputs/apk/debug/app-debug.apk'
    if (-not (Test-Path -LiteralPath $apk)) { throw 'Gradle finished but the expected APK was not found.' }
    Write-Host "APK ready: $apk"
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:GRADLE_USER_HOME = $previousGradleHome
}
