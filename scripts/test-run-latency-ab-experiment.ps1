[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Assert-Fixture {
    param(
        [Parameter(Mandatory = $true)] [bool] $Condition,
        [Parameter(Mandatory = $true)] [string] $Message
    )
    if (-not $Condition) { throw $Message }
}

function Invoke-ExpectedFailure {
    param(
        [Parameter(Mandatory = $true)] [scriptblock] $Action,
        [Parameter(Mandatory = $true)] [string] $Message
    )
    $failed = $false
    try { & $Action } catch { $failed = $true }
    Assert-Fixture $failed $Message
}

function Get-ManifestFromOutput {
    param([Parameter(Mandatory = $true)] [object[]] $Output)
    $manifest = @($Output | Where-Object { $_ -is [psobject] -and $_.PSObject.Properties['Status'] }) | Select-Object -Last 1
    if ($null -eq $manifest) {
        $manifest = @($Output | Where-Object { $_ -is [psobject] -and $_.PSObject.Properties['status'] }) | Select-Object -Last 1
    }
    if ($null -eq $manifest) { throw "Orchestrator did not return a manifest. Output: $($Output -join "`n")" }
    return $manifest
}

$scriptRoot = Split-Path -Parent $PSScriptRoot
$orchestratorPath = Join-Path $scriptRoot 'scripts\run-latency-ab-experiment.ps1'
$baselineFixtureRunnerPath = Join-Path $scriptRoot 'scripts\fixtures\latency-ab\fixture-runner-baseline.mjs'
$optimizedFixtureRunnerPath = Join-Path $scriptRoot 'scripts\fixtures\latency-ab\fixture-runner-optimized.mjs'

if (-not (Test-Path -LiteralPath $orchestratorPath -PathType Leaf)) {
    throw "Expected orchestrator is not present yet: $orchestratorPath"
}

$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('latency-ab-orchestrator-fixture-' + [guid]::NewGuid().ToString('N'))
$baselineRoot = Join-Path $fixtureRoot 'baseline'
$optimizedRoot = Join-Path $fixtureRoot 'optimized'
$artifactRoot = Join-Path $fixtureRoot 'artifacts'
$statePath = Join-Path $fixtureRoot 'concurrency.state'
$baselineReplayPath = Join-Path $fixtureRoot 'baseline-replay.json'
$optimizedReplayPath = Join-Path $fixtureRoot 'optimized-replay.json'
$pathsToRemove = New-Object 'System.Collections.Generic.List[string]'

try {
    New-Item -ItemType Directory -Force -Path $baselineRoot, $optimizedRoot, $artifactRoot | Out-Null
    $pathsToRemove.Add($fixtureRoot) | Out-Null
    Set-Content -LiteralPath $baselineReplayPath -Value '[{"trialId":"fixture-baseline"}]' -NoNewline
    Set-Content -LiteralPath $optimizedReplayPath -Value '[{"trialId":"fixture-optimized"}]' -NoNewline
    foreach ($root in @($baselineRoot, $optimizedRoot)) {
        New-Item -ItemType Directory -Force -Path (Join-Path $root 'coordinator\config'), (Join-Path $root 'src') | Out-Null
        Set-Content -LiteralPath (Join-Path $root 'src\runtime.txt') -Value 'identical source fixture' -NoNewline
        Set-Content -LiteralPath (Join-Path $root 'README.md') -Value 'fixture' -NoNewline
        Set-Content -LiteralPath (Join-Path $root 'coordinator\config\latency-matrix.json') -Value (@'
{
  "version": 1,
  "scenarios": [
    { "id": "pair-a", "agents": 1, "seed": 11 },
    { "id": "pair-b", "agents": 4, "seed": 22 },
    { "id": "pair-c", "agents": 8, "seed": 33 },
    { "id": "pair-d", "agents": 16, "seed": 44 }
  ]
}
'@) -NoNewline
    }

    $common = @{
        BaselinePath = $baselineRoot
        OptimizedPath = $optimizedRoot
        MatrixPath = 'coordinator\config\latency-matrix.json'
        BaselineRunnerPath = $baselineFixtureRunnerPath
        OptimizedRunnerPath = $optimizedFixtureRunnerPath
        OutputRoot = $artifactRoot
        OuterTimeoutSeconds = 5
        MaxRetries = 1
        Seed = 12345
        Mode = @('instant')
        RunnerArguments = @('--shared-state-path', $statePath, '--retry-once')
        BaselineRunnerArguments = @('--shared-state-path', $statePath, '--retry-once', '--label', 'baseline')
        OptimizedRunnerArguments = @('--shared-state-path', $statePath, '--retry-once', '--label', 'optimized')
        BaselinePlanningConcurrency = 16
        OptimizedPlanningConcurrency = 16
        BaselineReplayRecordingsPath = $baselineReplayPath
        OptimizedReplayRecordingsPath = $optimizedReplayPath
    }

    $firstOutput = @( & $orchestratorPath @common )
    $firstManifest = Get-ManifestFromOutput $firstOutput
    Assert-Fixture ([string]$firstManifest.Status -eq 'passed') 'A successful fixture run did not pass.'
    Assert-Fixture ([int]$firstManifest.results.Count -eq 8) 'Expected one result per pair and arm.'
    Assert-Fixture ([int]$firstManifest.results[0].result.trials.Count -eq 2) 'Aggregate runner repetitions were not preserved.'
    Assert-Fixture ([double]$firstManifest.results[0].latencyMs -eq 18) 'Aggregate runner latency was not summarized as repetition p95.'
    Assert-Fixture ([bool]$firstManifest.results[0].cleanupOk) 'Validated cleanup status was not preserved in the manifest summary.'
    Assert-Fixture ([int]$firstManifest.armConfig.baseline.planningConcurrency -eq 16) 'Baseline planning concurrency was not recorded.'
    Assert-Fixture ([int]$firstManifest.armConfig.optimized.planningConcurrency -eq 16) 'Optimized planning concurrency was not recorded.'
    Assert-Fixture ([string]$firstManifest.armConfig.baseline.runnerArguments[-1] -eq 'baseline') 'Baseline runner arguments were not recorded.'
    Assert-Fixture ([string]$firstManifest.armConfig.optimized.runnerArguments[-1] -eq 'optimized') 'Optimized runner arguments were not recorded.'
    Assert-Fixture ([string]$firstManifest.armConfig.baseline.replayRecordingsPath -eq [IO.Path]::GetFullPath($baselineReplayPath)) 'Baseline replay path was not recorded.'
    Assert-Fixture ([string]$firstManifest.armConfig.optimized.replayRecordingsPath -eq [IO.Path]::GetFullPath($optimizedReplayPath)) 'Optimized replay path was not recorded.'
    Assert-Fixture ([string]$firstManifest.armConfig.baseline.replayRecordingsPath -ne [string]$firstManifest.armConfig.optimized.replayRecordingsPath) 'Per-arm replay paths were collapsed.'
    Assert-Fixture ([int]$firstManifest.armOrder.Count -eq 8) 'Arm order was not recorded for every arm.'
    Assert-Fixture ([string]$firstManifest.sourceHashes.before.baseline -eq [string]$firstManifest.sourceHashes.after.baseline) 'Baseline source hash drifted without a fixture request.'
    Assert-Fixture ([string]$firstManifest.sourceHashes.before.optimized -eq [string]$firstManifest.sourceHashes.after.optimized) 'Optimized source hash drifted without a fixture request.'
    Assert-Fixture ([string]$firstManifest.matrixHashes.before.baseline.canonical -eq [string]$firstManifest.matrixHashes.after.baseline.canonical) 'Baseline canonical matrix hash changed.'
    Assert-Fixture ([string]$firstManifest.matrixHashes.before.optimized.effective -eq [string]$firstManifest.matrixHashes.after.optimized.effective) 'Optimized effective matrix hash changed.'
    Assert-Fixture (Test-Path -LiteralPath ([string]$firstManifest.runRoot) -PathType Container) 'Run root was not created.'
    Assert-Fixture ((Get-Content -Raw -LiteralPath (Join-Path ([string]$firstManifest.runRoot) 'manifest.json')) -match '"status"\s*:\s*"passed"') 'Manifest artifact was not written.'
    Assert-Fixture ((Get-Content -Raw -LiteralPath "$statePath.max") -match 'maxActive=1') 'Fixture observed overlapping arm processes.'

    $noArgsArtifactRoot = Join-Path $fixtureRoot 'artifacts-no-args'
    New-Item -ItemType Directory -Force -Path $noArgsArtifactRoot | Out-Null
    $noArgs = $common.Clone()
    $noArgs.OutputRoot = $noArgsArtifactRoot
    $noArgs.Remove('RunnerArguments')
    $noArgs.Remove('BaselineRunnerArguments')
    $noArgs.Remove('OptimizedRunnerArguments')
    $noArgs.Remove('BaselineReplayRecordingsPath')
    $noArgs.Remove('OptimizedReplayRecordingsPath')
    $noArgsOutput = @( & $orchestratorPath @noArgs )
    $noArgsManifest = Get-ManifestFromOutput $noArgsOutput
    Assert-Fixture ([string]$noArgsManifest.Status -eq 'passed') 'Omitted runner argument arrays were not accepted by the real interface.'

    $secondArtifactRoot = Join-Path $fixtureRoot 'artifacts-second'
    New-Item -ItemType Directory -Force -Path $secondArtifactRoot | Out-Null
    $secondCommon = $common.Clone()
    $secondCommon.OutputRoot = $secondArtifactRoot
    $secondOutput = @( & $orchestratorPath @secondCommon )
    $secondManifest = Get-ManifestFromOutput $secondOutput
    Assert-Fixture ([string]::Join(',', [string[]]$firstManifest.armOrder) -eq [string]::Join(',', [string[]]$secondManifest.armOrder)) 'Explicit seed did not produce deterministic arm order.'

    $mixedMatrixText = @'
{
  "version": 1,
  "scenarios": [
    { "id": "instant-only", "mode": "instant", "agents": 1 },
    { "id": "replay-only", "mode": "replay", "agents": 4 },
    { "id": "live-only", "mode": "live", "agents": 8, "providerProfile": { "provider": "fixture-live" } }
  ]
}
'@
    foreach ($root in @($baselineRoot, $optimizedRoot)) {
        Set-Content -LiteralPath (Join-Path $root 'coordinator\config\mixed-matrix.json') -Value $mixedMatrixText -NoNewline
    }
    $mixedArtifactRoot = Join-Path $fixtureRoot 'artifacts-mixed'
    New-Item -ItemType Directory -Force -Path $mixedArtifactRoot | Out-Null
    $mixedParameters = @{
        BaselinePath = $baselineRoot; OptimizedPath = $optimizedRoot; MatrixPath = 'coordinator\config\mixed-matrix.json';
        BaselineRunnerPath = $baselineFixtureRunnerPath; OptimizedRunnerPath = $optimizedFixtureRunnerPath; OutputRoot = $mixedArtifactRoot; OuterTimeoutSeconds = 5; MaxRetries = 0;
        Seed = 12345; Mode = @('instant', 'replay'); RunnerArguments = @('--shared-state-path', $statePath)
    }
    $mixedOutput = @( & $orchestratorPath @mixedParameters )
    $mixedManifest = Get-ManifestFromOutput $mixedOutput
    Assert-Fixture ([string]$mixedManifest.Status -eq 'passed') 'Mixed-mode matrix run did not pass.'
    Assert-Fixture ([int]$mixedManifest.results.Count -eq 4) 'Mode selection cross-product created extra mixed-mode trials.'
    Assert-Fixture (@([string[]]($mixedManifest.cells | ForEach-Object { $_.trialId })) -contains 'instant-only') 'Explicit instant trial identity was not preserved.'
    Assert-Fixture (@([string[]]($mixedManifest.cells | ForEach-Object { $_.trialId })) -contains 'replay-only') 'Explicit replay trial identity was not preserved.'
    Assert-Fixture (@([string[]]($mixedManifest.cells | ForEach-Object { $_.trialId })) -notcontains 'live-only') 'Unselected live trial was not filtered.'

    $driftArtifactRoot = Join-Path $fixtureRoot 'artifacts-drift'
    New-Item -ItemType Directory -Force -Path $driftArtifactRoot | Out-Null
    $driftArgs = @('--shared-state-path', $statePath, '--drift-source')
    Invoke-ExpectedFailure -Action {
        $parameters = @{
            BaselinePath = $baselineRoot; OptimizedPath = $optimizedRoot; MatrixPath = 'coordinator\config\latency-matrix.json';
            BaselineRunnerPath = $baselineFixtureRunnerPath; OptimizedRunnerPath = $optimizedFixtureRunnerPath; OutputRoot = $driftArtifactRoot; OuterTimeoutSeconds = 5; MaxRetries = 0;
            Seed = 12345; Mode = @('instant'); RunnerArguments = $driftArgs
        }
        & $orchestratorPath @parameters
    } -Message 'Source hash drift was not rejected.'
    $driftFile = Join-Path $baselineRoot 'src\fixture-drift.txt'
    if (Test-Path -LiteralPath $driftFile -PathType Leaf) { Remove-Item -LiteralPath $driftFile -Force }

    $malformedArtifactRoot = Join-Path $fixtureRoot 'artifacts-malformed'
    New-Item -ItemType Directory -Force -Path $malformedArtifactRoot | Out-Null
    Invoke-ExpectedFailure -Action {
        $parameters = @{
            BaselinePath = $baselineRoot; OptimizedPath = $optimizedRoot; MatrixPath = 'coordinator\config\latency-matrix.json';
            BaselineRunnerPath = $baselineFixtureRunnerPath; OptimizedRunnerPath = $optimizedFixtureRunnerPath; OutputRoot = $malformedArtifactRoot; OuterTimeoutSeconds = 5; MaxRetries = 0;
            Seed = 12345; Mode = @('instant'); RunnerArguments = @('--shared-state-path', $statePath, '--fixture-mode', 'malformed')
        }
        & $orchestratorPath @parameters
    } -Message 'Malformed runner JSON was not rejected.'

    $leakArtifactRoot = Join-Path $fixtureRoot 'artifacts-leak'
    New-Item -ItemType Directory -Force -Path $leakArtifactRoot | Out-Null
    Invoke-ExpectedFailure -Action {
        $parameters = @{
            BaselinePath = $baselineRoot; OptimizedPath = $optimizedRoot; MatrixPath = 'coordinator\config\latency-matrix.json';
            BaselineRunnerPath = $baselineFixtureRunnerPath; OptimizedRunnerPath = $optimizedFixtureRunnerPath; OutputRoot = $leakArtifactRoot; OuterTimeoutSeconds = 5; MaxRetries = 0;
            Seed = 12345; Mode = @('instant'); RunnerArguments = @('--shared-state-path', $statePath, '--fixture-mode', 'cleanup-leak')
        }
        & $orchestratorPath @parameters
    } -Message 'Cleanup residue was not rejected.'

    $timeoutArtifactRoot = Join-Path $fixtureRoot 'artifacts-timeout'
    New-Item -ItemType Directory -Force -Path $timeoutArtifactRoot | Out-Null
    Invoke-ExpectedFailure -Action {
        $parameters = @{
            BaselinePath = $baselineRoot; OptimizedPath = $optimizedRoot; MatrixPath = 'coordinator\config\latency-matrix.json';
            BaselineRunnerPath = $baselineFixtureRunnerPath; OptimizedRunnerPath = $optimizedFixtureRunnerPath; OutputRoot = $timeoutArtifactRoot; OuterTimeoutSeconds = 1; MaxRetries = 0;
            Seed = 12345; Mode = @('instant'); RunnerArguments = @('--shared-state-path', $statePath, '--fixture-mode', 'timeout')
        }
        & $orchestratorPath @parameters
    } -Message 'Outer timeout was not enforced.'

    $liveArtifactRoot = Join-Path $fixtureRoot 'artifacts-live'
    New-Item -ItemType Directory -Force -Path $liveArtifactRoot | Out-Null
    $liveCommon = $common.Clone()
    $liveCommon.OutputRoot = $liveArtifactRoot
    $liveCommon.Mode = @('live')
    $liveCommon.Remove('BaselineRunnerArguments')
    $liveCommon.Remove('OptimizedRunnerArguments')
    $liveCommon.RunnerArguments = @('--shared-state-path', $statePath, '--fixture-mode', 'skip-live')
    $liveOutput = @( & $orchestratorPath @liveCommon )
    $liveManifest = Get-ManifestFromOutput $liveOutput
    Assert-Fixture ([string]$liveManifest.Status -eq 'passed') 'Optional live SKIPPED run should pass.'

    $requiredLiveArtifactRoot = Join-Path $fixtureRoot 'artifacts-required-live'
    New-Item -ItemType Directory -Force -Path $requiredLiveArtifactRoot | Out-Null
    $requiredLiveCommon = $liveCommon.Clone()
    $requiredLiveCommon.OutputRoot = $requiredLiveArtifactRoot
    $requiredLiveCommon.RequireLive = $true
    Invoke-ExpectedFailure -Action { & $orchestratorPath @requiredLiveCommon } -Message 'RequireLive did not reject all-skipped live results.'

    Write-Host 'Latency A/B orchestrator fixture tests passed.'
    $global:LASTEXITCODE = 0
}
catch {
    Get-ChildItem -LiteralPath $artifactRoot -Filter result.json -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object { (Get-Content -Raw -LiteralPath $_.FullName) -match '"status"\s*:\s*"FAILED"' } |
        ForEach-Object { Write-Host "Failed fixture result $($_.FullName):`n$(Get-Content -Raw -LiteralPath $_.FullName)" }
    throw
}
finally {
    foreach ($path in $pathsToRemove) {
        if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force -ErrorAction SilentlyContinue }
    }
}
