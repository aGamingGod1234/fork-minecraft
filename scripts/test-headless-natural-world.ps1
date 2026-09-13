[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$project = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$wrapper = Join-Path $PSScriptRoot 'run-headless-provider-matrix.ps1'
$tokens = $null; $errors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($wrapper, [ref] $tokens, [ref] $errors)
if ($errors.Count -gt 0) { throw $errors[0].Message }
foreach ($definition in @($ast.FindAll({ param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $true))) { Invoke-Expression $definition.Extent.Text }
. (Join-Path $PSScriptRoot 'offline-server-policy.ps1')

$CapabilityProbe = $false; $RequireAll = $true
$MaxManifestBytes = 65536; $MaxMatrixReportBytes = 262144; $MaxDiagnosticText = 4096
$StartupTimeoutSeconds = 1; $CleanupTimeoutSeconds = 1; $RunnerGraceSeconds = 1
$GracefulStopTimeoutMilliseconds = 1; $StartupBindRetries = 0; $CoordinatorBindRetries = 0
$script:nextPort = 40000; $script:starts = @(); $script:launchMode = 'stop'
function Protect-LocalFile([string] $Path) { }
function Get-FileHash { throw 'Manifest hashing must not depend on inherited PowerShell module paths' }
function New-Secret { return 'fixture-private-value' }
function Reserve-FreePort([int] $Preferred = 0, [int[]] $Exclude = @()) { $script:nextPort += 1; return $script:nextPort }
function Get-ConfiguredPort([string] $Name) { return 0 }
function Stop-TrackedProcessIds($ProcessIds) { }
function Assert-TrackedProcessIdsGone($ProcessIds) { }
function Add-ProcessTreeSnapshot($ProcessIds, $Identity, $Snapshot) { }
function Complete-RedirectedProcess($Handle) { }
function Wait-Condition($Condition, $Timeout, $Message) { }
function Measure-RunnerResourcesUntilExit($Runner, $Handles, $Identities, $Deadline) { return [pscustomobject]@{ processCount = 2; peakRssBytes = 0 } }
function Start-RedirectedProcess([string] $File, [string] $Arguments, [string] $WorkingDirectory, [string] $Stdout, [string] $Stderr, $Environment) {
	$script:starts += [pscustomobject]@{ file = $File; arguments = $Arguments }
	if ($script:launchMode -eq 'stop') { throw 'fixture-stop-before-launch' }
	if ($Arguments -match 'player-capability-probe\.mjs') {
		if ($Arguments -notmatch '--run-directory "([^"]+)"') { throw 'Missing isolated probe output directory' }
		[IO.File]::WriteAllText((Join-Path $Matches[1] 'player-capability-report.json'), '{"status":"PASSED","checks":[{"name":"fixture","passed":true}]}')
	}
	return @{ Process = [pscustomobject]@{ Id = (100 + $script:starts.Count); HasExited = $true; ExitCode = 0 }; Identity = [pscustomobject]@{ ProcessId = (100 + $script:starts.Count); ParentProcessId = 1; CreationDate = 'fixture' } }
}
function Assert-True([bool] $Condition, [string] $Message) { if (-not $Condition) { throw $Message } }

$temporaryRoot = [IO.Path]::GetFullPath((Join-Path ([IO.Path]::GetTempPath()) ('arena-natural-world-test-' + [Guid]::NewGuid().ToString('N'))))
$temporaryBoundary = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
if (-not $temporaryRoot.StartsWith($temporaryBoundary, [StringComparison]::OrdinalIgnoreCase)) { throw 'Fixture directory escaped the system temporary directory' }
try {
	$template = Join-Path $temporaryRoot 'template'
	$runs = Join-Path $temporaryRoot 'runs'
	$config = Join-Path $temporaryRoot 'coordinator\config'
	New-Item -ItemType Directory -Path $template, $runs, $config -Force | Out-Null
	$properties = Join-Path $template 'server.properties'
	[IO.File]::WriteAllText($properties, "online-mode=false`nserver-ip=127.0.0.1`nlevel-seed=99`nlevel-type=minecraft:flat`ndifficulty=peaceful`n")
	[IO.File]::WriteAllText((Join-Path $config 'dynamic-agents.json'), '{"bridge":{"host":"127.0.0.1","port":1},"codex":{}}')
	$jar = Join-Path $temporaryRoot 'arena-fixture.jar'
	[IO.File]::WriteAllText($jar, 'fixture')
	$world = [pscustomobject]@{ mode = 'natural'; seed = '9223372036854775807'; generator = 'minecraft:normal'; difficulty = 'hard'; gameMode = 'survival'; rules = [pscustomobject]@{}; spawn = [pscustomobject]@{ policy = 'world_spawn' } }
	$scenario = [pscustomobject]@{ id = 'natural-fixture'; provider = 'codex'; model = 'fixture'; reasoningEffort = 'low'; serviceTier = 'priority'; timeoutMs = 1000; world = $world }
	$report = Invoke-Scenario $scenario $temporaryRoot $runs $template 'fixture-matrix.json' 'fixture-java' 'fixture-node' $jar -Keep
	Assert-True ($report.status -eq 'FAILED' -and $report.wrapperDiagnostics -eq 'fixture-stop-before-launch') 'Fixture did not stop before any process launch'
	$actualProperties = Get-Content -LiteralPath (Join-Path $report.artifacts.serverDirectory 'server.properties') -Raw
	foreach ($expected in @('level-seed=9223372036854775807', 'level-type=minecraft:normal', 'difficulty=hard', 'gamemode=survival', 'generate-structures=true')) { Assert-True ($actualProperties.Contains($expected)) "Missing natural property $expected" }
	Assert-True ((Get-Content -LiteralPath $properties -Raw).Contains('level-seed=99')) 'Launcher changed its source template'
	Assert-True (-not (Test-Path -LiteralPath (Join-Path $report.artifacts.serverDirectory $report.artifacts.levelName))) 'Natural world existed before server launch'
	$identityPath = Join-Path (Split-Path -Parent $report.artifacts.serverDirectory) 'world-manifest.json'
	$identity = Get-Content -LiteralPath $identityPath -Raw | ConvertFrom-Json
	Assert-True ($identity.fresh -eq $true -and $identity.world.seed -ceq '9223372036854775807' -and $identity.worldId -eq $report.artifacts.levelName) 'Fresh-world identity lost its exact settings'
	Assert-True ($identity.modSha256 -ceq 'f16d05ec6b29248d2c61adb1e9263f78e4f7bace1b955014a2d17872cfe4064d') 'Fresh-world identity lost the exact mod hash'
	Assert-True (@(Get-ChildItem -LiteralPath $report.artifacts.providerWorkspace -Recurse -File).Count -eq 0) 'Evaluator files leaked into the provider workspace'
	Write-Output 'PASS natural launch writes exact settings and fresh-world identity without changing the template'

	$saved = Join-Path $template 'saved-world'
	New-Item -ItemType Directory -Path $saved | Out-Null
	[IO.File]::WriteAllText((Join-Path $saved 'level.dat'), 'fixture')
	$rejected = $false
	try { $null = Invoke-Scenario $scenario $temporaryRoot $runs $template 'fixture-matrix.json' 'fixture-java' 'fixture-node' $jar -Keep }
	catch { $rejected = $_.Exception.Message -match 'without saved worlds' }
	Assert-True $rejected 'Natural launcher accepted a saved user world'
	Assert-True ($script:starts.Count -eq 1) 'Rejected natural world started a process'
	Write-Output 'PASS saved-world templates are rejected before any launch'

	$CapabilityProbe = $true; $script:launchMode = 'probe'; $script:starts = @()
	$scenario.world = [pscustomobject]@{ mode = 'arena' }
	$probeReport = Invoke-Scenario $scenario $temporaryRoot $runs $template 'fixture-matrix.json' 'fixture-java' 'fixture-node' $jar -Keep
	Assert-True ($probeReport.status -eq 'PASSED') 'Provider-free probe result was not retained'
	Assert-True ($script:starts.Count -eq 2) 'Provider-free probe must launch only Fabric and the probe process'
	Assert-True ($script:starts[0].arguments -match 'coordinatorAutoStart=false') 'Fabric may start an untracked coordinator'
	Assert-True ($script:starts[1].arguments -match 'player-capability-probe\.mjs' -and $script:starts[1].arguments -match '--bridge-secret-file') 'Probe did not receive the production bridge connection'
	Assert-True (@($script:starts | Where-Object { $_.arguments -match 'dynamic-main|headless-matrix\.mjs' }).Count -eq 0) 'Provider-free probe launched the model coordinator'
	Write-Output 'PASS capability probe bypasses the model coordinator and retains its report'
} finally {
	$resolvedTarget = [IO.Path]::GetFullPath($temporaryRoot)
	if (-not $resolvedTarget.StartsWith($temporaryBoundary, [StringComparison]::OrdinalIgnoreCase) -or -not ([IO.Path]::GetFileName($resolvedTarget).StartsWith('arena-natural-world-test-'))) { throw 'Refusing to remove an unexpected fixture directory' }
	if (Test-Path -LiteralPath $resolvedTarget) { Remove-Item -LiteralPath $resolvedTarget -Recurse -Force }
}
