[CmdletBinding()]
param([string] $ProjectRoot)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
$root = [IO.Path]::GetFullPath($ProjectRoot)
$properties = [ordered]@{}
foreach ($line in Get-Content -LiteralPath (Join-Path $root 'gradle.properties')) {
	$trimmed = $line.Trim()
	if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
	$separator = $trimmed.IndexOf('=')
	if ($separator -gt 0) { $properties[$trimmed.Substring(0, $separator).Trim()] = $trimmed.Substring($separator + 1).Trim() }
}
$modJarName = "arena-agents-$($properties.mod_version).jar"
$voiceJarName = "arena-agents-voice-$($properties.mod_version).jar"
$fabricApiJarName = "fabric-api-$($properties.fabric_api_version).jar"
$carpetJarName = "fabric-carpet-$($properties.carpet_version).jar"
$updater = Join-Path $root 'scripts\install-normal-profile-update.ps1'
& $updater -ProjectRoot $root -GameDirectory $env:TEMP -TestProcessClassification

function Get-FileSnapshot([string] $Path) {
    return @(Get-ChildItem -LiteralPath $Path -Recurse -File | Sort-Object FullName | ForEach-Object {
        $_.FullName.Substring($Path.Length + 1) + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
    })
}

function Assert-SameSnapshot([string[]] $Before, [string[]] $After, [string] $Message) {
    if (@(Compare-Object $Before $After).Count -ne 0) { throw $Message }
}

function Invoke-WithFileLockRetry([scriptblock] $Action) {
    $deadline = [Diagnostics.Stopwatch]::StartNew()
    while ($true) {
        try { & $Action; return } catch {
            $cause = $_.Exception.GetBaseException()
            # Windows application scanning can briefly retain a just-executed image.
            if ($cause -isnot [IO.IOException] -or ($cause.HResult -band 0xffff) -notin @(32, 33) -or $deadline.ElapsedMilliseconds -ge 10000) { throw }
            Start-Sleep -Milliseconds 100
        }
    }
}

function Assert-CurrentUserRuntimeAccess([string] $Path, [switch] $RequireInheritance) {
    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $acl = Get-Acl -LiteralPath $Path
    $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    $matching = @($rules | Where-Object {
        $_.IdentityReference -eq $currentUser -and
        $_.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
        ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -eq [Security.AccessControl.FileSystemRights]::FullControl
    })
    if ($matching.Count -eq 0) {
        throw 'Updater did not preserve runtime access for the current Windows user.'
    }
    if ($RequireInheritance -and @($matching | Where-Object {
        ($_.InheritanceFlags -band [Security.AccessControl.InheritanceFlags]::ContainerInherit) -ne 0 -and
        ($_.InheritanceFlags -band [Security.AccessControl.InheritanceFlags]::ObjectInherit) -ne 0
    }).Count -eq 0) { throw 'Updater did not make current-user runtime access inheritable.' }
}

