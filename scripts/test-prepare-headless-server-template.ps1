[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptPath = Join-Path $PSScriptRoot 'prepare-headless-server-template.ps1'
$utf8 = [Text.UTF8Encoding]::new($false)

function Write-TestText([string] $Path, [string] $Value) {
	$parent = Split-Path -Parent $Path
	if (-not [string]::IsNullOrWhiteSpace($parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
	[IO.File]::WriteAllText($Path, $Value, $utf8)
}

function Assert-True([bool] $Condition, [string] $Message) {
	if (-not $Condition) { throw "FAIL $Message" }
}

function Assert-Fails([scriptblock] $Action, [string] $Pattern) {
	$failed = $false
	try { & $Action } catch {
		$failed = $true
		if ($_.Exception.Message -notmatch $Pattern) { throw "FAIL expected '$Pattern', got '$($_.Exception.Message)'" }
	}
	if (-not $failed) { throw "FAIL expected failure matching '$Pattern'" }
}

$fixture = Join-Path ([IO.Path]::GetTempPath()) "arena-headless-template-test-$([Guid]::NewGuid().ToString('N'))"
try {
	$source = Join-Path $fixture 'server-template'
	$target = Join-Path $fixture 'runtime\server-template'
	$downloads = Join-Path $fixture 'runtime\downloads'
	$buildJar = Join-Path $fixture 'build\libs\arena-agents-0.2.0.jar'
	$carpetJar = Join-Path $fixture 'libs\fabric-carpet-26.1+v260402.jar'
	$installer = Join-Path $downloads 'fabric-installer.jar'
	$fabricApi = Join-Path $downloads 'fabric-api.jar'
	$fakeJava = Join-Path $fixture 'fake-java.ps1'
	$fakeJava21 = Join-Path $fixture 'fake-java-21.ps1'

	Write-TestText (Join-Path $source 'server.properties') "online-mode=false`nserver-ip=127.0.0.1`nserver-port=0`nenable-rcon=false`nlevel-name=headless-template-world`npause-when-empty-seconds=-1`n"
	Write-TestText (Join-Path $source 'README.md') "Generated binaries are installed at setup time.`n"
	Write-TestText $buildJar 'arena-mod-fixture'
	Write-TestText $carpetJar 'fabric-carpet-fixture'
	Write-TestText $installer 'fabric-installer-fixture'
	Write-TestText $fabricApi 'fabric-api-fixture'
	Write-TestText $fakeJava @'
if ($args -contains '-version') {
	Write-Output 'openjdk version "25.0.2"'
	exit 0
}
$directoryIndex = [Array]::IndexOf($args, '-dir')
if ($directoryIndex -lt 0 -or $directoryIndex + 1 -ge $args.Count) { throw 'missing installer directory' }
$directory = [string] $args[$directoryIndex + 1]
New-Item -ItemType Directory -Path (Join-Path $directory 'libraries\fixture') -Force | Out-Null
[IO.File]::WriteAllText((Join-Path $directory 'fabric-server-launch.jar'), 'fabric-launcher')
[IO.File]::WriteAllText((Join-Path $directory 'server.jar'), 'minecraft-server')
[IO.File]::WriteAllText((Join-Path $directory 'libraries\fixture\library.jar'), 'fabric-library')
[IO.File]::WriteAllText((Join-Path $directory 'installer-arguments.txt'), ($args -join ' '))
'@
	Write-TestText $fakeJava21 @'
if ($args -contains '-version') {
	Write-Output 'openjdk version "21.0.7"'
	exit 0
}
throw 'Java 21 candidate must not run the installer'
'@

	$arguments = @{
		ProjectRoot = $fixture
		TemplateSource = $source
		TemplatePath = $target
		JavaPath = $fakeJava21
		InstallerPath = $installer
		InstallerSha256 = (Get-FileHash -LiteralPath $installer -Algorithm SHA256).Hash
		FabricApiPath = $fabricApi
		FabricApiSha256 = (Get-FileHash -LiteralPath $fabricApi -Algorithm SHA256).Hash
		SkipBuild = $true
	}

	$previousJavaOverride = [Environment]::GetEnvironmentVariable('ARENA_HEADLESS_JAVA')
	[Environment]::SetEnvironmentVariable('ARENA_HEADLESS_JAVA', $fakeJava)
	try {
		Assert-Fails { & $scriptPath @arguments } 'AcceptMinecraftEula|EULA'
		Assert-True (-not (Test-Path -LiteralPath $target)) 'EULA rejection leaves no generated template'

		& $scriptPath @arguments -AcceptMinecraftEula | Out-Null
	} finally {
		[Environment]::SetEnvironmentVariable('ARENA_HEADLESS_JAVA', $previousJavaOverride)
	}

	foreach ($relative in @(
		'fabric-server-launch.jar', 'server.jar', 'libraries\fixture\library.jar',
		'mods\arena-agents-0.2.0.jar', 'mods\fabric-api.jar', 'mods\fabric-carpet-26.1+v260402.jar', 'server.properties',
		'eula.txt', 'template-manifest.json', 'README.md'
	)) {
		Assert-True (Test-Path -LiteralPath (Join-Path $target $relative) -PathType Leaf) "materialized template contains $relative"
	}
	Assert-True (-not (Test-Path -LiteralPath (Join-Path $target 'world'))) 'materialized template contains no world'
	Assert-True (-not (Test-Path -LiteralPath (Join-Path $target 'logs'))) 'materialized template contains no logs'
	Assert-True ((Get-Content -LiteralPath (Join-Path $target 'eula.txt') -Raw) -eq "eula=true`n") 'EULA acceptance is explicit and deterministic'
	$materializedProperties = Get-Content -LiteralPath (Join-Path $target 'server.properties') -Raw
	Assert-True ($materializedProperties -match '(?m)^server-ip=127\.0\.0\.1\r?$') 'materialized template binds offline gameplay to loopback'
	$installerArguments = Get-Content -LiteralPath (Join-Path $target 'installer-arguments.txt') -Raw
	Assert-True ($installerArguments -match 'server.+-mcversion 26\.1\.2.+-loader 0\.19\.3.+-downloadMinecraft') 'installer receives exact pinned versions and Minecraft download flag'
	$manifest = Get-Content -LiteralPath (Join-Path $target 'template-manifest.json') -Raw | ConvertFrom-Json
	Assert-True ($manifest.minecraftVersion -eq '26.1.2') 'manifest records Minecraft version'
	Assert-True ($manifest.fabricLoaderVersion -eq '0.19.3') 'manifest records Fabric loader version'
	Assert-True ($manifest.fabricInstallerSha256 -eq $arguments.InstallerSha256) 'manifest records verified installer hash'
	Assert-True ($manifest.fabricApiSha256 -eq $arguments.FabricApiSha256) 'manifest records verified Fabric API hash'
	Write-Output 'PASS isolated Fabric server template materialization and EULA guard'
} finally {
	if (Test-Path -LiteralPath $fixture) { Remove-Item -LiteralPath $fixture -Recurse -Force }
}
