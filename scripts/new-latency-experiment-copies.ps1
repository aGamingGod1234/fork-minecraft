[CmdletBinding()]
param(
    [string] $ProjectRoot,
    [string] $DestinationRoot
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:ExcludedDirectoryNames = @(
    '.git',
    '.worktrees',
    '.gradle',
    'build',
    'run',
    'logs',
    '.playwright-cli',
    'output',
    'credentials',
    'node_modules',
    '.superpowers'
)

$script:ExcludedRootDirectoryNames = @(
    'runtime'
)

$script:ExcludedFileNamePatterns = @(
    '*.env',
    '*.env.*',
    '*credentials*',
    '*oauth*',
    '*api-key*',
    '*api_key*',
    '*apikey*',
    '*token*',
    '*bridge-secret*',
    '*bridgesecret*',
    '*provider-login*',
    '*providerlogin*',
    '*client-secret*',
    '*clientsecret*',
    '*secret*'
)

function Invoke-GitCommand {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $Repository,
        [Parameter(Mandatory = $true)]
        [string[]] $Arguments
    )

    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& git -C $Repository @Arguments 2>&1)
        $exitCode = $LASTEXITCODE
    }
    catch {
        throw "Git command could not be started: git -C $Repository $($Arguments -join ' ')"
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }

    if ($exitCode -ne 0) {
        throw "Git command failed with exit code ${exitCode}: git -C $Repository $($Arguments -join ' ')"
    }

    return $output
}

function Resolve-DirectoryPath {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $Path,
        [Parameter(Mandatory = $true)]
        [bool] $CreateIfMissing
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw 'A non-empty directory path is required.'
    }

    if ($CreateIfMissing -and -not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Force -Path $Path | Out-Null
    }

    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "Directory does not exist: $Path"
    }

    $resolved = Resolve-Path -LiteralPath $Path -ErrorAction Stop
    return [IO.Path]::GetFullPath($resolved.ProviderPath)
}

function Test-PathUnderRoot {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $CandidatePath,
        [Parameter(Mandatory = $true)]
        [string] $RootPath
    )

    $candidate = [IO.Path]::GetFullPath($CandidatePath)
    $root = [IO.Path]::GetFullPath($RootPath)
    if ($root.Length -gt 3) {
        $root = $root.TrimEnd([char[]] @('\', '/'))
    }

    $rootPrefix = $root
    if (-not $rootPrefix.EndsWith([string][IO.Path]::DirectorySeparatorChar) -and
        -not $rootPrefix.EndsWith([string][IO.Path]::AltDirectorySeparatorChar)) {
        $rootPrefix += [IO.Path]::DirectorySeparatorChar
    }

    return $candidate.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)
}

function Test-ExcludedRelativePath {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $RelativePath
    )

    $normalizedPath = $RelativePath.Replace('\', '/')
    $segments = $normalizedPath -split '/'
    if ($segments.Count -gt 0 -and $script:ExcludedRootDirectoryNames -contains $segments[0]) {
        return $true
    }
    foreach ($segment in $segments) {
        if ($script:ExcludedDirectoryNames -contains $segment) {
            return $true
        }
        foreach ($pattern in $script:ExcludedFileNamePatterns) {
            if ($segment -like $pattern) {
                return $true
            }
        }
    }

    return $false
}

