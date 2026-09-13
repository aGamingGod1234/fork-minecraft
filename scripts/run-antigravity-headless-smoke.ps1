[CmdletBinding()]
param(
	[string] $ProjectRoot,
	[string] $Model = 'gemini-3.1-pro',
	[string] $Thinking = 'low',
	[int] $StartupTimeoutSeconds = 120,
	[int] $GoalTimeoutSeconds = 180
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'offline-server-policy.ps1')
. (Join-Path $PSScriptRoot 'project-metadata.ps1')

$MinecraftPort = 25565
$BridgePort = 25570
$PollMilliseconds = 250
$StatusPollSeconds = 2
$ServerStopTimeoutSeconds = 30

function Quote-Argument([string] $Value) {
	return '"' + $Value.Replace('"', '\"') + '"'
}

function Wait-Condition(
	[scriptblock] $Condition,
	[int] $TimeoutSeconds,
	[string] $FailureMessage
) {
	$deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
	while ([DateTime]::UtcNow -lt $deadline) {
		if (& $Condition) {
			return
		}
		Start-Sleep -Milliseconds $PollMilliseconds
	}
	throw $FailureMessage
}

function Read-Log([string] $Path) {
	if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
		return ''
	}
	$stream = [IO.FileStream]::new(
		$Path,
		[IO.FileMode]::Open,
		[IO.FileAccess]::Read,
		[IO.FileShare]::ReadWrite -bor [IO.FileShare]::Delete
	)
	try {
		$reader = [IO.StreamReader]::new($stream, [Text.Encoding]::UTF8, $true)
		try {
			return $reader.ReadToEnd()
		} finally {
			$reader.Dispose()
		}
	} finally {
		$stream.Dispose()
	}
}

function Start-RedirectedProcess(
	[string] $FileName,
	[string] $Arguments,
	[string] $WorkingDirectory,
	[hashtable] $Environment = @{}
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
	if (-not $process.Start()) {
		throw "Could not start process: $FileName"
	}
	return @{
		Process = $process
		Stdout = $process.StandardOutput.ReadToEndAsync()
		Stderr = $process.StandardError.ReadToEndAsync()
	}
}

function Stop-ProcessTree([int] $ProcessId) {
	$children = @(
		Get-CimInstance Win32_Process -Filter "ParentProcessId=$ProcessId" -ErrorAction SilentlyContinue
	)
	foreach ($child in $children) {
		Stop-ProcessTree -ProcessId ([int] $child.ProcessId)
	}
	Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
}

function Test-Port([int] $Port, [string] $State = 'Listen') {
	return $null -ne (
		Get-NetTCPConnection -LocalPort $Port -State $State -ErrorAction SilentlyContinue |
			Select-Object -First 1
	)
}

if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
	$ProjectRoot = Split-Path -Parent $PSScriptRoot
}
$Project = [IO.Path]::GetFullPath($ProjectRoot)
$Server = Join-Path $Project 'runtime\server'
$Coordinator = Join-Path $Project 'coordinator'
$Java = Join-Path $Project 'runtime\toolchains\temurin-25\jdk-25.0.3+9\bin\java.exe'
$ServerLauncher = Join-Path $Server 'fabric-server-launch.jar'
$ServerProperties = Join-Path $Server 'server.properties'
$BuiltMod = Resolve-ArenaModJar $Project
$ServerMod = Join-Path $Server "mods\$([IO.Path]::GetFileName($BuiltMod))"
$CoordinatorMain = Join-Path $Coordinator 'src\dynamic-main.mjs'
$CoordinatorConfig = Join-Path $Coordinator 'config\dynamic-agents.json'
$SecretPath = Join-Path $Project 'runtime\bridge-secret.txt'
$ServerLog = Join-Path $Server 'logs\latest.log'

foreach ($required in @(
	$Java,
	$ServerLauncher,
	$ServerProperties,
	$BuiltMod,
	$CoordinatorMain,
	$CoordinatorConfig,
	$SecretPath
)) {
	if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
		throw "Missing headless smoke prerequisite: $required"
	}
}
Assert-ArenaOfflineServerLoopback $ServerProperties -RequireOffline
if ((Test-Port $MinecraftPort) -or (Test-Port $BridgePort)) {
	throw "Headless smoke requires free ports $MinecraftPort and $BridgePort"
}

