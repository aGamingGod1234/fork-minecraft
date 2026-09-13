[CmdletBinding()]
param(
	[Parameter(Mandatory)] [string] $PackageRoot,
	[Parameter(Mandatory)] [string] $JavaPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function File-Snapshot([string] $Root) {
	if (-not (Test-Path -LiteralPath $Root)) { return @() }
	return @(Get-ChildItem -LiteralPath $Root -File -Recurse -Force |
		Where-Object { $_.Name -notlike '*.lock' -and $_.FullName -notmatch '[\\/]distribution-backups[\\/]' } |
		Sort-Object FullName |
		ForEach-Object { $_.FullName.Substring($Root.Length + 1) + ':' + (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash })
}

$package = (Resolve-Path -LiteralPath $PackageRoot).Path
$installer = Join-Path $package 'scripts\install-distribution.ps1'
$metadata = [ordered]@{}
foreach ($line in Get-Content -LiteralPath (Join-Path $package 'distribution.properties')) {
	$separator = $line.IndexOf('=')
	if ($separator -gt 0) { $metadata[$line.Substring(0, $separator)] = $line.Substring($separator + 1) }
}
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("arena-package-install-test-" + [Guid]::NewGuid().ToString('N'))
$appData = Join-Path $testRoot 'appdata'
$game = Join-Path $testRoot 'game'
$launcherProfiles = Join-Path $appData '.minecraft\launcher_profiles.json'
$versionId = "fabric-loader-$($metadata.loader_version)-$($metadata.minecraft_version)"
$versionMetadata = Join-Path $appData ".minecraft\versions\$versionId\$versionId.json"
$mods = Join-Path $game 'mods'
$installedRoot = Join-Path $game 'arena-agents-runtime'
$previousAppData = $env:APPDATA
try {
	New-Item -ItemType Directory -Force -Path $mods, (Split-Path -Parent $launcherProfiles), (Split-Path -Parent $versionMetadata) | Out-Null
	[IO.File]::WriteAllText($launcherProfiles, '{"profiles":{}}')
	[IO.File]::WriteAllText($versionMetadata, '{}')
	[IO.File]::WriteAllText((Join-Path $mods 'arena-agents-old-unparseable.jar'), 'old arena')
	[IO.File]::WriteAllText((Join-Path $mods 'fabric-api-old.jar'), 'old api')
	[IO.File]::WriteAllText((Join-Path $mods 'fabric-carpet-old.jar'), 'old carpet')
	[IO.File]::WriteAllText((Join-Path $mods 'voicechat-fabric-old.jar'), 'old voicechat')
	[IO.File]::WriteAllText((Join-Path $mods 'unrelated.jar'), 'keep')
	$voiceAddon = Join-Path $mods 'arena-agents-voice-0.1.0.jar'
	[IO.File]::WriteAllText($voiceAddon, 'optional voice addon')
	$voiceAddonHash = (Get-FileHash -LiteralPath $voiceAddon -Algorithm SHA256).Hash
	$env:APPDATA = $appData

	& $installer -JavaPath $JavaPath -LauncherProfiles $launcherProfiles -GameDirectory $game
	$expectedModNames = @(
		"arena-agents-$($metadata.mod_version).jar",
		"arena-agents-voice-$($metadata.voice_addon_version).jar",
		"fabric-api-$($metadata.fabric_api_version).jar",
		"fabric-carpet-$($metadata.carpet_version).jar",
		"voicechat-fabric-$($metadata.voicechat_version).jar"
	)
	$owned = @(Get-ChildItem -LiteralPath $mods -Filter '*.jar' -File |
		Where-Object {
			$_.Name -match '^(?i:fabric-api|fabric-carpet|voicechat-fabric)-.+\.jar$' -or
			$_.Name -match '^(?i:arena-agents).+\.jar$'
		} |
		Select-Object -ExpandProperty Name | Sort-Object)
	if (@(Compare-Object ($expectedModNames | Sort-Object) $owned).Count -ne 0) { throw 'Successful package update retained a stale package-owned JAR.' }
	if (-not (Test-Path -LiteralPath (Join-Path $mods 'unrelated.jar') -PathType Leaf)) { throw 'Successful package update removed an unrelated mod.' }
	if (Test-Path -LiteralPath $voiceAddon -PathType Leaf) {
		throw 'Successful package update retained a stale Arena Agents Voice add-on.'
	}
	if (Test-Path -LiteralPath (Join-Path $mods 'voicechat-fabric-old.jar') -PathType Leaf) {
		throw 'Successful package update retained a stale Simple Voice Chat JAR.'
	}
	foreach ($secretName in @('bridge-secret.txt', 'voice-secret.txt')) {
		if (-not (Test-Path -LiteralPath (Join-Path $installedRoot "runtime\$secretName") -PathType Leaf)) { throw "Installed secret is missing: $secretName" }
	}

	foreach ($failurePoint in @('AfterRuntimePromotion', 'AfterModRemoval', 'AfterModsPromotion')) {
		$modsBefore = File-Snapshot $mods
		$runtimeBefore = File-Snapshot $installedRoot
		$profilesBefore = (Get-FileHash -LiteralPath $launcherProfiles -Algorithm SHA256).Hash
		$failed = $false
		try { & $installer -JavaPath $JavaPath -LauncherProfiles $launcherProfiles -GameDirectory $game -FailurePoint $failurePoint }
		catch { if ($_.Exception.Message -notmatch 'Injected failure') { throw }; $failed = $true }
		if (-not $failed) { throw "Failure injection did not occur: $failurePoint" }
		if (@(Compare-Object $modsBefore (File-Snapshot $mods)).Count -ne 0) { throw "Mod rollback failed at $failurePoint." }
		if (@(Compare-Object $runtimeBefore (File-Snapshot $installedRoot)).Count -ne 0) { throw "Runtime rollback failed at $failurePoint." }
		if ((Get-FileHash -LiteralPath $launcherProfiles -Algorithm SHA256).Hash -ne $profilesBefore) { throw "Profile changed at $failurePoint." }
	}

	$profileDocument = Get-Content -LiteralPath $launcherProfiles -Raw | ConvertFrom-Json
	$profileDocument.profiles.'arena-agents-modpack'.javaArgs = '-Xms1G -Xmx4G -Dstale=true'
	[IO.File]::WriteAllText($launcherProfiles, ($profileDocument | ConvertTo-Json -Depth 64))
	$modsBefore = File-Snapshot $mods
	$runtimeBefore = File-Snapshot $installedRoot
	$profilesBefore = (Get-FileHash -LiteralPath $launcherProfiles -Algorithm SHA256).Hash
	$failed = $false
	try { & $installer -JavaPath $JavaPath -LauncherProfiles $launcherProfiles -GameDirectory $game -FailurePoint AfterProfilePromotion }
	catch { if ($_.Exception.Message -notmatch 'Injected failure') { throw }; $failed = $true }
	if (-not $failed) { throw 'Failure injection did not occur: AfterProfilePromotion' }
	if (@(Compare-Object $modsBefore (File-Snapshot $mods)).Count -ne 0) { throw 'Mod rollback failed after profile promotion.' }
	if (@(Compare-Object $runtimeBefore (File-Snapshot $installedRoot)).Count -ne 0) { throw 'Runtime rollback failed after profile promotion.' }
	if ((Get-FileHash -LiteralPath $launcherProfiles -Algorithm SHA256).Hash -ne $profilesBefore) { throw 'Profile rollback failed after profile promotion.' }

	Write-Host 'PASS: packaged install removes stale core and voice JARs, and rolls back runtime, mods, and launcher profile together'
} finally {
	$env:APPDATA = $previousAppData
	if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot -Recurse -Force }
}