function Get-SourceManifestHash {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $WorktreePath
    )

    $entries = @(
        Get-ChildItem -LiteralPath $WorktreePath -File -Recurse -Force -ErrorAction Stop |
            ForEach-Object {
                $relativePath = $_.FullName.Substring($WorktreePath.Length).TrimStart([char[]] @('\', '/')).Replace('\', '/')
                if (Test-ExcludedRelativePath -RelativePath $relativePath) {
                    return
                }

                $fileHash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
                "$relativePath`0$fileHash"
            }
    )

    $entries = @($entries | Sort-Object -CaseSensitive)
    $manifestText = [string]::Join("`n", [string[]] $entries)
    $hashAlgorithm = [Security.Cryptography.SHA256]::Create()
    try {
        $manifestBytes = [Text.Encoding]::UTF8.GetBytes($manifestText)
        return ([BitConverter]::ToString($hashAlgorithm.ComputeHash($manifestBytes))).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $hashAlgorithm.Dispose()
    }
}

function Get-SnapshotPathspecs {
    [CmdletBinding()]
    param()

    $excludedPathspecs = New-Object 'System.Collections.Generic.List[string]'
    foreach ($directoryName in $script:ExcludedDirectoryNames) {
        $excludedPathspecs.Add(":(exclude,glob,icase)$directoryName")
        $excludedPathspecs.Add(":(exclude,glob,icase)$directoryName/**")
        $excludedPathspecs.Add(":(exclude,glob,icase)**/$directoryName")
        $excludedPathspecs.Add(":(exclude,glob,icase)**/$directoryName/**")
    }
    foreach ($fileNamePattern in $script:ExcludedFileNamePatterns) {
        $excludedPathspecs.Add(":(exclude,glob,icase)$fileNamePattern")
        $excludedPathspecs.Add(":(exclude,glob,icase)**/$fileNamePattern")
        $excludedPathspecs.Add(":(exclude,glob,icase)$fileNamePattern/**")
        $excludedPathspecs.Add(":(exclude,glob,icase)**/$fileNamePattern/**")
    }

    $pathspecs = @('.')
    $pathspecs += @($excludedPathspecs)
    foreach ($directoryName in $script:ExcludedRootDirectoryNames) {
        $pathspecs += @(
            ":(exclude,glob,icase)$directoryName",
            ":(exclude,glob,icase)$directoryName/**"
        )
    }
    return $pathspecs
}

function New-LatencyExperimentCopies {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string] $ProjectRoot,
        [Parameter(Mandatory = $true)]
        [string] $DestinationRoot
    )

    $resolvedProjectRoot = Resolve-DirectoryPath -Path $ProjectRoot -CreateIfMissing $false
    $resolvedDestinationRoot = Resolve-DirectoryPath -Path $DestinationRoot -CreateIfMissing $true

    $gitTopLevel = [string](Invoke-GitCommand -Repository $resolvedProjectRoot -Arguments @('rev-parse', '--show-toplevel') | Select-Object -First 1)
    if ([string]::IsNullOrWhiteSpace($gitTopLevel)) {
        throw "Project root is not a Git worktree: $resolvedProjectRoot"
    }
    $gitTopLevel = [IO.Path]::GetFullPath($gitTopLevel.Trim())
    if (-not $gitTopLevel.Equals($resolvedProjectRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Project root must be the Git worktree root: $resolvedProjectRoot"
    }

    $baselinePath = [IO.Path]::GetFullPath((Join-Path $resolvedDestinationRoot 'latency-baseline'))
    $optimizedPath = [IO.Path]::GetFullPath((Join-Path $resolvedDestinationRoot 'latency-optimized'))
    foreach ($worktreePath in @($baselinePath, $optimizedPath)) {
        if (-not (Test-PathUnderRoot -CandidatePath $worktreePath -RootPath $resolvedDestinationRoot)) {
            throw "Refusing to create a worktree outside the destination root: $worktreePath"
        }
        if (Test-Path -LiteralPath $worktreePath) {
            throw "Destination worktree already exists: $worktreePath"
        }
    }

    $temporaryIndexPath = Join-Path ([IO.Path]::GetTempPath()) ('latency-experiment-index-' + [guid]::NewGuid().ToString('N'))
    $previousIndexFile = $env:GIT_INDEX_FILE
    $temporaryIndexActive = $false
    $createdWorktrees = New-Object 'System.Collections.Generic.List[string]'
    $completed = $false

    try {
        $env:GIT_INDEX_FILE = $temporaryIndexPath
        $temporaryIndexActive = $true

        $null = Invoke-GitCommand -Repository $resolvedProjectRoot -Arguments @('read-tree', '--empty')
        $null = Invoke-GitCommand -Repository $resolvedProjectRoot -Arguments (Get-SnapshotPathspecs | ForEach-Object { if ($_ -eq '.') { 'add'; '-A'; '-f'; '--'; $_ } else { $_ } })
        $treeId = [string](Invoke-GitCommand -Repository $resolvedProjectRoot -Arguments @('write-tree') | Select-Object -First 1)
        $treeId = $treeId.Trim()
        if ([string]::IsNullOrWhiteSpace($treeId)) {
            throw 'Git did not return a snapshot tree ID.'
        }

        $parentId = $null
        $headResult = @(& git -C $resolvedProjectRoot rev-parse --verify HEAD 2>&1)
        $headExitCode = $LASTEXITCODE
        if ($headExitCode -eq 0 -and $headResult.Count -gt 0) {
            $parentId = ([string]$headResult[0]).Trim()
        }

        $commitArguments = @('commit-tree', $treeId)
        if (-not [string]::IsNullOrWhiteSpace($parentId)) {
            $commitArguments += @('-p', $parentId)
        }
        $commitArguments += @('-m', 'Latency experiment snapshot')
        $snapshotId = [string](Invoke-GitCommand -Repository $resolvedProjectRoot -Arguments $commitArguments | Select-Object -First 1)
        $snapshotId = $snapshotId.Trim()
        if ([string]::IsNullOrWhiteSpace($snapshotId)) {
            throw 'Git did not return a snapshot commit ID.'
        }

        # Worktree checkout must create its own index from the snapshot. Do not restore the
        # caller's custom index yet, and do not let the temporary staging index leak into it.
        Remove-Item Env:GIT_INDEX_FILE -ErrorAction SilentlyContinue

        $null = Invoke-GitCommand -Repository $resolvedProjectRoot -Arguments @('worktree', 'add', '--quiet', '--detach', $baselinePath, $snapshotId)
        $createdWorktrees.Add($baselinePath) | Out-Null
        $null = Invoke-GitCommand -Repository $resolvedProjectRoot -Arguments @('worktree', 'add', '--quiet', '--detach', $optimizedPath, $snapshotId)
        $createdWorktrees.Add($optimizedPath) | Out-Null

        foreach ($worktreePath in @($baselinePath, $optimizedPath)) {
            $resolvedWorktreePath = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $worktreePath -ErrorAction Stop).ProviderPath)
            if (-not (Test-PathUnderRoot -CandidatePath $resolvedWorktreePath -RootPath $resolvedDestinationRoot)) {
                throw "Created worktree resolved outside the destination root: $resolvedWorktreePath"
            }
        }

        $baselineSourceHash = Get-SourceManifestHash -WorktreePath $baselinePath
        $optimizedSourceHash = Get-SourceManifestHash -WorktreePath $optimizedPath
        $completed = $true

        return [pscustomobject]@{
            SnapshotId = $snapshotId
            BaselinePath = $baselinePath
            OptimizedPath = $optimizedPath
            BaselineSourceHash = $baselineSourceHash
            OptimizedSourceHash = $optimizedSourceHash
        }
    }
    finally {
        if ($temporaryIndexActive) {
            if ($null -eq $previousIndexFile) {
                Remove-Item Env:GIT_INDEX_FILE -ErrorAction SilentlyContinue
            }
            else {
                $env:GIT_INDEX_FILE = $previousIndexFile
            }
        }

        if (-not $completed) {
            for ($index = $createdWorktrees.Count - 1; $index -ge 0; $index--) {
                try {
                    $null = Invoke-GitCommand -Repository $resolvedProjectRoot -Arguments @('worktree', 'remove', '--force', $createdWorktrees[$index])
                }
                catch {
                    # Preserve the original failure; cleanup is best effort.
                }
            }
        }

        if (Test-Path -LiteralPath $temporaryIndexPath) {
            Remove-Item -LiteralPath $temporaryIndexPath -Force -ErrorAction SilentlyContinue
        }
    }
}

if (-not [string]::IsNullOrWhiteSpace($ProjectRoot) -or -not [string]::IsNullOrWhiteSpace($DestinationRoot)) {
    New-LatencyExperimentCopies -ProjectRoot $ProjectRoot -DestinationRoot $DestinationRoot
}
