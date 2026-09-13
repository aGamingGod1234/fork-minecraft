[CmdletBinding()]
param(
	[string] $ProjectRoot,
	[string] $ServerTemplate,
	[string] $JavaPath,
	[int] $StartupTimeoutSeconds = 180,
	[switch] $KeepArtifacts
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$PollMilliseconds = 200
$ManagedName = 'DeathSmoke'
$ManagedPlayerName = $ManagedName
$OrdinaryName = 'CarpetSmoke'

function Read-SharedText([string] $Path) {
	if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return '' }
	$stream = [IO.FileStream]::new($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read,
		[IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete)
	try {
		$reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8, $true)
		try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
	} finally { $stream.Dispose() }
}

function Wait-Until([scriptblock] $Condition, [int] $TimeoutSeconds, [string] $Failure) {
	$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
	while ([DateTime]::UtcNow -lt $deadline) {
		if (& $Condition) { return }
		Start-Sleep -Milliseconds $PollMilliseconds
	}
	throw $Failure
}

function Reserve-Port([int[]] $Excluded = @()) {
	for ($attempt = 0; $attempt -lt 30; $attempt += 1) {
		$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
		try {
			$listener.Start()
			$port = ([Net.IPEndPoint] $listener.LocalEndpoint).Port
			if ($Excluded -notcontains $port) { return $port }
		} finally { $listener.Stop() }
	}
	throw 'Could not reserve a local test port'
}

function Send-Command([Diagnostics.Process] $Process, [string] $Command) {
	$Process.StandardInput.WriteLine($Command)
	$Process.StandardInput.Flush()
}

function Quote-Argument([string] $Value) {
	return '"' + $Value.Replace('"', '\"') + '"'
}

function Require-NoSessionMessages([string] $Text, [string] $PlayerName, [string] $Label) {
	$leaveCount = ([regex]::Matches($Text, [regex]::Escape("$PlayerName left the game"))).Count
	$joinCount = ([regex]::Matches($Text, [regex]::Escape("$PlayerName joined the game"))).Count
	if ($leaveCount -ne 0 -or $joinCount -ne 0) {
		throw "$Label changed the player session: leaves=$leaveCount joins=$joinCount"
	}
}