$Node = (Get-Command node -ErrorAction Stop).Source
$Agy = (Get-Command agy -ErrorAction Stop).Source
$null = & $Agy --version
if ($LASTEXITCODE -ne 0) {
	throw 'Antigravity CLI is not available'
}

Copy-Item -LiteralPath $BuiltMod -Destination $ServerMod -Force
$Secret = [IO.File]::ReadAllText($SecretPath).Trim()
if ($Secret.Length -lt 32) {
	throw 'Bridge secret must contain at least 32 characters'
}

$StartedAt = [DateTime]::UtcNow
$UniqueSuffix = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$AgentName = "AntigravitySmoke$UniqueSuffix"
$ExpectedCliModel = "$Model-$Thinking"
$ServerHandle = $null
$CoordinatorHandle = $null
$AgyProcessObserved = $false
$ShortId = $null
$Completed = $false
$FinalRevision = $null
$Result = $null
$Failure = $null

try {
	$bridgeArgument = "-Darenaagents.bridgeSecretFile=$SecretPath"
	$serverArguments = "$(Quote-Argument $bridgeArgument) -Xms1G -Xmx4G -jar $(Quote-Argument $ServerLauncher) nogui"
	$ServerHandle = Start-RedirectedProcess $Java $serverArguments $Server
	$ServerHandle.Process.StandardInput.WriteLine('')
	$ServerHandle.Process.StandardInput.Flush()
	Wait-Condition {
		(Test-Port $MinecraftPort) -and (Read-Log $ServerLog).Contains('Done (')
	} $StartupTimeoutSeconds 'Minecraft server did not become ready'

	$coordinatorArguments = "$(Quote-Argument $CoordinatorMain) --config $(Quote-Argument $CoordinatorConfig)"
	$CoordinatorHandle = Start-RedirectedProcess $Node $coordinatorArguments $Coordinator @{
		ARENA_AGENT_BRIDGE_SECRET = $Secret
	}
	Wait-Condition {
		Test-Port $BridgePort 'Established'
	} $StartupTimeoutSeconds 'Coordinator did not establish the authenticated bridge'

	$serverInput = $ServerHandle.Process.StandardInput
	$serverInput.WriteLine('execute in minecraft:overworld run forceload add 60 60 75 75')
	$serverInput.Flush()
	Start-Sleep -Seconds 2
	$serverInput.WriteLine('execute in minecraft:overworld run fill 60 70 60 75 70 75 minecraft:stone')
	$serverInput.WriteLine('execute in minecraft:overworld run fill 60 71 60 75 75 75 minecraft:air')
	$serverInput.Flush()
	Start-Sleep -Seconds 1
	$serverInput.WriteLine(
		"execute in minecraft:overworld positioned 68 71 68 run codex summon gemini $Model $Thinking $AgentName"
	)
	$serverInput.Flush()
	Wait-Condition {
		$log = Read-Log $ServerLog
		$match = [regex]::Match(
			$log,
			"Summoned gemini agent $([regex]::Escape($AgentName))/(?<id>[0-9a-f]{8}) " +
				"\[$([regex]::Escape($Model)) . $([regex]::Escape($Thinking))\]"
		)
		if ($match.Success) {
			$script:ShortId = $match.Groups['id'].Value
			return $true
		}
		return $false
	} 30 'Gemini NPC was not summoned with the expected model and thinking tag'

	$goalLogOffset = (Read-Log $ServerLog).Length
	$serverInput.WriteLine(
		"codex start $AgentName Wait safely for 100 milliseconds and then complete the goal."
	)
	$serverInput.Flush()
	Wait-Condition {
		(Read-Log $ServerLog).Substring($goalLogOffset).Contains(
			"AI agent start accepted for $AgentName/$ShortId"
		)
	} 30 'Minecraft did not accept the Antigravity NPC goal'

	$deadline = [DateTime]::UtcNow.AddSeconds($GoalTimeoutSeconds)
	while ([DateTime]::UtcNow -lt $deadline -and -not $Completed) {
		$agyProcess = Get-CimInstance Win32_Process -Filter "Name='agy.exe'" -ErrorAction SilentlyContinue |
			Where-Object { $_.CommandLine -match [regex]::Escape($ExpectedCliModel) } |
			Select-Object -First 1
		if ($null -ne $agyProcess) {
			$AgyProcessObserved = $true
		}
		$serverInput.WriteLine("codex status $AgentName")
		$serverInput.Flush()
		Start-Sleep -Seconds $StatusPollSeconds
		$goalLog = (Read-Log $ServerLog).Substring($goalLogOffset)
		if ($goalLog -match "$([regex]::Escape($AgentName))/$ShortId " +
				"\[$([regex]::Escape($Model)) . $([regex]::Escape($Thinking))\] " +
				"state=ERROR") {
			throw 'Antigravity NPC entered ERROR during the live goal'
		}
		if ($goalLog -match "$([regex]::Escape($AgentName))/$ShortId " +
				"\[$([regex]::Escape($Model)) . $([regex]::Escape($Thinking))\] " +
				"state=DEAD") {
			throw 'Antigravity NPC died during the live goal'
		}
		$completionMatch = [regex]::Match(
			$goalLog,
			"$([regex]::Escape($AgentName))/$ShortId " +
				"\[$([regex]::Escape($Model)) . $([regex]::Escape($Thinking))\] " +
				"state=IDLE revision=(?<revision>[1-9][0-9]*) queued=0 goal=none"
		)
		if ($completionMatch.Success) {
			$Completed = $true
			$FinalRevision = [int] $completionMatch.Groups['revision'].Value
		}
	}
	if (-not $Completed) {
		throw "Antigravity NPC did not complete the live goal within $GoalTimeoutSeconds seconds"
	}

	$Workspace = Get-ChildItem -LiteralPath (Join-Path $Project 'runtime\agent-workspaces\gemini') -Directory |
		Where-Object { $_.LastWriteTimeUtc -ge $StartedAt.AddSeconds(-2) } |
		Sort-Object LastWriteTimeUtc -Descending |
		Select-Object -First 1
	if ($null -eq $Workspace) {
		throw 'No isolated Gemini workspace was created for the live agent'
	}
	if (-not $AgyProcessObserved) {
		throw "The expected Antigravity process model '$ExpectedCliModel' was not observed"
	}

	$Result = [pscustomobject]@{
		success = $true
		provider = 'gemini'
		backend = 'antigravity-cli'
		antigravityVersion = (& $Agy --version).Trim()
		model = $Model
		thinking = $Thinking
		cliModel = $ExpectedCliModel
		agentName = $AgentName
		agentShortId = $ShortId
		finalState = 'IDLE'
		goalRevision = $FinalRevision
		workspace = $Workspace.FullName
		agyProcessObserved = $AgyProcessObserved
		modSha256 = (Get-FileHash -LiteralPath $ServerMod -Algorithm SHA256).Hash.ToLowerInvariant()
	}
} catch {
	$Failure = $_
} finally {
	if ($null -ne $ServerHandle -and -not $ServerHandle.Process.HasExited) {
		try {
			if (-not [string]::IsNullOrWhiteSpace($ShortId)) {
				$ServerHandle.Process.StandardInput.WriteLine("codex remove $AgentName")
				$ServerHandle.Process.StandardInput.Flush()
				Start-Sleep -Milliseconds $PollMilliseconds
			}
			$ServerHandle.Process.StandardInput.WriteLine(
				'execute in minecraft:overworld run forceload remove 60 60 75 75'
			)
			$ServerHandle.Process.StandardInput.WriteLine('stop')
			$ServerHandle.Process.StandardInput.Flush()
			if (-not $ServerHandle.Process.WaitForExit($ServerStopTimeoutSeconds * 1000)) {
				Stop-ProcessTree $ServerHandle.Process.Id
			}
		} catch {
			Stop-ProcessTree $ServerHandle.Process.Id
		}
	}
	if ($null -ne $CoordinatorHandle -and -not $CoordinatorHandle.Process.HasExited) {
		Stop-ProcessTree $CoordinatorHandle.Process.Id
	}
	Wait-Condition {
		-not (Test-Port $MinecraftPort) -and -not (Test-Port $BridgePort)
	} 30 'Headless smoke cleanup left a listener running'
}

if ($null -ne $Failure) {
	$coordinatorError = ''
	if ($null -ne $CoordinatorHandle) {
		$coordinatorError = [string] $CoordinatorHandle.Stderr.Result
	}
	$errorTail = (($coordinatorError -split "\r?\n") | Select-Object -Last 20) -join [Environment]::NewLine
	throw "$($Failure.Exception.Message)$([Environment]::NewLine)Coordinator stderr:$([Environment]::NewLine)$errorTail"
}

$Result | ConvertTo-Json -Depth 4
