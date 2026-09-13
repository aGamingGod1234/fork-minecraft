[CmdletBinding()]
param(
    [string] $ProjectRoot,
    [string] $ArchivePath,
    [string] $ArchiveSha256
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }

$ExpectedVersion = '25.0.3'
$ToolchainRoot = Join-Path ([IO.Path]::GetFullPath($ProjectRoot)) 'runtime\toolchains\temurin-25'
$JdkRoot = Join-Path $ToolchainRoot 'jdk-25.0.3+9'
$Java = Join-Path $JdkRoot 'bin\java.exe'

function Assert-Java25([string] $JavaPath) {
    if (-not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) {
        throw "Java executable is missing: $JavaPath"
    }
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $JavaPath
    $startInfo.Arguments = '-version'
    $startInfo.UseShellExecute = $false
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [Diagnostics.Process]::Start($startInfo)
    $versionOutput = $process.StandardOutput.ReadToEnd() + $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0 -or $versionOutput -notmatch [regex]::Escape($ExpectedVersion)) {
        throw "Expected Temurin Java $ExpectedVersion at $JavaPath. Output: $versionOutput"
    }
}

if (Test-Path -LiteralPath $Java -PathType Leaf) {
    Assert-Java25 $Java
    Write-Host "Verified existing project-local JDK: $JdkRoot"
    exit 0
}

if ([string]::IsNullOrWhiteSpace($ArchivePath) -or [string]::IsNullOrWhiteSpace($ArchiveSha256)) {
    throw 'JDK is absent. Supply -ArchivePath and its trusted -ArchiveSha256; unverified downloads are refused.'
}

$resolvedArchive = (Resolve-Path -LiteralPath $ArchivePath).Path
$actualHash = (Get-FileHash -LiteralPath $resolvedArchive -Algorithm SHA256).Hash
if (-not $actualHash.Equals($ArchiveSha256, [StringComparison]::OrdinalIgnoreCase)) {
    throw "JDK archive SHA-256 mismatch. Expected $ArchiveSha256, got $actualHash."
}

New-Item -ItemType Directory -Force -Path $ToolchainRoot | Out-Null
$temporaryRoot = Join-Path $ToolchainRoot '.extracting'
if (Test-Path -LiteralPath $temporaryRoot) {
    Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $temporaryRoot | Out-Null
try {
    Expand-Archive -LiteralPath $resolvedArchive -DestinationPath $temporaryRoot
    $candidate = Get-ChildItem -LiteralPath $temporaryRoot -Directory | Select-Object -First 1
    if ($null -eq $candidate) { throw 'JDK archive did not contain a root directory.' }
    Move-Item -LiteralPath $candidate.FullName -Destination $JdkRoot
} finally {
    if (Test-Path -LiteralPath $temporaryRoot) {
        Remove-Item -LiteralPath $temporaryRoot -Recurse -Force
    }
}

Assert-Java25 $Java
Write-Host "Installed and verified project-local JDK: $JdkRoot"