function Require-ContainedRunPath([string] $Path, [string] $RuntimeRoot) {
	$full = [IO.Path]::GetFullPath($Path)
	$root = [IO.Path]::GetFullPath($RuntimeRoot).TrimEnd('\') + '\'
	$underRoot = $full.StartsWith($root, [StringComparison]::OrdinalIgnoreCase)
	$safeName = ([IO.Path]::GetFileName($full)).StartsWith('managed-death-smoke-', [StringComparison]::Ordinal)
	if (-not $underRoot -or -not $safeName) {
		throw "Unsafe managed-death smoke path: $full"
	}
}

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
$project = [IO.Path]::GetFullPath($ProjectRoot)
$runtime = Join-Path $project 'runtime'
if ([string]::IsNullOrWhiteSpace($ServerTemplate)) { $ServerTemplate = Join-Path $runtime 'server-template' }
if ([string]::IsNullOrWhiteSpace($JavaPath)) {
	$JavaPath = Join-Path $runtime 'toolchains\temurin-25\jdk-25.0.3+9\bin\java.exe'
}
$template = [IO.Path]::GetFullPath($ServerTemplate)
$java = [IO.Path]::GetFullPath($JavaPath)
$builtJar = Join-Path $project 'build\libs\arena-agents-0.2.0.jar'
foreach ($required in @($template, $java, $builtJar)) {
	if (-not (Test-Path -LiteralPath $required)) { throw "Missing managed-death smoke prerequisite: $required" }
}

$run = Join-Path $runtime ("managed-death-smoke-" + [Guid]::NewGuid().ToString('N'))
Require-ContainedRunPath $run $runtime
$server = Join-Path $run 'server'
$stdout = Join-Path $run 'server.stdout.log'
$stderr = Join-Path $run 'server.stderr.log'
$process = $null
$failure = $null

try {
	New-Item -ItemType Directory -Path $run | Out-Null
	Copy-Item -LiteralPath $template -Destination $server -Recurse
	Copy-Item -LiteralPath $builtJar -Destination (Join-Path $server 'mods\arena-agents-0.2.0.jar') -Force
	$serverPort = Reserve-Port
	$bridgePort = Reserve-Port @($serverPort)
	$propertiesPath = Join-Path $server 'server.properties'
	$properties = Get-Content -LiteralPath $propertiesPath -Raw
	foreach ($entry in @{
		'server-port' = $serverPort
		'server-ip' = '127.0.0.1'
		'online-mode' = 'false'
		'enforce-secure-profile' = 'false'
		'spawn-protection' = '0'
	}.GetEnumerator()) {
		$line = "$($entry.Key)=$($entry.Value)"
		if ($properties -match "(?m)^$([regex]::Escape($entry.Key))=.*$") {
			$properties = [regex]::Replace($properties, "(?m)^$([regex]::Escape($entry.Key))=.*$", $line)
		} else { $properties += "`n$line" }
	}
	[IO.File]::WriteAllText($propertiesPath, $properties, [Text.UTF8Encoding]::new($false))
	$secretPath = Join-Path $run 'bridge-secret.txt'
	$secretBytes = [byte[]]::new(32)
	$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
	try { $rng.GetBytes($secretBytes) } finally { $rng.Dispose() }
	$secret = ([BitConverter]::ToString($secretBytes) -replace '-', '').ToLowerInvariant()
	[IO.File]::WriteAllText($secretPath, $secret, [Text.UTF8Encoding]::new($false))

	$start = [Diagnostics.ProcessStartInfo]::new()
	$start.FileName = $java
	$start.WorkingDirectory = $server
	$start.UseShellExecute = $false
	$start.CreateNoWindow = $true
	$start.RedirectStandardInput = $true
	$start.RedirectStandardOutput = $true
	$start.RedirectStandardError = $true
	$start.Arguments = @(
		(Quote-Argument "-Darenaagents.bridgeSecretFile=$secretPath"),
		(Quote-Argument "-Darenaagents.bridgePort=$bridgePort"),
		'-Xms512M', '-Xmx2G', '-jar',
		(Quote-Argument (Join-Path $server 'fabric-server-launch.jar')),
		'nogui'
	) -join ' '
	$process = [Diagnostics.Process]::new()
	$process.StartInfo = $start
	if (-not $process.Start()) { throw 'Could not start the isolated Fabric server' }
	$stdoutTask = $process.StandardOutput.ReadToEndAsync()
	$stderrTask = $process.StandardError.ReadToEndAsync()
	$latestLog = Join-Path $server 'logs\latest.log'
	Wait-Until { (Read-SharedText $latestLog).Contains('Done (') } $StartupTimeoutSeconds 'Isolated Fabric server did not start'

	Send-Command $process 'execute in minecraft:overworld run forceload add 0 0'
	Start-Sleep -Seconds 5
	Send-Command $process 'execute in minecraft:overworld run fill 0 70 0 24 70 24 minecraft:obsidian'
	Send-Command $process 'execute in minecraft:overworld run fill 0 71 0 24 74 24 minecraft:air'
	Send-Command $process "execute in minecraft:overworld positioned 8 71 8 run codex summon gpt-5.6-sol high $ManagedName"
	Wait-Until { (Read-SharedText $latestLog) -match "(?i)$([regex]::Escape($ManagedName)) joined the game" } 60 'Managed fake player did not join'
	$joinedMatch = [regex]::Matches(
		(Read-SharedText $latestLog),
		"(?im)(?<name>$([regex]::Escape($ManagedName))) joined the game"
	) | Select-Object -Last 1
	$ManagedPlayerName = $joinedMatch.Groups['name'].Value
	Send-Command $process "codex stop $ManagedName"
	Start-Sleep -Seconds 1

	$managedOffset = (Read-SharedText $latestLog).Length
	Send-Command $process 'execute in minecraft:overworld run fill 7 71 7 9 72 9 minecraft:lava'
	Wait-Until {
		$slice = (Read-SharedText $latestLog).Substring($managedOffset)
		$slice -match "(?i)$([regex]::Escape($ManagedPlayerName)) (tried to swim in lava|burned|went up in flames)"
	} 45 'Natural lava damage did not kill the managed fake player'
	Start-Sleep -Seconds 10
	$managedSlice = (Read-SharedText $latestLog).Substring($managedOffset)
	Require-NoSessionMessages $managedSlice $ManagedPlayerName 'Managed death/respawn'
	Send-Command $process 'execute in minecraft:overworld run fill 7 71 7 9 72 9 minecraft:air'
	Send-Command $process "msg $ManagedPlayerName session_continuity_check"
	Send-Command $process "tp $ManagedPlayerName 12 71 12"
	Start-Sleep -Seconds 2
	$managedAfterControl = (Read-SharedText $latestLog).Substring($managedOffset)
	if ($managedAfterControl -match '(?i)(no player was found|player not found|unknown player)') {
		throw 'Managed fake player stopped being a /msg or control target after respawn'
	}

	Send-Command $process "execute in minecraft:overworld run player $OrdinaryName spawn at 18 71 18"
	Wait-Until { (Read-SharedText $latestLog).Contains("$OrdinaryName joined the game") } 30 'Ordinary Carpet fake player did not join'
	$ordinaryOffset = (Read-SharedText $latestLog).Length
	Send-Command $process "kill $OrdinaryName"
	Wait-Until { (Read-SharedText $latestLog).Substring($ordinaryOffset).Contains("$OrdinaryName left the game") } 45 `
		'Ordinary Carpet fake player did not retain its stock death disconnect'
	Start-Sleep -Seconds 2
	$ordinarySlice = (Read-SharedText $latestLog).Substring($ordinaryOffset)
	$ordinaryLeaves = ([regex]::Matches($ordinarySlice, [regex]::Escape("$OrdinaryName left the game"))).Count
	if ($ordinaryLeaves -ne 1) { throw "Ordinary Carpet death emitted $ordinaryLeaves leave messages instead of one" }
	if ($ordinarySlice.Contains("$OrdinaryName joined the game")) { throw 'Ordinary Carpet bot was incorrectly auto-respawned' }

	Write-Output 'PASS managed natural lava death kept one session for 10 seconds, stayed messageable/controllable, and ordinary Carpet death remained stock.'
} catch {
	$failure = $_
	throw
} finally {
	if ($null -ne $process -and -not $process.HasExited) {
		try { Send-Command $process 'stop'; $process.WaitForExit(15000) | Out-Null } catch { }
		if (-not $process.HasExited) { Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue }
	}
	if (-not $KeepArtifacts -and $null -eq $failure -and (Test-Path -LiteralPath $run)) {
		Require-ContainedRunPath $run $runtime
		Remove-Item -LiteralPath $run -Recurse -Force
	} elseif (Test-Path -LiteralPath $run) {
		Write-Warning "Managed-death smoke artifacts retained at $run"
	}
}
