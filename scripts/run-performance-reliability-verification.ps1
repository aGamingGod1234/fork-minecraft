[CmdletBinding()]
param(
    [string] $ProjectRoot,
    [ValidateRange(1, 500)]
    [int] $SoakRuns = 50,
    [switch] $ClaimTwoX,
    [string] $BaselineLatencyEvidence,
    [string] $OptimizedLatencyEvidence,
    [string] $InstrumentationEvidence,
    [string] $LatencyAcceptancePolicy,
    [string] $LatencyAcceptanceOutput
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }

$Project = [IO.Path]::GetFullPath($ProjectRoot)
$AutomatedVerifier = Join-Path $Project 'scripts\run-automated-verification.ps1'
$ModelAuthoredVerifier = Join-Path $Project 'scripts\verify-model-authored-programs.ps1'
$Coordinator = Join-Path $Project 'coordinator'
$SoakTest = Join-Path $Coordinator 'test\eight-agent-soak.test.mjs'
$AcceptanceCli = Join-Path $Coordinator 'src\benchmark\latency-acceptance-cli.mjs'

foreach ($required in @($AutomatedVerifier, $ModelAuthoredVerifier, $SoakTest)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing verification prerequisite: $required" }
}

if ($ClaimTwoX) {
    if ([string]::IsNullOrWhiteSpace($LatencyAcceptancePolicy)) { $LatencyAcceptancePolicy = Join-Path $Coordinator 'config\latency-acceptance.json' }
    if ([string]::IsNullOrWhiteSpace($LatencyAcceptanceOutput)) { $LatencyAcceptanceOutput = Join-Path $Project 'runtime\performance-evidence\latency-acceptance.json' }
    foreach ($required in @($AcceptanceCli, $BaselineLatencyEvidence, $OptimizedLatencyEvidence, $InstrumentationEvidence, $LatencyAcceptancePolicy)) {
        if ([string]::IsNullOrWhiteSpace($required) -or -not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "A claimed 2x verification requires every acceptance evidence file. Missing: $required" }
    }
    $acceptanceParent = Split-Path -Parent ([IO.Path]::GetFullPath($LatencyAcceptanceOutput))
    New-Item -ItemType Directory -Force -Path $acceptanceParent | Out-Null
}

$startedAt = [Diagnostics.Stopwatch]::StartNew()
$previousErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $verificationOutput = & powershell -NoProfile -ExecutionPolicy Bypass -File $AutomatedVerifier -ProjectRoot $Project 2>&1
    $verificationExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
if ($verificationExitCode -ne 0) {
    $verificationOutput | ForEach-Object { Write-Host $_ }
    throw "Automated verification failed with code $verificationExitCode"
}
$verificationOutput |
    Where-Object { $_ -match 'PASS: \d+ protocol and bridge assertions|# tests \d+|Automated Java' } |
    ForEach-Object { Write-Host $_ }

$previousTask10ErrorActionPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $task10Output = & powershell -NoProfile -ExecutionPolicy Bypass -File $ModelAuthoredVerifier -ProjectRoot $Project 2>&1
    $task10ExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousTask10ErrorActionPreference
}
$task10Output | ForEach-Object { Write-Host $_ }
if ($task10ExitCode -ne 0) {
    throw "Model-authored realtime program gate failed with code $task10ExitCode"
}

Push-Location $Coordinator
try {
    for ($iteration = 1; $iteration -le $SoakRuns; $iteration++) {
        $soakOutput = & node --test $SoakTest 2>&1
        if ($LASTEXITCODE -ne 0) {
            $soakOutput | ForEach-Object { Write-Host $_ }
            throw "Eight-agent soak failed on iteration $iteration"
        }
    }
} finally {
    Pop-Location
}

if ($ClaimTwoX) {
    $acceptanceOutput = & node $AcceptanceCli --baseline $BaselineLatencyEvidence --optimized $OptimizedLatencyEvidence --policy $LatencyAcceptancePolicy --instrumentation $InstrumentationEvidence --output $LatencyAcceptanceOutput 2>&1
    $acceptanceExitCode = $LASTEXITCODE
    $acceptanceOutput | ForEach-Object { Write-Host $_ }
    if ($acceptanceExitCode -ne 0) { throw "The claimed 2x latency acceptance gate failed with code $acceptanceExitCode" }
}

$startedAt.Stop()
Write-Host ("Performance and reliability verification passed: full verifier plus {0}/{0} soak runs in {1:N1}s. 2x claim gate: {2}." -f $SoakRuns, $startedAt.Elapsed.TotalSeconds, $(if ($ClaimTwoX) { 'passed' } else { 'not requested' }))
