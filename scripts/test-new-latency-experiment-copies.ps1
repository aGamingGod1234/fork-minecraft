[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-FixtureGit {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Repository,
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = & git -C $Repository @Arguments 2>&1
        $exitCode = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) {
        throw "Fixture git command failed: git $($Arguments -join ' ')"
    }
    return @($output)
}

function Assert-Fixture {
    param(
        [Parameter(Mandatory = $true)]
        [bool] $Condition,
        [Parameter(Mandatory = $true)]
        [string] $Message
    )

    if (-not $Condition) { throw $Message }
}

function Get-ByteString {
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path
    )

    return [Convert]::ToBase64String([IO.File]::ReadAllBytes($Path))
}

function Invoke-ExpectedFailure {
    param(
        [Parameter(Mandatory = $true)]
        [scriptblock] $Action,
        [Parameter(Mandatory = $true)]
        [string] $Message
    )

    $failed = $false
    try {
        & $Action
    }
    catch {
        $failed = $true
    }
    Assert-Fixture $failed $Message
}

$scriptRoot = Split-Path -Parent $PSScriptRoot
$creationScript = Join-Path $scriptRoot 'scripts\new-latency-experiment-copies.ps1'

# Load the containment helper before fixture variables are initialized; the creation script
# has parameter variables named ProjectRoot and DestinationRoot.
. $creationScript

$fixtureRoot = Join-Path ([IO.Path]::GetTempPath()) ('latency-experiment-copy-fixture-' + [guid]::NewGuid().ToString('N'))
$projectRoot = Join-Path $fixtureRoot 'project'
$destinationRoot = Join-Path $fixtureRoot 'destination'
$worktreePaths = New-Object 'System.Collections.Generic.List[string]'
$originalGitIndexFile = $env:GIT_INDEX_FILE
Remove-Item Env:GIT_INDEX_FILE -ErrorAction SilentlyContinue

