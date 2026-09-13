[CmdletBinding()]
param(
	[string] $ProjectRoot,
	[string] $TemplateSource,
	[string] $TemplatePath,
	[string] $JavaPath,
	[string] $InstallerPath,
	[string] $InstallerSha256 = '2487A69DD6F9D9C2605265A7142D77C26AB62EDC620E6BCF810D581D2EE31B79',
	[string] $FabricApiPath,
	[string] $FabricApiSha256 = '43BDFC59A21ACE202345BC4C42C751FA36B80617A61CF7B2F8C3698B806305D8',
	[switch] $SkipBuild,
	[switch] $AcceptMinecraftEula
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'offline-server-policy.ps1')
. (Join-Path $PSScriptRoot 'project-metadata.ps1')

$MinecraftVersion = '26.1.2'
$FabricLoaderVersion = '0.19.3'
$FabricInstallerVersion = '1.1.1'
$FabricApiVersion = '0.150.0+26.1.2'
$FabricInstallerUri = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/$FabricInstallerVersion/fabric-installer-$FabricInstallerVersion.jar"
$FabricApiUri = "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/$FabricApiVersion/fabric-api-$FabricApiVersion.jar"
$utf8 = [Text.UTF8Encoding]::new($false)

function Assert-UnderRoot([string] $Path, [string] $Root, [string] $Label) {
	$fullPath = [IO.Path]::GetFullPath($Path)
	$fullRoot = [IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
	if (-not $fullPath.StartsWith($fullRoot, [StringComparison]::OrdinalIgnoreCase)) {
		throw "$Label escaped its allowed root: $fullPath"
	}
}

function Resolve-Java([string] $Requested, [string] $Project) {
	$candidates = @()
	if (-not [string]::IsNullOrWhiteSpace($Requested)) { $candidates += $Requested }
	$override = [Environment]::GetEnvironmentVariable('ARENA_HEADLESS_JAVA')
	if (-not [string]::IsNullOrWhiteSpace($override)) { $candidates += $override }
	if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe') }
	$candidates += (Join-Path $Project 'runtime\toolchains\temurin-25\jdk-25.0.3+9\bin\java.exe')
	$command = Get-Command java.exe -ErrorAction SilentlyContinue
	if ($null -ne $command) { $candidates += $command.Source }
	$seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
	$checked = [Collections.Generic.List[string]]::new()
	foreach ($candidate in $candidates) {
		if ([string]::IsNullOrWhiteSpace($candidate) -or -not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
		$resolved = [IO.Path]::GetFullPath($candidate)
		if (-not $seen.Add($resolved)) { continue }
		$previousErrorAction = $ErrorActionPreference
		try {
			$ErrorActionPreference = 'Continue'
			$versionText = (& $resolved -version 2>&1 | Out-String)
		} catch {
			$checked.Add("unusable Java at $resolved")
			continue
		} finally {
			$ErrorActionPreference = $previousErrorAction
		}
		if ($versionText -match '(?i)(?:openjdk|java) version "(?<major>\d+)') {
			$major = [int] $Matches.major
			if ($major -eq 25) { return $resolved }
			$checked.Add("Java $major at $resolved")
		} else {
			$checked.Add("unknown version at $resolved")
		}
	}
	if ($checked.Count -eq 0) { throw 'Java 25 is required; pass -JavaPath or set JAVA_HOME' }
	throw "Java 25 is required; checked $($checked -join '; ')"
}

function Get-VerifiedArtifact([string] $Path, [string] $Uri, [string] $ExpectedSha256, [string] $Label) {
	$fullPath = [IO.Path]::GetFullPath($Path)
	$parent = Split-Path -Parent $fullPath
	New-Item -ItemType Directory -Path $parent -Force | Out-Null
	if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
		Invoke-WebRequest -UseBasicParsing -Uri $Uri -OutFile $fullPath
	}
	$actual = (Get-FileHash -LiteralPath $fullPath -Algorithm SHA256).Hash
	if (-not $actual.Equals($ExpectedSha256, [StringComparison]::OrdinalIgnoreCase)) {
		throw "$Label SHA-256 mismatch. Expected $ExpectedSha256, got $actual"
	}
	return $fullPath
}

if (-not $AcceptMinecraftEula) {
	throw 'Pass -AcceptMinecraftEula to confirm acceptance of the Minecraft EULA before downloading or materializing the server template'
}

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
$project = [IO.Path]::GetFullPath($ProjectRoot)
$runtime = Join-Path $project 'runtime'
if ([string]::IsNullOrWhiteSpace($TemplateSource)) { $TemplateSource = Join-Path $project 'server-template' }
if ([string]::IsNullOrWhiteSpace($TemplatePath)) { $TemplatePath = Join-Path $runtime 'server-template' }
$source = [IO.Path]::GetFullPath($TemplateSource)
$target = [IO.Path]::GetFullPath($TemplatePath)
Assert-UnderRoot $target $runtime 'Template path'
if (-not (Test-Path -LiteralPath $source -PathType Container)) { throw "Missing tracked template source: $source" }
if (Test-Path -LiteralPath $target) { throw "Template path already exists; remove it deliberately before rebuilding: $target" }
foreach ($forbidden in @('world', 'logs', 'eula.txt', 'server.jar', 'fabric-server-launch.jar', 'libraries', 'mods')) {
	if (Test-Path -LiteralPath (Join-Path $source $forbidden)) { throw "Tracked template source must not contain generated server state: $forbidden" }
}

if ([string]::IsNullOrWhiteSpace($InstallerPath)) { $InstallerPath = Join-Path $runtime "downloads\fabric-installer-$FabricInstallerVersion.jar" }
if ([string]::IsNullOrWhiteSpace($FabricApiPath)) { $FabricApiPath = Join-Path $runtime "downloads\fabric-api-$FabricApiVersion.jar" }
$installer = Get-VerifiedArtifact $InstallerPath $FabricInstallerUri $InstallerSha256 'Fabric installer'
$fabricApi = Get-VerifiedArtifact $FabricApiPath $FabricApiUri $FabricApiSha256 'Fabric API'
$java = Resolve-Java $JavaPath $project

if (-not $SkipBuild) {
	$gradle = Join-Path $project 'gradlew.bat'
	if (-not (Test-Path -LiteralPath $gradle -PathType Leaf)) { throw "Missing Gradle wrapper: $gradle" }
	$previousJavaHome = $env:JAVA_HOME
	$env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $java)
	Push-Location $project
	try {
		& $gradle build --no-daemon --console=plain
		if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE" }
	} finally {
		Pop-Location
		$env:JAVA_HOME = $previousJavaHome
	}
}

