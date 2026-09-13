[CmdletBinding()]
param([string] $ProjectRoot)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }

$Project = [IO.Path]::GetFullPath($ProjectRoot)
$JavaHome = Join-Path $Project 'runtime\toolchains\temurin-25\jdk-25.0.3+9'
$Java = Join-Path $JavaHome 'bin\java.exe'
$Coordinator = Join-Path $Project 'coordinator'
$Gradle = Join-Path $Project 'gradlew.bat'
$RetainedFeatureVerifier = Join-Path $Project 'scripts\audit-retained-features.ps1'

foreach ($required in @($Java, $Gradle, (Join-Path $Coordinator 'package.json'), $RetainedFeatureVerifier)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Missing incremental verification prerequisite: $required"
    }
}

$previousJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $JavaHome
Push-Location $Project
try {
    # This route deliberately keeps Gradle outputs and its build cache. The
    # clean, no-cache verifier remains the release/CI correctness gate.
    & $Gradle check --build-cache --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw "Incremental Gradle verification failed with code $LASTEXITCODE" }
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
}

Push-Location $Coordinator
try {
    & npm.cmd test
    if ($LASTEXITCODE -ne 0) { throw "Incremental coordinator tests failed with code $LASTEXITCODE" }
} finally {
    Pop-Location
}

& $RetainedFeatureVerifier -ProjectRoot $Project

Write-Host 'Incremental Java, Fabric, and coordinator verification passed.'
