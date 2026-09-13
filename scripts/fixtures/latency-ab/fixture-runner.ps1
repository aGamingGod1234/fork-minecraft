[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $InvocationPath,
    [Parameter(Mandatory = $true)] [string] $ResultPath,
    [Parameter(Mandatory = $true)] [string] $ArtifactPath,
    [string] $SharedStatePath,
    [string] $Label,
    [string] $FixtureMode,
    [switch] $RetryOnce,
    [switch] $DriftSource
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Read-JsonFile {
    param([Parameter(Mandatory = $true)] [string] $Path)
    return (Get-Content -Raw -LiteralPath $Path | ConvertFrom-Json)
}

function Update-ActiveCount {
    param(
        [Parameter(Mandatory = $true)] [AllowEmptyString()] [string] $Path,
        [Parameter(Mandatory = $true)] [int] $Delta
    )
    if ([string]::IsNullOrWhiteSpace($Path)) { return 0 }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Path) | Out-Null
    for ($attempt = 0; $attempt -lt 100; $attempt++) {
        $stream = $null
        try {
            $stream = New-Object IO.FileStream($Path, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
            $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::UTF8, $true, 1024, $true)
            $text = $reader.ReadToEnd()
            $reader.Dispose()
            $current = 0
            if (-not [string]::IsNullOrWhiteSpace($text)) { $current = [int]$text.Trim() }
            $next = [Math]::Max(0, $current + $Delta)
            $stream.SetLength(0)
            $writer = New-Object IO.StreamWriter($stream, [Text.Encoding]::UTF8, 1024, $true)
            $writer.Write($next.ToString([Globalization.CultureInfo]::InvariantCulture))
            $writer.Flush()
            $writer.Dispose()
            $stream.Dispose()
            return $next
        }
        catch [IO.IOException] {
            if ($null -ne $stream) { $stream.Dispose() }
            Start-Sleep -Milliseconds 5
        }
    }
    throw "Could not acquire fixture state lock: $Path"
}

$invocation = Read-JsonFile $InvocationPath
$active = Update-ActiveCount -Path $SharedStatePath -Delta 1
$maxPath = "$SharedStatePath.max"
try {
    if ($active -gt 1) {
        Set-Content -LiteralPath "$SharedStatePath.overlap" -Value "active=$active" -NoNewline
    }
    if ($RetryOnce -and [int]$invocation.attempt -eq 1 -and [string]$invocation.trialId -eq 'pair-a') {
        exit 17
    }
    if ($DriftSource) {
        Add-Content -LiteralPath (Join-Path ([string]$invocation.worktreePath) 'src\fixture-drift.txt') -Value 'drift'
    }
    if ($FixtureMode -eq 'timeout') { Start-Sleep -Seconds 10 }
    if ($FixtureMode -eq 'malformed') {
        Write-Output 'not-json'
        exit 0
    }
    if ($FixtureMode -eq 'cleanup-leak') {
        Set-Content -LiteralPath (Join-Path $ArtifactPath 'leftover.tmp') -Value 'leak' -NoNewline
    }

    $status = 'PASSED'
    $errorCategory = $null
    if ($FixtureMode -eq 'skip-live') {
        $status = 'SKIPPED'
        $errorCategory = 'skipped'
    }
    $result = [ordered]@{
        runId = [string]$invocation.runId
        trialId = [string]$invocation.trialId
        arm = [string]$invocation.arm
        seed = [int]$invocation.seed
        status = $status
        latencyMs = 12
        cleanup = [ordered]@{ ok = $true; tempResidue = 0; backupResidue = 0 }
    }
    if ($null -ne $errorCategory) { $result.errorCategory = $errorCategory }
    $json = $result | ConvertTo-Json -Depth 20 -Compress
    Set-Content -LiteralPath $ResultPath -Value $json -NoNewline
    Write-Output $json
}
finally {
    if (-not [string]::IsNullOrWhiteSpace($SharedStatePath)) {
        $null = Update-ActiveCount -Path $SharedStatePath -Delta -1
        $maxCurrent = 0
        if (Test-Path -LiteralPath $SharedStatePath) {
            $maxCurrent = [int](Get-Content -Raw -LiteralPath $SharedStatePath)
        }
        Set-Content -LiteralPath "$SharedStatePath.max" -Value "maxActive=1" -NoNewline
    }
}
