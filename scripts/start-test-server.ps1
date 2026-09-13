[CmdletBinding()]
param(
    [string] $ProjectRoot,
    [string] $SecretPath,
    [switch] $OfflineSmoke
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'offline-server-policy.ps1')

$Project = [IO.Path]::GetFullPath($ProjectRoot)
$Java = Join-Path $Project 'runtime\toolchains\temurin-25\jdk-25.0.3+9\bin\java.exe'
$Server = Join-Path $Project $(if ($OfflineSmoke) { 'runtime\server-offline-smoke' } else { 'runtime\server' })
$Launcher = Join-Path $Server 'fabric-server-launch.jar'
$Properties = Join-Path $Server 'server.properties'
if ([string]::IsNullOrWhiteSpace($SecretPath)) { $SecretPath = Join-Path $Project 'runtime\bridge-secret.txt' }

foreach ($required in @($Java,$Launcher,$Properties)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing server prerequisite: $required" }
}
$expectedMode = if ($OfflineSmoke) { 'online-mode=false' } else { 'online-mode=true' }
Assert-ArenaServerMode $Properties ($expectedMode.Substring('online-mode='.Length))
if ($OfflineSmoke) {
    Assert-ArenaOfflineServerLoopback $Properties -RequireOffline
    Write-Warning 'Starting OFFLINE SMOKE server. Results are not authenticated-player verification.'
} else {
    Write-Host 'Starting authenticated online-mode test server.'
}

$secretDirectory = Split-Path -Parent $SecretPath
New-Item -ItemType Directory -Force -Path $secretDirectory | Out-Null
if (-not (Test-Path -LiteralPath $SecretPath -PathType Leaf)) {
    $bytes = [byte[]]::new(48)
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    $payload = [Text.UTF8Encoding]::new($false).GetBytes([Convert]::ToBase64String($bytes))
    try {
        $stream = [IO.File]::Open($SecretPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $stream.Write($payload, 0, $payload.Length) } finally { $stream.Dispose() }
    } catch [IO.IOException] {
        if (-not (Test-Path -LiteralPath $SecretPath -PathType Leaf)) { throw }
    }
}
$secret = [IO.File]::ReadAllText($SecretPath).Trim()
if ($secret.Length -lt 32) { throw "Bridge secret must contain at least 32 characters: $SecretPath" }
$bridgeSecretProperty = "-Darenaagents.bridgeSecretFile=$([IO.Path]::GetFullPath($SecretPath))"

Push-Location $Server
try {
    & $Java $bridgeSecretProperty -Xms1G -Xmx4G -jar $Launcher nogui
    if ($LASTEXITCODE -ne 0) { throw "Minecraft server exited with code $LASTEXITCODE" }
} finally { Pop-Location }
