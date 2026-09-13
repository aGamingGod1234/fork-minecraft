[CmdletBinding()]
param(
	[Parameter(Mandatory)] [string] $PackageRoot,
	[Parameter(Mandatory)] [string] $ServerTemplate,
	[string] $JavaPath,
	[ValidateRange(30, 300)] [int] $StartupTimeoutSeconds = 150,
	[ValidateRange(10, 120)] [int] $ShutdownTimeoutSeconds = 45
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'offline-server-policy.ps1')

function Resolve-Java([string] $Requested) {
	$candidates = [Collections.Generic.List[string]]::new()
	if (-not [string]::IsNullOrWhiteSpace($Requested)) { $candidates.Add($Requested) }
	if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) { $candidates.Add((Join-Path $env:JAVA_HOME 'bin\java.exe')) }
	$command = Get-Command java.exe -ErrorAction SilentlyContinue
	if ($null -ne $command) { $candidates.Add($command.Source) }
	foreach ($candidate in $candidates) {
		if (Test-Path -LiteralPath $candidate -PathType Leaf) { return (Resolve-Path -LiteralPath $candidate).Path }
	}
	throw 'Java 25 was not found for the distribution Fabric boot check.'
}

function Quote-Argument([string] $Value) {
	if ($Value -notmatch '[\s"]') { return $Value }
	return '"' + ($Value -replace '(\\*)"', '$1$1\"' -replace '(\\+)$', '$1$1') + '"'
}

function New-RandomSecret {
	$bytes = [byte[]]::new(48)
	$random = [Security.Cryptography.RandomNumberGenerator]::Create()
	try { $random.GetBytes($bytes) }
	finally { $random.Dispose() }
	return [Convert]::ToBase64String($bytes)
}

function Send-ProcessInput([Diagnostics.Process] $Process, [string] $Command) {
	$bytes = [Text.UTF8Encoding]::new($false).GetBytes($Command + "`n")
	$stream = $Process.StandardInput.BaseStream
	$stream.Write($bytes, 0, $bytes.Length)
	$stream.Flush()
}

function Receive-ProcessOutput(
	[Diagnostics.Process] $Process,
	[ref] $StandardOutputTask,
	[ref] $StandardErrorTask,
	[Collections.Concurrent.ConcurrentQueue[string]] $Lines
) {
	while ($null -ne $StandardOutputTask.Value -and $StandardOutputTask.Value.IsCompleted) {
		$line = $StandardOutputTask.Value.Result
		if ($null -eq $line) { $StandardOutputTask.Value = $null; break }
		$Lines.Enqueue($line)
		$StandardOutputTask.Value = $Process.StandardOutput.ReadLineAsync()
	}
	while ($null -ne $StandardErrorTask.Value -and $StandardErrorTask.Value.IsCompleted) {
		$line = $StandardErrorTask.Value.Result
		if ($null -eq $line) { $StandardErrorTask.Value = $null; break }
		$Lines.Enqueue($line)
		$StandardErrorTask.Value = $Process.StandardError.ReadLineAsync()
	}
}

