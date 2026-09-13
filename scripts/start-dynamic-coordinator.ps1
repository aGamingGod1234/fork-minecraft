[CmdletBinding()]
param(
    [string] $ProjectRoot,
    [string] $ConfigPath,
    [string] $SecretPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
$Project = [IO.Path]::GetFullPath($ProjectRoot)
$Coordinator = Join-Path $Project 'coordinator'
$Main = Join-Path $Coordinator 'src\dynamic-main.mjs'
if ([string]::IsNullOrWhiteSpace($ConfigPath)) { $ConfigPath = Join-Path $Coordinator 'config\dynamic-agents.json' }
if ([string]::IsNullOrWhiteSpace($SecretPath)) { $SecretPath = Join-Path $Project 'runtime\bridge-secret.txt' }

$ResolvedConfig = (Resolve-Path -LiteralPath $ConfigPath).Path
foreach ($required in @($Main, $ResolvedConfig)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing dynamic coordinator prerequisite: $required" }
}

$node = (Get-Command node -ErrorAction Stop).Source
$nodeVersion = (& $node --version).Trim()
if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch '^v(\d+)') { throw "Could not parse Node.js version '$nodeVersion'." }
if ([int]$Matches[1] -lt 22) { throw "Node.js 22 or newer is required; found $nodeVersion" }

$codex = (Get-Command codex -ErrorAction Stop).Source
& $codex login status | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Codex is not authenticated.' }

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
$env:ARENA_AGENT_BRIDGE_SECRET = $secret

Push-Location $Coordinator
try {
    & $node $Main --config $ResolvedConfig
    if ($LASTEXITCODE -ne 0) { throw "Dynamic coordinator exited with code $LASTEXITCODE" }
} finally {
    Remove-Item Env:ARENA_AGENT_BRIDGE_SECRET -ErrorAction SilentlyContinue
    Pop-Location
}
