[CmdletBinding()]
param(
	[Parameter(Mandatory = $true)] [string] $ProjectRoot,
	[string] $MatrixPath,
	[string] $ScenarioId,
	[string] $ServerTemplate,
	[switch] $CapabilityProbe,
	[switch] $RequireAll,
	[switch] $KeepArtifacts
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'offline-server-policy.ps1')
. (Join-Path $PSScriptRoot 'project-metadata.ps1')

$PollMilliseconds = 250
$StartupTimeoutSeconds = 120
$CleanupTimeoutSeconds = 30
$RunnerGraceSeconds = 30
$GracefulStopTimeoutMilliseconds = 10000
$OutputDrainTimeoutMilliseconds = 1000
$configuredStartupTimeout = 0
$configuredCleanupTimeout = 0
$configuredRunnerGrace = 0
$configuredGracefulStopTimeout = 0
$configuredOutputDrainTimeout = 0
if ([int]::TryParse([Environment]::GetEnvironmentVariable('ARENA_HEADLESS_STARTUP_TIMEOUT_SECONDS'), [ref] $configuredStartupTimeout) -and $configuredStartupTimeout -gt 0) { $StartupTimeoutSeconds = $configuredStartupTimeout }
if ([int]::TryParse([Environment]::GetEnvironmentVariable('ARENA_HEADLESS_CLEANUP_TIMEOUT_SECONDS'), [ref] $configuredCleanupTimeout) -and $configuredCleanupTimeout -gt 0) { $CleanupTimeoutSeconds = $configuredCleanupTimeout }
if ([int]::TryParse([Environment]::GetEnvironmentVariable('ARENA_HEADLESS_RUNNER_GRACE_SECONDS'), [ref] $configuredRunnerGrace) -and $configuredRunnerGrace -gt 0) { $RunnerGraceSeconds = $configuredRunnerGrace }
if ([int]::TryParse([Environment]::GetEnvironmentVariable('ARENA_HEADLESS_GRACEFUL_STOP_TIMEOUT_SECONDS'), [ref] $configuredGracefulStopTimeout) -and $configuredGracefulStopTimeout -gt 0) { $GracefulStopTimeoutMilliseconds = $configuredGracefulStopTimeout * 1000 }
if ([int]::TryParse([Environment]::GetEnvironmentVariable('ARENA_HEADLESS_OUTPUT_DRAIN_TIMEOUT_MILLISECONDS'), [ref] $configuredOutputDrainTimeout) -and $configuredOutputDrainTimeout -gt 0) { $OutputDrainTimeoutMilliseconds = $configuredOutputDrainTimeout }
$MaxPortAttempts = 30
$StartupBindRetries = 2
$CoordinatorBindRetries = 2
$MaxSelectedScenarios = 24
$MaxManifestBytes = 65536
$MaxMatrixReportBytes = 262144
$MaxDiagnosticText = 4096

function Quote-Argument([string] $Value) {
	return '"' + $Value.Replace('"', '\"') + '"'
}

function Read-Text([string] $Path) {
	if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return '' }
	try {
		$share = [IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete
		$stream = [IO.FileStream]::new($Path, [IO.FileMode]::Open, [IO.FileAccess]::Read, $share)
		try {
			$reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8, $true, 4096, $true)
			try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
		} finally {
			$stream.Dispose()
		}
	} catch [IO.IOException] {
		return ''
	}
}

function Test-BindFailure([string] $ServerLogPath, [string] $ServerStderrPath) {
	$text = "$(Read-Text $ServerLogPath)`n$(Read-Text $ServerStderrPath)"
	return $text -match '(?i)(address already in use|failed to bind|could not bind|bind.+failed|port.+already)'
}

