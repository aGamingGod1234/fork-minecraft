[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'offline-server-policy.ps1')
$utf8 = [Text.UTF8Encoding]::new($false)

function Write-Properties([string] $Path, [string] $Value) {
	[IO.File]::WriteAllText($Path, $Value, $utf8)
}

function Assert-Fails([scriptblock] $Action, [string] $Pattern) {
	$failed = $false
	try { & $Action } catch {
		$failed = $true
		if ($_.Exception.Message -notmatch $Pattern) { throw "Expected '$Pattern', got '$($_.Exception.Message)'" }
	}
	if (-not $failed) { throw "Expected failure matching '$Pattern'" }
}

$fixture = Join-Path ([IO.Path]::GetTempPath()) "arena-offline-policy-$([Guid]::NewGuid().ToString('N'))"
try {
	New-Item -ItemType Directory -Path $fixture | Out-Null
	$properties = Join-Path $fixture 'server.properties'

	Write-Properties $properties "online-mode=false`n"
	Assert-Fails { Assert-ArenaOfflineServerLoopback $properties -RequireOffline } 'server-ip=127\.0\.0\.1'

	Write-Properties $properties "online-mode=false`nserver-ip=0.0.0.0`n"
	Assert-Fails { Assert-ArenaOfflineServerLoopback $properties -RequireOffline } 'server-ip=127\.0\.0\.1'

	Write-Properties $properties "online-mode=false`nserver-ip=127.0.0.1`nserver-ip=0.0.0.0`n"
	Assert-Fails { Assert-ArenaOfflineServerLoopback $properties -RequireOffline } 'exactly one'

	Write-Properties $properties "online-mode=false`nserver-ip=127.0.0.1`nserver\-ip=0.0.0.0`n"
	Assert-Fails { Assert-ArenaOfflineServerLoopback $properties -RequireOffline } 'exactly one'

	Write-Properties $properties "online-mode=false`nserver-ip=127.0.0.1`nserver\u002dip:0.0.0.0`n"
	Assert-Fails { Assert-ArenaOfflineServerLoopback $properties -RequireOffline } 'exactly one'

	Write-Properties $properties "online-mode=false`nserver-ip=127.0.0.1`nserver\`n-ip=0.0.0.0`n"
	Assert-Fails { Assert-ArenaOfflineServerLoopback $properties -RequireOffline } 'exactly one'

	Write-Properties $properties "online-mode=true`nonline\-mode=false`nserver-ip=0.0.0.0`n"
	Assert-Fails { Assert-ArenaServerMode $properties 'true' } 'exactly one'

	Write-Properties $properties "online-mode=false`nserver-ip=127.0.0.1`n"
	Assert-ArenaOfflineServerLoopback $properties -RequireOffline

	Write-Properties $properties "online-mode=true`n"
	Assert-ArenaServerMode $properties 'true'
	Assert-ArenaOfflineServerLoopback $properties

	$java = Join-Path $fixture 'runtime\toolchains\temurin-25\jdk-25.0.3+9\bin\java.exe'
	New-Item -ItemType Directory -Path (Split-Path -Parent $java) -Force | Out-Null
	Copy-Item -LiteralPath $env:ComSpec -Destination $java
	foreach ($serverName in @('server', 'server-offline-smoke')) {
		$server = Join-Path $fixture "runtime\$serverName"
		New-Item -ItemType Directory -Path $server -Force | Out-Null
		Write-Properties (Join-Path $server 'fabric-server-launch.jar') 'fixture'
	}

	$offlineProperties = Join-Path $fixture 'runtime\server-offline-smoke\server.properties'
	Write-Properties $offlineProperties "online-mode=false`nserver-ip=0.0.0.0`n"
	Assert-Fails { & (Join-Path $PSScriptRoot 'start-test-server.ps1') -ProjectRoot $fixture -OfflineSmoke } 'server-ip=127\.0\.0\.1'
	Write-Properties $offlineProperties "online-mode=false`nserver-ip=127.0.0.1`n"
	& (Join-Path $PSScriptRoot 'start-test-server.ps1') -ProjectRoot $fixture -OfflineSmoke | Out-Null

	$onlineProperties = Join-Path $fixture 'runtime\server\server.properties'
	Write-Properties $onlineProperties "online-mode=true`n"
	& (Join-Path $PSScriptRoot 'start-test-server.ps1') -ProjectRoot $fixture | Out-Null

	New-Item -ItemType Directory -Path (Join-Path $fixture 'build\libs'), (Join-Path $fixture 'coordinator\src'), (Join-Path $fixture 'coordinator\config') -Force | Out-Null
	Write-Properties (Join-Path $fixture 'build\libs\arena-agents-0.2.0.jar') 'fixture'
	Write-Properties (Join-Path $fixture 'coordinator\src\dynamic-main.mjs') 'fixture'
	Write-Properties (Join-Path $fixture 'coordinator\config\dynamic-agents.json') '{}'
	Write-Properties (Join-Path $fixture 'runtime\bridge-secret.txt') ('x' * 48)
	Write-Properties $onlineProperties "online-mode=false`nserver-ip=0.0.0.0`n"
	Assert-Fails { & (Join-Path $PSScriptRoot 'run-antigravity-headless-smoke.ps1') -ProjectRoot $fixture } 'server-ip=127\.0\.0\.1'

	Write-Output 'PASS offline Minecraft launchers reject unsafe listeners before process startup while online mode remains unchanged'
} finally {
	if (Test-Path -LiteralPath $fixture) { Remove-Item -LiteralPath $fixture -Recurse -Force }
}
