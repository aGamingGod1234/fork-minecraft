[CmdletBinding()]
param(
    [string] $ProjectRoot,
    [string] $MatrixPath,
    [string] $OutputDirectory,
    [ValidateRange(5, 30)] [int] $Repetitions = 5,
    [string] $ServerTemplate,
    [switch] $RequireLive,
    [switch] $KeepHeadlessArtifacts
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
$project = [IO.Path]::GetFullPath($ProjectRoot)
if ([string]::IsNullOrWhiteSpace($MatrixPath)) { $MatrixPath = Join-Path $project 'coordinator\config\latency-headless-matrix.json' }
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) { $OutputDirectory = Join-Path $project 'runtime\latency-headless-evidence' }
$matrixFile = [IO.Path]::GetFullPath($MatrixPath)
$output = [IO.Path]::GetFullPath($OutputDirectory)
$runner = Join-Path $project 'scripts\run-headless-provider-matrix.ps1'
foreach ($required in @($matrixFile, $runner)) { if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing headless latency prerequisite: $required" } }
$matrix = Get-Content -Raw -LiteralPath $matrixFile | ConvertFrom-Json
$loads = @{}
$providers = @{}
foreach ($scenario in @($matrix.scenarios)) {
    $load = if ($null -ne $scenario.PSObject.Properties['rosterSize']) { [int]$scenario.rosterSize } else { 1 }
    if ($load -notin @(1, 8, 16)) { throw "Headless latency scenario '$($scenario.id)' must use rosterSize 1, 8, or 16" }
    if ($loads.ContainsKey([string]$scenario.id)) { throw "Duplicate headless latency scenario '$($scenario.id)'" }
    $loads[[string]$scenario.id] = $load
    $providers[[string]$scenario.id] = [string]$scenario.provider
}
if (@($loads.Values | Sort-Object -Unique) -join ',' -ne '1,8,16') { throw 'Headless latency matrix must cover roster sizes 1, 8, and 16' }

New-Item -ItemType Directory -Force -Path $output | Out-Null
$trials = [Collections.Generic.List[object]]::new()
$runs = [Collections.Generic.List[object]]::new()
for ($repetition = 1; $repetition -le $Repetitions; $repetition += 1) {
    $arguments = @{ ProjectRoot = $project; MatrixPath = $matrixFile }
    if (-not [string]::IsNullOrWhiteSpace($ServerTemplate)) { $arguments.ServerTemplate = $ServerTemplate }
    if ($RequireLive) { $arguments.RequireAll = $true }
    if ($KeepHeadlessArtifacts) { $arguments.KeepArtifacts = $true }
    $raw = & $runner @arguments 2>&1
    $exitCode = $LASTEXITCODE
    $text = ($raw | ForEach-Object { [string]$_ }) -join [Environment]::NewLine
    $report = $null
    try { $report = $text | ConvertFrom-Json } catch { throw "Headless latency runner returned malformed JSON on repetition ${repetition}" }
    if ($exitCode -ne 0 -or [string]$report.status -eq 'FAILED') { throw "Headless latency runner failed on repetition ${repetition}" }
    $runs.Add([pscustomobject]@{ repetition = $repetition; runId = [string]$report.runId; status = [string]$report.status })
    foreach ($scenario in @($report.scenarios)) {
        $load = $loads[[string]$scenario.scenarioId]
		$metrics = if ($null -ne $scenario.PSObject.Properties['metrics']) { $scenario.metrics } else { $null }
		$minecraftMspt = if ($null -ne $metrics -and $null -ne $metrics.PSObject.Properties['resources'] -and $null -ne $metrics.resources) { $metrics.resources.minecraftMspt } else { $null }
        $elapsed = if ($null -ne $scenario.PSObject.Properties['elapsedMs']) { $scenario.elapsedMs } elseif ($null -ne $scenario.PSObject.Properties['timings'] -and $null -ne $scenario.timings) { $scenario.timings.scenarioElapsedMs } else { $null }
		$factualSuccess = $null -ne $scenario.PSObject.Properties['factualSuccess'] -and $scenario.factualSuccess -eq $true
        $trials.Add([pscustomobject][ordered]@{
            trialId = [string]$scenario.scenarioId
            repetition = $repetition
            agentLoad = $load
            sessionState = 'cold'
            status = [string]$scenario.status
            factualSuccess = $factualSuccess
            synthetic = $false
            latencyMs = $elapsed
			tickP95Ms = $null
			minecraftMspt = $minecraftMspt
            provider = $providers[[string]$scenario.scenarioId]
            source = 'real-headless-fabric-coordinator'
        })
    }
}
$status = if (@($trials | Where-Object { $_.status -eq 'FAILED' }).Count -gt 0) { 'FAILED' } elseif (@($trials | Where-Object { $_.status -eq 'PASSED' }).Count -gt 0) { 'PASSED' } else { 'SKIPPED' }
$evidence = [pscustomobject][ordered]@{
    schemaVersion = 1
    status = $status
    sessionState = 'cold'
    repetitions = $Repetitions
    note = 'Each sample starts a fresh Fabric server and coordinator. Warm-session evidence needs the persistent-session hook documented in the performance evidence report.'
    runs = @($runs)
    trials = @($trials)
}
$evidencePath = Join-Path $output 'latency-headless-evidence.json'
[IO.File]::WriteAllText($evidencePath, ($evidence | ConvertTo-Json -Depth 12), [Text.UTF8Encoding]::new($false))
$evidence | ConvertTo-Json -Depth 12
if ($RequireLive -and $status -ne 'PASSED') { throw 'Required live headless latency evidence was unavailable or failed' }