$package = (Resolve-Path -LiteralPath $PackageRoot).Path
$template = (Resolve-Path -LiteralPath $ServerTemplate).Path
$java = Resolve-Java $JavaPath
$savedErrorPreference = $ErrorActionPreference
try {
	$ErrorActionPreference = 'Continue'
	$javaVersion = (& $java -version 2>&1 | Out-String)
} finally {
	$ErrorActionPreference = $savedErrorPreference
}
if ($LASTEXITCODE -ne 0 -or $javaVersion -notmatch '(?i)(?:openjdk|java) version "25(?:\.|\")') {
	throw "The distribution Fabric boot check requires Java 25. Detected: $($javaVersion.Trim())"
}
foreach ($required in @(
	(Join-Path $package 'mods'),
	(Join-Path $package 'distribution.properties'),
	(Join-Path $template 'fabric-server-launch.jar'),
	(Join-Path $template 'server.jar'),
	(Join-Path $template 'libraries'),
	(Join-Path $template 'server.properties')
)) {
	if (-not (Test-Path -LiteralPath $required)) { throw "Fabric boot prerequisite is missing: $required" }
}
$packageMods = @(Get-ChildItem -LiteralPath (Join-Path $package 'mods') -Filter '*.jar' -File)
if ($packageMods.Count -ne 5) { throw "The staged distribution must contain exactly five mod JARs; found $($packageMods.Count)." }

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("arena-distribution-boot-" + [Guid]::NewGuid().ToString('N'))
$server = Join-Path $testRoot 'server'
$process = $null
$processStarted = $false
$lines = [Collections.Concurrent.ConcurrentQueue[string]]::new()
$standardOutputTask = $null
$standardErrorTask = $null
try {
	New-Item -ItemType Directory -Path $server -Force | Out-Null
	foreach ($entry in Get-ChildItem -LiteralPath $template -Force) {
		if ($entry.Name -eq 'mods') { continue }
		Copy-Item -LiteralPath $entry.FullName -Destination $server -Recurse -Force
	}
	$serverMods = Join-Path $server 'mods'
	New-Item -ItemType Directory -Path $serverMods | Out-Null
	foreach ($mod in $packageMods) { Copy-Item -LiteralPath $mod.FullName -Destination (Join-Path $serverMods $mod.Name) -Force }
	$copiedMods = @(Get-ChildItem -LiteralPath $serverMods -Filter '*.jar' -File)
	if (@(Compare-Object @($packageMods.Name | Sort-Object) @($copiedMods.Name | Sort-Object)).Count -ne 0) {
		throw 'Temporary server mod names differ from the staged distribution.'
	}
	foreach ($mod in $packageMods) {
		if ((Get-FileHash -LiteralPath $mod.FullName -Algorithm SHA256).Hash -ne
				(Get-FileHash -LiteralPath (Join-Path $serverMods $mod.Name) -Algorithm SHA256).Hash) {
			throw "Temporary server mod hash differs from the staged distribution: $($mod.Name)"
		}
	}
	[IO.File]::WriteAllText((Join-Path $server 'eula.txt'), "eula=true`n", [Text.UTF8Encoding]::new($false))
	Assert-ArenaOfflineServerLoopback (Join-Path $server 'server.properties') -RequireOffline
	$bridgeSecret = Join-Path $testRoot 'bridge-secret.txt'
	$voiceSecret = Join-Path $testRoot 'voice-secret.txt'
	[IO.File]::WriteAllText($bridgeSecret, (New-RandomSecret), [Text.UTF8Encoding]::new($false))
	[IO.File]::WriteAllText($voiceSecret, (New-RandomSecret), [Text.UTF8Encoding]::new($false))

	$arguments = @(
		'-Xms512M', '-Xmx1G',
		"-Darenaagents.bridgeSecretFile=$bridgeSecret",
		"-Darenaagents.voiceSecretFile=$voiceSecret",
		"-Darenaagents.packageRoot=$package",
		'-jar', 'fabric-server-launch.jar', 'nogui'
	) | ForEach-Object { Quote-Argument $_ }
	$start = [Diagnostics.ProcessStartInfo]::new()
	$start.FileName = $java
	$start.Arguments = $arguments -join ' '
	$start.WorkingDirectory = $server
	$start.UseShellExecute = $false
	$start.CreateNoWindow = $true
	$start.RedirectStandardInput = $true
	$start.RedirectStandardOutput = $true
	$start.RedirectStandardError = $true
	$process = [Diagnostics.Process]::new()
	$process.StartInfo = $start
	$hostInputEncoding = [Console]::InputEncoding
	try {
		[Console]::InputEncoding = [Text.UTF8Encoding]::new($false)
		if (-not $process.Start()) { throw 'Fabric server process did not start.' }
	} finally {
		[Console]::InputEncoding = $hostInputEncoding
	}
	$processStarted = $true
	$standardOutputTask = $process.StandardOutput.ReadLineAsync()
	$standardErrorTask = $process.StandardError.ReadLineAsync()

	$deadline = [DateTime]::UtcNow.AddSeconds($StartupTimeoutSeconds)
	$ready = $false
	while ([DateTime]::UtcNow -lt $deadline) {
		Receive-ProcessOutput $process ([ref]$standardOutputTask) ([ref]$standardErrorTask) $lines
		$log = $lines.ToArray() -join "`n"
		if ($log -match '(?m)Done \([0-9.]+s\)! For help, type "help"') { $ready = $true; break }
		if ($log -match '(?im)(Mixin apply failed|MixinTransformerError|Could not execute entrypoint|Exception in server tick loop|A crash report has been saved|Encountered an unexpected exception)') {
			throw 'Fabric server reported a fatal startup error.'
		}
		if ($process.HasExited) { throw "Fabric server exited before readiness with code $($process.ExitCode)." }
		Start-Sleep -Milliseconds 200
	}
	if (-not $ready) { throw "Fabric server did not reach the Done marker within $StartupTimeoutSeconds seconds." }

	Send-ProcessInput $process 'codex status'
	$statusDeadline = [DateTime]::UtcNow.AddSeconds(15)
	$statusObserved = $false
	while ([DateTime]::UtcNow -lt $statusDeadline) {
		Receive-ProcessOutput $process ([ref]$standardOutputTask) ([ref]$standardErrorTask) $lines
		$log = $lines.ToArray() -join "`n"
		if ($log -match 'Coordinator generation state does not match|COORDINATOR_RUNTIME_INVALID') {
			throw 'The packaged coordinator runtime failed its generation integrity check.'
		}
		if ($log -match 'You have not created any agents yet\.') { $statusObserved = $true; break }
		if ($process.HasExited) { throw 'Fabric server exited before the status command completed.' }
		Start-Sleep -Milliseconds 100
	}
	if (-not $statusObserved) { throw 'The packaged mod loaded, but its codex status command did not answer.' }

	$coordinatorDeadline = [DateTime]::UtcNow.AddSeconds(20)
	$coordinatorStarted = $false
	while ([DateTime]::UtcNow -lt $coordinatorDeadline) {
		Receive-ProcessOutput $process ([ref]$standardOutputTask) ([ref]$standardErrorTask) $lines
		$log = $lines.ToArray() -join "`n"
		if ($log -match 'Coordinator generation state does not match|COORDINATOR_RUNTIME_INVALID') {
			throw 'The packaged coordinator runtime failed its generation integrity check.'
		}
		if ($log -match 'Started the Arena Agents coordinator') { $coordinatorStarted = $true; break }
		if ($process.HasExited) { throw 'Fabric server exited before the packaged coordinator started.' }
		Start-Sleep -Milliseconds 100
	}
	if (-not $coordinatorStarted) { throw 'The packaged coordinator did not start within 20 seconds.' }

	Send-ProcessInput $process 'stop'
	if (-not $process.WaitForExit($ShutdownTimeoutSeconds * 1000)) { throw "Fabric server did not stop within $ShutdownTimeoutSeconds seconds." }
	$process.WaitForExit()
	Receive-ProcessOutput $process ([ref]$standardOutputTask) ([ref]$standardErrorTask) $lines
	if ($process.ExitCode -ne 0) { throw "Fabric server stopped with exit code $($process.ExitCode)." }
	$finalLog = $lines.ToArray() -join "`n"
	if ($finalLog -match '(?im)(Mixin apply failed|MixinTransformerError|Exception in server tick loop|A crash report has been saved)') {
		throw 'Fabric server log contains a fatal mixin or crash marker.'
	}
	Write-Host "PASS: exact staged mod set reached Done, answered codex status, started its coordinator, and stopped cleanly ($($packageMods.Name -join ', '))"
} catch {
	$failure = $_
	$tail = @($lines.ToArray() | Select-Object -Last 160) -join "`n"
	if (-not [string]::IsNullOrWhiteSpace($tail)) { Write-Host "Fabric boot log tail:`n$tail" }
	throw $failure
} finally {
	if ($null -ne $process) {
		if ($processStarted -and -not $process.HasExited) {
			try { Send-ProcessInput $process 'stop' } catch { }
			if (-not $process.WaitForExit(5000)) {
				try {
					& taskkill.exe /PID $process.Id /T /F 2>&1 | Out-Null
					if (-not $process.WaitForExit(5000)) { $process.Kill() }
				} catch { try { $process.Kill() } catch { } }
			}
		}
		$process.Dispose()
	}
	if (Test-Path -LiteralPath $testRoot) {
		$resolvedTestRoot = (Resolve-Path -LiteralPath $testRoot).Path
		$tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
		if (-not $resolvedTestRoot.StartsWith($tempRoot, [StringComparison]::OrdinalIgnoreCase)) { throw "Refusing to remove non-temporary boot directory: $resolvedTestRoot" }
		Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
	}
}