function Test-CoordinatorReady([string] $ProtocolAuditPath, $Scenario) {
	if (-not (Test-Path -LiteralPath $ProtocolAuditPath -PathType Leaf)) { return $false }
	$scenarioServiceTier = 'priority'
	if ($null -ne $Scenario.PSObject.Properties['serviceTier'] -and $null -ne $Scenario.serviceTier) {
		$scenarioServiceTier = [string] $Scenario.serviceTier
	}
	$authenticated = $false
	$profileReady = $false
	$providerSettled = $false
	$providerCatalogReady = $false
	foreach ($line in @(Get-Content -LiteralPath $ProtocolAuditPath -ErrorAction SilentlyContinue)) {
		if ([string]::IsNullOrWhiteSpace($line)) { continue }
		try { $row = $line | ConvertFrom-Json } catch { continue }
		$envelope = $row.envelope
		if ($null -eq $envelope) { continue }
		$helloAcknowledged = [string] $row.direction -eq 'server_to_coordinator' `
				-and [string] $envelope.type -eq 'hello_ack' `
				-and $envelope.payload.authenticated -eq $true
		if ($helloAcknowledged) {
			$authenticated = $true
		}
		if ([string] $row.direction -eq 'coordinator_to_server' -and [string] $envelope.type -eq 'coordinator_status') {
			$providerComponent = "provider:$([string] $Scenario.provider)"
			$providerSettled = @($envelope.payload.components | Where-Object {
				[string] $_.component -eq $providerComponent -and [string] $_.state -in @('ready', 'degraded')
			}).Count -gt 0
		}
		if ([string] $row.direction -ne 'coordinator_to_server' -or [string] $envelope.type -ne 'catalog_snapshot') { continue }
		foreach ($model in @($envelope.payload.models)) {
			if ([string] $model.provider -eq [string] $Scenario.provider) { $providerCatalogReady = $true }
			$modelId = if ($null -ne $model.model) { [string] $model.model } else { [string] $model.id }
			$efforts = @($model.reasoningEfforts | ForEach-Object {
				if ($_ -is [string]) { [string] $_ } elseif ($null -ne $_.reasoningEffort) { [string] $_.reasoningEffort }
			})
			$tiers = @($model.serviceTiers | ForEach-Object {
				if ($_ -is [string]) { [string] $_ } elseif ($null -ne $_.id) { [string] $_.id }
			})
			$tierReady = $tiers.Count -eq 0 -or $tiers -contains $scenarioServiceTier
			$matchesProfile = [string] $model.provider -eq [string] $Scenario.provider `
					-and $modelId -eq [string] $Scenario.model `
					-and $efforts -contains [string] $Scenario.reasoningEffort `
					-and $tierReady
			if ($matchesProfile) {
				$profileReady = $true
				break
			}
		}
	}
	return $authenticated -and ($profileReady -or ($providerSettled -and $providerCatalogReady))
}

function Protect-LocalFile([string] $Path) {
	# Do not inherit a broad ACL for generated credentials or their server config.
	$grant = "$($env:USERNAME):(R,W)"
	& icacls.exe $Path /inheritance:r /grant:r $grant | Out-Null
	if ($LASTEXITCODE -ne 0) { throw "Could not restrict permissions on generated secret: $Path" }
}

function Write-PrivateText([string] $Path, [string] $Value) {
	$parent = Split-Path -Parent $Path
	New-Item -ItemType Directory -Path $parent -Force | Out-Null
	[IO.File]::WriteAllText($Path, $Value, [Text.UTF8Encoding]::new($false))
	Protect-LocalFile $Path
}

function Wait-Condition([scriptblock] $Condition, [int] $TimeoutSeconds, [string] $FailureMessage) {
	$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
	while ([DateTime]::UtcNow -lt $deadline) {
		if (& $Condition) { return }
		Start-Sleep -Milliseconds $PollMilliseconds
	}
	throw $FailureMessage
}

function Get-ProcessCommand([string] $Name) {
	$command = Get-Command $Name -ErrorAction SilentlyContinue
	if ($null -eq $command) { return $null }
	return $command.Source
}

function Test-Port([int] $Port) {
	$connections = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
	return $null -ne ($connections | Select-Object -First 1)
}

function Reserve-FreePort([int] $Preferred = 0, [int[]] $Exclude = @()) {
	if ($Preferred -gt 0) {
		if ($Exclude -contains $Preferred) { throw "Configured ports must be distinct; port $Preferred was requested more than once" }
		if (Test-Port $Preferred) { throw "Required port $Preferred is already occupied" }
		return $Preferred
	}
	for ($attempt = 0; $attempt -lt $MaxPortAttempts; $attempt += 1) {
		$listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
		try {
			$listener.Start()
			$port = ([Net.IPEndPoint] $listener.LocalEndpoint).Port
			if ($Exclude -contains $port) { continue }
			return $port
		} finally {
			$listener.Stop()
		}
	}
	throw 'Could not allocate a free local TCP port'
}

function Get-ConfiguredPort([string] $EnvironmentName) {
	$value = [Environment]::GetEnvironmentVariable($EnvironmentName)
	if ([string]::IsNullOrWhiteSpace($value)) { return 0 }
	$port = 0
	if (-not [int]::TryParse($value, [ref] $port) -or $port -lt 1 -or $port -gt 65535) {
		throw "$EnvironmentName must be a valid TCP port"
	}
	return $port
}

function ConvertTo-ProcessCreationKey($Value) {
	if ($null -eq $Value) { return $null }
	if ($Value -is [DateTime]) {
		$ticks = $Value.ToUniversalTime().Ticks
		$millisecondTicks = $ticks - ($ticks % [TimeSpan]::TicksPerMillisecond)
		return $millisecondTicks.ToString([Globalization.CultureInfo]::InvariantCulture)
	}
	$valueText = [string] $Value
	if ([string]::IsNullOrWhiteSpace($valueText)) { return $null }
	return $valueText
}

function Get-ProcessSnapshot() {
	$byId = @{}
	foreach ($process in @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue)) {
		$id = [int] $process.ProcessId
		$creationDate = ConvertTo-ProcessCreationKey $process.CreationDate
		if ($id -le 0 -or $null -eq $creationDate) { continue }
		$byId[$id] = [pscustomobject]@{
			ProcessId = $id
			ParentProcessId = [int] $process.ParentProcessId
			CreationDate = $creationDate
			WorkingSetSize = if ($null -eq $process.WorkingSetSize) { 0L } else { [long] $process.WorkingSetSize }
		}
	}
	return $byId
}

function Test-ProcessIdentityMatch($Identity, $Process) {
	if ($null -eq $Identity -or $null -eq $Process) { return $false }
	$expectedCreation = ConvertTo-ProcessCreationKey $Identity.CreationDate
	$actualCreation = ConvertTo-ProcessCreationKey $Process.CreationDate
	return [int] $Identity.ProcessId -eq [int] $Process.ProcessId `
		-and [int] $Identity.ParentProcessId -eq [int] $Process.ParentProcessId `
		-and $null -ne $expectedCreation -and $expectedCreation -eq $actualCreation
}

function Test-ChildCreationAfterParent($Parent, $Child) {
	[long] $parentTicks = 0
	[long] $childTicks = 0
	if (-not [long]::TryParse((ConvertTo-ProcessCreationKey $Parent.CreationDate), [ref] $parentTicks)) { return $false }
	if (-not [long]::TryParse((ConvertTo-ProcessCreationKey $Child.CreationDate), [ref] $childTicks)) { return $false }
	return $childTicks -ge $parentTicks
}

function Add-ProcessTreeSnapshot([System.Collections.Generic.List[object]] $ProcessIdentities, $ProcessIdentity, $Processes = $null) {
	$processes = if ($null -eq $Processes) { Get-ProcessSnapshot } else { $Processes }
	$rootId = if ($ProcessIdentity -is [int]) { [int] $ProcessIdentity } else { [int] $ProcessIdentity.ProcessId }
	if ($rootId -le 0) { return }
	$expectedRoot = if ($ProcessIdentity -is [int]) { $null } else { $ProcessIdentity }
	$existingRoot = @($ProcessIdentities.ToArray() | Where-Object { [int] $_.ProcessId -eq $rootId } | Select-Object -First 1)

	$childrenByParent = @{}
	foreach ($process in $processes.Values) {
		$parentId = [int] $process.ParentProcessId
		if (-not $childrenByParent.ContainsKey($parentId)) { $childrenByParent[$parentId] = [System.Collections.Generic.List[object]]::new() }
		$childrenByParent[$parentId].Add($process)
	}
	$pending = [System.Collections.Generic.Queue[object]]::new()
	$visited = [System.Collections.Generic.HashSet[int]]::new()
	if ($processes.ContainsKey($rootId)) {
		if ($null -ne $expectedRoot -and -not (Test-ProcessIdentityMatch $expectedRoot $processes[$rootId])) { return }
		if ($existingRoot.Count -gt 0 -and -not (Test-ProcessIdentityMatch $existingRoot[0] $processes[$rootId])) { return }
		$pending.Enqueue($processes[$rootId])
	} else {
		if ($null -eq $expectedRoot -or $existingRoot.Count -eq 0 -or -not (Test-ProcessIdentityMatch $expectedRoot $existingRoot[0])) { return }
		if ($childrenByParent.ContainsKey($rootId)) {
			foreach ($child in $childrenByParent[$rootId]) {
				if (Test-ChildCreationAfterParent $expectedRoot $child) { $pending.Enqueue($child) }
			}
		}
	}
	while ($pending.Count -gt 0) {
		$current = $pending.Dequeue()
		$currentId = [int] $current.ProcessId
		if (-not $visited.Add($currentId)) { continue }
		$existing = @($ProcessIdentities.ToArray() | Where-Object { [int] $_.ProcessId -eq $currentId } | Select-Object -First 1)
		if ($existing.Count -gt 0 -and -not (Test-ProcessIdentityMatch $existing[0] $current)) { continue }
		if ($existing.Count -eq 0) {
			$ProcessIdentities.Add([pscustomobject]@{ ProcessId = $currentId; ParentProcessId = [int] $current.ParentProcessId; CreationDate = [string] $current.CreationDate })
		}
		if ($childrenByParent.ContainsKey($currentId)) {
			foreach ($child in $childrenByParent[$currentId]) {
				if (Test-ChildCreationAfterParent $current $child) { $pending.Enqueue($child) }
			}
		}
	}
}

function Get-TrackedResourceSnapshot([System.Collections.Generic.List[object]] $ProcessIdentities, $Processes = $null) {
	$processes = if ($null -eq $Processes) { Get-ProcessSnapshot } else { $Processes }
	$liveCount = 0
	[long] $rssBytes = 0
	foreach ($identity in @($ProcessIdentities.ToArray())) {
		$id = [int] $identity.ProcessId
		if (-not $processes.ContainsKey($id) -or -not (Test-ProcessIdentityMatch $identity $processes[$id])) { continue }
		$liveCount += 1
		$rssBytes += [long] $processes[$id].WorkingSetSize
	}
	return [pscustomobject]@{ processCount = $liveCount; rssBytes = $rssBytes }
}

function Add-TrackedProcessIdentity([System.Collections.Generic.List[object]] $ProcessIdentities, $Identity) {
	if ($null -eq $Identity) { return }
	$id = [int] $Identity.ProcessId
	if ($id -le 0) { return }
	$existing = @($ProcessIdentities.ToArray() | Where-Object { [int] $_.ProcessId -eq $id } | Select-Object -First 1)
	if ($existing.Count -eq 0) {
		$ProcessIdentities.Add([pscustomobject]@{
			ProcessId = $id
			ParentProcessId = [int] $Identity.ParentProcessId
			CreationDate = [string] $Identity.CreationDate
		})
	}
}

function Measure-RunnerResourcesUntilExit(
	$RunnerHandle,
	[object[]] $TrackedHandles,
	[System.Collections.Generic.List[object]] $ProcessIdentities,
	[DateTime] $Deadline
) {
	$initialRoots = [System.Collections.Generic.HashSet[int]]::new()
	[long] $initialRssBytes = 0
	foreach ($handle in $TrackedHandles) {
		if ($null -eq $handle -or $null -eq $handle.Process -or -not $initialRoots.Add([int] $handle.Process.Id)) { continue }
		# Capture each launched root before the first CIM sample. A fast root can
		# exit before sampling, while its descendants remain live and must still be
		# included in resource accounting and cleanup.
		Add-TrackedProcessIdentity $ProcessIdentities $handle.Identity
		if ($null -ne $handle.InitialRssBytes) { $initialRssBytes += [long] $handle.InitialRssBytes }
	}
	$peakProcessCount = $initialRoots.Count
	[long] $peakRssBytes = $initialRssBytes
	while ($true) {
		$directProcessCount = 0
		[long] $directRssBytes = 0
		foreach ($handle in $TrackedHandles) {
			if ($null -eq $handle -or $null -eq $handle.Process) { continue }
			try {
				$handle.Process.Refresh()
				if (-not $handle.Process.HasExited) {
					$directProcessCount += 1
					$directRssBytes += [long] $handle.Process.WorkingSet64
				}
			} catch {}
		}
		$peakProcessCount = [Math]::Max($peakProcessCount, $directProcessCount)
		$peakRssBytes = [Math]::Max($peakRssBytes, $directRssBytes)
		$runnerExitedBeforeSample = $RunnerHandle.Process.HasExited
		$processes = Get-ProcessSnapshot
		foreach ($handle in $TrackedHandles) {
			if ($null -ne $handle -and $null -ne $handle.Process) { Add-ProcessTreeSnapshot $ProcessIdentities $(if ($null -ne $handle.Identity) { $handle.Identity } else { $handle.Process.Id }) $processes }
		}
		$sample = Get-TrackedResourceSnapshot $ProcessIdentities $processes
		$peakProcessCount = [Math]::Max($peakProcessCount, [int] $sample.processCount)
		$peakRssBytes = [Math]::Max($peakRssBytes, [long] $sample.rssBytes)
		$runnerExited = $runnerExitedBeforeSample
		if (-not $runnerExited) { $runnerExited = $RunnerHandle.Process.WaitForExit($PollMilliseconds) }
		if ($runnerExited) {
			if (-not $runnerExitedBeforeSample) {
				$finalProcesses = Get-ProcessSnapshot
				foreach ($handle in $TrackedHandles) {
					if ($null -ne $handle -and $null -ne $handle.Process) { Add-ProcessTreeSnapshot $ProcessIdentities $(if ($null -ne $handle.Identity) { $handle.Identity } else { $handle.Process.Id }) $finalProcesses }
				}
				$finalSample = Get-TrackedResourceSnapshot $ProcessIdentities $finalProcesses
				$peakProcessCount = [Math]::Max($peakProcessCount, [int] $finalSample.processCount)
				$peakRssBytes = [Math]::Max($peakRssBytes, [long] $finalSample.rssBytes)
			}
			break
		}
		if ([DateTime]::UtcNow -ge $Deadline) { throw 'Scenario runner timed out' }
	}
	return [pscustomobject]@{ processCount = $peakProcessCount; peakRssBytes = $peakRssBytes }
}

function Stop-TrackedProcessIds([System.Collections.Generic.List[object]] $ProcessIdentities) {
	$deadline = [DateTime]::UtcNow.AddSeconds($CleanupTimeoutSeconds)
	$remaining = @()
	do {
		foreach ($root in @($ProcessIdentities.ToArray())) { Add-ProcessTreeSnapshot $ProcessIdentities $root }
		$processes = Get-ProcessSnapshot
		foreach ($identity in @($ProcessIdentities.ToArray() | Sort-Object ProcessId -Descending)) {
			$id = [int] $identity.ProcessId
			if (-not $processes.ContainsKey($id) -or -not (Test-ProcessIdentityMatch $identity $processes[$id])) { continue }
			try { Stop-Process -Id $id -Force -ErrorAction Stop } catch {
				$current = Get-ProcessSnapshot
				if ($current.ContainsKey($id) -and (Test-ProcessIdentityMatch $identity $current[$id])) { throw "Could not terminate tracked process ${id}: $($_.Exception.Message)" }
			}
		}
		Start-Sleep -Milliseconds 100
		$current = Get-ProcessSnapshot
		$remaining = @($ProcessIdentities.ToArray() | Where-Object { $current.ContainsKey([int] $_.ProcessId) -and (Test-ProcessIdentityMatch $_ $current[[int] $_.ProcessId]) })
		if ($remaining.Count -eq 0) { return }
	} while ([DateTime]::UtcNow -lt $deadline)
	throw "Tracked process cleanup left live PIDs: $(@($remaining | ForEach-Object { $_.ProcessId }) -join ',')"
}

function Assert-TrackedProcessIdsGone([System.Collections.Generic.List[object]] $ProcessIdentities) {
	$current = Get-ProcessSnapshot
	$remaining = @($ProcessIdentities.ToArray() | Where-Object { $current.ContainsKey([int] $_.ProcessId) -and (Test-ProcessIdentityMatch $_ $current[[int] $_.ProcessId]) })
	if ($remaining.Count -gt 0) { throw "Tracked process cleanup left live PIDs: $(@($remaining | ForEach-Object { $_.ProcessId }) -join ',')" }
}

function Stop-ProcessTree([int] $ProcessId) {
	$ids = [System.Collections.Generic.List[object]]::new()
	Add-ProcessTreeSnapshot $ids $ProcessId
	Stop-TrackedProcessIds $ids
}

function Start-RedirectedProcess(
	[string] $FileName,
	[string] $Arguments,
	[string] $WorkingDirectory,
	[string] $StdoutPath,
	[string] $StderrPath,
	[hashtable] $Environment
) {
	$startInfo = [Diagnostics.ProcessStartInfo]::new()
	$startInfo.FileName = $FileName
	$startInfo.Arguments = $Arguments
	$startInfo.WorkingDirectory = $WorkingDirectory
	$startInfo.UseShellExecute = $false
	$startInfo.CreateNoWindow = $true
	$startInfo.RedirectStandardInput = $true
	$startInfo.RedirectStandardOutput = $true
	$startInfo.RedirectStandardError = $true
	foreach ($entry in $Environment.GetEnumerator()) {
		$startInfo.EnvironmentVariables[$entry.Key] = [string] $entry.Value
	}
	$process = [Diagnostics.Process]::new()
	$process.StartInfo = $startInfo
	if (-not $process.Start()) { throw "Could not start process: $FileName" }
	$process.Refresh()
	$identity = [pscustomobject]@{
		ProcessId = [int] $process.Id
		ParentProcessId = [int] $PID
		CreationDate = ConvertTo-ProcessCreationKey $process.StartTime
	}
	$initialRssBytes = [long] $process.WorkingSet64
	$stdoutTask = $process.StandardOutput.ReadToEndAsync()
	$stderrTask = $process.StandardError.ReadToEndAsync()
	return @{
		Process = $process
		Identity = $identity
		InitialRssBytes = $initialRssBytes
		StdoutTask = $stdoutTask
		StderrTask = $stderrTask
		StdoutPath = $StdoutPath
		StderrPath = $StderrPath
	}
}

function Complete-RedirectedProcess($Handle) {
	if ($null -eq $Handle) { return }
	if (-not $Handle.Process.HasExited) { return }
	foreach ($stream in @(@{ Task = $Handle.StdoutTask; Path = $Handle.StdoutPath }, @{ Task = $Handle.StderrTask; Path = $Handle.StderrPath })) {
		try {
			if ($stream.Task.Wait($OutputDrainTimeoutMilliseconds)) {
				[IO.File]::WriteAllText($stream.Path, [string] $stream.Task.Result)
			} else {
				[IO.File]::WriteAllText($stream.Path, '[output drain timed out]')
			}
		} catch {
			try { [IO.File]::WriteAllText($stream.Path, '[output drain failed]') } catch {}
		}
	}
}

function Write-BoundedJson([string] $Path, [object] $Value, [int] $MaximumBytes, [string] $Label) {
	$json = $Value | ConvertTo-Json -Depth 20
	$encoding = [Text.UTF8Encoding]::new($false)
	if ($encoding.GetByteCount($json) -gt $MaximumBytes) { throw "$Label exceeds the bounded size of $MaximumBytes bytes" }
	[IO.File]::WriteAllText($Path, $json, $encoding)
}

function Get-FileSha256([string] $Path) {
	$stream = [IO.File]::OpenRead($Path)
	try {
		$algorithm = [Security.Cryptography.SHA256]::Create()
		try { return [BitConverter]::ToString($algorithm.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
		finally { $algorithm.Dispose() }
	} finally { $stream.Dispose() }
}

function Read-BoundedJson([string] $Path, [int] $MaximumBytes, [string] $Label) {
	if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing $Label at $Path" }
	$item = Get-Item -LiteralPath $Path
	if ($item.Length -gt $MaximumBytes) { throw "$Label exceeds the bounded size of $MaximumBytes bytes" }
	return (Read-Text $Path) | ConvertFrom-Json
}

function New-Secret() {
	$bytes = New-Object byte[] 48
	$generator = [Security.Cryptography.RandomNumberGenerator]::Create()
	try { $generator.GetBytes($bytes) } finally { $generator.Dispose() }
	return [Convert]::ToBase64String($bytes)
}

function Set-ServerProperties([string] $Path, [hashtable] $Values) {
	$lines = @()
	if (Test-Path -LiteralPath $Path -PathType Leaf) { $lines = @(Get-Content -LiteralPath $Path) }
	$seen = @{}
	$result = @(
	foreach ($line in $lines) {
		$entry = ConvertTo-ArenaServerPropertyEntry $line
		if ($null -ne $entry -and $Values.ContainsKey($entry.Key)) {
			if (-not $seen.ContainsKey($entry.Key)) { "$($entry.Key)=$($Values[$entry.Key])" }
			$seen[$entry.Key] = $true
			continue
		}
		$line
	}
	)
	foreach ($entry in $Values.GetEnumerator()) {
		if (-not $seen.ContainsKey($entry.Key)) { $result += "$($entry.Key)=$($entry.Value)" }
	}
	[IO.File]::WriteAllText($Path, (($result -join [Environment]::NewLine) + [Environment]::NewLine), [Text.UTF8Encoding]::new($false))
}

function Remove-ScenarioArtifacts([string] $ScenarioDirectory) {
	foreach ($relativePath in @('server', 'provider-workspaces', 'traces', 'logs', 'rcon-password.txt', 'coordinator-config.json', 'coordinator.jsonl', 'coordinator-private.jsonl', 'protocol.jsonl', 'provider-turns.private.jsonl')) {
		$target = Join-Path $ScenarioDirectory $relativePath
		if (-not (Test-Path -LiteralPath $target)) { continue }
		$extendedTarget = if ($target.StartsWith('\\')) { '\\?\UNC\' + $target.Substring(2) } else { '\\?\' + [IO.Path]::GetFullPath($target) }
		$deadline = [DateTime]::UtcNow.AddSeconds($CleanupTimeoutSeconds)
		$lastError = $null
		do {
			try {
				if ([IO.Directory]::Exists($extendedTarget)) {
					[IO.Directory]::Delete($extendedTarget, $true)
				} elseif ([IO.File]::Exists($extendedTarget)) {
					[IO.File]::Delete($extendedTarget)
				}
				$lastError = $null
				break
			} catch {
				$lastError = $_
				if (-not (Test-Path -LiteralPath $target)) { $lastError = $null; break }
				if ([DateTime]::UtcNow -ge $deadline) { throw "Could not remove generated artifact '$target': $($_.Exception.Message)" }
				Start-Sleep -Milliseconds 100
			}
		} while ([DateTime]::UtcNow -lt $deadline)
		if ($null -ne $lastError -and (Test-Path -LiteralPath $target)) { throw "Could not remove generated artifact '$target': $($lastError.Exception.Message)" }
		if (Test-Path -LiteralPath $target) { throw "Artifact cleanup left '$target' behind" }
	}
}

function Resolve-Java([string] $Project) {
	$candidates = @()
	$override = [Environment]::GetEnvironmentVariable('ARENA_HEADLESS_JAVA')
	if (-not [string]::IsNullOrWhiteSpace($override)) {
		$candidates = @($override)
	} else {
		$candidates += (Join-Path $Project 'runtime\toolchains\temurin-25\jdk-25.0.3+9\bin\java.exe')
		$found = Get-ProcessCommand 'java'
		if ($null -ne $found) { $candidates += $found }
	}
	foreach ($candidate in $candidates) {
		if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { continue }
		$previousErrorAction = $ErrorActionPreference
		$ErrorActionPreference = 'Continue'
		$version = (& $candidate -version 2>&1 | Out-String)
		$ErrorActionPreference = $previousErrorAction
		if ($LASTEXITCODE -ne 0) { continue }
		$match = [regex]::Match($version, 'version\s+"(?<major>\d+)')
		if ($match.Success -and [int] $match.Groups['major'].Value -ge 25) { return $candidate }
	}
	throw 'Java 25 or newer is required'
}

function Resolve-Node() {
	$override = [Environment]::GetEnvironmentVariable('ARENA_HEADLESS_NODE')
	$node = if (-not [string]::IsNullOrWhiteSpace($override)) { $override } else { Get-ProcessCommand 'node' }
	if ($null -eq $node -or -not (Test-Path -LiteralPath $node -PathType Leaf)) { throw 'Node.js 22 or newer is required' }
	$previousErrorAction = $ErrorActionPreference
	$ErrorActionPreference = 'Continue'
	$version = (& $node --version 2>&1 | Out-String)
	$ErrorActionPreference = $previousErrorAction
	$match = [regex]::Match($version, 'v(?<major>\d+)')
	if (-not $match.Success -or [int] $match.Groups['major'].Value -lt 22) { throw 'Node.js 22 or newer is required' }
	return $node
}

function Read-Matrix([string] $Path, [string] $Node, [string] $Project) {
	if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing matrix file: $Path" }
	$validated = & $Node (Join-Path $Project 'coordinator\src\headless-config.mjs') $Path
	if ($LASTEXITCODE -ne 0) { throw 'Headless matrix validation failed before server setup' }
	$matrix = $validated | ConvertFrom-Json
	if ($null -eq $matrix.scenarios -or @($matrix.scenarios).Count -eq 0) { throw 'Matrix must contain one or more scenarios' }
	return $matrix
}

function Get-ProviderCommand([string] $Provider) {
	switch ($Provider.ToLowerInvariant()) {
		'codex' { return 'codex' }
		'gemini' { return 'agy' }
		'kimi' { return 'kimi' }
		'cursor' { return (Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'cursor-agent\agent.ps1') }
		default { throw "Unsupported provider '$Provider'" }
	}
}

function ConvertTo-SafePathSegment([string] $Value) {
	$segment = [regex]::Replace($Value, '[^A-Za-z0-9._-]', '_').Trim('.')
	if ([string]::IsNullOrWhiteSpace($segment)) { $segment = 'scenario' }
	return $segment.Substring(0, [Math]::Min(48, $segment.Length))
}

function ConvertTo-BoundedText([object] $Value, [int] $Maximum = 128) {
	$text = [string] $Value
	if ($text.Length -gt $Maximum) { return $text.Substring(0, $Maximum) }
	return $text
}

function Assert-SafeScenarioId([string] $Value) {
	if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -gt 128 -or $Value -match '[\\/\x00-\x1f\x7f:*?"<>|]' -or $Value.Contains('..')) {
		throw "Scenario ID must be a safe Windows path segment without separators, '..', control characters, or reserved filename characters: $Value"
	}
}

function Test-ProviderPreflight([string] $Provider) {
	if ([Environment]::GetEnvironmentVariable('ARENA_HEADLESS_SKIP_PROVIDER_PREFLIGHT') -eq '1') {
		return [pscustomobject]@{ Available = $true; Reason = $null }
	}
	$commandName = Get-ProviderCommand $Provider
	$command = Get-ProcessCommand $commandName
	if ($null -eq $command) { return [pscustomobject]@{ Available = $false; Reason = "Provider executable '$commandName' is unavailable" } }
	try {
		$previousErrorAction = $ErrorActionPreference
		$ErrorActionPreference = 'Continue'
		$null = & $command --version 2>&1
		$ErrorActionPreference = $previousErrorAction
		if ($LASTEXITCODE -ne 0) { return [pscustomobject]@{ Available = $false; Reason = "Provider executable '$commandName' failed preflight" } }
	} catch {
		return [pscustomobject]@{ Available = $false; Reason = "Provider executable '$commandName' failed preflight" }
	}
	return [pscustomobject]@{ Available = $true; Reason = $null }
}

function New-ScenarioConfig([string] $Source, [string] $Destination, [int] $BridgePort, [string] $WorkspaceRoot) {
	$config = Get-Content -Raw -LiteralPath $Source | ConvertFrom-Json
	$config.bridge.host = '127.0.0.1'
	$config.bridge.port = $BridgePort
	if ($null -eq $config.PSObject.Properties['workspaceRoot']) { $config | Add-Member -NotePropertyName workspaceRoot -NotePropertyValue $WorkspaceRoot } else { $config.workspaceRoot = $WorkspaceRoot }
	if ($null -eq $config.codex.PSObject.Properties['cwd']) { $config.codex | Add-Member -NotePropertyName cwd -NotePropertyValue $WorkspaceRoot } else { $config.codex.cwd = $WorkspaceRoot }
	if ($null -eq $config.codex.PSObject.Properties['launchProfile'] -or $null -eq $config.codex.launchProfile) { $config.codex | Add-Member -NotePropertyName launchProfile -NotePropertyValue ([pscustomobject]@{}) }
	$launchProfile = $config.codex.launchProfile
	if ($null -eq $launchProfile.PSObject.Properties['cwd']) { $launchProfile | Add-Member -NotePropertyName cwd -NotePropertyValue $WorkspaceRoot } else { $launchProfile.cwd = $WorkspaceRoot }
	[IO.File]::WriteAllText($Destination, ($config | ConvertTo-Json -Depth 20), [Text.UTF8Encoding]::new($false))
}

function Invoke-Scenario($Scenario, [string] $Project, [string] $RunDirectory, [string] $Template, [string] $MatrixFile, [string] $Java, [string] $Node, [string] $BuiltJar, [switch] $Keep) {
	$scenarioId = [string] $Scenario.id
	$serviceTier = 'priority'
	if ($null -ne $Scenario.PSObject.Properties['serviceTier'] -and $null -ne $Scenario.serviceTier) { $serviceTier = [string] $Scenario.serviceTier }
	$scenarioDirectory = Join-Path $RunDirectory ("$(ConvertTo-SafePathSegment $scenarioId)-$([Guid]::NewGuid().ToString('N').Substring(0, 8))")
	$setupStarted = $false
	$secretCreated = $false
	$naturalWorld = $null -ne $Scenario.PSObject.Properties['world'] -and $Scenario.world.mode -eq 'natural'
	$worldManifestPath = $null
	try {
	$setupStarted = $true
	New-Item -ItemType Directory -Path $scenarioDirectory -Force | Out-Null
	$serverDirectory = Join-Path $scenarioDirectory 'server'
	if ($naturalWorld) {
		if (@(Get-ChildItem -LiteralPath $Template -Recurse -File -Filter 'level.dat').Count -gt 0) { throw 'Natural evaluations require a clean server template without saved worlds' }
		if (@(Get-ChildItem -LiteralPath $Template -Recurse -Attributes ReparsePoint).Count -gt 0) { throw 'Natural evaluation templates cannot contain linked directories or files' }
	}
	Copy-Item -LiteralPath $Template -Destination $serverDirectory -Recurse -Force
	$world = [IO.Path]::GetFullPath((Join-Path $serverDirectory 'world'))
	$serverBoundary = [IO.Path]::GetFullPath($serverDirectory).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
	if (-not $world.StartsWith($serverBoundary, [StringComparison]::OrdinalIgnoreCase)) { throw 'Copied world cleanup escaped the isolated server directory' }
	if (Test-Path -LiteralPath $world) { Remove-Item -LiteralPath $world -Recurse -Force }
	$modsDirectory = Join-Path $serverDirectory 'mods'
	New-Item -ItemType Directory -Path $modsDirectory -Force | Out-Null
	Copy-Item -LiteralPath $BuiltJar -Destination (Join-Path $modsDirectory ([IO.Path]::GetFileName($BuiltJar))) -Force
	$worldName = "headless-$([IO.Path]::GetFileName($RunDirectory))-$scenarioId-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
	$bridgePort = Reserve-FreePort (Get-ConfiguredPort 'ARENA_HEADLESS_BRIDGE_PORT')
	$rconPort = Reserve-FreePort (Get-ConfiguredPort 'ARENA_HEADLESS_RCON_PORT') @($bridgePort)
	$serverPort = Reserve-FreePort (Get-ConfiguredPort 'ARENA_HEADLESS_MINECRAFT_PORT') @($bridgePort, $rconPort)
	$secret = New-Secret
	$secretPath = Join-Path $scenarioDirectory 'rcon-password.txt'
	$secretCreated = $true
	Write-PrivateText $secretPath $secret
	$propertiesPath = Join-Path $serverDirectory 'server.properties'
	Set-ServerProperties $propertiesPath @{
		'online-mode' = 'false'
		'server-ip' = '127.0.0.1'
		'enable-rcon' = 'true'
		'rcon.password' = $secret
		'rcon.port' = $rconPort
		'rcon.ip' = '127.0.0.1'
		'server-port' = $serverPort
		'level-name' = $worldName
		'pause-when-empty-seconds' = '-1'
	}
	if ($naturalWorld) {
		if (Test-Path -LiteralPath (Join-Path $serverDirectory $worldName)) { throw 'Natural evaluation world must not exist before server startup' }
		Set-ServerProperties $propertiesPath @{
			'level-seed' = [string] $Scenario.world.seed
			'level-type' = [string] $Scenario.world.generator
			'difficulty' = [string] $Scenario.world.difficulty
			'gamemode' = [string] $Scenario.world.gameMode
			'generator-settings' = '{}'
			'generate-structures' = 'true'
		}
		$worldManifestPath = Join-Path $scenarioDirectory 'world-manifest.json'
		$worldManifest = [pscustomobject]@{
			version = 1; scenarioId = $scenarioId; worldId = $worldName; fresh = $true; world = $Scenario.world
			modSha256 = Get-FileSha256 $BuiltJar
		}
		Write-BoundedJson $worldManifestPath $worldManifest $MaxManifestBytes 'world manifest'
	}
	Assert-ArenaOfflineServerLoopback $propertiesPath -RequireOffline
	Protect-LocalFile $propertiesPath
	$logsDirectory = Join-Path $scenarioDirectory 'logs'
	$traceDirectory = Join-Path $scenarioDirectory 'traces'
	$providerWorkspace = Join-Path $scenarioDirectory 'provider-workspaces'
	New-Item -ItemType Directory -Path $logsDirectory, $traceDirectory, $providerWorkspace -Force | Out-Null
	$serverLog = Join-Path $serverDirectory 'logs\latest.log'
	$protocolAudit = Join-Path $scenarioDirectory 'protocol.jsonl'
	$providerTurns = Join-Path $scenarioDirectory 'provider-turns.private.jsonl'
	$coordinatorTrace = Join-Path $scenarioDirectory 'coordinator.jsonl'
	$coordinatorPrivateTrace = Join-Path $scenarioDirectory 'coordinator-private.jsonl'
	[IO.File]::WriteAllText($coordinatorTrace, '', [Text.UTF8Encoding]::new($false))
	Write-PrivateText $coordinatorPrivateTrace ''
	$coordinatorConfig = Join-Path $scenarioDirectory 'coordinator-config.json'
	$sourceConfig = Join-Path $Project 'coordinator\config\dynamic-agents.json'
	if (-not (Test-Path -LiteralPath $sourceConfig -PathType Leaf)) { throw "Missing coordinator config: $sourceConfig" }
	New-ScenarioConfig $sourceConfig $coordinatorConfig $bridgePort $providerWorkspace
	$manifest = [pscustomobject]@{
		runId = [IO.Path]::GetFileName($RunDirectory); scenarioId = $scenarioId; provider = [string] $Scenario.provider
		model = [string] $Scenario.model; reasoningEffort = [string] $Scenario.reasoningEffort; serviceTier = $serviceTier
		rosterSize = if ($null -eq $Scenario.PSObject.Properties['rosterSize']) { 1 } else { [int] $Scenario.rosterSize }
		serverDirectory = $serverDirectory; providerWorkspace = $providerWorkspace; protocolAudit = $protocolAudit; providerTurns = $providerTurns
		ports = [pscustomobject]@{ minecraft = $serverPort; rcon = $rconPort; bridge = $bridgePort }; levelName = $worldName
		world = if ($naturalWorld) { $Scenario.world } else { [pscustomobject]@{ mode = 'arena' } }
	}
	Write-BoundedJson (Join-Path $scenarioDirectory 'manifest.json') $manifest $MaxManifestBytes 'scenario manifest'
	} catch {
		$setupException = $_
		$setupDiagnostics = ConvertTo-BoundedText "Scenario setup failed ($($_.Exception.GetType().Name))" $MaxDiagnosticText
		$setupCleanupStatus = 'NOT_REQUIRED'
		if (-not $Keep -and $setupStarted) {
			$setupCleanupStatus = 'CLEAN'
			try { Remove-ScenarioArtifacts $scenarioDirectory } catch {
				$setupCleanupStatus = 'FAILED'
				$setupDiagnostics = 'Scenario setup cleanup failed'
			}
		}
		if ($setupStarted -and (Test-Path -LiteralPath $scenarioDirectory -PathType Container)) {
			$setupReport = [pscustomobject]@{
				status = 'FAILED'; scenarioId = (ConvertTo-BoundedText $scenarioId); provider = (ConvertTo-BoundedText $Scenario.provider)
				model = (ConvertTo-BoundedText $Scenario.model); reasoningEffort = (ConvertTo-BoundedText $Scenario.reasoningEffort)
				exitCode = $null; cleanup = [pscustomobject]@{ status = $setupCleanupStatus }; artifactsKept = [bool] $Keep; diagnostics = $setupDiagnostics
			}
			try { Write-BoundedJson (Join-Path $scenarioDirectory 'report.json') $setupReport $MaxMatrixReportBytes 'scenario setup report' } catch {}
		}
		if (-not $secretCreated) {
			if ($setupCleanupStatus -eq 'FAILED') { throw 'Scenario setup cleanup failed' }
			throw $setupException
		}
		throw $setupDiagnostics
	}
	$serverHandle = $null
	$coordinatorHandle = $null
	$runnerHandle = $null
	$failure = $null
	$runnerExit = $null
	$runnerReport = $null
	$cleanupFailure = $null
	$processIds = [System.Collections.Generic.List[object]]::new()
	$peakProcessCount = 0
	[long] $peakRssBytes = 0
	try {
		$serverStdoutPath = Join-Path $logsDirectory 'fabric.stdout.log'
		$serverStderrPath = Join-Path $logsDirectory 'fabric.stderr.log'
		$serverAttempt = 0
		$serverReady = $false
		while (-not $serverReady) {
			$serverAttempt += 1
			$serverArgs = "-Darenaagents.bridgePort=$bridgePort -Darenaagents.coordinatorAutoStart=false -Darenaagents.bridgeSecretFile=$(Quote-Argument $secretPath) -Xms1G -Xmx4G -jar $(Quote-Argument (Join-Path $serverDirectory 'fabric-server-launch.jar')) nogui"
			$serverHandle = Start-RedirectedProcess $Java $serverArgs $serverDirectory $serverStdoutPath $serverStderrPath @{}
			try {
				Wait-Condition { (Test-Port $serverPort) -and ((Read-Text $serverLog).Contains('Done (')) } $StartupTimeoutSeconds 'Fabric server did not become ready'
				Wait-Condition { Test-Port $rconPort } $StartupTimeoutSeconds 'RCON did not become ready'
				$serverReady = $true
			} catch {
				Complete-RedirectedProcess $serverHandle
				$bindFailure = $serverHandle.Process.HasExited -and (Test-BindFailure $serverLog $serverStderrPath)
				if (-not $bindFailure -or $serverAttempt -gt $StartupBindRetries) { throw }
				Add-ProcessTreeSnapshot $processIds $serverHandle.Identity
				try { Stop-TrackedProcessIds $processIds } catch { throw "Server bind retry cleanup failed: $($_.Exception.Message)" }
				$serverHandle = $null
				$bridgePort = Reserve-FreePort (Get-ConfiguredPort 'ARENA_HEADLESS_BRIDGE_PORT')
				$rconPort = Reserve-FreePort (Get-ConfiguredPort 'ARENA_HEADLESS_RCON_PORT') @($bridgePort)
				$serverPort = Reserve-FreePort (Get-ConfiguredPort 'ARENA_HEADLESS_MINECRAFT_PORT') @($bridgePort, $rconPort)
				Set-ServerProperties $propertiesPath @{
					'online-mode' = 'false'; 'server-ip' = '127.0.0.1'; 'enable-rcon' = 'true'; 'rcon.password' = $secret; 'rcon.port' = $rconPort; 'rcon.ip' = '127.0.0.1'
					'server-port' = $serverPort; 'level-name' = $worldName; 'pause-when-empty-seconds' = '-1'
				}
				Assert-ArenaOfflineServerLoopback $propertiesPath -RequireOffline
				Protect-LocalFile $propertiesPath
				New-ScenarioConfig $sourceConfig $coordinatorConfig $bridgePort $providerWorkspace
			}
		}
		# Keep the complete server tree tracked before any coordinator retry can restart it.
		Add-ProcessTreeSnapshot $processIds $serverHandle.Identity
		if (-not $CapabilityProbe) {
		$coordinatorArgs = "$(Quote-Argument (Join-Path $Project 'coordinator\src\dynamic-main.mjs')) --config $(Quote-Argument $coordinatorConfig)"
		$coordinatorStdoutPath = Join-Path $traceDirectory 'dynamic.stdout.log'
		$coordinatorStderrPath = Join-Path $traceDirectory 'dynamic.stderr.log'
		$coordinatorAttempt = 0
		$coordinatorReady = $false
		while (-not $coordinatorReady) {
			$coordinatorAttempt += 1
			$coordinatorEnvironment = @{
				ARENA_AGENT_BRIDGE_SECRET = $secret; ARENA_HEADLESS_RUN_ID = [IO.Path]::GetFileName($RunDirectory); ARENA_HEADLESS_SCENARIO_ID = $scenarioId
				ARENA_PROTOCOL_AUDIT_PATH = $protocolAudit; ARENA_PROVIDER_TURNS_PATH = $providerTurns; ARENA_HEADLESS_TRACE_PATH = $coordinatorTrace
				ARENA_HEADLESS_PRIVATE_TRACE_PATH = $coordinatorPrivateTrace
				ARENA_HEADLESS_BRIDGE_PORT = $bridgePort; ARENA_HEADLESS_RCON_PORT = $rconPort; ARENA_HEADLESS_MINECRAFT_PORT = $serverPort
			}
			$coordinatorHandle = Start-RedirectedProcess $Node $coordinatorArgs (Join-Path $Project 'coordinator') $coordinatorStdoutPath $coordinatorStderrPath $coordinatorEnvironment
			try {
				Wait-Condition {
					if ($coordinatorHandle.Process.HasExited) { throw "Coordinator exited before bridge readiness: $(ConvertTo-BoundedText (Read-Text $coordinatorStderrPath) $MaxDiagnosticText)" }
					(Test-Port $bridgePort) -and (Test-CoordinatorReady $protocolAudit $Scenario)
				} $StartupTimeoutSeconds 'Coordinator bridge did not become ready'
				$coordinatorReady = $true
			} catch {
				Complete-RedirectedProcess $coordinatorHandle
				$bindFailure = $coordinatorHandle.Process.HasExited -and (Test-BindFailure $coordinatorStdoutPath $coordinatorStderrPath)
				if (-not $bindFailure -or $coordinatorAttempt -gt $CoordinatorBindRetries) { throw }
				Add-ProcessTreeSnapshot $processIds $coordinatorHandle.Identity
				try { Stop-TrackedProcessIds $processIds } catch { throw "Coordinator bind retry cleanup failed: $($_.Exception.Message)" }
				$coordinatorHandle = $null
				# The Fabric process owns the bridge listener. Reallocate all three ports and
				# restart it before retrying the coordinator, otherwise a new bridge port
				# would never be served by the old process.
				$bridgePort = Reserve-FreePort (Get-ConfiguredPort 'ARENA_HEADLESS_BRIDGE_PORT')
				$rconPort = Reserve-FreePort (Get-ConfiguredPort 'ARENA_HEADLESS_RCON_PORT') @($bridgePort)
				$serverPort = Reserve-FreePort (Get-ConfiguredPort 'ARENA_HEADLESS_MINECRAFT_PORT') @($bridgePort, $rconPort)
				Set-ServerProperties $propertiesPath @{
					'online-mode' = 'false'; 'server-ip' = '127.0.0.1'; 'enable-rcon' = 'true'; 'rcon.password' = $secret; 'rcon.port' = $rconPort; 'rcon.ip' = '127.0.0.1'
					'server-port' = $serverPort; 'level-name' = $worldName; 'pause-when-empty-seconds' = '-1'
				}
				Assert-ArenaOfflineServerLoopback $propertiesPath -RequireOffline
				Protect-LocalFile $propertiesPath
				New-ScenarioConfig $sourceConfig $coordinatorConfig $bridgePort $providerWorkspace
				$serverArgs = "-Darenaagents.bridgePort=$bridgePort -Darenaagents.coordinatorAutoStart=false -Darenaagents.bridgeSecretFile=$(Quote-Argument $secretPath) -Xms1G -Xmx4G -jar $(Quote-Argument (Join-Path $serverDirectory 'fabric-server-launch.jar')) nogui"
				$serverHandle = Start-RedirectedProcess $Java $serverArgs $serverDirectory $serverStdoutPath $serverStderrPath @{}
				Wait-Condition { (Test-Port $serverPort) -and ((Read-Text $serverLog).Contains('Done (')) } $StartupTimeoutSeconds 'Fabric server did not become ready after coordinator bind retry'
				Wait-Condition { Test-Port $rconPort } $StartupTimeoutSeconds 'RCON did not become ready after coordinator bind retry'
				Add-ProcessTreeSnapshot $processIds $serverHandle.Identity
			}
		}
		}
		if ($CapabilityProbe) {
			$runnerArgs = "$(Quote-Argument (Join-Path $Project 'coordinator\src\player-capability-probe.mjs')) --run-directory $(Quote-Argument $scenarioDirectory) --rcon-host 127.0.0.1 --rcon-port $rconPort --rcon-password-file $(Quote-Argument $secretPath) --bridge-port $bridgePort --bridge-secret-file $(Quote-Argument $secretPath) --timeout-ms $([int64] $Scenario.timeoutMs)"
		} else {
			if ($naturalWorld) {
				$spawnArgs = @((Join-Path $Project 'coordinator\src\headless-world-spawn.mjs'), (Join-Path (Join-Path $serverDirectory $worldName) 'level.dat'), '--rcon-port', [string] $rconPort, '--password-file', $secretPath, '--timeout-ms', [string] ([Math]::Min(120000, $StartupTimeoutSeconds * 1000)))
				if ($Scenario.world.spawn.policy -eq 'surface') { $spawnArgs += @('--surface-x', [string] $Scenario.world.spawn.x, '--surface-z', [string] $Scenario.world.spawn.z) }
				$spawnEvidenceText = & $Node @spawnArgs
				if ($LASTEXITCODE -ne 0) { throw 'Natural spawn metadata or chunk readiness failed before agent evaluation' }
				$spawnEvidence = ($spawnEvidenceText -join "`n") | ConvertFrom-Json
				$worldManifest | Add-Member -NotePropertyName savedSpawn -NotePropertyValue $spawnEvidence.savedSpawn -Force
				$worldManifest | Add-Member -NotePropertyName spawnLoading -NotePropertyValue $spawnEvidence.spawnLoading -Force
				Write-BoundedJson $worldManifestPath $worldManifest $MaxManifestBytes 'world manifest'
			}
			$runnerArgs = "$(Quote-Argument (Join-Path $Project 'coordinator\src\headless-matrix.mjs')) --config $(Quote-Argument $MatrixFile) --scenario $(Quote-Argument $scenarioId) --run-directory $(Quote-Argument $scenarioDirectory) --rcon-host 127.0.0.1 --rcon-port $rconPort --rcon-password-file $(Quote-Argument $secretPath) --protocol-audit $(Quote-Argument $protocolAudit) --provider-turns $(Quote-Argument $providerTurns)"
			if ($naturalWorld) { $runnerArgs += " --world-manifest $(Quote-Argument $worldManifestPath)" }
			if ($RequireAll) { $runnerArgs += ' --require-all' }
		}
		$runnerEnvironment = @{
			ARENA_HEADLESS_RUN_ID = [IO.Path]::GetFileName($RunDirectory); ARENA_HEADLESS_SCENARIO_ID = $scenarioId
			ARENA_PROTOCOL_AUDIT_PATH = $protocolAudit; ARENA_PROVIDER_TURNS_PATH = $providerTurns
		}
		$runnerHandle = Start-RedirectedProcess $Node $runnerArgs (Join-Path $Project 'coordinator') (Join-Path $traceDirectory 'runner.stdout.log') (Join-Path $traceDirectory 'runner.stderr.log') $runnerEnvironment
		Add-ProcessTreeSnapshot $processIds $serverHandle.Identity
		if ($null -ne $coordinatorHandle) { Add-ProcessTreeSnapshot $processIds $coordinatorHandle.Identity }
		Add-ProcessTreeSnapshot $processIds $runnerHandle.Identity
		$repetitions = if ($null -ne $Scenario.PSObject.Properties['repetitions']) { [Math]::Max(1, [int] $Scenario.repetitions) } else { 1 }
		$runnerDeadline = [DateTime]::UtcNow.AddMilliseconds(([int64] $Scenario.timeoutMs * $repetitions) + ($RunnerGraceSeconds * 1000))
		try {
			$resourcePeak = Measure-RunnerResourcesUntilExit $runnerHandle @($serverHandle, $coordinatorHandle, $runnerHandle) $processIds $runnerDeadline
		} catch {
			if ($_.Exception.Message -eq 'Scenario runner timed out') { throw "Scenario '$scenarioId' timed out" }
			throw
		}
		$peakProcessCount = [int] $resourcePeak.processCount
		$peakRssBytes = [long] $resourcePeak.peakRssBytes
		$runnerExit = $runnerHandle.Process.ExitCode
		Complete-RedirectedProcess $runnerHandle
		if ($CapabilityProbe) {
			$runnerReport = Read-BoundedJson (Join-Path $scenarioDirectory 'player-capability-report.json') $MaxMatrixReportBytes 'player capability probe report'
			if (@('PASSED', 'FAILED') -notcontains [string] $runnerReport.status) { throw 'Player capability probe returned an invalid status' }
		} elseif ($repetitions -eq 1) {
			$runnerReport = Read-BoundedJson (Join-Path $scenarioDirectory 'report.json') $MaxMatrixReportBytes 'runner scenario report'
			if ([string] $runnerReport.scenarioId -ne $scenarioId -or @('PASSED', 'FAILED', 'SKIPPED') -notcontains [string] $runnerReport.status) {
				throw "Runner scenario report for '$scenarioId' is invalid"
			}
		} else {
			$runnerReport = Read-BoundedJson (Join-Path $scenarioDirectory 'matrix-report.json') $MaxMatrixReportBytes 'runner repetition report'
			$repetitionReports = @($runnerReport.scenarios)
			if (@('PASSED', 'FAILED', 'SKIPPED') -notcontains [string] $runnerReport.status -or $repetitionReports.Count -ne $repetitions -or @($repetitionReports | Where-Object { [string] $_.scenarioId -ne $scenarioId -or @('PASSED', 'FAILED', 'SKIPPED') -notcontains [string] $_.status }).Count -gt 0) {
				throw "Runner repetition report for '$scenarioId' is invalid"
			}
		}
		if ($runnerExit -ne 0) { throw "Scenario '$scenarioId' failed with runner exit code $runnerExit" }
	} catch {
		$failure = $_
	} finally {
		foreach ($handle in @($runnerHandle, $coordinatorHandle, $serverHandle)) {
			if ($null -ne $handle -and $null -ne $handle.Process) { Add-ProcessTreeSnapshot $processIds $handle.Identity }
		}
		if ($null -ne $serverHandle -and $null -ne $serverHandle.Process) {
			try {
				if (-not $serverHandle.Process.HasExited) {
					$serverHandle.Process.StandardInput.WriteLine('stop')
					$serverHandle.Process.StandardInput.Flush()
					$null = $serverHandle.Process.WaitForExit($GracefulStopTimeoutMilliseconds)
				}
			} catch {}
		}
		try { Stop-TrackedProcessIds $processIds } catch { if ($null -eq $cleanupFailure) { $cleanupFailure = $_ }; if ($null -eq $failure) { $failure = $_ } }
		try { Assert-TrackedProcessIdsGone $processIds } catch { if ($null -eq $cleanupFailure) { $cleanupFailure = $_ }; if ($null -eq $failure) { $failure = $_ } }
		try { Wait-Condition { -not (Test-Port $serverPort) -and -not (Test-Port $rconPort) -and -not (Test-Port $bridgePort) } $CleanupTimeoutSeconds 'Scenario cleanup left an allocated listener running' } catch { $cleanupFailure = $_; if ($null -eq $failure) { $failure = $_ } }
		foreach ($handle in @($runnerHandle, $coordinatorHandle, $serverHandle)) { Complete-RedirectedProcess $handle }
	}
	if (-not $Keep) {
		try { Remove-ScenarioArtifacts $scenarioDirectory } catch { $cleanupFailure = $_; if ($null -eq $failure) { $failure = $_ } }
	}
	$status = if ($null -ne $failure) { 'FAILED' } elseif ($null -ne $runnerReport) { [string] $runnerReport.status } else { 'FAILED' }
	$cleanupStatus = if ($null -eq $cleanupFailure) { 'CLEAN' } else { 'FAILED' }
	$reportFields = [ordered]@{}
	if ($null -ne $runnerReport) {
		foreach ($property in $runnerReport.PSObject.Properties) { $reportFields[$property.Name] = $property.Value }
	}
	$metricFields = [ordered]@{}
	if ($null -ne $runnerReport -and $null -ne $runnerReport.PSObject.Properties['metrics'] -and $null -ne $runnerReport.metrics) {
		foreach ($property in $runnerReport.metrics.PSObject.Properties) { $metricFields[$property.Name] = $property.Value }
	}
	$resourceFields = [ordered]@{}
	if ($metricFields.Contains('resources') -and $null -ne $metricFields['resources']) {
		foreach ($property in $metricFields['resources'].PSObject.Properties) { $resourceFields[$property.Name] = $property.Value }
	}
	$resourceFields['processCount'] = $peakProcessCount
	$resourceFields['peakRssBytes'] = $peakRssBytes
	if (-not $resourceFields.Contains('minecraftMspt')) { $resourceFields['minecraftMspt'] = $null }
	$metricFields['resources'] = [pscustomobject] $resourceFields
	$reportFields['metrics'] = [pscustomobject] $metricFields
	$reportFields['status'] = $status
	$reportFields['scenarioId'] = ConvertTo-BoundedText $scenarioId
	$reportFields['exitCode'] = $runnerExit
	$reportFields['cleanup'] = [pscustomobject]@{
		status = $cleanupStatus
		runner = if ($null -eq $runnerReport -or $null -eq $runnerReport.PSObject.Properties['cleanup']) { $null } else { $runnerReport.cleanup }
		processIds = @($processIds | ForEach-Object { [int] $_.ProcessId } | Select-Object -Unique)
		diagnostics = if ($null -eq $cleanupFailure) { $null } else { ConvertTo-BoundedText $cleanupFailure.Exception.Message $MaxDiagnosticText }
	}
	$reportFields['artifacts'] = $manifest
	$reportFields['artifactsKept'] = [bool] $Keep
	if ($null -ne $failure) {
		$wrapperDiagnostics = ConvertTo-BoundedText $failure.Exception.Message $MaxDiagnosticText
		$reportFields['wrapperDiagnostics'] = $wrapperDiagnostics
		if (-not $reportFields.Contains('diagnostics') -or [string]::IsNullOrWhiteSpace([string] $reportFields['diagnostics'])) { $reportFields['diagnostics'] = $wrapperDiagnostics }
	}
	$report = [pscustomobject] $reportFields
	Write-BoundedJson (Join-Path $scenarioDirectory 'report.json') $report $MaxMatrixReportBytes 'scenario report'
	return $report
}

