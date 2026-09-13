[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $JarPath,
    [string] $StagingPath,
    [string] $SourceCoordinatorPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$resolvedJar = (Resolve-Path -LiteralPath $JarPath).Path
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($resolvedJar)
try {
    $prefix = 'arena-agents/coordinator/'
    $entries = @($archive.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
    $manifestPath = "${prefix}coordinator-manifest.txt"
    if ($entries -notcontains $manifestPath) {
        throw "Coordinator manifest is missing from $resolvedJar."
    }

    $manifestEntry = $archive.GetEntry($manifestPath)
    $reader = [IO.StreamReader]::new($manifestEntry.Open())
    try { $manifest = @($reader.ReadToEnd() -split "\r?\n" | Where-Object { $_ -ne '' }) }
    finally { $reader.Dispose() }

	$manifestRecords = @($manifest | ForEach-Object {
		if ($_ -notmatch '^(?<Hash>[0-9a-f]{64}) (?<Path>.+)$') { throw "Invalid coordinator manifest entry: $_" }
		[pscustomobject]@{ Hash = $Matches.Hash; Path = $Matches.Path }
	})

    $actual = @($entries |
        Where-Object { $_.StartsWith($prefix) -and $_ -ne $manifestPath -and -not $_.EndsWith('/') } |
        ForEach-Object { $_.Substring($prefix.Length) } |
        Sort-Object)
    $expected = @($manifestRecords.Path | Sort-Object)
    if (@(Compare-Object -ReferenceObject $expected -DifferenceObject $actual).Count -ne 0) {
        throw 'Coordinator manifest does not match the embedded runtime file set.'
    }

    foreach ($required in @(
        'package.json',
        'package-lock.json',
        'config/dynamic-agents.json',
        'src/dynamic-main.mjs',
		'src/job-gate.mjs',
		'src/posix-process-group.mjs',
		'src/posix-process-wrapper.mjs',
		'src/voice/local-speech-requirements.txt',
		'src/voice/local-speech-worker.py',
		'config/minecraft-agent/AGENTS.md',
		'config/minecraft-agent/.codex/skills/minecraft-control/SKILL.md',
        'node_modules/acorn/package.json',
        'node_modules/acorn/dist/acorn.mjs'
    )) {
        if ($expected -notcontains $required) { throw "Required coordinator entry is missing: $required" }
    }

    $forbidden = @($expected | Where-Object {
        $_ -match '(?i)(^|/)(logs?|traces?|tests?|workspaces?|runtime)(/|$)' -or
        $_ -match '(?i)(^|/)node_modules/\.' -or
        $_ -match '(?i)(secret|token|credential|\.env)'
    })
    if ($forbidden.Count -ne 0) {
        throw "Forbidden coordinator entries are embedded: $($forbidden -join ', ')"
    }

	$developmentOnly = @(
		'src/benchmark/',
		'src/simulator/',
		'src/headless-matrix.mjs',
		'src/headless-rcon.mjs',
		'src/headless-world.mjs',
		'src/headless-world-spawn.mjs',
		'src/headless-config.mjs',
		'src/headless-capabilities.mjs',
		'src/player-capability-probe.mjs',
		'src/native-tool-ab-runner.mjs',
		'src/native-tool-ab-trial.mjs',
		'src/native-tool-cli-boundary.mjs',
		'src/native-tool-load-probe.mjs',
		'src/native-tool-probe.mjs',
		'config/headless-provider-matrix.json',
		'config/latency-acceptance.json',
		'config/latency-experiment-matrix.json',
		'config/latency-headless-matrix.json',
		'config/latency-matrix.json',
		'config/task9-performance-matrix.json'
	)
	$embeddedDevelopment = @($expected | Where-Object {
		$entry = $_
		$developmentOnly | Where-Object { $entry -eq $_ -or $entry.StartsWith($_) }
	})
	if ($embeddedDevelopment.Count -ne 0) {
		throw "Development-only coordinator entries are embedded: $($embeddedDevelopment -join ', ')"
	}

	foreach ($record in $manifestRecords) {
		$entry = $archive.GetEntry($prefix + $record.Path)
		if ($null -eq $entry) { throw "Manifest resource is missing: $($record.Path)" }
		$sha = [Security.Cryptography.SHA256]::Create()
		$stream = $entry.Open()
		try { $actualHash = (($sha.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') }) -join '') }
		finally { $stream.Dispose(); $sha.Dispose() }
		if ($actualHash -cne $record.Hash) { throw "Manifest hash differs from embedded resource: $($record.Path)" }
	}

    if (-not [string]::IsNullOrWhiteSpace($StagingPath)) {
        $resolvedStaging = (Resolve-Path -LiteralPath $StagingPath).Path
        $staged = @(Get-ChildItem -LiteralPath $resolvedStaging -Recurse -File |
            ForEach-Object { $_.FullName.Substring($resolvedStaging.Length + 1).Replace('\', '/') } |
            Sort-Object)
        if (@(Compare-Object -ReferenceObject $expected -DifferenceObject $staged).Count -ne 0) {
            throw "Installed coordinator staging does not match the embedded manifest: $resolvedStaging"
        }
        if ([string]::IsNullOrWhiteSpace($SourceCoordinatorPath)) { throw 'SourceCoordinatorPath is required for hash-based staging parity.' }
        $resolvedSource = (Resolve-Path -LiteralPath $SourceCoordinatorPath).Path
        foreach ($relative in $expected) {
            $sourceHash = (Get-FileHash -LiteralPath (Join-Path $resolvedSource ($relative.Replace('/', '\'))) -Algorithm SHA256).Hash
            $stagedHash = (Get-FileHash -LiteralPath (Join-Path $resolvedStaging ($relative.Replace('/', '\'))) -Algorithm SHA256).Hash
            if ($sourceHash -ne $stagedHash) { throw "Installed coordinator hash differs from source: $relative" }
        }
    }

    Write-Host "Coordinator packaging verified: $($expected.Count) files; jar=$resolvedJar"
    if (-not [string]::IsNullOrWhiteSpace($StagingPath)) { Write-Host "Coordinator staging parity verified: $StagingPath" }
}
finally {
    $archive.Dispose()
}
