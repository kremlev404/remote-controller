#Requires -Version 5.1
<#
.SYNOPSIS
    Builds the desktop app and copies outputs to artifacts/desktop.

.PARAMETER Target
    all      - portable folder + exe + msi (default)
    portable - unpacked app only (createDistributable)
    exe      - Windows .exe installer
    msi      - Windows .msi installer

.PARAMETER Clean
    Runs :desktop:clean before building.

.EXAMPLE
    .\scripts\build-desktop.ps1
    .\scripts\build-desktop.ps1 -Target exe
#>
param(
    [ValidateSet("all", "portable", "exe", "msi")]
    [string]$Target = "all",

    [switch]$Clean
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$ArtifactsDir = Join-Path $ProjectRoot "artifacts\desktop"
$BinariesDir = Join-Path $ProjectRoot "desktop\build\compose\binaries\main"
$PackageName = "Remote Controller Server"

function Resolve-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }

    $candidates = @(
        "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
        "${env:ProgramFiles}\Android\Android Studio\jbr",
        "${env:ProgramFiles(x86)}\Android\Android Studio\jbr",
        "${env:ProgramFiles}\Java\jdk-21",
        "${env:ProgramFiles}\Java\jdk-17",
        "${env:ProgramFiles}\Eclipse Adoptium\jdk-21*",
        "${env:ProgramFiles}\Microsoft\jdk-21*"
    )

    foreach ($candidate in $candidates) {
        $matches = @(Get-Item $candidate -ErrorAction SilentlyContinue)
        foreach ($match in $matches) {
            $javaExe = Join-Path $match.FullName "bin\java.exe"
            if (Test-Path $javaExe) {
                return $match.FullName
            }
        }
    }

    throw "JDK not found. Set JAVA_HOME or install JDK 17+."
}

function Resolve-GradleLauncher {
    $gradlew = Join-Path $ProjectRoot "gradlew.bat"
    if (Test-Path $gradlew) {
        return $gradlew
    }

    $cachedGradle = Get-ChildItem -Path "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.6.1-bin\*\gradle-9.6.1\bin\gradle.bat" `
        -ErrorAction SilentlyContinue |
        Select-Object -First 1

    if ($cachedGradle) {
        return $cachedGradle.FullName
    }

    $pathGradle = Get-Command gradle -ErrorAction SilentlyContinue
    if ($pathGradle) {
        return $pathGradle.Source
    }

    throw "Gradle not found. Open the project in Android Studio once, or add Gradle Wrapper (gradlew.bat)."
}

function Invoke-Gradle {
    param([string[]]$Tasks)

    $javaHome = Resolve-JavaHome
    $gradle = Resolve-GradleLauncher

    $env:JAVA_HOME = $javaHome
    $env:PATH = "$(Join-Path $javaHome 'bin');$env:PATH"

    Write-Host "Using JAVA_HOME=$javaHome"
    Write-Host "Using Gradle=$gradle"
    Write-Host "Running: $gradle $($Tasks -join ' ')"

    & $gradle @Tasks --project-dir $ProjectRoot --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed with exit code $LASTEXITCODE"
    }
}

function Copy-IfExists {
    param(
        [string]$SourcePattern,
        [string]$DestinationDir
    )

    $items = Get-ChildItem -Path $SourcePattern -ErrorAction SilentlyContinue
    foreach ($item in $items) {
        Copy-Item -Path $item.FullName -Destination $DestinationDir -Force
        Write-Host "Copied $($item.Name) -> $DestinationDir"
    }
}

Write-Host "Project: $ProjectRoot"
Write-Host "Artifacts: $ArtifactsDir"

New-Item -ItemType Directory -Force -Path $ArtifactsDir | Out-Null

$tasks = @()
if ($Clean) {
    $tasks += ":desktop:clean"
}

switch ($Target) {
    "portable" { $tasks += ":desktop:createDistributable" }
    "exe" { $tasks += ":desktop:packageExe" }
    "msi" { $tasks += ":desktop:packageMsi" }
    "all" {
        $tasks += @(
            ":desktop:createDistributable",
            ":desktop:packageExe",
            ":desktop:packageMsi"
        )
    }
}

Invoke-Gradle -Tasks $tasks

if ($Target -in @("all", "portable")) {
    $portableSource = Join-Path $BinariesDir "app\$PackageName"
    $portableDest = Join-Path $ArtifactsDir "portable"

    if (-not (Test-Path $portableSource)) {
        throw "Portable build output not found: $portableSource"
    }

    if (Test-Path $portableDest) {
        Remove-Item -Path $portableDest -Recurse -Force
    }

    Copy-Item -Path $portableSource -Destination $portableDest -Recurse
    Write-Host "Copied portable app -> $portableDest"
}

if ($Target -in @("all", "exe")) {
    Copy-IfExists -SourcePattern (Join-Path $BinariesDir "exe\*.exe") -DestinationDir $ArtifactsDir
}

if ($Target -in @("all", "msi")) {
    Copy-IfExists -SourcePattern (Join-Path $BinariesDir "msi\*.msi") -DestinationDir $ArtifactsDir
}

Write-Host ""
Write-Host "Done. Desktop artifacts are in:"
Write-Host "  $ArtifactsDir"