$root = [IO.Path]::GetFullPath($ProjectRoot)
if ([string]::IsNullOrWhiteSpace($MatrixPath)) { $MatrixPath = Join-Path $root 'coordinator\config\headless-provider-matrix.json' }
if ([string]::IsNullOrWhiteSpace($ServerTemplate)) {
		$ServerTemplate = Join-Path $root 'runtime\server-template'
		if (-not (Test-Path -LiteralPath $ServerTemplate -PathType Container)) { $ServerTemplate = Join-Path $root 'runtime\server' }
}
$MatrixPath = [IO.Path]::GetFullPath($MatrixPath)
$ServerTemplate = [IO.Path]::GetFullPath($ServerTemplate)

$java = Resolve-Java $root
$node = Resolve-Node
$serverLauncher = Join-Path $ServerTemplate 'fabric-server-launch.jar'
if (-not (Test-Path -LiteralPath $ServerTemplate -PathType Container)) { throw "Missing server template: $ServerTemplate" }
if (-not (Test-Path -LiteralPath $serverLauncher -PathType Leaf)) { throw "Missing Fabric server launcher: $serverLauncher" }
$builtJar = Resolve-ArenaModJar $root
if (-not (Test-Path -LiteralPath $builtJar -PathType Leaf)) { throw "Missing built mod JAR: $builtJar" }
$matrix = Read-Matrix $MatrixPath $node $root
$selected = @($matrix.scenarios | Where-Object { [string]::IsNullOrWhiteSpace($ScenarioId) -or [string] $_.id -eq $ScenarioId })
if ($selected.Count -eq 0) { throw "Unknown scenario '$ScenarioId'" }
if ($selected.Count -gt $MaxSelectedScenarios) { throw "Selected scenario count $($selected.Count) exceeds the bounded maximum of $MaxSelectedScenarios" }
foreach ($scenario in $selected) { Assert-SafeScenarioId ([string] $scenario.id) }
if ($CapabilityProbe) {
	if ([string]::IsNullOrWhiteSpace($ScenarioId) -or $selected.Count -ne 1) { throw 'CapabilityProbe requires one explicit ScenarioId' }
	if ($selected[0].world.mode -ne 'arena') { throw 'CapabilityProbe prepares mechanics fixtures and requires arena mode; natural evaluations preserve terrain' }
	if (($null -ne $selected[0].PSObject.Properties['repetitions'] -and [int] $selected[0].repetitions -ne 1) -or ($null -ne $selected[0].PSObject.Properties['rosterSize'] -and [int] $selected[0].rosterSize -ne 1)) { throw 'CapabilityProbe requires one agent and one repetition' }
	if (-not (Test-Path -LiteralPath (Join-Path $root 'coordinator\src\player-capability-probe.mjs') -PathType Leaf)) { throw 'Missing player capability probe script' }
}
$manifestScenarios = @(
	foreach ($scenario in $selected) {
		$assertionTypes = @()
		if ($null -ne $scenario.PSObject.Properties['assert']) {
			$assertionTypes = @($scenario.assert | ForEach-Object {
				if ($null -ne $_ -and $null -ne $_.PSObject.Properties['type']) { ConvertTo-BoundedText $_.type 64 }
			}) | Select-Object -First 16
		}
		$serviceTier = 'priority'
		if ($null -ne $scenario.PSObject.Properties['serviceTier'] -and $null -ne $scenario.serviceTier) {
			$serviceTier = ConvertTo-BoundedText $scenario.serviceTier
		}
		[pscustomobject]@{
			id = ConvertTo-BoundedText $scenario.id
			provider = ConvertTo-BoundedText $scenario.provider
			model = ConvertTo-BoundedText $scenario.model
			reasoningEffort = ConvertTo-BoundedText $scenario.reasoningEffort
			serviceTier = $serviceTier
			rosterSize = if ($null -eq $scenario.PSObject.Properties['rosterSize']) { 1 } else { [int] $scenario.rosterSize }
			timeoutMs = if ($null -ne $scenario.PSObject.Properties['timeoutMs']) { [int] $scenario.timeoutMs } else { $null }
			assertionTypes = @($assertionTypes)
		}
	}
)
$manifestVersion = if ($null -ne $matrix.PSObject.Properties['version']) { [int] $matrix.version } else { 1 }
$manifestSummary = [pscustomobject]@{ version = $manifestVersion; scenarioCount = $manifestScenarios.Count; scenarios = $manifestScenarios }
$runId = "run-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())-$([Guid]::NewGuid().ToString('N').Substring(0, 8))"
$runDirectory = Join-Path $root "runtime\headless-runs\$runId"
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
$reports = @()
$manifestPath = Join-Path $runDirectory 'matrix-manifest.json'
Write-BoundedJson $manifestPath $manifestSummary $MaxManifestBytes 'matrix manifest'
foreach ($scenario in $selected) {
	$preflight = if ($CapabilityProbe) { [pscustomobject]@{ Available = $true; Reason = $null } } else { Test-ProviderPreflight ([string] $scenario.provider) }
	if (-not $preflight.Available) {
		$status = if ($RequireAll) { 'FAILED' } else { 'SKIPPED' }
		$reports += [pscustomobject]@{ status = $status; scenarioId = (ConvertTo-BoundedText $scenario.id); provider = (ConvertTo-BoundedText $scenario.provider); skippedReason = (ConvertTo-BoundedText $preflight.Reason $MaxDiagnosticText); cleanup = [pscustomobject]@{ status = 'NOT_STARTED' } }
		continue
	}
	try {
		$reports += Invoke-Scenario $scenario $root $runDirectory $ServerTemplate $MatrixPath $java $node $builtJar -Keep:$KeepArtifacts
	} catch {
		$reports += [pscustomobject]@{ status = 'FAILED'; scenarioId = (ConvertTo-BoundedText $scenario.id); provider = (ConvertTo-BoundedText $scenario.provider); cleanup = [pscustomobject]@{ status = 'FAILED' }; diagnostics = (ConvertTo-BoundedText $_.Exception.Message $MaxDiagnosticText) }
	}
}
$failed = @($reports | Where-Object { $_.status -eq 'FAILED' })
$matrixStatus = if ($failed.Count -gt 0) { 'FAILED' } elseif (@($reports | Where-Object { $_.status -eq 'PASSED' }).Count -gt 0) { 'PASSED' } else { 'SKIPPED' }
$matrixReport = [pscustomobject]@{ runId = $runId; status = $matrixStatus; requireAll = [bool] $RequireAll; scenarios = @($reports | Select-Object -First $MaxSelectedScenarios); reportPath = (Join-Path $runDirectory 'matrix-report.json'); artifactsKept = [bool] $KeepArtifacts }
Write-BoundedJson $matrixReport.reportPath $matrixReport $MaxMatrixReportBytes 'matrix report'
$matrixReport | ConvertTo-Json -Depth 20
if ($failed.Count -gt 0) {
	$diagnostics = (@($failed | ForEach-Object { if ($_.diagnostics) { $_.diagnostics } }) -join '; ')
	if ([string]::IsNullOrWhiteSpace($diagnostics)) { $diagnostics = 'unknown failure' }
	throw "Headless provider matrix contains failed required scenarios: $diagnostics"
}
