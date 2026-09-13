[CmdletBinding()]
param(
    [string] $ProjectRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }

$Project = [IO.Path]::GetFullPath($ProjectRoot)
$Coordinator = Join-Path $Project 'coordinator'
$Gradle = Join-Path $Project 'gradlew.bat'
$RepoJavaHome = Join-Path $Project 'runtime\toolchains\temurin-25\jdk-25.0.3+9'

foreach ($required in @(
    (Join-Path $Coordinator 'package.json'),
    $Gradle
)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
        throw "Missing verification prerequisite: $required"
    }
}

function Resolve-Java25Home {
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { $candidates += [IO.Path]::GetFullPath($env:JAVA_HOME) }
    $candidates += $RepoJavaHome
    $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($candidate in $candidates) {
        if (-not $seen.Add($candidate)) { continue }
        $java = Join-Path $candidate 'bin\java.exe'
        if (-not (Test-Path -LiteralPath $java -PathType Leaf)) { continue }
        $versionOutput = @()
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try { $versionOutput = @(& $java -version 2>&1) } finally { $ErrorActionPreference = $previousPreference }
        $versionText = ($versionOutput | ForEach-Object { [string] $_ }) -join "`n"
        if ($versionText -match '(?im)version\s+"25(?:\.|"|$)') { return $candidate }
    }
    throw "Java 25 is required. Set JAVA_HOME to a Java 25 JDK or provide the repository toolchain at '$RepoJavaHome'. Checked JAVA_HOME and the repository runtime; neither resolved Java 25."
}

function Invoke-CheckedNodeTests {
    param([string[]] $TestFiles)

    Push-Location $Coordinator
    try {
        $output = @(& node --test @TestFiles 2>&1)
        $exitCode = $LASTEXITCODE
    } finally {
        Pop-Location
    }

    $output | ForEach-Object { Write-Host $_ }
    if ($exitCode -ne 0) {
        throw "Model-authored Node verification failed with code $exitCode"
    }
    return $output
}

$nodeTests = @(
    'test/arena-script-facts.test.mjs',
    'test/arena-script-interpreter.test.mjs',
    'test/arena-script-parser.test.mjs',
    'test/arena-script-program-engine.test.mjs',
    'test/program-runtime-manager.test.mjs',
    'test/protocol-v2.test.mjs',
    'test/model-authored-programs-e2e.test.mjs'
)
$nodeOutput = Invoke-CheckedNodeTests -TestFiles $nodeTests

$summaryMatch = $null
foreach ($line in $nodeOutput) {
    $candidate = [regex]::Match([string] $line, 'TASK10_E2E_SUMMARY\s+(\{.*\})')
    if ($candidate.Success) { $summaryMatch = $candidate }
}
if ($null -eq $summaryMatch) { throw 'Missing TASK10_E2E_SUMMARY from model-authored E2E output.' }

try {
    $summary = $summaryMatch.Groups[1].Value | ConvertFrom-Json -ErrorAction Stop
} catch {
    throw "Invalid TASK10_E2E_SUMMARY JSON: $($_.Exception.Message)"
}
if ($summary.timing.benchmarkRequired -ne $true) {
    throw 'The E2E summary did not require a real benchmark.'
}
if ([int] $summary.passed -ne 10 -or @($summary.scenarios).Count -ne 10) {
    throw "Expected exactly 10 passed E2E scenarios, got passed=$($summary.passed), scenarios=$(@($summary.scenarios).Count)."
}
if (@($summary.scenarios | Where-Object { $_.passed -ne $true }).Count -ne 0) {
    throw 'The E2E summary contained a scenario that did not pass.'
}
if ([string] $summary.timing.basis -ne 'deterministic_fake_clock') {
    throw "Unexpected E2E timing basis: $($summary.timing.basis)"
}
if (@($summary.timing.syntheticLocal).Count -eq 0 -or @($summary.timing.syntheticProvider).Count -eq 0) {
    throw 'E2E synthetic timing segments are missing.'
}

Write-Host ("TASK10 scenarios: {0}/{0} passed." -f [int] $summary.passed)
Write-Host ("TASK10 segmented timing basis: {0} (synthetic fixture timing; not live measured latency)." -f $summary.timing.basis)
foreach ($segment in @($summary.timing.syntheticLocal) + @($summary.timing.syntheticProvider)) {
    Write-Host ("TASK10 latency [synthetic fixture, not measured] {0}: count={1}, p50Ms={2}, p95Ms={3}" -f $segment.operation, $segment.count, $segment.p50Ms, $segment.p95Ms)
}

$realTimerMatch = $null
foreach ($line in $nodeOutput) {
    $candidate = [regex]::Match([string] $line, 'REAL_TIMER_LATENCY_SUMMARY\s+(\{.*\})')
    if ($candidate.Success) { $realTimerMatch = $candidate }
}
if ($null -eq $realTimerMatch) { throw 'Missing REAL_TIMER_LATENCY_SUMMARY from the monotonic-clock manager benchmark.' }
try {
    $realTimer = $realTimerMatch.Groups[1].Value | ConvertFrom-Json -ErrorAction Stop
} catch {
    throw "Invalid REAL_TIMER_LATENCY_SUMMARY JSON: $($_.Exception.Message)"
}
if ([string] $realTimer.basis -ne 'performance_now_monotonic_clock') { throw "Unexpected real timer basis: $($realTimer.basis)" }
$realSegments = @($realTimer.segments)
$expectedRealOperations = @('event_receipt_to_branch', 'branch_to_bridge_send')
if ($realSegments.Count -ne $expectedRealOperations.Count) { throw "Expected $($expectedRealOperations.Count) real timer segments, got $($realSegments.Count)." }
foreach ($operation in $expectedRealOperations) {
    $segment = @($realSegments | Where-Object { $_.operation -eq $operation })
    if ($segment.Count -ne 1) { throw "Missing or duplicate real timer segment: $operation" }
    $metric = $segment[0]
    if ([int] $metric.count -ne 1000) { throw "Real timer segment $operation must contain 1000 samples, got $($metric.count)." }
    foreach ($field in @('p50Ms', 'p95Ms')) {
        $value = [double] $metric.$field
        if ([double]::IsNaN($value) -or [double]::IsInfinity($value) -or $value -lt 0) { throw "Real timer segment $operation has invalid ${field}: $($metric.$field)." }
    }
    Write-Host ("TASK10 latency [real performance.now timer] {0}: count={1}, p50Ms={2}, p95Ms={3}" -f $operation, $metric.count, $metric.p50Ms, $metric.p95Ms)
}

$JavaHome = Resolve-Java25Home
$previousJavaHome = $env:JAVA_HOME
$env:JAVA_HOME = $JavaHome
Push-Location $Project
try {
    & $Gradle verifyCore --no-daemon --console=plain
    $gradleExitCode = $LASTEXITCODE
} finally {
    Pop-Location
    $env:JAVA_HOME = $previousJavaHome
}
if ($gradleExitCode -ne 0) {
    throw "Java verifyCore failed with code $gradleExitCode"
}

Write-Host 'Model-authored ArenaScript, protocol, manager, E2E, and Java verifyCore gate passed.'
