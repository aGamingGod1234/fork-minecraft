[CmdletBinding()]
param(
	[Parameter(Mandatory)] [string] $ArchivePath,
	[string] $BuiltJarPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Read-ZipText([IO.Compression.ZipArchive] $Archive, [string] $Path) {
	$entry = $Archive.GetEntry($Path)
	if ($null -eq $entry) { throw "Distribution archive is missing '$Path'." }
	$reader = [IO.StreamReader]::new($entry.Open(), [Text.UTF8Encoding]::new($false), $true)
	try { return $reader.ReadToEnd() }
	finally { $reader.Dispose() }
}

function Read-Properties([string] $Text) {
	$result = [ordered]@{}
	foreach ($line in $Text -split "\r?\n") {
		$trimmed = $line.Trim()
		if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
		$separator = $trimmed.IndexOf('=')
		if ($separator -le 0 -or $separator -eq $trimmed.Length - 1) { throw "Invalid distribution property: $trimmed" }
		$key = $trimmed.Substring(0, $separator).Trim()
		$value = $trimmed.Substring($separator + 1).Trim()
		if ($result.Contains($key)) { throw "Duplicate distribution property: $key" }
		$result[$key] = $value
	}
	return $result
}

function Get-ZipEntrySha256([IO.Compression.ZipArchiveEntry] $Entry) {
	$sha = [Security.Cryptography.SHA256]::Create()
	$stream = $Entry.Open()
	try { return (($sha.ComputeHash($stream) | ForEach-Object { $_.ToString('x2') }) -join '') }
	finally { $stream.Dispose(); $sha.Dispose() }
}

$resolvedArchive = (Resolve-Path -LiteralPath $ArchivePath).Path
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($resolvedArchive)
try {
	$files = @($archive.Entries |
		Where-Object { -not $_.FullName.EndsWith('/') } |
		ForEach-Object { $_.FullName.Replace('\', '/') })
	if ($files.Count -ne @($files | Sort-Object -Unique).Count) { throw 'Distribution archive contains duplicate file paths.' }
	foreach ($path in $files) {
		if ($path.StartsWith('/') -or $path -match '(^|/)\.\.(/|$)' -or $path -match '^[A-Za-z]:') {
			throw "Distribution archive contains an unsafe path: $path"
		}
	}

	$properties = Read-Properties (Read-ZipText $archive 'distribution.properties')
	foreach ($required in @('mod_version', 'minecraft_version', 'loader_version', 'fabric_api_version', 'carpet_version', 'voicechat_version', 'voice_addon_version')) {
		if (-not $properties.Contains($required)) { throw "Distribution metadata is missing '$required'." }
	}
	$expectedMods = @(
		"mods/arena-agents-$($properties.mod_version).jar"
		"mods/arena-agents-voice-$($properties.voice_addon_version).jar"
		"mods/fabric-api-$($properties.fabric_api_version).jar"
		"mods/fabric-carpet-$($properties.carpet_version).jar"
		"mods/voicechat-fabric-$($properties.voicechat_version).jar"
	)
	$actualMods = @($files | Where-Object { $_ -like 'mods/*.jar' } | Sort-Object)
	if (@(Compare-Object ($expectedMods | Sort-Object) $actualMods).Count -ne 0) {
		throw "Distribution mod set differs from metadata. Expected: $($expectedMods -join ', '); actual: $($actualMods -join ', ')"
	}
	foreach ($required in @(
		'README.md',
		'scripts/install-distribution.ps1',
		'scripts/distribution-runtime.ps1',
		'scripts/verify-startup-packaging.ps1',
		'coordinator/src/dynamic-main.mjs',
		'coordinator/src/posix-process-group.mjs',
		'coordinator/src/posix-process-wrapper.mjs',
		'coordinator/.arena-agents-bundle-manifest',
		'runtime/toolchains/node/node.exe'
	)) {
		if ($files -notcontains $required) { throw "Distribution archive is missing '$required'." }
	}

	$manifestRecords = @((Read-ZipText $archive 'coordinator/.arena-agents-bundle-manifest') -split "\r?\n" |
		Where-Object { $_ -ne '' } |
		ForEach-Object {
			if ($_ -notmatch '^(?<Hash>[0-9a-f]{64}) (?<Path>.+)$') { throw "Invalid coordinator manifest entry: $_" }
			if ($Matches.Path.StartsWith('/') -or $Matches.Path -match '(^|/)\.\.(/|$)' -or $Matches.Path -match '^[A-Za-z]:') {
				throw "Coordinator manifest contains an unsafe path: $($Matches.Path)"
			}
			[pscustomobject]@{ Hash = $Matches.Hash; Path = $Matches.Path }
		})
	if ($manifestRecords.Count -eq 0) { throw 'Coordinator manifest is empty.' }
	$manifestPaths = @($manifestRecords.Path | Sort-Object)
	if ($manifestPaths.Count -ne @($manifestPaths | Sort-Object -Unique).Count) { throw 'Coordinator manifest contains duplicate paths.' }
	$coordinatorFiles = @($files |
		Where-Object { $_.StartsWith('coordinator/') -and $_ -ne 'coordinator/.arena-agents-bundle-manifest' } |
		ForEach-Object { $_.Substring('coordinator/'.Length) } |
		Sort-Object)
	if (@(Compare-Object $manifestPaths $coordinatorFiles).Count -ne 0) {
		throw 'Coordinator manifest differs from the staged runtime file set.'
	}
	foreach ($record in $manifestRecords) {
		$entry = $archive.GetEntry("coordinator/$($record.Path)")
		if ($null -eq $entry -or (Get-ZipEntrySha256 $entry) -cne $record.Hash) {
			throw "Coordinator manifest hash mismatch: $($record.Path)"
		}
	}

	$readme = Read-ZipText $archive 'README.md'
	$scriptReferences = @([regex]::Matches($readme, '(?i)\.\\scripts\\(?<Name>[A-Za-z0-9._-]+\.ps1)') |
		ForEach-Object { "scripts/$($_.Groups['Name'].Value)" } | Sort-Object -Unique)
	if ($scriptReferences.Count -eq 0) { throw 'Distribution README does not contain an installation command.' }
	foreach ($reference in $scriptReferences) {
		if ($files -notcontains $reference) { throw "Distribution README references a script that is absent from the ZIP: $reference" }
	}
	foreach ($sourceOnly in @('prepare-runtime.ps1', 'install-launcher-profiles.ps1', 'start-test-server.ps1', 'start-dynamic-coordinator.ps1')) {
		if ($readme -match [regex]::Escape($sourceOnly)) { throw "Distribution README references source-only script '$sourceOnly'." }
	}

	if (-not [string]::IsNullOrWhiteSpace($BuiltJarPath)) {
		$resolvedJar = (Resolve-Path -LiteralPath $BuiltJarPath).Path
		$entry = $archive.GetEntry("mods/arena-agents-$($properties.mod_version).jar")
		$archiveHash = Get-ZipEntrySha256 $entry
		$builtHash = (Get-FileHash -LiteralPath $resolvedJar -Algorithm SHA256).Hash.ToLowerInvariant()
		if ($archiveHash -cne $builtHash) { throw 'Distribution Arena Agents JAR differs from the built JAR.' }
	}

	Write-Host "Windows distribution verified: version $($properties.mod_version), $($files.Count) files"
} finally {
	$archive.Dispose()
}