try {
    New-Item -ItemType Directory -Force -Path $projectRoot, $destinationRoot | Out-Null
    Invoke-FixtureGit -Repository $fixtureRoot -Arguments @('init', '--quiet', $projectRoot) | Out-Null
    Invoke-FixtureGit -Repository $projectRoot -Arguments @('config', 'user.name', 'Latency Fixture') | Out-Null
    Invoke-FixtureGit -Repository $projectRoot -Arguments @('config', 'user.email', 'latency-fixture@example.invalid') | Out-Null

    Set-Content -LiteralPath (Join-Path $projectRoot '.gitignore') -Value "runtime/`nnode_modules/`n.superpowers/`n"
    Set-Content -LiteralPath (Join-Path $projectRoot 'tracked.txt') -Value "before edit`n"
    Set-Content -LiteralPath (Join-Path $projectRoot 'staged.txt') -Value "before stage`n"

    $excludedPaths = @(
        '.ENV',
        'Credentials.json',
        'Credentials\service-account.json',
        '.GrAdLe\cache.txt',
        'BUILD\artifact.txt',
        'Run\pid.txt',
        'Runtime\bridge-secret.txt',
        'Runtime\state.dat',
        'LOGS\server.log',
        '.PLAYWRIGHT-CLI\trace.json',
        'OUTPUT\result.json',
        '.WORKTREES\old-snapshot.txt',
        'NODE_MODULES\leftpad\index.js',
        '.SUPERPOWERS\state.json',
        'OAuth\refresh-token.json',
        'API-Key.json',
        'api_key.json',
        'Provider-Login.json',
        'BridgeSecret.json',
        'client-secret.json',
        'authToken.json'
    )
    foreach ($relativePath in $excludedPaths) {
        $absolutePath = Join-Path $projectRoot $relativePath
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $absolutePath) | Out-Null
        Set-Content -LiteralPath $absolutePath -Value ('excluded-' + $relativePath)
    }

    Invoke-FixtureGit -Repository $projectRoot -Arguments @('add', '.gitignore', 'tracked.txt', 'staged.txt') | Out-Null
    Invoke-FixtureGit -Repository $projectRoot -Arguments (@('add', '-f', '--') + $excludedPaths.Replace('\', '/')) | Out-Null
    Invoke-FixtureGit -Repository $projectRoot -Arguments @('commit', '--quiet', '-m', 'fixture baseline') | Out-Null

    Set-Content -LiteralPath (Join-Path $projectRoot 'tracked.txt') -Value "tracked working edit`n"
    Set-Content -LiteralPath (Join-Path $projectRoot 'staged.txt') -Value "staged working edit`n"
    Invoke-FixtureGit -Repository $projectRoot -Arguments @('add', 'staged.txt') | Out-Null
    New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'src\voice-addon') | Out-Null
    Set-Content -LiteralPath (Join-Path $projectRoot 'src\voice-addon\untracked-source.mjs') -Value "export const fixtureSource = true;`n"
    New-Item -ItemType Directory -Force -Path (Join-Path $projectRoot 'src\main\java\dev\example\runtime') | Out-Null
    Set-Content -LiteralPath (Join-Path $projectRoot 'src\main\java\dev\example\runtime\Fixture.java') -Value "package dev.example.runtime;`n"

    $indexPath = Join-Path $projectRoot '.git\index'
    $indexBefore = Get-ByteString -Path $indexPath
    $workingPaths = @(
        'tracked.txt',
        'staged.txt',
        'src\voice-addon\untracked-source.mjs',
        'src\main\java\dev\example\runtime\Fixture.java',
        'Runtime\bridge-secret.txt'
    )
    $workingBytesBefore = @{}
    foreach ($relativePath in $workingPaths) {
        $workingBytesBefore[$relativePath] = Get-ByteString -Path (Join-Path $projectRoot $relativePath)
    }

    $preservedIndexPath = Join-Path $fixtureRoot 'preserved-index'
    Copy-Item -LiteralPath $indexPath -Destination $preservedIndexPath
    $preservedIndexBefore = Get-ByteString -Path $preservedIndexPath
    $env:GIT_INDEX_FILE = $preservedIndexPath

    $result = & $creationScript -ProjectRoot $projectRoot -DestinationRoot $destinationRoot
    if ($LASTEXITCODE -ne 0) { throw 'Copy creation script returned a non-zero exit code.' }
    if ($null -eq $result) { throw 'Copy creation script returned no result.' }
    Assert-Fixture ([string]$env:GIT_INDEX_FILE -eq $preservedIndexPath) 'Pre-existing GIT_INDEX_FILE was not restored.'
    Assert-Fixture ((Get-ByteString -Path $preservedIndexPath) -eq $preservedIndexBefore) 'Pre-existing GIT_INDEX_FILE bytes changed.'

    $baselinePath = [IO.Path]::GetFullPath([string]$result.BaselinePath)
    $optimizedPath = [IO.Path]::GetFullPath([string]$result.OptimizedPath)
    $worktreePaths.Add($baselinePath)
    $worktreePaths.Add($optimizedPath)
    Remove-Item Env:GIT_INDEX_FILE -ErrorAction SilentlyContinue
    foreach ($copyPath in @($baselinePath, $optimizedPath)) {
        Assert-Fixture (Test-Path -LiteralPath $copyPath -PathType Container) "Snapshot worktree is missing: $copyPath"
        Assert-Fixture ((Get-Content -Raw -LiteralPath (Join-Path $copyPath 'tracked.txt')).TrimEnd([char[]] @("`r", "`n")) -eq 'tracked working edit') 'Tracked edit was not copied.'
        Assert-Fixture ((Get-Content -Raw -LiteralPath (Join-Path $copyPath 'staged.txt')).TrimEnd([char[]] @("`r", "`n")) -eq 'staged working edit') 'Pre-staged file content was not copied.'
        Assert-Fixture (Test-Path -LiteralPath (Join-Path $copyPath 'src\voice-addon\untracked-source.mjs') -PathType Leaf) 'Untracked source file was not copied.'
        Assert-Fixture (Test-Path -LiteralPath (Join-Path $copyPath 'src\main\java\dev\example\runtime\Fixture.java') -PathType Leaf) 'Source package runtime directory was not copied.'
        foreach ($relativePath in $excludedPaths) {
            Assert-Fixture (-not (Test-Path -LiteralPath (Join-Path $copyPath $relativePath))) "Excluded path was copied: $relativePath"
        }
        $trackedFiles = @(Invoke-FixtureGit -Repository $copyPath -Arguments @('ls-files', '--error-unmatch', '--', 'tracked.txt', 'staged.txt'))
        Assert-Fixture ($trackedFiles.Count -eq 2) "Snapshot worktree index is missing tracked files: $copyPath"
        $statusOutput = [string]::Join("`n", [string[]]@(Invoke-FixtureGit -Repository $copyPath -Arguments @('status', '--porcelain')))
        Assert-Fixture ([string]::IsNullOrWhiteSpace($statusOutput)) "Snapshot worktree is dirty: $copyPath"
        Invoke-FixtureGit -Repository $copyPath -Arguments @('diff', '--exit-code') | Out-Null
    }
    Assert-Fixture ([string]$result.BaselineSourceHash -eq [string]$result.OptimizedSourceHash) 'Snapshot source hashes do not match.'
    Assert-Fixture (-not [string]::IsNullOrWhiteSpace([string]$result.SnapshotId)) 'Snapshot ID is missing.'

    $indexAfter = Get-ByteString -Path $indexPath
    Assert-Fixture ($indexBefore -eq $indexAfter) 'Original Git index bytes changed.'
    foreach ($relativePath in $workingPaths) {
        Assert-Fixture ((Get-ByteString -Path (Join-Path $projectRoot $relativePath)) -eq $workingBytesBefore[$relativePath]) "Original working file changed: $relativePath"
    }

    Remove-Item Env:GIT_INDEX_FILE -ErrorAction SilentlyContinue
    foreach ($worktreePath in @($baselinePath, $optimizedPath)) {
        Invoke-FixtureGit -Repository $projectRoot -Arguments @('worktree', 'remove', '--force', '--', $worktreePath) | Out-Null
    }
    $worktreePaths.Clear()

    $existingBaselinePath = Join-Path $destinationRoot 'latency-baseline'
    New-Item -ItemType Directory -Force -Path $existingBaselinePath | Out-Null
    Invoke-ExpectedFailure -Action { & $creationScript -ProjectRoot $projectRoot -DestinationRoot $destinationRoot } -Message 'Pre-existing destination worktree was not rejected.'
    Remove-Item -LiteralPath $existingBaselinePath -Recurse -Force

    Assert-Fixture (-not (Test-PathUnderRoot -CandidatePath (Join-Path $fixtureRoot 'outside') -RootPath $destinationRoot)) 'Destination containment check accepted an outside path.'

    $optimizedRegistrationPath = Join-Path $destinationRoot 'latency-optimized'
    Invoke-FixtureGit -Repository $projectRoot -Arguments @('worktree', 'add', '--quiet', '--detach', $optimizedRegistrationPath, 'HEAD') | Out-Null
    Remove-Item -LiteralPath $optimizedRegistrationPath -Recurse -Force
    Invoke-ExpectedFailure -Action { & $creationScript -ProjectRoot $projectRoot -DestinationRoot $destinationRoot } -Message 'Second worktree creation failure was not surfaced.'
    Assert-Fixture (-not (Test-Path -LiteralPath (Join-Path $destinationRoot 'latency-baseline'))) 'First worktree was not cleaned up after second worktree failure.'
    Invoke-FixtureGit -Repository $projectRoot -Arguments @('worktree', 'prune') | Out-Null

    Write-Host 'Latency experiment copy fixture and failure-isolation tests passed.'
}
finally {
    $env:GIT_INDEX_FILE = $originalGitIndexFile
    foreach ($worktreePath in $worktreePaths) {
        if (Test-Path -LiteralPath $worktreePath) {
            & git -C $projectRoot worktree remove --force -- $worktreePath *> $null
        }
    }
    if (Test-Path -LiteralPath $fixtureRoot) {
        Remove-Item -LiteralPath $fixtureRoot -Recurse -Force
    }
}
