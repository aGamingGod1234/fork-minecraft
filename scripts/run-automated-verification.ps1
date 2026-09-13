[CmdletBinding()]
param([string] $ProjectRoot)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'project-metadata.ps1')
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }

$Project = [IO.Path]::GetFullPath($ProjectRoot)
$JavaHome = Join-Path $Project 'runtime\toolchains\temurin-25\jdk-25.0.3+9'
$Java = Join-Path $JavaHome 'bin\java.exe'
$Coordinator = Join-Path $Project 'coordinator'
$CoordinatorPackagingVerifier = Join-Path $Project 'scripts\verify-coordinator-packaging.ps1'
$DistributionRuntimeVerifier = Join-Path $Project 'scripts\verify-distribution-runtime.ps1'
$StartupPackagingVerifier = Join-Path $Project 'scripts\verify-startup-packaging.ps1'
$NormalProfileUpdaterVerifier = Join-Path $Project 'scripts\test-install-normal-profile-update.ps1'
$RetainedFeatureVerifier = Join-Path $Project 'scripts\audit-retained-features.ps1'
$AgentJar = Resolve-ArenaModJar $Project
if (-not (Test-Path -LiteralPath $Java -PathType Leaf)) { throw "Missing project JDK: $Java" }
if (-not (Test-Path -LiteralPath (Join-Path $Coordinator 'package.json') -PathType Leaf)) { throw 'Missing coordinator package.json.' }
if (-not (Test-Path -LiteralPath $CoordinatorPackagingVerifier -PathType Leaf)) { throw 'Missing coordinator packaging verifier.' }
if (-not (Test-Path -LiteralPath $DistributionRuntimeVerifier -PathType Leaf)) { throw 'Missing distribution runtime verifier.' }
if (-not (Test-Path -LiteralPath $StartupPackagingVerifier -PathType Leaf)) { throw 'Missing startup packaging verifier.' }
if (-not (Test-Path -LiteralPath $NormalProfileUpdaterVerifier -PathType Leaf)) { throw 'Missing normal profile updater verifier.' }
if (-not (Test-Path -LiteralPath $RetainedFeatureVerifier -PathType Leaf)) { throw 'Missing retained-feature audit.' }

$env:JAVA_HOME = $JavaHome
Push-Location $Project
try {
    $gradle = Start-Process -FilePath (Join-Path $Project 'gradlew.bat') `
        -ArgumentList @('clean', 'check', 'build', 'verifyCore', '--no-build-cache', '--rerun-tasks', '--no-daemon', '--console=plain') `
        -NoNewWindow -Wait -PassThru
    if ($gradle.ExitCode -ne 0) { throw "Gradle verification failed with code $($gradle.ExitCode)" }
} finally { Pop-Location }

& $CoordinatorPackagingVerifier -JarPath $AgentJar
& $DistributionRuntimeVerifier
& $StartupPackagingVerifier -PackageRoot $Project
& $NormalProfileUpdaterVerifier -ProjectRoot $Project
& $RetainedFeatureVerifier -ProjectRoot $Project

Push-Location $Coordinator
try {
    $npm = Start-Process -FilePath 'npm.cmd' -ArgumentList @('test') -NoNewWindow -Wait -PassThru
    if ($npm.ExitCode -ne 0) { throw "Coordinator tests failed with code $($npm.ExitCode)" }
} finally { Pop-Location }

$launcherInstaller = Get-Content -LiteralPath (Join-Path $Project 'scripts\install-launcher-profiles.ps1') -Raw
foreach ($requiredContract in @(
    'arenaagents.bridgeSecretFile',
    'runtime\bridge-secret.txt',
    'RandomNumberGenerator',
    'LegacyJavaArgs'
)) {
    if ($launcherInstaller.IndexOf($requiredContract, [StringComparison]::Ordinal) -lt 0) {
        throw "Launcher installer is missing the bridge-secret contract: $requiredContract"
    }
}

Write-Host 'Automated Java, Fabric, coordinator, and fake-E2E verification passed.'