$target = Join-Path $env:TEMP ('arena normal profile test ' + [guid]::NewGuid().ToString('N'))
$mods = Join-Path $target 'mods'
$installedRoot = Join-Path $target 'arena-agents-runtime'
$runtimeCoordinator = Join-Path $installedRoot 'coordinator'
$runtime = Join-Path $installedRoot 'runtime'
$config = Join-Path $installedRoot 'config'
$secretPath = Join-Path $runtime 'bridge-secret.txt'
$journalPath = Join-Path $runtime 'coordinator-generation.properties'
$externalConfigPath = Join-Path $config 'provider-settings.json'
$sourceNode = Join-Path $root 'runtime\toolchains\node\node.exe'
$installedNode = Join-Path $runtime 'toolchains\node\node.exe'
New-Item -ItemType Directory -Force -Path $mods, $runtimeCoordinator, $runtime, $config | Out-Null
$verificationFailed = $false
try {
    Copy-Item -LiteralPath (Join-Path $root ("build\libs\" + $modJarName)) -Destination (Join-Path $mods 'arena-agents-old-unparseable.jar')
    Copy-Item -LiteralPath (Join-Path $root ("voice-addon\build\libs\" + $voiceJarName)) -Destination (Join-Path $mods 'arena-agents-voice-0.0.1.jar')
    Set-Content -LiteralPath (Join-Path $mods 'unrelated.jar') -Value 'keep'
    Set-Content -LiteralPath (Join-Path $mods $fabricApiJarName) -Value 'api'
    Set-Content -LiteralPath (Join-Path $mods $carpetJarName) -Value 'carpet'
    Set-Content -LiteralPath (Join-Path $runtimeCoordinator 'stale.log') -Value 'remove'
    Set-Content -LiteralPath $secretPath -Value ('a' * 32)
    Set-Content -LiteralPath $journalPath -Value 'phase=ready'
    Set-Content -LiteralPath $externalConfigPath -Value '{"provider":"preserve-me"}'
    New-Item -ItemType File -Force -Path (Join-Path $installedRoot '.arena-runtime-install.lock') | Out-Null

    $secretHash = (Get-FileHash -LiteralPath $secretPath -Algorithm SHA256).Hash
    $externalConfigHash = (Get-FileHash -LiteralPath $externalConfigPath -Algorithm SHA256).Hash
    $modsBefore = Get-FileSnapshot $mods
    $runtimeBefore = Get-FileSnapshot $installedRoot
    $icacls = Join-Path $env:SystemRoot 'System32\icacls.exe'
    & $icacls $journalPath '/inheritance:r' '/grant:r' '*S-1-5-32-544:F' '*S-1-5-18:F' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the Administrator-only journal regression fixture.' }
    try {
        & $updater -ProjectRoot $root -GameDirectory $target -FailurePoint AfterNodeSwap
        throw 'Fresh-target Node failure injection did not occur.'
    } catch {
        if ($_.Exception.Message -notmatch 'Injected failure') { throw }
    }
    if (Test-Path -LiteralPath $installedNode) { throw 'Fresh-target rollback left a Node.js runtime behind.' }
    Assert-SameSnapshot $modsBefore (Get-FileSnapshot $mods) 'Fresh-target rollback did not restore the prior mod state.'
    Assert-SameSnapshot $runtimeBefore (Get-FileSnapshot $installedRoot) 'Fresh-target rollback did not restore the prior runtime state.'

    & $updater -ProjectRoot $root -GameDirectory $target
    if (-not (Test-Path -LiteralPath (Join-Path $mods $modJarName))) { throw 'Updated jar missing.' }
    if (-not (Test-Path -LiteralPath (Join-Path $mods $voiceJarName))) { throw 'Updated voice-addon jar missing.' }
    if (Test-Path -LiteralPath (Join-Path $mods 'arena-agents-old-unparseable.jar')) { throw 'Stale Arena jar remains.' }
    if (Test-Path -LiteralPath (Join-Path $mods 'arena-agents-voice-0.0.1.jar')) { throw 'Stale voice-addon jar remains.' }
    if (-not (Test-Path -LiteralPath (Join-Path $mods 'unrelated.jar'))) { throw 'Unrelated mod was changed.' }
    if (Test-Path -LiteralPath (Join-Path $runtimeCoordinator 'stale.log')) { throw 'Stale coordinator state remains.' }
    $installedManifest = Join-Path $runtimeCoordinator '.arena-agents-bundle-manifest'
    if (-not (Test-Path -LiteralPath $installedManifest -PathType Leaf)) { throw 'Updated coordinator is missing its Java generation manifest.' }
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $installedJarPath = Join-Path $mods $modJarName
    $archive = [IO.Compression.ZipFile]::OpenRead($installedJarPath)
    try {
        $manifestEntry = $archive.GetEntry('arena-agents/coordinator/coordinator-manifest.txt')
        if ($null -eq $manifestEntry) { throw 'Updated JAR is missing its embedded coordinator manifest.' }
        $reader = [IO.StreamReader]::new($manifestEntry.Open())
        try { $embeddedManifest = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $archive.Dispose() }
    if ([IO.File]::ReadAllText($installedManifest) -cne $embeddedManifest) {
        throw 'Installed coordinator generation manifest differs from the exact deployed JAR.'
    }
	$installedGeneration = (Get-FileHash -LiteralPath $installedManifest -Algorithm SHA256).Hash.ToLowerInvariant()
	$installedState = ConvertFrom-StringData (Get-Content -LiteralPath $journalPath -Raw)
	if ([string] $installedState.activeGeneration -cne $installedGeneration -or [string] $installedState.candidateGeneration -cne $installedGeneration) {
		throw 'Updated coordinator generation journal does not match the deployed coordinator.'
	}
    if (-not (Test-Path -LiteralPath $installedNode -PathType Leaf)) { throw 'Bundled Node runtime missing after update.' }
    if ((Get-FileHash $installedNode -Algorithm SHA256).Hash -ne (Get-FileHash $sourceNode -Algorithm SHA256).Hash) {
        throw 'Installed Node runtime differs from the bundled runtime.'
    }
    $nodeStart = [Diagnostics.ProcessStartInfo]::new($installedNode, '--version')
    $nodeStart.UseShellExecute = $false
    $nodeStart.CreateNoWindow = $true
    $nodeStart.RedirectStandardOutput = $true
    $nodeStart.RedirectStandardError = $true
    $nodeProcess = [Diagnostics.Process]::Start($nodeStart)
    try {
        $stdout = $nodeProcess.StandardOutput.ReadToEndAsync()
        $stderr = $nodeProcess.StandardError.ReadToEndAsync()
        if (-not $nodeProcess.WaitForExit(10000)) {
            $nodeProcess.Kill()
            $nodeProcess.WaitForExit()
            throw 'Installed Node version probe timed out.'
        }
        $nodeVersion = $stdout.GetAwaiter().GetResult().Trim()
        $nodeError = $stderr.GetAwaiter().GetResult().Trim()
        $nodeExitCode = $nodeProcess.ExitCode
    } finally { $nodeProcess.Dispose() }
    if ($nodeExitCode -ne 0 -or $nodeVersion -notmatch '^v?(?<major>\d+)' -or [int]$Matches.major -lt 22) {
        throw "Installed Node runtime is not executable Node.js 22+: $nodeVersion $nodeError"
    }

    $installedHash = (Get-FileHash (Join-Path $mods $modJarName) -Algorithm SHA256).Hash
    $sourceHash = (Get-FileHash (Join-Path $root ("build\libs\" + $modJarName)) -Algorithm SHA256).Hash
    if ($installedHash -ne $sourceHash) { throw 'Installed jar hash differs from source jar.' }
    $installedVoiceHash = (Get-FileHash (Join-Path $mods $voiceJarName) -Algorithm SHA256).Hash
    $sourceVoiceHash = (Get-FileHash (Join-Path $root ("voice-addon\build\libs\" + $voiceJarName)) -Algorithm SHA256).Hash
    if ($installedVoiceHash -ne $sourceVoiceHash) { throw 'Installed voice-addon jar hash differs from source jar.' }
    if ((Get-FileHash $secretPath -Algorithm SHA256).Hash -ne $secretHash) { throw 'Secret changed after successful update.' }
    if ((Get-FileHash $externalConfigPath -Algorithm SHA256).Hash -ne $externalConfigHash) { throw 'External config changed after successful update.' }
    Assert-CurrentUserRuntimeAccess $installedRoot -RequireInheritance
    Assert-CurrentUserRuntimeAccess $journalPath

    Invoke-WithFileLockRetry { Set-Content -LiteralPath $installedNode -Value 'old-node-fixture' }
    $oldNodeHash = (Get-FileHash -LiteralPath $installedNode -Algorithm SHA256).Hash
    foreach ($failurePoint in @('AfterJarsBackup', 'AfterBackup', 'AfterJarSwap', 'AfterCoordinatorSwap', 'AfterNodeSwap', 'AfterGenerationStateSwap')) {
        $before = Get-FileSnapshot $mods
        $runtimeBefore = Get-FileSnapshot $installedRoot
        try { & $updater -ProjectRoot $root -GameDirectory $target -FailurePoint $failurePoint; throw "Failure injection did not occur: $failurePoint" } catch { if ($_.Exception.Message -notmatch 'Injected failure') { throw } }
        if (@(Get-ChildItem -LiteralPath $target -Directory -Filter '.arena-agents-backup-*').Count -eq 0) { throw "No preserved backup after failure: $failurePoint" }
        Assert-SameSnapshot $before (Get-FileSnapshot $mods) "Forced failure did not restore prior mod state: $failurePoint"
        Assert-SameSnapshot $runtimeBefore (Get-FileSnapshot $installedRoot) "Forced failure did not restore runtime state: $failurePoint"
        if ((Get-FileHash $installedNode -Algorithm SHA256).Hash -ne $oldNodeHash) { throw "Forced failure did not restore the prior Node runtime: $failurePoint" }
        if ((Get-FileHash $secretPath -Algorithm SHA256).Hash -ne $secretHash) { throw "Secret changed after failure: $failurePoint" }
        if ((Get-FileHash $externalConfigPath -Algorithm SHA256).Hash -ne $externalConfigHash) { throw "External config changed after failure: $failurePoint" }
        if (-not (Test-Path -LiteralPath (Join-Path $runtimeCoordinator 'src\dynamic-main.mjs'))) { throw "Forced failure did not restore coordinator state: $failurePoint" }
    }
    & $updater -ProjectRoot $root -GameDirectory $target
    if ((Get-FileHash $installedNode -Algorithm SHA256).Hash -ne (Get-FileHash $sourceNode -Algorithm SHA256).Hash) {
        throw 'A subsequent successful update did not replace the old Node runtime.'
    }
    Write-Host "Normal profile updater temp end-to-end test passed with bundled Node.js $nodeVersion."
} catch {
    $verificationFailed = $true
    throw
} finally {
    try {
        if (Test-Path -LiteralPath $target) {
            $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
            & (Join-Path $env:SystemRoot 'System32\icacls.exe') $target '/grant:r' "*$currentUser`:F" '/T' '/C' '/Q' | Out-Null
            Invoke-WithFileLockRetry { if (Test-Path -LiteralPath $target) { Remove-Item -LiteralPath $target -Recurse -Force } }
        }
    } catch {
        if (-not $verificationFailed) { throw }
        Write-Warning "Updater fixture cleanup failed: $_"
    }
}
