[CmdletBinding()]
param(
    [string] $NodePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

Remove-Item Env:\ARENA_AGENT_BRIDGE_SECRET -ErrorAction SilentlyContinue
Remove-Item Env:\ARENA_AGENT_VOICE_SECRET -ErrorAction SilentlyContinue
Remove-Item Env:\ARENA_AGENT_VOICE_SECRET_FILE -ErrorAction SilentlyContinue

$PackageRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$SecretPath = Join-Path $PackageRoot 'runtime\bridge-secret.txt'
$VoiceSecretPath = Join-Path $PackageRoot 'runtime\voice-secret.txt'
$CoordinatorPath = Join-Path $PackageRoot 'coordinator\src\dynamic-main.mjs'
$ConfigPath = Join-Path $PackageRoot 'coordinator\config\dynamic-agents.json'

foreach ($required in @($SecretPath, $VoiceSecretPath, $CoordinatorPath, $ConfigPath)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Missing Arena Agents runtime file: $required"
    }
}

$bundledNodeRelativePath = if ($env:OS -eq 'Windows_NT') {
    'runtime\toolchains\node\node.exe'
} else {
    'runtime/toolchains/node/bin/node'
}
$nodeCandidate = if ([string]::IsNullOrWhiteSpace($NodePath)) {
    Join-Path $PackageRoot $bundledNodeRelativePath
} else {
    if (-not [IO.Path]::IsPathRooted($NodePath)) {
        throw 'The trusted Node.js override must be an absolute path.'
    }
    $NodePath
}
if (-not (Test-Path -LiteralPath $nodeCandidate -PathType Leaf)) {
    throw "The trusted Node.js executable was not found: $nodeCandidate"
}
$resolvedNode = (Resolve-Path -LiteralPath $nodeCandidate).Path
$nodeVersion = (& $resolvedNode --version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw "Could not run the trusted Node.js executable at '$resolvedNode'." }
if ($nodeVersion -notmatch '^v(\d+)') { throw "Could not parse Node.js version '$nodeVersion'." }
if ([int]$Matches[1] -lt 22) { throw "Node.js 22 or newer is required; detected $nodeVersion." }

$secret = [IO.File]::ReadAllText($SecretPath).Trim()
if ($secret.Length -lt 32) { throw 'Arena Agents bridge secret is missing or invalid.' }
$voiceSecret = [IO.File]::ReadAllText($VoiceSecretPath).Trim()
if ($voiceSecret.Length -lt 16 -or $voiceSecret.Length -gt 512) { throw 'Arena Agents voice secret is missing or invalid.' }
if ($voiceSecret -ceq $secret) { throw 'Arena Agents bridge and voice secrets must be distinct.' }
$env:ARENA_AGENT_BRIDGE_SECRET = $secret
$env:ARENA_AGENT_VOICE_SECRET_FILE = $VoiceSecretPath
Remove-Item Env:\ARENA_AGENT_VOICE_SECRET -ErrorAction SilentlyContinue
try {
    & $resolvedNode $CoordinatorPath --config $ConfigPath
    if ($LASTEXITCODE -ne 0) { throw "Arena Agents coordinator exited with code $LASTEXITCODE." }
} finally {
    Remove-Item Env:\ARENA_AGENT_BRIDGE_SECRET -ErrorAction SilentlyContinue
    Remove-Item Env:\ARENA_AGENT_VOICE_SECRET -ErrorAction SilentlyContinue
    Remove-Item Env:\ARENA_AGENT_VOICE_SECRET_FILE -ErrorAction SilentlyContinue
}
