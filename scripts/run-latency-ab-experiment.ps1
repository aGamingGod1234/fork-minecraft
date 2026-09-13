[CmdletBinding()]
param(
    [Alias('BaselineRoot', 'BaselineWorktree')]
    [string] $BaselinePath,
    [Alias('OptimizedRoot', 'OptimizedWorktree')]
    [string] $OptimizedPath,
    [string] $MatrixPath = 'coordinator\config\latency-matrix.json',
    [Alias('RunnerCommand', 'RunnerExecutable')]
    [AllowNull()]
    [string] $RunnerPath,
    [AllowNull()]
    [string] $BaselineRunnerPath,
    [AllowNull()]
    [string] $OptimizedRunnerPath,
    [switch] $NeutralRunner,
    [Alias('RunnerArgs')]
    [string[]] $RunnerArguments = @('__LATENCY_AB_NO_ARGS__'),
    [Alias('BaselineArguments')]
    [AllowNull()]
    [AllowEmptyCollection()]
    [string[]] $BaselineRunnerArguments,
    [Alias('OptimizedArguments')]
    [AllowNull()]
    [AllowEmptyCollection()]
    [string[]] $OptimizedRunnerArguments,
    [ValidateRange(1, 16)]
    [int] $BaselinePlanningConcurrency = 16,
    [ValidateRange(1, 16)]
    [int] $OptimizedPlanningConcurrency = 16,
    [ValidateRange(0, 16)]
    [int] $FixedPlanningConcurrency = 0,
    [AllowNull()]
    [Alias('BaselineReplayPath')]
    [string] $BaselineReplayRecordingsPath,
    [AllowNull()]
    [Alias('OptimizedReplayPath')]
    [string] $OptimizedReplayRecordingsPath,
    [string] $OutputRoot,
    [Alias('TimeoutSeconds', 'ArmTimeoutSeconds')]
    [ValidateRange(1, 86400)]
    [int] $OuterTimeoutSeconds = 300,
    [Alias('RetryCount')]
    [ValidateRange(0, 3)]
    [int] $MaxRetries = 0,
    [string[]] $Mode = @('instant'),
    [Alias('Provider')]
    [string[]] $Providers = @(),
    [switch] $RequireLive,
    [switch] $RequireProviders,
    [int] $Seed = 424242,
    [ValidateRange(1024, 1048576)]
    [int] $MaxOutputBytes = 65536,
    [ValidateRange(1024, 1048576)]
    [int] $MaxResultBytes = 131072,
    [switch] $KeepArtifacts
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:LatencyAbOrchestratorVersion = '1.1.0'
$script:ExcludedDirectoryNames = @(
    '.git', '.worktrees', '.gradle', 'build', 'run', 'logs', '.playwright-cli',
    'output', 'credentials', 'node_modules', '.superpowers'
)
$script:ExcludedRootDirectoryNames = @('runtime')
$script:ExcludedFileNamePatterns = @(
    '*.env', '*.env.*', '*credentials*', '*oauth*', '*api-key*', '*api_key*',
    '*apikey*', '*token*', '*bridge-secret*', '*bridgesecret*', '*provider-login*',
    '*providerlogin*', '*client-secret*', '*clientsecret*', '*secret*'
)

function Get-OptionalProperty {
    param(
        [Parameter(Mandatory = $true)] [object] $Object,
        [Parameter(Mandatory = $true)] [string] $Name
    )
    if ($null -eq $Object) { return $null }
    if ($Object -is [System.Collections.IDictionary]) {
        if (-not $Object.Contains($Name)) { return $null }
        return $Object[$Name]
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Test-PlainObject {
    param([AllowNull()] [object] $Value)
    if ($null -eq $Value -or $Value -is [string] -or $Value -is [System.Collections.IDictionary]) { return $false }
    return $Value.GetType().FullName -eq 'System.Management.Automation.PSCustomObject'
}

function Resolve-RequiredDirectory {
    param([Parameter(Mandatory = $true)] [string] $Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { throw 'A worktree directory path is required.' }
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { throw "Worktree directory does not exist: $Path" }
    return [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $Path -ErrorAction Stop).ProviderPath)
}

function Resolve-DirectoryForCreate {
    param([Parameter(Mandatory = $true)] [string] $Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { throw 'An output directory path is required.' }
    if (-not (Test-Path -LiteralPath $Path)) { New-Item -ItemType Directory -Force -Path $Path | Out-Null }
    if (-not (Test-Path -LiteralPath $Path -PathType Container)) { throw "Output path is not a directory: $Path" }
    return [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $Path -ErrorAction Stop).ProviderPath)
}

function Test-PathUnderRoot {
    param(
        [Parameter(Mandatory = $true)] [string] $CandidatePath,
        [Parameter(Mandatory = $true)] [string] $RootPath
    )
    $candidate = [IO.Path]::GetFullPath($CandidatePath)
    $root = [IO.Path]::GetFullPath($RootPath)
    if ($root.Length -gt 3) { $root = $root.TrimEnd([char[]] @('\', '/')) }
    $prefix = $root
    if (-not $prefix.EndsWith([string][IO.Path]::DirectorySeparatorChar) -and
        -not $prefix.EndsWith([string][IO.Path]::AltDirectorySeparatorChar)) {
        $prefix += [IO.Path]::DirectorySeparatorChar
    }
    return $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)
}

function Test-PathEqual {
    param([string] $Left, [string] $Right)
    return [IO.Path]::GetFullPath($Left).Equals([IO.Path]::GetFullPath($Right), [StringComparison]::OrdinalIgnoreCase)
}

function ConvertTo-CanonicalJsonValue {
    param([AllowNull()] [object] $Value)
    if ($null -eq $Value) { return $null }
    if ($Value -is [System.Collections.IDictionary]) {
        $ordered = [ordered]@{}
        foreach ($key in @($Value.Keys | Sort-Object -CaseSensitive)) {
            $ordered[[string]$key] = ConvertTo-CanonicalJsonValue $Value[$key]
        }
        return ,$ordered
    }
    if ($Value.GetType().FullName -eq 'System.Management.Automation.PSCustomObject') {
        $ordered = [ordered]@{}
        foreach ($property in @($Value.PSObject.Properties | Sort-Object Name -CaseSensitive)) {
            $ordered[$property.Name] = ConvertTo-CanonicalJsonValue $property.Value
        }
        return ,$ordered
    }
    if (($Value -is [System.Collections.IEnumerable]) -and -not ($Value -is [string])) {
        $items = New-Object 'System.Collections.Generic.List[object]'
        foreach ($item in $Value) { $items.Add((ConvertTo-CanonicalJsonValue $item)) | Out-Null }
        return ,@($items.ToArray())
    }
    return $Value
}

function ConvertTo-RedactedValue {
    param(
        [AllowNull()] [object] $Value,
        [string] $PropertyName = ''
    )
    if ($PropertyName -match '(?i)(token|secret|password|authorization|cookie|api[-_]?key|oauth|credential)') {
        return '[REDACTED]'
    }
    if ($null -eq $Value) { return $null }
    if ($Value -is [System.Collections.IDictionary]) {
        $result = [ordered]@{}
        foreach ($key in @($Value.Keys)) {
            $result[[string]$key] = ConvertTo-RedactedValue $Value[$key] ([string]$key)
        }
        return ,$result
    }
    if ($Value.GetType().FullName -eq 'System.Management.Automation.PSCustomObject') {
        $result = [ordered]@{}
        foreach ($property in @($Value.PSObject.Properties | ForEach-Object { $_ })) {
            $redactedProperty = ConvertTo-RedactedValue $property.Value $property.Name
            $result[$property.Name] = $redactedProperty
        }
        return ,$result
    }
    if (($Value -is [System.Collections.IEnumerable]) -and -not ($Value -is [string])) {
        $result = New-Object 'System.Collections.Generic.List[object]'
        foreach ($item in $Value) { $result.Add((ConvertTo-RedactedValue $item)) | Out-Null }
        return ,@($result.ToArray())
    }
    return $Value
}

function ConvertTo-RedactedRunnerArguments {
    param([AllowEmptyCollection()] [string[]] $Arguments)
    $result = New-Object 'System.Collections.Generic.List[string]'
    $redactNext = $false
    foreach ($argument in @($Arguments)) {
        $text = [string]$argument
        if ($redactNext) {
            $result.Add('[REDACTED]') | Out-Null
            $redactNext = $false
            continue
        }
        if ($text -match '(?i)(token|secret|password|authorization|cookie|api[-_]?key|oauth|credential)') {
            if ($text -match '^(.*?)([:=]).*$') {
                $result.Add("$($Matches[1])$($Matches[2])[REDACTED]") | Out-Null
            }
            else {
                $result.Add($text) | Out-Null
                $redactNext = $true
            }
            continue
        }
        $result.Add($text) | Out-Null
    }
    return @($result.ToArray())
}

function ConvertTo-SafeText {
    param([AllowNull()] [object] $Value)
    if ($null -eq $Value) { return '' }
    $text = [string]$Value
    $text = [regex]::Replace($text, '(?i)(bearer\s+)[^\s,;]+', '$1[REDACTED]')
    $text = [regex]::Replace($text, '(?i)((?:token|secret|password|authorization|api[-_]?key|cookie)\s*[:=]\s*)[^\s,;]+', '$1[REDACTED]')
    return $text
}

function Write-JsonArtifact {
    param(
        [Parameter(Mandatory = $true)] [string] $Path,
        [Parameter(Mandatory = $true)] [object] $Value,
        [int] $Depth = 50
    )
    $json = $Value | ConvertTo-Json -Depth $Depth
    $encoding = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText($Path, $json, $encoding)
}

function Get-Sha256Bytes {
    param([Parameter(Mandatory = $true)] [byte[]] $Bytes)
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant() }
    finally { $sha.Dispose() }
}

function Get-RawFileSha256 {
    param([Parameter(Mandatory = $true)] [string] $Path)
    return Get-Sha256Bytes ([IO.File]::ReadAllBytes($Path))
}

function Get-ArmExecutionConfig {
    param(
        [Parameter(Mandatory = $true)] [ValidateSet('baseline', 'optimized')] [string] $ArmName,
        [Parameter(Mandatory = $true)] [ValidateRange(1, 16)] [int] $PlanningConcurrency,
        [Parameter(Mandatory = $true)] [AllowNull()] [AllowEmptyCollection()] [string[]] $RunnerArguments,
        [AllowNull()] [string] $ReplayRecordingsPath
    )
    $raw = [ordered]@{
        arm = $ArmName
        planningConcurrency = $PlanningConcurrency
        replayRecordingsPath = $ReplayRecordingsPath
        runnerArguments = if ($null -eq $RunnerArguments) { @() } else { @($RunnerArguments) }
    }
    $canonical = ConvertTo-CanonicalJsonValue $raw
    $json = $canonical | ConvertTo-Json -Depth 30 -Compress
    return [pscustomobject]@{
        Raw = $raw
        Redacted = [ordered]@{ arm = $ArmName; planningConcurrency = $PlanningConcurrency; replayRecordingsPath = ConvertTo-RedactedValue $ReplayRecordingsPath 'replayRecordingsPath'; runnerArguments = ConvertTo-RedactedRunnerArguments @($RunnerArguments) }
        Sha256 = Get-Sha256Bytes ([Text.Encoding]::UTF8.GetBytes($json))
    }
}

function Test-ExcludedRelativePath {
    param([Parameter(Mandatory = $true)] [string] $RelativePath)
    $normalizedPath = $RelativePath.Replace('\', '/')
    $segments = $normalizedPath -split '/'
    if ($segments.Count -gt 0 -and $script:ExcludedRootDirectoryNames -contains $segments[0]) { return $true }
    foreach ($segment in $segments) {
        if ($script:ExcludedDirectoryNames -contains $segment) { return $true }
        foreach ($pattern in $script:ExcludedFileNamePatterns) {
            if ($segment -like $pattern) { return $true }
        }
    }
    return $false
}

function Get-SourceManifestSha256 {
    param([Parameter(Mandatory = $true)] [string] $WorktreePath)
    $entries = @(
        Get-ChildItem -LiteralPath $WorktreePath -File -Recurse -Force -ErrorAction Stop |
            ForEach-Object {
                $relativePath = $_.FullName.Substring($WorktreePath.Length).TrimStart([char[]] @('\', '/')).Replace('\', '/')
                if (Test-ExcludedRelativePath $relativePath) { return }
                $fileHash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
                "$relativePath`0$fileHash"
            }
    )
    $entries = @($entries | Sort-Object -CaseSensitive)
    return Get-Sha256Bytes ([Text.Encoding]::UTF8.GetBytes([string]::Join("`n", [string[]]$entries)))
}

function Get-CanonicalMatrixState {
    param(
        [Parameter(Mandatory = $true)] [string] $ArmPath,
        [Parameter(Mandatory = $true)] [string] $MatrixPathValue,
        [Parameter(Mandatory = $true)] [string[]] $Modes,
        [Parameter(Mandatory = $true)] [AllowEmptyCollection()] [string[]] $ProviderValues,
        [Parameter(Mandatory = $true)] [int] $RunSeed,
        [Parameter(Mandatory = $true)] [int] $Retries,
        [Parameter(Mandatory = $true)] [int] $TimeoutSeconds
    )
    $matrixFile = if ([IO.Path]::IsPathRooted($MatrixPathValue)) {
        [IO.Path]::GetFullPath($MatrixPathValue)
    }
    else {
        [IO.Path]::GetFullPath((Join-Path $ArmPath $MatrixPathValue))
    }
    if (-not (Test-Path -LiteralPath $matrixFile -PathType Leaf)) { throw "Latency matrix does not exist: $matrixFile" }
    $bytes = [IO.File]::ReadAllBytes($matrixFile)
    if ($bytes.Length -gt 4MB) { throw "Latency matrix exceeds the bounded 4 MiB limit: $matrixFile" }
    $text = [Text.Encoding]::UTF8.GetString($bytes)
    try { $parsed = $text | ConvertFrom-Json -ErrorAction Stop }
    catch { throw "Latency matrix is not valid JSON: $matrixFile" }
    if (-not (Test-PlainObject $parsed)) { throw 'Latency matrix must contain a JSON object.' }

    $canonical = ConvertTo-CanonicalJsonValue $parsed
    $canonicalJson = $canonical | ConvertTo-Json -Depth 100 -Compress
    $effective = [ordered]@{
        schemaVersion = 1
        matrix = $canonical
        execution = [ordered]@{
            modes = @($Modes)
            providers = @($ProviderValues)
            seed = $RunSeed
            maxRetries = $Retries
            outerTimeoutSeconds = $TimeoutSeconds
        }
    }
    $effectiveCanonical = ConvertTo-CanonicalJsonValue $effective
    $effectiveJson = $effectiveCanonical | ConvertTo-Json -Depth 100 -Compress
    return [pscustomobject]@{
        Path = $matrixFile
        Parsed = $parsed
        Canonical = $canonical
        CanonicalJson = $canonicalJson
        CanonicalSha256 = Get-Sha256Bytes ([Text.Encoding]::UTF8.GetBytes($canonicalJson))
        RawSha256 = Get-Sha256Bytes $bytes
        Effective = $effectiveCanonical
        EffectiveJson = $effectiveJson
        EffectiveSha256 = Get-Sha256Bytes ([Text.Encoding]::UTF8.GetBytes($effectiveJson))
    }
}

function Get-SourceAndMatrixState {
    param(
        [Parameter(Mandatory = $true)] [string] $ArmPath,
        [Parameter(Mandatory = $true)] [string] $MatrixPathValue,
        [Parameter(Mandatory = $true)] [string[]] $Modes,
        [Parameter(Mandatory = $true)] [AllowEmptyCollection()] [string[]] $ProviderValues,
        [Parameter(Mandatory = $true)] [int] $RunSeed,
        [Parameter(Mandatory = $true)] [int] $Retries,
        [Parameter(Mandatory = $true)] [int] $TimeoutSeconds
    )
    $matrix = Get-CanonicalMatrixState -ArmPath $ArmPath -MatrixPathValue $MatrixPathValue -Modes $Modes -ProviderValues $ProviderValues -RunSeed $RunSeed -Retries $Retries -TimeoutSeconds $TimeoutSeconds
    return [pscustomobject]@{
        SourceSha256 = Get-SourceManifestSha256 $ArmPath
        Matrix = $matrix
    }
}

function Assert-StateUnchanged {
    param(
        [Parameter(Mandatory = $true)] [object] $Before,
        [Parameter(Mandatory = $true)] [object] $After,
        [Parameter(Mandatory = $true)] [string] $ArmName
    )
    if ($Before.SourceSha256 -ne $After.SourceSha256) { throw "Source hash drift detected in $ArmName arm." }
    if ($Before.Matrix.CanonicalSha256 -ne $After.Matrix.CanonicalSha256) { throw "Canonical latency matrix hash drift detected in $ArmName arm." }
    if ($Before.Matrix.EffectiveSha256 -ne $After.Matrix.EffectiveSha256) { throw "Effective latency matrix hash drift detected in $ArmName arm." }
}

function Get-ScenarioItems {
    param([Parameter(Mandatory = $true)] [object] $Matrix)
    foreach ($field in @('scenarios', 'trials', 'entries', 'cells')) {
        $value = Get-OptionalProperty $Matrix $field
        if ($null -ne $value) { return @($value) }
    }
    throw 'Latency matrix must contain a scenarios, trials, entries, or cells array.'
}

function ConvertTo-SafePathSegment {
    param([Parameter(Mandatory = $true)] [string] $Value)
    $segment = $Value.Trim()
    if ([string]::IsNullOrWhiteSpace($segment) -or $segment.Length -gt 128) { throw 'Trial identity must be nonblank and no longer than 128 characters.' }
    if ($segment -match '[\\/:*?"<>|\x00-\x1f]') { throw "Trial identity contains unsafe path characters: $Value" }
    return ($segment -replace '[^A-Za-z0-9._-]', '_')
}

function New-ExperimentCells {
    param(
        [Parameter(Mandatory = $true)] [object] $Matrix,
        [Parameter(Mandatory = $true)] [string[]] $Modes,
        [Parameter(Mandatory = $true)] [AllowEmptyCollection()] [string[]] $ProviderValues
    )
    [object[]] $items = @(Get-ScenarioItems $Matrix)
    [string[]] $modeArray = @($Modes)
    [string[]] $providerArray = @($ProviderValues)
    if ($items.Count -eq 0) { throw 'Latency matrix contains no scenarios.' }
    $cells = New-Object 'System.Collections.Generic.List[object]'
    $seen = @{}
    foreach ($item in $items) {
        if (-not (Test-PlainObject $item)) { throw 'Every latency matrix scenario must be a JSON object.' }
        $id = $null
        foreach ($field in @('pairingKey', 'trialId', 'id', 'key', 'name')) {
            $candidate = Get-OptionalProperty $item $field
            if ($null -ne $candidate -and -not [string]::IsNullOrWhiteSpace([string]$candidate)) {
                $id = ([string]$candidate).Trim(); break
            }
        }
        if ([string]::IsNullOrWhiteSpace($id)) {
            $load = Get-OptionalProperty $item 'agents'
            if ($null -eq $load) { $load = Get-OptionalProperty $item 'agentCount' }
            if ($null -eq $load) { throw 'Every latency matrix scenario needs an id or agent count.' }
            $id = "agents-$load"
        }

        $itemModeRaw = Get-OptionalProperty $item 'mode'
        $hasItemMode = $null -ne $itemModeRaw -and -not [string]::IsNullOrWhiteSpace([string]$itemModeRaw)
        $itemMode = if ($hasItemMode) { ([string]$itemModeRaw).Trim().ToLowerInvariant() } else { $null }
        if ($hasItemMode -and @('instant', 'replay', 'live') -notcontains $itemMode) { throw "Unsupported matrix trial mode: $itemModeRaw" }
        $modeCandidates = if ($hasItemMode) { @($itemMode) } else { @($modeArray) }

        $profile = Get-OptionalProperty $item 'providerProfile'
        $itemProviderRaw = $null
        if ($null -ne $profile -and (Test-PlainObject $profile)) { $itemProviderRaw = Get-OptionalProperty $profile 'provider' }
        if ($null -eq $itemProviderRaw) { $itemProviderRaw = Get-OptionalProperty $item 'provider' }
        $hasItemProvider = $null -ne $itemProviderRaw -and -not [string]::IsNullOrWhiteSpace([string]$itemProviderRaw)
        $itemProvider = if ($hasItemProvider) { ([string]$itemProviderRaw).Trim().ToLowerInvariant() } else { $null }
        if ($hasItemProvider -and $itemProvider -notmatch '^[a-z0-9][a-z0-9._-]{0,63}$') { throw "Unsafe matrix provider name: $itemProviderRaw" }

        foreach ($modeValue in $modeCandidates) {
            if ($modeArray -notcontains $modeValue) { continue }
            # Provider selection filters matrix-declared profiles. It does not
            # manufacture additional trials for each requested provider.
            if ($modeValue -eq 'live' -and $providerArray.Count -gt 0 -and -not $hasItemProvider) { continue }
            if ($hasItemProvider -and $providerArray.Count -gt 0 -and $providerArray -notcontains $itemProvider) { continue }
            $providerValue = if ($hasItemProvider) { $itemProvider } else { '' }
            $cellId = $id
            if (-not $hasItemMode -and $modeArray.Count -gt 1) { $cellId = "$cellId--$modeValue" }
            if ($seen.ContainsKey($cellId)) { throw "Duplicate latency trial identity: $cellId" }
            $seen[$cellId] = $true
            $cells.Add([pscustomobject]@{
                TrialId = $cellId
                BaseTrialId = $id
                Folder = ConvertTo-SafePathSegment $cellId
                Mode = $modeValue
                Provider = $providerValue
                Scenario = $item
            }) | Out-Null
        }
    }
    if ($cells.Count -eq 0) { throw 'Latency matrix contains no trials matching the requested mode/provider selection.' }
    return @($cells.ToArray())
}

function Normalize-Selection {
    param(
        [Parameter(Mandatory = $true)] [string[]] $Modes,
        [Parameter(Mandatory = $true)] [AllowEmptyCollection()] [string[]] $ProviderValues
    )
    $normalizedModes = @($Modes | ForEach-Object { ([string]$_).Trim().ToLowerInvariant() } | Where-Object { $_ -ne '' } | Select-Object -Unique)
    if ($normalizedModes.Count -eq 0) { throw 'At least one benchmark mode is required.' }
    foreach ($modeValue in $normalizedModes) {
        if (@('instant', 'replay', 'live') -notcontains $modeValue) { throw "Unsupported benchmark mode: $modeValue" }
    }
    $normalizedProviders = @($ProviderValues | ForEach-Object { ([string]$_).Trim().ToLowerInvariant() } | Where-Object { $_ -ne '' } | Select-Object -Unique)
    foreach ($providerValue in $normalizedProviders) {
        if ($providerValue -notmatch '^[a-z0-9][a-z0-9._-]{0,63}$') { throw "Unsafe provider name: $providerValue" }
    }
    return [pscustomobject]@{ Modes = @($normalizedModes); Providers = @($normalizedProviders) }
}

function Get-NextRandomIndex {
    param(
        [Parameter(Mandatory = $true)] [ref] $State,
        [Parameter(Mandatory = $true)] [int] $Bound
    )
    if ($Bound -le 0) { throw 'Random bound must be positive.' }
    $State.Value = (($State.Value * 1103515245 + 12345) % 2147483648)
    return [int]($State.Value % $Bound)
}

function Get-ToolVersion {
    param([Parameter(Mandatory = $true)] [string] $Name)
    try {
        $command = Get-Command $Name -ErrorAction Stop
        $version = ''
        if ($Name -in @('git', 'node')) {
            $output = @(& $command.Source '--version' 2>$null)
            if ($LASTEXITCODE -eq 0 -and $output.Count -gt 0) { $version = ([string]$output[0]).Trim() }
        }
        if ([string]::IsNullOrWhiteSpace($version)) { $version = [string]$command.Version }
        if ([string]::IsNullOrWhiteSpace($version)) { $version = 'available' }
        return $version.Substring(0, [Math]::Min(128, $version.Length))
    }
    catch { return 'unavailable' }
}

function Resolve-RunnerPathForArm {
    param(
        [AllowNull()] [string] $RequestedPath,
        [Parameter(Mandatory = $true)] [string] $WorktreePath,
        [Parameter(Mandatory = $true)] [string] $DefaultRelativePath
    )
    if ([string]::IsNullOrWhiteSpace($RequestedPath)) {
        return [IO.Path]::GetFullPath((Join-Path $WorktreePath $DefaultRelativePath))
    }
    $candidate = $RequestedPath
    if (-not [IO.Path]::IsPathRooted($candidate) -and -not (Test-Path -LiteralPath $candidate)) {
        $candidate = Join-Path $WorktreePath $candidate
    }
    return [IO.Path]::GetFullPath($candidate)
}

function Resolve-OptionalReplayRecordingsPath {
    param(
        [AllowNull()] [string] $RequestedPath,
        [Parameter(Mandatory = $true)] [string] $WorktreePath
    )
    if ([string]::IsNullOrWhiteSpace($RequestedPath)) { return $null }
    $candidate = if ([IO.Path]::IsPathRooted($RequestedPath)) { $RequestedPath } else { Join-Path $WorktreePath $RequestedPath }
    $resolved = [IO.Path]::GetFullPath($candidate)
    if (-not (Test-Path -LiteralPath $resolved -PathType Leaf)) { throw "Replay recordings file does not exist: $resolved" }
    return $resolved
}

function Get-RunnerCommandPlan {
    param([Parameter(Mandatory = $true)] [string] $Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { throw 'A runner command or script path is required.' }
    $resolved = $Path
    if (-not [IO.Path]::IsPathRooted($resolved) -and (Test-Path -LiteralPath $resolved)) {
        $resolved = [IO.Path]::GetFullPath((Resolve-Path -LiteralPath $resolved).ProviderPath)
    }
    elseif ([IO.Path]::IsPathRooted($resolved)) {
        $resolved = [IO.Path]::GetFullPath($resolved)
    }
    else {
        $command = Get-Command $Path -ErrorAction SilentlyContinue
        if ($null -eq $command) { throw "Runner command was not found: $Path" }
        $resolved = $command.Source
    }
    if (Test-Path -LiteralPath $resolved -PathType Leaf) {
        $extension = [IO.Path]::GetExtension($resolved).ToLowerInvariant()
        if ($extension -eq '.ps1') {
            $powershellPath = Join-Path $PSHOME 'powershell.exe'
            if (-not (Test-Path -LiteralPath $powershellPath)) { $powershellPath = (Get-Command powershell.exe).Source }
            return [pscustomobject]@{ Kind = 'powershell'; Path = $resolved; Executable = $powershellPath; PrefixArguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $resolved); DisplayName = [IO.Path]::GetFileName($resolved) }
        }
        if ($extension -in @('.mjs', '.js')) {
            $node = (Get-Command node.exe -ErrorAction Stop).Source
            return [pscustomobject]@{ Kind = 'node'; Path = $resolved; Executable = $node; PrefixArguments = @($resolved); DisplayName = [IO.Path]::GetFileName($resolved) }
        }
        if ($extension -in @('.cmd', '.bat')) {
            return [pscustomobject]@{ Kind = 'executable'; Path = $resolved; Executable = $env:ComSpec; PrefixArguments = @('/d', '/s', '/c', ('"{0}"' -f $resolved)); DisplayName = [IO.Path]::GetFileName($resolved) }
        }
        return [pscustomobject]@{ Kind = 'executable'; Path = $resolved; Executable = $resolved; PrefixArguments = @(); DisplayName = [IO.Path]::GetFileName($resolved) }
    }
    throw "Runner path is not a file or command: $Path"
}

function Quote-WindowsArgument {
    param([AllowEmptyString()] [string] $Value)
    if ($Value -notmatch '[\s"]') { return $Value }
    return '"' + $Value.Replace('"', '\"') + '"'
}

function Join-WindowsArguments {
    param([Parameter(Mandatory = $true)] [string[]] $Arguments)
    return [string]::Join(' ', @($Arguments | ForEach-Object { Quote-WindowsArgument ([string]$_) }))
}

function Expand-RunnerArgument {
    param(
        [Parameter(Mandatory = $true)] [string] $Argument,
        [Parameter(Mandatory = $true)] [object] $Context
    )
    $expanded = $Argument
    $replacements = @{
        '{InvocationPath}' = [string]$Context.InvocationPath
        '{ResultPath}' = [string]$Context.ResultPath
            '{ArtifactPath}' = [string]$Context.ArtifactPath
        '{WorktreePath}' = [string]$Context.WorktreePath
        '{MatrixPath}' = [string]$Context.MatrixPath
        '{EffectiveMatrixPath}' = [string]$Context.EffectiveMatrixPath
        '{TrialId}' = [string]$Context.TrialId
        '{Arm}' = [string]$Context.Arm
        '{Mode}' = [string]$Context.Mode
        '{Provider}' = [string]$Context.Provider
        '{Seed}' = [string]$Context.Seed
        '{RunId}' = [string]$Context.RunId
    }
    foreach ($key in $replacements.Keys) { $expanded = $expanded.Replace($key, $replacements[$key]) }
    return $expanded
}

function Get-ArmRunnerArguments {
    param(
        [Parameter(Mandatory = $true)] [string] $ArmName,
        [AllowNull()] [AllowEmptyCollection()] [string[]] $CommonArguments,
        [AllowNull()] [AllowEmptyCollection()] [string[]] $BaselineArguments,
        [AllowNull()] [AllowEmptyCollection()] [string[]] $OptimizedArguments
    )
    if ($ArmName -eq 'baseline' -and $null -ne $BaselineArguments) { return @($BaselineArguments | Where-Object { $_ -ne '__LATENCY_AB_NO_ARGS__' -and -not [string]::IsNullOrEmpty([string]$_) }) }
    if ($ArmName -eq 'optimized' -and $null -ne $OptimizedArguments) { return @($OptimizedArguments | Where-Object { $_ -ne '__LATENCY_AB_NO_ARGS__' -and -not [string]::IsNullOrEmpty([string]$_) }) }
    return @($CommonArguments | Where-Object { $_ -ne '__LATENCY_AB_NO_ARGS__' -and -not [string]::IsNullOrEmpty([string]$_) })
}

function Get-RunnerInvocationArguments {
    param(
        [Parameter(Mandatory = $true)] [object] $CommandPlan,
        [Parameter(Mandatory = $true)] [AllowNull()] [AllowEmptyCollection()] [string[]] $UserRunnerArguments,
        [Parameter(Mandatory = $true)] [object] $Context
    )
    $expanded = @($UserRunnerArguments | Where-Object { $_ -ne '__LATENCY_AB_NO_ARGS__' -and -not [string]::IsNullOrEmpty([string]$_) } | ForEach-Object { Expand-RunnerArgument -Argument ([string]$_) -Context $Context })
    if ([string]$CommandPlan.Kind -eq 'node') {
        # The real latency-runner-cli emits one bounded JSON result on stdout
        # and owns its artifact directory. Keep its native flag contract here;
        # the PowerShell fixture contract below is intentionally separate.
        $generated = @(
            '--matrix', [string]$Context.MatrixPath,
            '--trial-id', [string]$Context.RunnerTrialId,
            '--artifact-directory', [string]$Context.RunnerArtifactPath,
            '--arm', [string]$Context.Arm,
            '--run-id', [string]$Context.RunId,
            '--source-hash', [string]$Context.SourceHash,
            '--config-hash', [string]$Context.ConfigHash,
            '--pairing-key', [string]$Context.TrialId,
            '--planning-concurrency', [string]$Context.PlanningConcurrency
        )
        if (-not [string]::IsNullOrWhiteSpace([string]$Context.ReplayRecordingsPath)) {
            if (@($expanded | Where-Object { $_ -eq '--replay-recordings' -or $_ -like '--replay-recordings=*' }).Count -gt 0) { throw 'Runner arguments must not override generated --replay-recordings.' }
            $generated += @('--replay-recordings', [string]$Context.ReplayRecordingsPath)
        }
        return @($generated + $expanded)
    }
    return @($expanded + @('-InvocationPath', $Context.InvocationPath, '-ResultPath', $Context.ResultPath, '-ArtifactPath', $Context.ArtifactPath))
}

function Test-ProcessAlive {
    param([int] $ProcessId)
    if ($ProcessId -le 0) { return $false }
    try { $null = Get-Process -Id $ProcessId -ErrorAction Stop; return $true }
    catch { return $false }
}

function Get-ProcessParentMap {
    try {
        $rows = @(Get-CimInstance Win32_Process -ErrorAction Stop | Select-Object ProcessId, ParentProcessId)
    }
    catch {
        try { $rows = @(Get-WmiObject Win32_Process -ErrorAction Stop | Select-Object ProcessId, ParentProcessId) }
        catch { return @{} }
    }
    $map = @{}
    foreach ($row in $rows) { $map[[int]$row.ProcessId] = [int]$row.ParentProcessId }
    return $map
}

function Test-IsProcessDescendantOf {
    param(
        [Parameter(Mandatory = $true)] [int] $ProcessId,
        [Parameter(Mandatory = $true)] [int] $RootProcessId,
        [Parameter(Mandatory = $true)] [hashtable] $ParentMap
    )
    $seen = New-Object 'System.Collections.Generic.HashSet[int]'
    $current = $ProcessId
    for ($depth = 0; $depth -lt 64; $depth++) {
        if ($current -eq $RootProcessId) { return $true }
        if (-not $seen.Add($current) -or -not $ParentMap.ContainsKey($current)) { return $false }
        $current = [int]$ParentMap[$current]
        if ($current -le 0) { return $false }
    }
    return $false
}

function Get-DescendantProcessIds {
    param([Parameter(Mandatory = $true)] [int] $RootProcessId)
    $all = @{}
    try {
        $processes = @(Get-CimInstance Win32_Process -ErrorAction Stop | Select-Object ProcessId, ParentProcessId)
    }
    catch {
        try { $processes = @(Get-WmiObject Win32_Process -ErrorAction Stop | Select-Object ProcessId, ParentProcessId) }
        catch { return @() }
    }
    $queue = New-Object 'System.Collections.Generic.Queue[int]'
    $queue.Enqueue($RootProcessId)
    while ($queue.Count -gt 0) {
        $parent = $queue.Dequeue()
        foreach ($process in $processes) {
            if ([int]$process.ParentProcessId -eq $parent -and [int]$process.ProcessId -ne $RootProcessId -and -not $all.ContainsKey([int]$process.ProcessId)) {
                $childId = [int]$process.ProcessId
                $all[$childId] = $true
                $queue.Enqueue($childId)
            }
        }
    }
    return @($all.Keys | ForEach-Object { [int]$_ })
}

function Stop-TrackedProcessTree {
    param(
        [Parameter(Mandatory = $true)] [int] $RootProcessId,
        [Parameter(Mandatory = $true)] [int[]] $TrackedProcessIds,
        [switch] $ForceRoot
    )
    $ids = New-Object 'System.Collections.Generic.HashSet[int]'
    if ($RootProcessId -gt 0) { $ids.Add($RootProcessId) | Out-Null }
    foreach ($id in $TrackedProcessIds) { if ($id -gt 0) { $ids.Add($id) | Out-Null } }
    # Once the root has exited its PID can be reused immediately. Do not query a
    # fresh descendant tree for an exited root, or a later process can be mistaken
    # for a child and an unrelated process can be terminated.
    if (Test-ProcessAlive $RootProcessId) {
        foreach ($id in @(Get-DescendantProcessIds -RootProcessId $RootProcessId)) { $ids.Add($id) | Out-Null }
    }
    $rootAlive = Test-ProcessAlive $RootProcessId
    # Validate every non-root PID against the current parent map. This remains
    # safe when the runner has just exited and prevents a reused PID from
    # being treated as part of the tracked tree.
    $parentMap = Get-ProcessParentMap
    foreach ($id in @($ids | Sort-Object -Descending)) {
        if (-not (Test-ProcessAlive $id)) { continue }
        if ($id -eq $RootProcessId -and -not $ForceRoot) { continue }
        if (-not $ForceRoot -and $id -ne $RootProcessId -and -not (Test-IsProcessDescendantOf -ProcessId $id -RootProcessId $RootProcessId -ParentMap $parentMap)) { continue }
        try { Stop-Process -Id $id -Force -ErrorAction SilentlyContinue } catch { }
    }
    $deadline = [DateTime]::UtcNow.AddSeconds(3)
    $remaining = @()
    do {
        $remaining = @($ids | Where-Object { Test-ProcessAlive ([int]$_) })
        if ($remaining.Count -eq 0) { break }
        Start-Sleep -Milliseconds 50
    } while ([DateTime]::UtcNow -lt $deadline)
    return [pscustomobject]@{ Ok = ($remaining.Count -eq 0); Remaining = @($remaining); Tracked = @($ids) }
}

function Invoke-RunnerProcess {
    param(
        [Parameter(Mandatory = $true)] [object] $CommandPlan,
        [Parameter(Mandatory = $true)] [string[]] $Arguments,
        [Parameter(Mandatory = $true)] [string] $WorkingDirectory,
        [Parameter(Mandatory = $true)] [int] $TimeoutSeconds,
        [Parameter(Mandatory = $true)] [int] $OutputLimit
    )
    $stdout = New-Object Text.StringBuilder
    $stderr = New-Object Text.StringBuilder
    $state = [hashtable]::Synchronized(@{
        StdoutBytes = 0
        StderrBytes = 0
        StdoutOverflow = $false
        StderrOverflow = $false
    })
    $process = New-Object Diagnostics.Process
    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.FileName = $CommandPlan.Executable
    $startInfo.Arguments = Join-WindowsArguments (@($CommandPlan.PrefixArguments + $Arguments))
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process.StartInfo = $startInfo
    $startedAt = [DateTime]::UtcNow
    $tracked = New-Object 'System.Collections.Generic.List[int]'
    $timedOut = $false
    $startError = $null
    $exitCode = $null
    $cleanup = $null
    try {
        try { $null = $process.Start() }
        catch { $startError = $_.Exception.Message; return [pscustomobject]@{ Started = $false; ExitCode = $null; TimedOut = $false; Stdout = ''; Stderr = ''; StdoutOverflow = $false; StderrOverflow = $false; Cleanup = [pscustomobject]@{ Ok = $true; Remaining = @(); Tracked = @() }; Error = $startError; DurationMs = 0 } }
        $tracked.Add($process.Id) | Out-Null
        # DataReceived scriptblock delegates are unreliable in Windows
        # PowerShell 5.1 after a short-lived child exits. Poll both asynchronous
        # readers on this runspace so the final JSON line is always drained.
        $stdoutDone = $false
        $stderrDone = $false
        $stdoutTask = $process.StandardOutput.ReadLineAsync()
        $stderrTask = $process.StandardError.ReadLineAsync()
        while ($true) {
            while (-not $stdoutDone -and $stdoutTask.IsCompleted) {
                $line = $stdoutTask.GetAwaiter().GetResult()
                if ($null -eq $line) { $stdoutDone = $true; break }
                $textLine = $line + [Environment]::NewLine
                $bytes = [Text.Encoding]::UTF8.GetByteCount($textLine)
                if (($state.StdoutBytes + $bytes) -gt $OutputLimit) { $state.StdoutOverflow = $true; break }
                [void]$stdout.Append($textLine)
                $state.StdoutBytes += $bytes
                $stdoutTask = $process.StandardOutput.ReadLineAsync()
            }
            while (-not $stderrDone -and $stderrTask.IsCompleted) {
                $line = $stderrTask.GetAwaiter().GetResult()
                if ($null -eq $line) { $stderrDone = $true; break }
                $textLine = $line + [Environment]::NewLine
                $bytes = [Text.Encoding]::UTF8.GetByteCount($textLine)
                if (($state.StderrBytes + $bytes) -gt $OutputLimit) { $state.StderrOverflow = $true; break }
                [void]$stderr.Append($textLine)
                $state.StderrBytes += $bytes
                $stderrTask = $process.StandardError.ReadLineAsync()
            }
            if ($state.StdoutOverflow -or $state.StderrOverflow) { break }
            if ($process.HasExited -and $stdoutDone -and $stderrDone) { break }
            if (-not $process.HasExited) {
                foreach ($childId in @(Get-DescendantProcessIds -RootProcessId $process.Id)) {
                    if (-not $tracked.Contains($childId)) { $tracked.Add($childId) | Out-Null }
                }
            }
            if (([DateTime]::UtcNow - $startedAt).TotalSeconds -ge $TimeoutSeconds) { $timedOut = $true; break }
            Start-Sleep -Milliseconds 10
        }
        if ($timedOut -or $state.StdoutOverflow -or $state.StderrOverflow) {
            $cleanup = Stop-TrackedProcessTree -RootProcessId $process.Id -TrackedProcessIds @($tracked.ToArray()) -ForceRoot
        }
        else {
            # The loop above records descendants while the root is alive. Avoid
            # a post-exit tree query because Windows may already have reused its PID.
            $cleanup = Stop-TrackedProcessTree -RootProcessId $process.Id -TrackedProcessIds @($tracked.ToArray())
        }
        try { $process.WaitForExit(1000) | Out-Null } catch { }
        if ($process.HasExited) { $exitCode = $process.ExitCode }
    }
    finally {
        $process.Dispose()
    }
    return [pscustomobject]@{
        Started = $true
        ExitCode = $exitCode
        TimedOut = $timedOut
        Stdout = $stdout.ToString()
        Stderr = $stderr.ToString()
        StdoutOverflow = [bool]$state.StdoutOverflow
        StderrOverflow = [bool]$state.StderrOverflow
        Cleanup = $cleanup
        Error = $null
        DurationMs = [Math]::Round(([DateTime]::UtcNow - $startedAt).TotalMilliseconds, 3)
    }
}

function Get-BoundedJsonFromPathOrText {
    param(
        [string] $Path,
        [string] $Text,
        [Parameter(Mandatory = $true)] [int] $Limit
    )
    $jsonText = $null
    if (-not [string]::IsNullOrWhiteSpace($Path) -and (Test-Path -LiteralPath $Path -PathType Leaf)) {
        $length = (Get-Item -LiteralPath $Path).Length
        if ($length -gt $Limit) { throw "Runner result exceeds the bounded $Limit byte limit." }
        $jsonText = [Text.Encoding]::UTF8.GetString([IO.File]::ReadAllBytes($Path))
    }
    else { $jsonText = [string]$Text }
    if ([Text.Encoding]::UTF8.GetByteCount($jsonText) -gt $Limit) { throw "Runner JSON output exceeds the bounded $Limit byte limit." }
    if ([string]::IsNullOrWhiteSpace($jsonText)) { throw 'Runner did not produce a JSON result.' }
    try { $parsed = $jsonText | ConvertFrom-Json -ErrorAction Stop }
    catch { throw "Runner result is malformed JSON: $($_.Exception.Message)" }
    if (-not (Test-PlainObject $parsed)) { throw 'Runner result must be a JSON object.' }
    return $parsed
}

function Normalize-ResultStatus {
    param([Parameter(Mandatory = $true)] [string] $Value)
    $normalized = $Value.Trim().ToLowerInvariant().Replace('-', '_').Replace(' ', '_')
    switch ($normalized) {
        'passed' { return 'PASSED' }
        'pass' { return 'PASSED' }
        'failed' { return 'FAILED' }
        'failure' { return 'FAILED' }
        'skipped' { return 'SKIPPED' }
        'skip' { return 'SKIPPED' }
        'timed_out' { return 'TIMED_OUT' }
        'timeout' { return 'TIMED_OUT' }
        default { throw "Runner result has unsupported status: $Value" }
    }
}

function Assert-ResultCleanup {
    param(
        [AllowNull()] [object] $Cleanup,
        [Parameter(Mandatory = $true)] [string] $Label
    )
    if (-not (Test-PlainObject $Cleanup)) { throw "Runner result $Label cleanup object is required." }
    $cleanupOk = Get-OptionalProperty $Cleanup 'ok'
    if ($cleanupOk -isnot [bool] -or -not $cleanupOk) { throw "Runner result $Label cleanup.ok must be true." }
    foreach ($field in @('tempResidue', 'temporaryResidue', 'backupResidue', 'temp', 'tmp', 'backup', 'activeActions', 'listeners')) {
        $residue = Get-OptionalProperty $Cleanup $field
        if ($null -eq $residue) { continue }
        if (($residue -is [bool] -and $residue) -or (($residue -is [int] -or $residue -is [long] -or $residue -is [double]) -and [double]$residue -gt 0)) {
            throw "Runner reported cleanup residue in $Label.$field."
        }
    }
    return $Cleanup
}

function Test-BoundedLatencyNumber {
    param([AllowNull()] [object] $Value)
    if ($Value -isnot [ValueType]) { return $false }
    try { $number = [double]$Value } catch { return $false }
    return (-not [double]::IsNaN($number) -and -not [double]::IsInfinity($number) -and $number -ge 0 -and $number -le 604800000)
}

function Assert-RunnerResult {
    param(
        [Parameter(Mandatory = $true)] [object] $Result,
        [Parameter(Mandatory = $true)] [object] $Context,
        [Parameter(Mandatory = $true)] [int] $ResultLimit
    )
    foreach ($property in @($Result.PSObject.Properties)) {
        if ($property.Name -in @('__proto__', 'prototype', 'constructor')) { throw 'Runner result contains an unsafe property.' }
    }

    $metadata = Get-OptionalProperty $Result 'metadata'
    $trials = Get-OptionalProperty $Result 'trials'
    $isAggregate = $null -ne $metadata -or $null -ne $trials
    if ($isAggregate) {
        if (-not (Test-PlainObject $metadata)) { throw 'Runner aggregate result metadata object is required.' }
        if ($null -eq $trials -or $trials -is [string]) { throw 'Runner aggregate result trials array is required.' }
        [object[]] $trialItems = @($trials)
        if ($trialItems.Count -eq 0 -or $trialItems.Count -gt 256) { throw 'Runner aggregate result trials array is outside the bounded range.' }
        $expectedRunnerId = [string]$Context.RunnerTrialId
        $metadataTrialId = Get-OptionalProperty $metadata 'trialId'
        if ($null -eq $metadataTrialId -or [string]$metadataTrialId -ne $expectedRunnerId) { throw 'Runner result metadata trial identity does not match the invocation.' }
        $matching = @($trialItems | Where-Object {
            $trialId = Get-OptionalProperty $_ 'trialId'
            $null -ne $trialId -and ([string]$trialId -eq $expectedRunnerId -or [string]$trialId -eq [string]$Context.TrialId)
        })
        if ($matching.Count -eq 0 -or $matching.Count -ne $trialItems.Count) { throw "Runner aggregate result contains missing or mixed trial identities: $expectedRunnerId." }
        $metadataArm = Get-OptionalProperty $metadata 'arm'
        if ($null -eq $metadataArm -or ([string]$metadataArm).Trim().ToLowerInvariant() -ne [string]$Context.Arm) { throw "Runner result arm does not match $($Context.Arm)." }
        $metadataRunId = Get-OptionalProperty $metadata 'runId'
        if ($null -eq $metadataRunId -or [string]$metadataRunId -ne [string]$Context.RunId) { throw 'Runner result run identity does not match the invocation.' }
        foreach ($field in @('sourceHash', 'configHash')) {
            $metadataValue = Get-OptionalProperty $metadata $field
            $expectedValue = [string]$Context.$field
            if ($null -eq $metadataValue -or [string]$metadataValue -ne $expectedValue) { throw "Runner result metadata $field does not match the invocation." }
        }
        $metadataConcurrency = Get-OptionalProperty $metadata 'planningConcurrency'
        if ($null -eq $metadataConcurrency -or [int]$metadataConcurrency -ne [int]$Context.PlanningConcurrency) { throw 'Runner result planning concurrency does not match the invocation.' }
        $rootCleanup = Assert-ResultCleanup -Cleanup (Get-OptionalProperty $Result 'cleanup') -Label 'root'
        $statusValue = Get-OptionalProperty $Result 'status'
        if ($null -eq $statusValue) { throw 'Runner aggregate result status is required.' }
        $status = Normalize-ResultStatus ([string]$statusValue)
        $durations = New-Object 'System.Collections.Generic.List[double]'
        $repetitions = New-Object 'System.Collections.Generic.HashSet[int]'
        foreach ($trial in $matching) {
            if (-not (Test-PlainObject $trial)) { throw 'Runner aggregate result trial must be a JSON object.' }
            $trialCleanup = Get-OptionalProperty $trial 'cleanup'
            if ($null -ne $trialCleanup) { $null = Assert-ResultCleanup -Cleanup $trialCleanup -Label 'trial' }
            $trialStatusValue = Get-OptionalProperty $trial 'status'
            if ($null -eq $trialStatusValue) { throw 'Runner aggregate trial status is required.' }
            $trialStatus = Normalize-ResultStatus ([string]$trialStatusValue)
            if ($status -eq 'PASSED' -and $trialStatus -notin @('PASSED', 'SKIPPED')) { throw 'Runner aggregate status and matching trial status disagree.' }
            if ($status -eq 'SKIPPED' -and $trialStatus -ne 'SKIPPED') { throw 'Runner aggregate skipped status and matching trial disagree.' }
            $duration = Get-OptionalProperty $trial 'durationMs'
            if ($null -ne $duration) {
                if (-not (Test-BoundedLatencyNumber $duration)) { throw 'Runner aggregate trial durationMs is not bounded.' }
                $durations.Add([double]$duration) | Out-Null
            }
            $repetition = Get-OptionalProperty $trial 'repetition'
            if ($matching.Count -gt 1) {
                if ($null -eq $repetition -or [int]$repetition -lt 1 -or -not $repetitions.Add([int]$repetition)) { throw 'Runner aggregate repetitions must be positive and unique.' }
            }
        }
        [double] $durationP95 = 0
        [bool] $hasDuration = $durations.Count -gt 0
        if ($hasDuration) {
            [double[]] $orderedDurations = $durations.ToArray()
            [Array]::Sort($orderedDurations)
            $durationP95 = $orderedDurations[[Math]::Max(0, [Math]::Ceiling($orderedDurations.Count * 0.95) - 1)]
        }
        if ($status -eq 'SKIPPED' -and [string]$Context.Mode -ne 'live') { throw 'Only live-provider cells may be SKIPPED.' }
        return [pscustomobject][ordered]@{
            runId = [string]$Context.RunId
            trialId = [string]$Context.TrialId
            runnerTrialId = [string](Get-OptionalProperty $trial 'trialId')
            arm = [string]$Context.Arm
            seed = [int]$Context.Seed
            status = $status
            latencyMs = if ($hasDuration) { $durationP95 } else { $null }
            latencyStatistic = if ($hasDuration) { 'durationMsP95' } else { $null }
            cleanup = ConvertTo-RedactedValue $rootCleanup
            metadata = ConvertTo-RedactedValue $metadata
            trials = ConvertTo-RedactedValue @($matching)
        }
    }

    $identity = $null
    foreach ($field in @('trialId', 'pairingKey', 'pairId', 'id')) {
        $candidate = Get-OptionalProperty $Result $field
        if ($null -ne $candidate) {
            if ($null -ne $identity -and [string]$identity -ne [string]$candidate) { throw 'Runner result trial identity aliases disagree.' }
            $identity = [string]$candidate
        }
    }
    if ([string]::IsNullOrWhiteSpace($identity) -or $identity -ne [string]$Context.TrialId) { throw "Runner result trial identity does not match $($Context.TrialId)." }
    $arm = Get-OptionalProperty $Result 'arm'
    if ($null -eq $arm -or ([string]$arm).Trim().ToLowerInvariant() -ne [string]$Context.Arm) { throw "Runner result arm does not match $($Context.Arm)." }
    $resultSeed = Get-OptionalProperty $Result 'seed'
    if ($null -ne $resultSeed -and [int64]$resultSeed -ne [int64]$Context.Seed) { throw 'Runner result seed does not match the invocation seed.' }
    $resultRunId = Get-OptionalProperty $Result 'runId'
    if ($null -ne $resultRunId -and [string]$resultRunId -ne [string]$Context.RunId) { throw 'Runner result run identity does not match the invocation.' }
    $statusValue = Get-OptionalProperty $Result 'status'
    if ($null -eq $statusValue) { throw 'Runner result status is required.' }
    $status = Normalize-ResultStatus ([string]$statusValue)
    if ($status -eq 'SKIPPED' -and [string]$Context.Mode -ne 'live') { throw 'Only live-provider cells may be SKIPPED.' }
    $cleanupValue = Assert-ResultCleanup -Cleanup (Get-OptionalProperty $Result 'cleanup') -Label 'root'
    foreach ($field in @('latencyMs', 'durationMs')) {
        $latency = Get-OptionalProperty $Result $field
        if ($null -eq $latency) { continue }
        if (-not (Test-BoundedLatencyNumber $latency)) { throw "Runner result $field is not a bounded non-negative number." }
    }
    $normalized = [ordered]@{}
    foreach ($property in @($Result.PSObject.Properties)) { $normalized[$property.Name] = ConvertTo-RedactedValue $property.Value $property.Name }
    $normalized.status = $status
    return [pscustomobject]$normalized
}

function Find-ArtifactResidue {
    param([Parameter(Mandatory = $true)] [string] $ArtifactPath)
    $residue = @(
        Get-ChildItem -LiteralPath $ArtifactPath -File -Recurse -Force -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match '(?i)(^|[._-])(temp|tmp|temporary|backup|bak)([._-]|$)' } |
            ForEach-Object { $_.FullName.Substring($ArtifactPath.Length).TrimStart([char[]] @('\', '/')).Replace('\', '/') }
    )
    return @($residue)
}

function New-FailureResult {
    param(
        [Parameter(Mandatory = $true)] [object] $Context,
        [Parameter(Mandatory = $true)] [string] $Category,
        [Parameter(Mandatory = $true)] [string] $Message,
        [Parameter(Mandatory = $true)] [bool] $CleanupOk
    )
    return [pscustomobject][ordered]@{
        runId = [string]$Context.RunId
        trialId = [string]$Context.TrialId
        arm = [string]$Context.Arm
        seed = [int]$Context.Seed
        status = 'FAILED'
        errorCategory = $Category
        error = (ConvertTo-SafeText $Message)
        cleanup = [ordered]@{ ok = $CleanupOk; tempResidue = 0; backupResidue = 0 }
    }
}

function Invoke-OneLatencyArm {
    param(
        [Parameter(Mandatory = $true)] [object] $Cell,
        [Parameter(Mandatory = $true)] [string] $ArmName,
        [Parameter(Mandatory = $true)] [string] $WorktreePath,
        [Parameter(Mandatory = $true)] [object] $InitialState,
        [Parameter(Mandatory = $true)] [object] $CommandPlan,
        [Parameter(Mandatory = $true)] [string] $RunId,
        [Parameter(Mandatory = $true)] [int] $RunSeed,
        [Parameter(Mandatory = $true)] [ValidateRange(1, 16)] [int] $PlanningConcurrency,
        [Parameter(Mandatory = $true)] [string] $ArmConfigHash,
        [AllowNull()] [string] $ReplayRecordingsPath,
        [Parameter(Mandatory = $true)] [int] $TimeoutSeconds,
        [Parameter(Mandatory = $true)] [int] $Retries,
        [Parameter(Mandatory = $true)] [AllowNull()] [AllowEmptyCollection()] [string[]] $UserRunnerArguments,
        [Parameter(Mandatory = $true)] [string] $MatrixPathValue,
        [Parameter(Mandatory = $true)] [string] $EffectiveMatrixPath,
        [Parameter(Mandatory = $true)] [string] $ArmRoot,
        [Parameter(Mandatory = $true)] [int] $OutputLimit,
        [Parameter(Mandatory = $true)] [int] $ResultLimit
    )
    New-Item -ItemType Directory -Force -Path $ArmRoot | Out-Null
    $final = $null
    $attemptRecords = New-Object 'System.Collections.Generic.List[object]'
    for ($attempt = 1; $attempt -le ($Retries + 1); $attempt++) {
        $attemptPath = Join-Path $ArmRoot ('attempt-{0:D2}' -f $attempt)
        New-Item -ItemType Directory -Force -Path $attemptPath | Out-Null
        $invocationPath = Join-Path $attemptPath 'invocation.json'
        $resultPath = Join-Path $attemptPath 'result.json'
        $runnerArtifactPath = Join-Path $attemptPath 'runner-artifacts'
        New-Item -ItemType Directory -Force -Path $runnerArtifactPath | Out-Null
        $context = [pscustomobject][ordered]@{
            schemaVersion = 1
            runId = $RunId
            trialId = [string]$Cell.TrialId
            baseTrialId = [string]$Cell.BaseTrialId
            runnerTrialId = [string]$Cell.BaseTrialId
            arm = $ArmName
            mode = [string]$Cell.Mode
            provider = [string]$Cell.Provider
            seed = $RunSeed
            planningConcurrency = $PlanningConcurrency
            sourceHash = [string]$InitialState.SourceSha256
            configHash = $ArmConfigHash
            replayRecordingsPath = $ReplayRecordingsPath
            attempt = $attempt
            timeoutSeconds = $TimeoutSeconds
            worktreePath = $WorktreePath
            matrixPath = $InitialState.Matrix.Path
            effectiveMatrixPath = $EffectiveMatrixPath
            artifactPath = $attemptPath
            runnerArtifactPath = $runnerArtifactPath
            invocationPath = $invocationPath
            resultPath = $resultPath
        }
        Write-JsonArtifact -Path $invocationPath -Value (ConvertTo-RedactedValue $context) -Depth 30
        $expandedArguments = Get-RunnerInvocationArguments -CommandPlan $CommandPlan -UserRunnerArguments $UserRunnerArguments -Context $context
        $processResult = Invoke-RunnerProcess -CommandPlan $CommandPlan -Arguments $expandedArguments -WorkingDirectory $WorktreePath -TimeoutSeconds $TimeoutSeconds -OutputLimit $OutputLimit
        Write-JsonArtifact -Path (Join-Path $attemptPath 'process.json') -Value ([ordered]@{
            started = $processResult.Started
            exitCode = $processResult.ExitCode
            timedOut = $processResult.TimedOut
            durationMs = $processResult.DurationMs
            stdoutBytes = [Text.Encoding]::UTF8.GetByteCount([string]$processResult.Stdout)
            stderrBytes = [Text.Encoding]::UTF8.GetByteCount([string]$processResult.Stderr)
            stdoutOverflow = $processResult.StdoutOverflow
            stderrOverflow = $processResult.StderrOverflow
            cleanup = $processResult.Cleanup
            error = (ConvertTo-SafeText $processResult.Error)
        })
        [IO.File]::WriteAllText((Join-Path $attemptPath 'stdout.txt'), (ConvertTo-SafeText $processResult.Stdout), (New-Object Text.UTF8Encoding($false)))
        [IO.File]::WriteAllText((Join-Path $attemptPath 'stderr.txt'), (ConvertTo-SafeText $processResult.Stderr), (New-Object Text.UTF8Encoding($false)))

        $candidate = $null
        $category = $null
        $message = $null
        $cleanupOk = if ($null -ne $processResult.Cleanup) { [bool]$processResult.Cleanup.Ok } else { $false }
        if (-not $processResult.Started) { $category = 'process_startup'; $message = [string]$processResult.Error }
        elseif ($processResult.TimedOut) { $category = 'timeout'; $message = "Runner exceeded the outer timeout of $TimeoutSeconds seconds." }
        elseif ($processResult.StdoutOverflow -or $processResult.StderrOverflow) { $category = 'process_output_overflow'; $message = 'Runner stdout or stderr exceeded the bounded output limit.' }
        elseif (-not $cleanupOk) { $category = 'process_cleanup_failure'; $message = 'Tracked runner process tree did not terminate completely.' }
        elseif ($processResult.ExitCode -ne 0) { $category = 'process_exit'; $message = "Runner exited with code $($processResult.ExitCode)." }
        else {
            try {
                $candidate = Get-BoundedJsonFromPathOrText -Path $resultPath -Text $processResult.Stdout -Limit $ResultLimit
                $candidate = Assert-RunnerResult -Result $candidate -Context $context -ResultLimit $ResultLimit
                if (-not (Test-Path -LiteralPath $resultPath -PathType Leaf)) {
                    Write-JsonArtifact -Path $resultPath -Value (ConvertTo-RedactedValue $candidate) -Depth 40
                }
                $residue = @(Find-ArtifactResidue $attemptPath)
                if ($residue.Count -gt 0) { throw "Temporary or backup residue remains: $($residue -join ', ')" }
            }
            catch {
                $candidate = $null
                $category = if ($_.Exception.Message -match 'cleanup|residue') { 'cleanup_failure' } elseif ($_.Exception.Message -match 'JSON|result') { 'malformed_result' } else { 'result_validation' }
                $message = $_.Exception.Message
            }
        }
        if ($null -eq $candidate) { $candidate = New-FailureResult -Context $context -Category $category -Message $message -CleanupOk $cleanupOk }
        $candidateRecord = [pscustomobject][ordered]@{
            trialId = [string]$Cell.TrialId
            arm = $ArmName
            mode = [string]$Cell.Mode
            provider = [string]$Cell.Provider
            attempt = $attempt
            status = [string](Get-OptionalProperty $candidate 'status')
            errorCategory = Get-OptionalProperty $candidate 'errorCategory'
            latencyMs = Get-OptionalProperty $candidate 'latencyMs'
            cleanupOk = [bool](Get-OptionalProperty (Get-OptionalProperty $candidate 'cleanup') 'ok')
            result = $candidate
        }
        $attemptRecords.Add($candidateRecord) | Out-Null
        $final = $candidateRecord
        if ($null -eq $category -and [string]$candidateRecord.status -in @('PASSED', 'SKIPPED')) { break }
        if ($null -eq $category -and [string]$candidateRecord.status -notin @('FAILED', 'TIMED_OUT')) { break }
    }
    Write-JsonArtifact -Path (Join-Path $ArmRoot 'result.json') -Value (ConvertTo-RedactedValue $final.result) -Depth 40
    Write-JsonArtifact -Path (Join-Path $ArmRoot 'attempts.json') -Value (ConvertTo-RedactedValue @($attemptRecords.ToArray())) -Depth 40
    return [pscustomobject]@{
        Summary = $final
        Attempts = @($attemptRecords.ToArray())
    }
}

function Get-ManifestRunnerInfo {
    param([Parameter(Mandatory = $true)] [object] $CommandPlan)
    $hash = $null
    if (-not [string]::IsNullOrWhiteSpace([string]$CommandPlan.Path) -and (Test-Path -LiteralPath $CommandPlan.Path -PathType Leaf)) {
        $hash = Get-RawFileSha256 -Path $CommandPlan.Path
    }
    return [ordered]@{
        kind = [string]$CommandPlan.Kind
        path = [string]$CommandPlan.Path
        sha256 = $hash
        displayName = [string]$CommandPlan.DisplayName
    }
}

function Get-ManifestToolVersions {
    param(
        [Parameter(Mandatory = $true)] [object] $BaselineCommandPlan,
        [Parameter(Mandatory = $true)] [object] $OptimizedCommandPlan
    )
    return [ordered]@{
        orchestrator = $script:LatencyAbOrchestratorVersion
        powershell = [string]$PSVersionTable.PSVersion
        git = Get-ToolVersion 'git'
        node = Get-ToolVersion 'node'
        runner = [ordered]@{
            baseline = Get-ManifestRunnerInfo $BaselineCommandPlan
            optimized = Get-ManifestRunnerInfo $OptimizedCommandPlan
        }
    }
}

function Invoke-LatencyAbExperiment {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)] [string] $BaselinePath,
        [Parameter(Mandatory = $true)] [string] $OptimizedPath,
        [AllowNull()] [string] $RunnerPath,
        [AllowNull()] [string] $BaselineRunnerPath,
        [AllowNull()] [string] $OptimizedRunnerPath,
        [switch] $NeutralRunner,
        [string] $MatrixPath = 'coordinator\config\latency-matrix.json',
        [AllowNull()] [object] $RunnerArguments = '__LATENCY_AB_NO_ARGS__',
        [AllowNull()] [AllowEmptyCollection()] [string[]] $BaselineRunnerArguments,
        [AllowNull()] [AllowEmptyCollection()] [string[]] $OptimizedRunnerArguments,
        [ValidateRange(1, 16)] [int] $BaselinePlanningConcurrency = 16,
        [ValidateRange(1, 16)] [int] $OptimizedPlanningConcurrency = 16,
        [ValidateRange(0, 16)] [int] $FixedPlanningConcurrency = 0,
        [AllowNull()] [string] $BaselineReplayRecordingsPath,
        [AllowNull()] [string] $OptimizedReplayRecordingsPath,
        [string] $OutputRoot,
        [ValidateRange(1, 86400)] [int] $OuterTimeoutSeconds = 300,
        [ValidateRange(0, 3)] [int] $MaxRetries = 0,
        [string[]] $Mode = @('instant'),
        [string[]] $Providers = @(),
        [switch] $RequireLive,
        [switch] $RequireProviders,
        [int] $Seed = 424242,
        [ValidateRange(1024, 1048576)] [int] $MaxOutputBytes = 65536,
        [ValidateRange(1024, 1048576)] [int] $MaxResultBytes = 131072,
        [switch] $KeepArtifacts
    )
    $resolvedBaseline = Resolve-RequiredDirectory $BaselinePath
    $resolvedOptimized = Resolve-RequiredDirectory $OptimizedPath
    if ($FixedPlanningConcurrency -gt 0) {
        $BaselinePlanningConcurrency = $FixedPlanningConcurrency
        $OptimizedPlanningConcurrency = $FixedPlanningConcurrency
    }
    if (Test-PathEqual $resolvedBaseline $resolvedOptimized) { throw 'Baseline and optimized worktrees must be different directories.' }
    $resolvedOutputRoot = if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
        Resolve-DirectoryForCreate (Join-Path ([IO.Path]::GetTempPath()) 'latency-ab-runs')
    }
    else { Resolve-DirectoryForCreate $OutputRoot }
    if ((Test-PathUnderRoot $resolvedOutputRoot $resolvedBaseline) -or (Test-PathUnderRoot $resolvedOutputRoot $resolvedOptimized)) {
        throw 'Artifact output must be outside both experiment worktrees.'
    }
    $defaultRunnerRelativePath = 'coordinator\src\benchmark\latency-runner-cli.mjs'
    $commonResolvedRunner = if ([string]::IsNullOrWhiteSpace($RunnerPath)) { $null } else { Resolve-RunnerPathForArm -RequestedPath $RunnerPath -WorktreePath $resolvedBaseline -DefaultRelativePath $defaultRunnerRelativePath }
    if ($null -ne $commonResolvedRunner -and [IO.Path]::GetExtension($commonResolvedRunner).ToLowerInvariant() -in @('.mjs', '.js') -and [string]::IsNullOrWhiteSpace($BaselineRunnerPath) -and [string]::IsNullOrWhiteSpace($OptimizedRunnerPath) -and -not $NeutralRunner) {
        throw 'A single Node runner cannot be used for both arms unless -NeutralRunner is explicit; provide -BaselineRunnerPath and -OptimizedRunnerPath.'
    }
    $baselineResolvedRunner = if ([string]::IsNullOrWhiteSpace($BaselineRunnerPath)) { $commonResolvedRunner } else { Resolve-RunnerPathForArm -RequestedPath $BaselineRunnerPath -WorktreePath $resolvedBaseline -DefaultRelativePath $defaultRunnerRelativePath }
    if ($null -eq $baselineResolvedRunner) { $baselineResolvedRunner = Resolve-RunnerPathForArm -RequestedPath $null -WorktreePath $resolvedBaseline -DefaultRelativePath $defaultRunnerRelativePath }
    $optimizedResolvedRunner = if ([string]::IsNullOrWhiteSpace($OptimizedRunnerPath)) { $commonResolvedRunner } else { Resolve-RunnerPathForArm -RequestedPath $OptimizedRunnerPath -WorktreePath $resolvedOptimized -DefaultRelativePath $defaultRunnerRelativePath }
    if ($null -eq $optimizedResolvedRunner) { $optimizedResolvedRunner = Resolve-RunnerPathForArm -RequestedPath $null -WorktreePath $resolvedOptimized -DefaultRelativePath $defaultRunnerRelativePath }
    $baselineCommandPlan = Get-RunnerCommandPlan $baselineResolvedRunner
    $optimizedCommandPlan = Get-RunnerCommandPlan $optimizedResolvedRunner
    $baselineReplayPath = Resolve-OptionalReplayRecordingsPath -RequestedPath $BaselineReplayRecordingsPath -WorktreePath $resolvedBaseline
    $optimizedReplayPath = Resolve-OptionalReplayRecordingsPath -RequestedPath $OptimizedReplayRecordingsPath -WorktreePath $resolvedOptimized
    $selection = Normalize-Selection -Modes $Mode -ProviderValues $Providers
    $commonRunnerArguments = if ($null -eq $RunnerArguments) { @() } else { @($RunnerArguments) }
    $baselineArguments = Get-ArmRunnerArguments -ArmName 'baseline' -CommonArguments $commonRunnerArguments -BaselineArguments $BaselineRunnerArguments -OptimizedArguments $OptimizedRunnerArguments
    $optimizedArguments = Get-ArmRunnerArguments -ArmName 'optimized' -CommonArguments $commonRunnerArguments -BaselineArguments $BaselineRunnerArguments -OptimizedArguments $OptimizedRunnerArguments
    $baselineConfig = Get-ArmExecutionConfig -ArmName 'baseline' -PlanningConcurrency $BaselinePlanningConcurrency -RunnerArguments $baselineArguments -ReplayRecordingsPath $baselineReplayPath
    $optimizedConfig = Get-ArmExecutionConfig -ArmName 'optimized' -PlanningConcurrency $OptimizedPlanningConcurrency -RunnerArguments $optimizedArguments -ReplayRecordingsPath $optimizedReplayPath
    $runSeed = [Math]::Abs([int64]$Seed)
    if ($runSeed -gt 2147483647) { $runSeed = $runSeed % 2147483648 }
    $runId = 'latency-ab-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 12)
    $runRoot = Join-Path $resolvedOutputRoot $runId
    New-Item -ItemType Directory -Force -Path $runRoot | Out-Null
    $manifest = [ordered]@{
        schemaVersion = 1
        toolVersion = $script:LatencyAbOrchestratorVersion
        runId = $runId
        orchestratorProcessId = [int]$PID
        status = 'running'
        runRoot = $runRoot
        selection = [ordered]@{ modes = @($selection.Modes); providers = @($selection.Providers); requireLive = [bool]$RequireLive; requireProviders = [bool]$RequireProviders }
        seed = [int]$runSeed
        retryPolicy = [ordered]@{ maxRetries = $MaxRetries; attemptsPerArm = $MaxRetries + 1 }
        timeout = [ordered]@{ outerTimeoutSeconds = $OuterTimeoutSeconds; maxOutputBytes = $MaxOutputBytes; maxResultBytes = $MaxResultBytes }
        tools = Get-ManifestToolVersions -BaselineCommandPlan $baselineCommandPlan -OptimizedCommandPlan $optimizedCommandPlan
        armConfig = [ordered]@{
            baseline = [ordered]@{ planningConcurrency = $BaselinePlanningConcurrency; replayRecordingsPath = $baselineConfig.Redacted.replayRecordingsPath; runnerArguments = $baselineConfig.Redacted.runnerArguments; configSha256 = $baselineConfig.Sha256 }
            optimized = [ordered]@{ planningConcurrency = $OptimizedPlanningConcurrency; replayRecordingsPath = $optimizedConfig.Redacted.replayRecordingsPath; runnerArguments = $optimizedConfig.Redacted.runnerArguments; configSha256 = $optimizedConfig.Sha256 }
        }
        armOrder = New-Object 'System.Collections.Generic.List[string]'
        results = New-Object 'System.Collections.Generic.List[object]'
        sourceHashes = [ordered]@{ before = [ordered]@{}; after = [ordered]@{} }
        matrixHashes = [ordered]@{ before = [ordered]@{}; after = [ordered]@{} }
        cells = @()
        errors = New-Object 'System.Collections.Generic.List[string]'
    }
    Write-JsonArtifact -Path (Join-Path $runRoot 'manifest.json') -Value $manifest -Depth 60
    $baselineState = $null
    $optimizedState = $null
    try {
        $baselineState = Get-SourceAndMatrixState -ArmPath $resolvedBaseline -MatrixPathValue $MatrixPath -Modes $selection.Modes -ProviderValues $selection.Providers -RunSeed ([int]$runSeed) -Retries $MaxRetries -TimeoutSeconds $OuterTimeoutSeconds
        $optimizedState = Get-SourceAndMatrixState -ArmPath $resolvedOptimized -MatrixPathValue $MatrixPath -Modes $selection.Modes -ProviderValues $selection.Providers -RunSeed ([int]$runSeed) -Retries $MaxRetries -TimeoutSeconds $OuterTimeoutSeconds
        if ($baselineState.Matrix.CanonicalSha256 -ne $optimizedState.Matrix.CanonicalSha256) { throw 'Canonical matrix hash differs between baseline and optimized worktrees.' }
        if ($baselineState.Matrix.EffectiveSha256 -ne $optimizedState.Matrix.EffectiveSha256) { throw 'Effective matrix hash differs between baseline and optimized worktrees.' }
        $manifest.sourceHashes.before.baseline = $baselineState.SourceSha256
        $manifest.sourceHashes.before.optimized = $optimizedState.SourceSha256
        $manifest.matrixHashes.before.baseline = [ordered]@{ canonical = $baselineState.Matrix.CanonicalSha256; effective = $baselineState.Matrix.EffectiveSha256; raw = $baselineState.Matrix.RawSha256 }
        $manifest.matrixHashes.before.optimized = [ordered]@{ canonical = $optimizedState.Matrix.CanonicalSha256; effective = $optimizedState.Matrix.EffectiveSha256; raw = $optimizedState.Matrix.RawSha256 }
        $effectivePath = Join-Path $runRoot 'matrix-effective.json'
        Write-JsonArtifact -Path $effectivePath -Value (ConvertTo-RedactedValue $baselineState.Matrix.Effective) -Depth 100
        $effectiveArtifactHash = Get-RawFileSha256 $effectivePath
        $cells = New-ExperimentCells -Matrix $baselineState.Matrix.Parsed -Modes $selection.Modes -ProviderValues $selection.Providers
        $manifest.cells = @($cells | ForEach-Object { [ordered]@{ trialId = $_.TrialId; baseTrialId = $_.BaseTrialId; mode = $_.Mode; provider = $_.Provider } })
        $manifest.effectiveConfig = [ordered]@{
            matrixPath = [IO.Path]::GetFileName($baselineState.Matrix.Path)
            matrixEffectiveArtifact = 'matrix-effective.json'
            canonicalSha256 = $baselineState.Matrix.CanonicalSha256
            effectiveSha256 = $baselineState.Matrix.EffectiveSha256
            modes = @($selection.Modes)
            providers = @($selection.Providers)
            armConfigSha256 = [ordered]@{ baseline = $baselineConfig.Sha256; optimized = $optimizedConfig.Sha256 }
        }
        Write-JsonArtifact -Path (Join-Path $runRoot 'manifest.json') -Value $manifest -Depth 60

        $randomState = [ref]([int64]$runSeed)
        foreach ($cell in $cells) {
            $firstArm = if ((Get-NextRandomIndex -State $randomState -Bound 2) -eq 0) { 'baseline' } else { 'optimized' }
            $order = if ($firstArm -eq 'baseline') { @('baseline', 'optimized') } else { @('optimized', 'baseline') }
            foreach ($armName in $order) {
                $manifest.armOrder.Add("$($cell.TrialId):$armName") | Out-Null
                $worktreePath = if ($armName -eq 'baseline') { $resolvedBaseline } else { $resolvedOptimized }
                $initialState = if ($armName -eq 'baseline') { $baselineState } else { $optimizedState }
                $armConfig = if ($armName -eq 'baseline') { $baselineConfig } else { $optimizedConfig }
                $armArguments = if ($armName -eq 'baseline') { $baselineArguments } else { $optimizedArguments }
                $armConcurrency = if ($armName -eq 'baseline') { $BaselinePlanningConcurrency } else { $OptimizedPlanningConcurrency }
                $armReplayPath = if ($armName -eq 'baseline') { $baselineReplayPath } else { $optimizedReplayPath }
                $armCommandPlan = if ($armName -eq 'baseline') { $baselineCommandPlan } else { $optimizedCommandPlan }
                $currentBefore = Get-SourceAndMatrixState -ArmPath $worktreePath -MatrixPathValue $MatrixPath -Modes $selection.Modes -ProviderValues $selection.Providers -RunSeed ([int]$runSeed) -Retries $MaxRetries -TimeoutSeconds $OuterTimeoutSeconds
                Assert-StateUnchanged -Before $initialState -After $currentBefore -ArmName $armName
                $armRoot = Join-Path (Join-Path $runRoot $cell.Folder) $armName
                $armOutcome = Invoke-OneLatencyArm -Cell $cell -ArmName $armName -WorktreePath $worktreePath -InitialState $initialState -CommandPlan $armCommandPlan -RunId $runId -RunSeed ([int]$runSeed) -PlanningConcurrency $armConcurrency -ArmConfigHash $armConfig.Sha256 -ReplayRecordingsPath $armReplayPath -TimeoutSeconds $OuterTimeoutSeconds -Retries $MaxRetries -UserRunnerArguments $armArguments -MatrixPathValue $MatrixPath -EffectiveMatrixPath $effectivePath -ArmRoot $armRoot -OutputLimit $MaxOutputBytes -ResultLimit $MaxResultBytes
                $manifest.results.Add($armOutcome.Summary) | Out-Null
                $currentAfter = Get-SourceAndMatrixState -ArmPath $worktreePath -MatrixPathValue $MatrixPath -Modes $selection.Modes -ProviderValues $selection.Providers -RunSeed ([int]$runSeed) -Retries $MaxRetries -TimeoutSeconds $OuterTimeoutSeconds
                Assert-StateUnchanged -Before $initialState -After $currentAfter -ArmName $armName
                if ($effectiveArtifactHash -ne (Get-RawFileSha256 $effectivePath)) { throw 'Effective matrix artifact was modified during the run.' }
                Write-JsonArtifact -Path (Join-Path $runRoot 'manifest.json') -Value $manifest -Depth 60
            }
        }
        $expectedKeys = @{}
        foreach ($cell in $cells) { foreach ($armName in @('baseline', 'optimized')) { $expectedKeys["$($cell.TrialId)|$armName"] = $true } }
        $actualKeys = @{}
        foreach ($result in @($manifest.results.ToArray())) {
            $key = "$($result.trialId)|$($result.arm)"
            if ($actualKeys.ContainsKey($key)) { throw "Duplicate result for trial arm: $key" }
            $actualKeys[$key] = $true
            if (-not $expectedKeys.ContainsKey($key)) { throw "Unexpected result for trial arm: $key" }
        }
        foreach ($key in $expectedKeys.Keys) { if (-not $actualKeys.ContainsKey($key)) { throw "Missing result for trial arm: $key" } }
        $livePassed = 0
        $liveSkipped = 0
        foreach ($result in @($manifest.results.ToArray())) {
            if ([string]$result.mode -eq 'live') {
                if ([string]$result.status -eq 'PASSED') { $livePassed++ }
                if ([string]$result.status -eq 'SKIPPED') { $liveSkipped++ }
            }
            if ([string]$result.status -eq 'SKIPPED') {
                if ([string]$result.mode -ne 'live') { throw "Non-live trial was skipped: $($result.trialId)" }
                if ($RequireProviders) { throw "Required provider trial was skipped: $($result.trialId)" }
            }
            elseif ([string]$result.status -ne 'PASSED') {
                throw "Latency trial failed: $($result.trialId) [$($result.arm)] ($($result.errorCategory))"
            }
        }
        if ($RequireLive -and $livePassed -eq 0) { throw 'RequireLive was set but no live provider trial passed.' }
        if ($RequireProviders -and $selection.Providers.Count -eq 0 -and $liveSkipped -gt 0) { throw 'RequireProviders was set but a live provider trial was skipped.' }
        $manifest.sourceHashes.after.baseline = (Get-SourceAndMatrixState -ArmPath $resolvedBaseline -MatrixPathValue $MatrixPath -Modes $selection.Modes -ProviderValues $selection.Providers -RunSeed ([int]$runSeed) -Retries $MaxRetries -TimeoutSeconds $OuterTimeoutSeconds).SourceSha256
        $manifest.sourceHashes.after.optimized = (Get-SourceAndMatrixState -ArmPath $resolvedOptimized -MatrixPathValue $MatrixPath -Modes $selection.Modes -ProviderValues $selection.Providers -RunSeed ([int]$runSeed) -Retries $MaxRetries -TimeoutSeconds $OuterTimeoutSeconds).SourceSha256
        $finalBaseline = Get-SourceAndMatrixState -ArmPath $resolvedBaseline -MatrixPathValue $MatrixPath -Modes $selection.Modes -ProviderValues $selection.Providers -RunSeed ([int]$runSeed) -Retries $MaxRetries -TimeoutSeconds $OuterTimeoutSeconds
        $finalOptimized = Get-SourceAndMatrixState -ArmPath $resolvedOptimized -MatrixPathValue $MatrixPath -Modes $selection.Modes -ProviderValues $selection.Providers -RunSeed ([int]$runSeed) -Retries $MaxRetries -TimeoutSeconds $OuterTimeoutSeconds
        $manifest.matrixHashes.after.baseline = [ordered]@{ canonical = $finalBaseline.Matrix.CanonicalSha256; effective = $finalBaseline.Matrix.EffectiveSha256; raw = $finalBaseline.Matrix.RawSha256 }
        $manifest.matrixHashes.after.optimized = [ordered]@{ canonical = $finalOptimized.Matrix.CanonicalSha256; effective = $finalOptimized.Matrix.EffectiveSha256; raw = $finalOptimized.Matrix.RawSha256 }
        $manifest.status = 'passed'
        $manifest.completedAt = [DateTime]::UtcNow.ToString('o')
        Write-JsonArtifact -Path (Join-Path $runRoot 'manifest.json') -Value $manifest -Depth 60
        $global:LASTEXITCODE = 0
        return [pscustomobject]$manifest
    }
    catch {
        $manifest.status = 'failed'
        $manifest.errors.Add((ConvertTo-SafeText $_.Exception.Message)) | Out-Null
        try {
            $failedBaseline = Get-SourceAndMatrixState -ArmPath $resolvedBaseline -MatrixPathValue $MatrixPath -Modes $selection.Modes -ProviderValues $selection.Providers -RunSeed ([int]$runSeed) -Retries $MaxRetries -TimeoutSeconds $OuterTimeoutSeconds
            $failedOptimized = Get-SourceAndMatrixState -ArmPath $resolvedOptimized -MatrixPathValue $MatrixPath -Modes $selection.Modes -ProviderValues $selection.Providers -RunSeed ([int]$runSeed) -Retries $MaxRetries -TimeoutSeconds $OuterTimeoutSeconds
            $manifest.sourceHashes.after.baseline = $failedBaseline.SourceSha256
            $manifest.sourceHashes.after.optimized = $failedOptimized.SourceSha256
            $manifest.matrixHashes.after.baseline = [ordered]@{ canonical = $failedBaseline.Matrix.CanonicalSha256; effective = $failedBaseline.Matrix.EffectiveSha256; raw = $failedBaseline.Matrix.RawSha256 }
            $manifest.matrixHashes.after.optimized = [ordered]@{ canonical = $failedOptimized.Matrix.CanonicalSha256; effective = $failedOptimized.Matrix.EffectiveSha256; raw = $failedOptimized.Matrix.RawSha256 }
        }
        catch { }
        $manifest.completedAt = [DateTime]::UtcNow.ToString('o')
        Write-JsonArtifact -Path (Join-Path $runRoot 'manifest.json') -Value $manifest -Depth 60
        $global:LASTEXITCODE = 1
        throw
    }
}

if ($MyInvocation.InvocationName -ne '.') {
    $invokeArgs = @{}
    foreach ($key in $PSBoundParameters.Keys) {
        if ($key -notin @('RunnerArguments','BaselineRunnerArguments','OptimizedRunnerArguments')) { $invokeArgs[$key] = $PSBoundParameters[$key] }
    }
    if ($null -ne $RunnerArguments -and @($RunnerArguments).Count -gt 0) { $invokeArgs.RunnerArguments = @($RunnerArguments) }
    if ($null -ne $BaselineRunnerArguments) { $invokeArgs.BaselineRunnerArguments = @($BaselineRunnerArguments) }
    if ($null -ne $OptimizedRunnerArguments) { $invokeArgs.OptimizedRunnerArguments = @($OptimizedRunnerArguments) }
    Invoke-LatencyAbExperiment @invokeArgs
}