$arenaJar = Resolve-ArenaModJar $project
$carpetJar = Resolve-ArenaCarpetJar $project
foreach ($required in @($arenaJar, $carpetJar)) {
	if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing required mod JAR: $required" }
}

New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$staging = Join-Path $runtime ".server-template-staging-$([Guid]::NewGuid().ToString('N'))"
Assert-UnderRoot $staging $runtime 'Template staging path'
try {
	New-Item -ItemType Directory -Path $staging | Out-Null
	Get-ChildItem -LiteralPath $source -Force | ForEach-Object {
		Copy-Item -LiteralPath $_.FullName -Destination $staging -Recurse -Force
	}
	$LASTEXITCODE = 0
	& $java -jar $installer server -dir $staging -mcversion $MinecraftVersion -loader $FabricLoaderVersion -downloadMinecraft
	if ($LASTEXITCODE -ne 0) { throw "Fabric server installation failed with exit code $LASTEXITCODE" }
	foreach ($required in @('fabric-server-launch.jar', 'server.jar', 'libraries', 'server.properties')) {
		if (-not (Test-Path -LiteralPath (Join-Path $staging $required))) { throw "Fabric installer did not create required template artifact: $required" }
	}
	Assert-ArenaOfflineServerLoopback (Join-Path $staging 'server.properties') -RequireOffline
	$mods = Join-Path $staging 'mods'
	New-Item -ItemType Directory -Path $mods -Force | Out-Null
	Copy-Item -LiteralPath $arenaJar -Destination (Join-Path $mods ([IO.Path]::GetFileName($arenaJar))) -Force
	Copy-Item -LiteralPath $fabricApi -Destination (Join-Path $mods ([IO.Path]::GetFileName($fabricApi))) -Force
	Copy-Item -LiteralPath $carpetJar -Destination (Join-Path $mods ([IO.Path]::GetFileName($carpetJar))) -Force
	[IO.File]::WriteAllText((Join-Path $staging 'eula.txt'), "eula=true`n", $utf8)
	foreach ($forbidden in @('world', 'logs')) {
		if (Test-Path -LiteralPath (Join-Path $staging $forbidden)) { throw "Generated template unexpectedly contains runtime state: $forbidden" }
	}
	$manifest = [ordered]@{
		schemaVersion = 1
		minecraftVersion = $MinecraftVersion
		fabricLoaderVersion = $FabricLoaderVersion
		fabricInstallerVersion = $FabricInstallerVersion
		fabricApiVersion = $FabricApiVersion
		fabricInstallerSha256 = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash
		fabricApiSha256 = (Get-FileHash -LiteralPath $fabricApi -Algorithm SHA256).Hash
		arenaAgentsSha256 = (Get-FileHash -LiteralPath $arenaJar -Algorithm SHA256).Hash
		fabricCarpetSha256 = (Get-FileHash -LiteralPath $carpetJar -Algorithm SHA256).Hash
		generatedAt = [DateTime]::UtcNow.ToString('o')
		thirdPartyBinariesTracked = $false
	}
	[IO.File]::WriteAllText((Join-Path $staging 'template-manifest.json'), ($manifest | ConvertTo-Json), $utf8)
	Move-Item -LiteralPath $staging -Destination $target
	$staging = $null
	Write-Output "Prepared isolated Fabric server template: $target"
} finally {
	if (-not [string]::IsNullOrWhiteSpace($staging) -and (Test-Path -LiteralPath $staging)) {
		Assert-UnderRoot $staging $runtime 'Template staging cleanup path'
		Remove-Item -LiteralPath $staging -Recurse -Force
	}
}
