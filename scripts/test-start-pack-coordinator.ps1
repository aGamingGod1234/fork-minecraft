[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$sourceScript = Join-Path $PSScriptRoot 'start-pack-coordinator.ps1'
$systemNode = (Get-Command node.exe -ErrorAction Stop).Source
$nodeVersion = (& $systemNode --version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $nodeVersion -notmatch '^v(?<major>\d+)' -or [int]$Matches.major -lt 22) {
    throw "This verification requires Node.js 22 or newer; found '$nodeVersion'."
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("arena-start-pack-test-" + [Guid]::NewGuid().ToString('N'))
$resolvedTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
if (-not $resolvedTestRoot.StartsWith($resolvedTempRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to use a test directory outside the system temporary directory: $resolvedTestRoot"
}

$savedPath = $env:PATH
$utf8NoBom = [Text.UTF8Encoding]::new($false)
try {
    $scriptDirectory = Join-Path $resolvedTestRoot 'scripts'
    $runtimeDirectory = Join-Path $resolvedTestRoot 'runtime'
    $coordinatorSourceDirectory = Join-Path $resolvedTestRoot 'coordinator\src'
    $coordinatorConfigDirectory = Join-Path $resolvedTestRoot 'coordinator\config'
    $bundledNodeDirectory = Join-Path $runtimeDirectory 'toolchains\node'
    $pathDecoyDirectory = Join-Path $resolvedTestRoot 'path-decoy'
    New-Item -ItemType Directory -Force -Path $scriptDirectory, $runtimeDirectory, $coordinatorSourceDirectory, $coordinatorConfigDirectory, $bundledNodeDirectory, $pathDecoyDirectory | Out-Null

    $scriptPath = Join-Path $scriptDirectory 'start-pack-coordinator.ps1'
    $bundledNode = Join-Path $bundledNodeDirectory 'node.exe'
    $pathDecoyNode = Join-Path $pathDecoyDirectory 'node.exe'
    $resultPath = Join-Path $runtimeDirectory 'launch-result.json'
    Copy-Item -LiteralPath $sourceScript -Destination $scriptPath
    Copy-Item -LiteralPath $systemNode -Destination $bundledNode
    Copy-Item -LiteralPath $systemNode -Destination $pathDecoyNode

    $secret = 'verified-test-secret-0123456789abcdef'
    $voiceSecret = 'verified-voice-secret-0123456789abcdef'
    [IO.File]::WriteAllText((Join-Path $runtimeDirectory 'bridge-secret.txt'), $secret, $utf8NoBom)
    [IO.File]::WriteAllText((Join-Path $runtimeDirectory 'voice-secret.txt'), $voiceSecret, $utf8NoBom)
    [IO.File]::WriteAllText((Join-Path $coordinatorConfigDirectory 'dynamic-agents.json'), '{}', $utf8NoBom)
    $env:ARENA_AGENT_VOICE_SECRET = 'inherited-plaintext-override'
    $testCoordinator = @'
import { writeFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const resultPath = fileURLToPath(new URL('../../runtime/launch-result.json', import.meta.url));
writeFileSync(resultPath, JSON.stringify({
  executable: process.execPath,
  secretMatches: process.env.ARENA_AGENT_BRIDGE_SECRET === 'verified-test-secret-0123456789abcdef',
  voiceSecretFileMatches: process.env.ARENA_AGENT_VOICE_SECRET_FILE === fileURLToPath(new URL('../../runtime/voice-secret.txt', import.meta.url)),
  plaintextVoiceSecretAbsent: process.env.ARENA_AGENT_VOICE_SECRET === undefined,
  configFlag: process.argv.at(-2),
  configPath: process.argv.at(-1),
}));
'@
    [IO.File]::WriteAllText((Join-Path $coordinatorSourceDirectory 'dynamic-main.mjs'), $testCoordinator, $utf8NoBom)

    $env:PATH = "$pathDecoyDirectory;$savedPath"
    & $scriptPath
    $result = Get-Content -Raw -LiteralPath $resultPath | ConvertFrom-Json
    if ([IO.Path]::GetFullPath($result.executable) -ne [IO.Path]::GetFullPath($bundledNode)) {
        throw "Pack launcher used an unexpected Node.js executable: $($result.executable)"
    }
    if (-not $result.secretMatches) { throw 'Pack launcher did not pass the bridge secret to the trusted Node.js process.' }
    if (-not $result.voiceSecretFileMatches -or -not $result.plaintextVoiceSecretAbsent) {
        throw 'Pack launcher did not pass only the dedicated voice-secret file to the trusted Node.js process.'
    }
    if (Test-Path Env:\ARENA_AGENT_VOICE_SECRET) { throw 'Pack launcher retained an inherited plaintext voice secret.' }
    if ($result.configFlag -ne '--config' -or [IO.Path]::GetFullPath($result.configPath) -ne [IO.Path]::GetFullPath((Join-Path $coordinatorConfigDirectory 'dynamic-agents.json'))) {
        throw 'Pack launcher did not pass the expected coordinator configuration path.'
    }

    Remove-Item -LiteralPath $resultPath
    & $scriptPath -NodePath $pathDecoyNode
    $overrideResult = Get-Content -Raw -LiteralPath $resultPath | ConvertFrom-Json
    if ([IO.Path]::GetFullPath($overrideResult.executable) -ne [IO.Path]::GetFullPath($pathDecoyNode)) {
        throw "Pack launcher ignored the explicit trusted Node.js override: $($overrideResult.executable)"
    }

    $relativeOverrideRejected = $false
    try {
        & $scriptPath -NodePath 'node.exe'
    } catch {
        $relativeOverrideRejected = $_.Exception.Message -match 'absolute path'
    }
    if (-not $relativeOverrideRejected) { throw 'Pack launcher accepted a relative trusted Node.js override.' }

    $voiceSecretPath = Join-Path $runtimeDirectory 'voice-secret.txt'
    Remove-Item -LiteralPath $resultPath
    Remove-Item -LiteralPath $voiceSecretPath
    $env:ARENA_AGENT_VOICE_SECRET = 'inherited-before-readiness-failure'
    $missingVoiceSecretRejected = $false
    try { & $scriptPath -NodePath $pathDecoyNode } catch { $missingVoiceSecretRejected = $_.Exception.Message -match 'voice-secret.txt' }
    if (-not $missingVoiceSecretRejected) { throw 'Pack launcher accepted a missing dedicated voice secret.' }
    if (Test-Path Env:\ARENA_AGENT_VOICE_SECRET) { throw 'Pack launcher retained plaintext voice credentials after readiness failure.' }
    [IO.File]::WriteAllText($voiceSecretPath, 'short', $utf8NoBom)
    $shortVoiceSecretRejected = $false
    try { & $scriptPath -NodePath $pathDecoyNode } catch { $shortVoiceSecretRejected = $_.Exception.Message -match 'voice secret is missing or invalid' }
    if (-not $shortVoiceSecretRejected) { throw 'Pack launcher accepted a short dedicated voice secret.' }
    [IO.File]::WriteAllText($voiceSecretPath, $secret, $utf8NoBom)
    $sharedVoiceSecretRejected = $false
    try { & $scriptPath -NodePath $pathDecoyNode } catch { $sharedVoiceSecretRejected = $_.Exception.Message -match 'must be distinct' }
    if (-not $sharedVoiceSecretRejected) { throw 'Pack launcher accepted a shared bridge and voice secret.' }
    [IO.File]::WriteAllText($voiceSecretPath, $voiceSecret, $utf8NoBom)

    Remove-Item -LiteralPath $resultPath -ErrorAction SilentlyContinue
    Remove-Item -LiteralPath $bundledNode
    [IO.File]::WriteAllText((Join-Path $runtimeDirectory 'bridge-secret.txt'), 'short-secret', $utf8NoBom)
    $pathFallbackRejectedBeforeSecretRead = $false
    try {
        & $scriptPath
    } catch {
        $pathFallbackRejectedBeforeSecretRead = $_.Exception.Message -match 'trusted Node.js executable was not found'
    }
    if (-not $pathFallbackRejectedBeforeSecretRead) {
        throw 'Pack launcher did not reject the PATH Node.js executable before validating the secret.'
    }
    if (Test-Path -LiteralPath $resultPath) {
        throw 'Pack launcher executed the PATH Node.js executable after the bundled runtime was removed.'
    }

    Write-Host 'Pack coordinator trusted Node.js resolution verified.'
}
finally {
    $env:PATH = $savedPath
    if (Test-Path -LiteralPath $resolvedTestRoot) {
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}
