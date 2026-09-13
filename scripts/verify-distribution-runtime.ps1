[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$helperPath = Join-Path $PSScriptRoot 'distribution-runtime.ps1'
if (-not (Test-Path -LiteralPath $helperPath -PathType Leaf)) {
	throw "Missing coordinator runtime deployment helper: $helperPath"
}

. $helperPath

function Assert-Equal {
	param([object] $Expected, [object] $Actual, [string] $Message)
	if ($Expected -ne $Actual) { throw "$Message. Expected '$Expected', got '$Actual'." }
}

function Assert-CurrentUserFullControl {
	param([string] $Path, [string] $Message)
	if ($env:OS -ne 'Windows_NT') { return }
	$currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().User
	$acl = Get-Acl -LiteralPath $Path
	$rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
	$matches = @($rules | Where-Object {
		$_.IdentityReference -eq $currentUser -and
		$_.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
		($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -eq [Security.AccessControl.FileSystemRights]::FullControl
	})
	if ($matches.Count -eq 0) { throw $Message }
}

function Write-TestCoordinatorManifest {
	param([string] $CoordinatorRoot)
	$manifestPath = Join-Path $CoordinatorRoot '.arena-agents-bundle-manifest'
	$lines = @(
		Get-ChildItem -LiteralPath $CoordinatorRoot -Recurse -File -Force | Where-Object {
			$_.FullName -ne $manifestPath
		} | ForEach-Object {
			$relative = $_.FullName.Substring($CoordinatorRoot.Length + 1).Replace('\', '/')
			$hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
			"$hash $relative"
		} | Sort-Object
	)
	[IO.File]::WriteAllText($manifestPath, (($lines -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("arena-agents-runtime-test-" + [Guid]::NewGuid().ToString('N'))
try {
	$sourceRoot = Join-Path $testRoot 'source'
	$installedRoot = Join-Path $testRoot 'installed'
	$sourceCoordinator = Join-Path $sourceRoot 'coordinator'
	$activeCoordinator = Join-Path $installedRoot 'coordinator'
	$sourceNode = Join-Path $sourceRoot $(if ($env:OS -eq 'Windows_NT') { 'runtime\toolchains\node\node.exe' } else { 'runtime/toolchains/node/bin/node' })
	$activeNode = Join-Path $installedRoot $(if ($env:OS -eq 'Windows_NT') { 'runtime\toolchains\node\node.exe' } else { 'runtime/toolchains/node/bin/node' })
	New-Item -ItemType Directory -Force -Path (Join-Path $sourceCoordinator 'src'), (Join-Path $sourceCoordinator 'config'), $activeCoordinator, (Split-Path -Parent $sourceNode) | Out-Null
	[IO.File]::WriteAllText((Join-Path $sourceCoordinator 'src\dynamic-main.mjs'), 'new-runtime')
	[IO.File]::WriteAllText((Join-Path $sourceCoordinator 'config\dynamic-agents.json'), '{"version":"new"}')
	[IO.File]::WriteAllText((Join-Path $sourceCoordinator 'package.json'), '{"name":"arena-agents-test"}')
	Write-TestCoordinatorManifest $sourceCoordinator
	[IO.File]::WriteAllText($sourceNode, 'node-runtime-v1')
	[IO.File]::WriteAllText((Join-Path $activeCoordinator 'legacy.txt'), 'old-runtime')
	if ($env:OS -eq 'Windows_NT') {
		$foreignRuntime = Join-Path $testRoot 'foreign-runtime'
		$foreignNode = Join-Path $foreignRuntime 'node.exe'
		New-Item -ItemType Directory -Force -Path $foreignRuntime | Out-Null
		[IO.File]::WriteAllText($foreignNode, 'foreign-acl-node')
		$icacls = Join-Path $env:SystemRoot 'System32\icacls.exe'
		& $icacls $foreignRuntime '/inheritance:r' '/grant:r' '*S-1-5-32-545:(OI)(CI)RX' '/Q' | Out-Null
		if ($LASTEXITCODE -eq 0) { & $icacls $foreignNode '/inheritance:r' '/grant:r' '*S-1-5-32-545:RX' '/Q' | Out-Null }
		if ($LASTEXITCODE -ne 0) { throw 'Could not create the foreign Node ACL regression fixture.' }
		Grant-ArenaRuntimeCurrentUserAccess -Path $foreignRuntime
		Assert-CurrentUserFullControl $foreignNode 'Runtime ACL normalization did not replace a foreign deployment-session ACL.'
	}

	$result = Install-ArenaCoordinatorRuntime -SourceRoot $sourceRoot -InstalledPackageRoot $installedRoot
	Assert-Equal 'new-runtime' ([IO.File]::ReadAllText((Join-Path $activeCoordinator 'src\dynamic-main.mjs'))) 'The active coordinator must come from the source package'
	if (Test-Path -LiteralPath (Join-Path $activeCoordinator 'legacy.txt')) { throw 'The active coordinator retained stale files after replacement.' }
	if ([string]::IsNullOrWhiteSpace($result.BackupPath) -or -not (Test-Path -LiteralPath (Join-Path $result.BackupPath 'legacy.txt'))) {
		throw 'The replaced coordinator was not retained in a backup.'
	}
	Assert-Equal 'node-runtime-v1' ([IO.File]::ReadAllText($activeNode)) 'The bundled Node.js runtime must be installed with the coordinator'
	if (-not (Test-Path -LiteralPath (Join-Path $activeCoordinator '.arena-agents-bundle-manifest') -PathType Leaf)) {
		throw 'Runtime promotion dropped the Java coordinator generation manifest.'
	}
	$generationStatePath = Join-Path $installedRoot 'runtime\coordinator-generation.properties'
	$installedGeneration = (Get-FileHash -LiteralPath (Join-Path $activeCoordinator '.arena-agents-bundle-manifest') -Algorithm SHA256).Hash.ToLowerInvariant()
	$generationState = ConvertFrom-StringData (Get-Content -LiteralPath $generationStatePath -Raw)
	Assert-Equal $installedGeneration $generationState.activeGeneration 'Runtime promotion must commit the matching Java active generation'
	Assert-Equal $installedGeneration $generationState.candidateGeneration 'A newly deployed coordinator remains a candidate until startup verification'
	$verifiedState = @(
		'# Arena Agents coordinator generation state',
		'phase=ready',
		"activeGeneration=$installedGeneration",
		"verifiedGeneration=$installedGeneration",
		'candidateGeneration=',
		'lastKnownGoodGeneration=',
		'stagingDirectory=',
		'previousActiveGeneration=',
		'rejectedGeneration='
	)
	[IO.File]::WriteAllText($generationStatePath, (($verifiedState -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
	Assert-CurrentUserFullControl $activeNode 'Runtime promotion imported a foreign Node ACL that excludes the interactive Windows user.'
	$activeBeforeMissingManifestProbe = @(Get-ChildItem -LiteralPath $activeCoordinator -Recurse -File -Force | Sort-Object FullName | ForEach-Object { $_.FullName + ':' + (Get-FileHash $_.FullName -Algorithm SHA256).Hash })
	Remove-Item -LiteralPath (Join-Path $sourceCoordinator '.arena-agents-bundle-manifest') -Force
	$missingManifestRejected = $false
	try { Install-ArenaCoordinatorRuntime -SourceRoot $sourceRoot -InstalledPackageRoot $installedRoot | Out-Null } catch {
		$missingManifestRejected = $_.Exception.Message -match 'manifest'
	}
	if (-not $missingManifestRejected) { throw 'A coordinator package without its Java generation manifest was not rejected.' }
	$activeAfterMissingManifestProbe = @(Get-ChildItem -LiteralPath $activeCoordinator -Recurse -File -Force | Sort-Object FullName | ForEach-Object { $_.FullName + ':' + (Get-FileHash $_.FullName -Algorithm SHA256).Hash })
	if (@(Compare-Object $activeBeforeMissingManifestProbe $activeAfterMissingManifestProbe).Count -ne 0) {
		throw 'Manifest preflight rejection changed the active coordinator.'
	}
	Write-TestCoordinatorManifest $sourceCoordinator
	[IO.File]::WriteAllText((Join-Path $sourceCoordinator 'src\dynamic-main.mjs'), 'new-runtime-v2')
	Write-TestCoordinatorManifest $sourceCoordinator
	[IO.File]::WriteAllText($sourceNode, 'node-runtime-v2')
	foreach ($failurePoint in @('AfterCoordinatorPromotion', 'AfterNodePromotion', 'AfterGenerationStatePromotion')) {
		$failed = $false
		try {
			Install-ArenaCoordinatorRuntime -SourceRoot $sourceRoot -InstalledPackageRoot $installedRoot -FailurePoint $failurePoint | Out-Null
		} catch {
			$failed = $true
		}
		if (-not $failed) { throw "Injected runtime deployment failure did not fail at $failurePoint." }
		Assert-Equal 'new-runtime' ([IO.File]::ReadAllText((Join-Path $activeCoordinator 'src\dynamic-main.mjs'))) "Coordinator rollback failed at $failurePoint"
		Assert-Equal 'node-runtime-v1' ([IO.File]::ReadAllText($activeNode)) "Node.js rollback failed at $failurePoint"
		$rolledBackState = ConvertFrom-StringData (Get-Content -LiteralPath $generationStatePath -Raw)
		Assert-Equal $installedGeneration $rolledBackState.activeGeneration "Generation state rollback failed at $failurePoint"
		$stagingLeaks = @(Get-ChildItem -LiteralPath $installedRoot -Recurse -Directory | Where-Object { $_.Name -like '*.staging-*' })
		if ($stagingLeaks.Count -ne 0) { throw "Runtime rollback retained staging directories at $failurePoint." }
	}
	Write-Host 'PASS: coordinator and Node.js roll back together after either promotion fails'

	$interrupted = $false
	try {
		Install-ArenaCoordinatorRuntime -SourceRoot $sourceRoot -InstalledPackageRoot $installedRoot -FailurePoint CrashAfterCoordinatorPromotion | Out-Null
	} catch {
		$interrupted = $true
	}
	if (-not $interrupted) { throw 'Injected hard runtime interruption did not fail.' }
	if (-not (Test-Path -LiteralPath (Join-Path $installedRoot '.arena-runtime-transaction.json') -PathType Leaf)) {
		throw 'Hard interruption did not retain its recovery journal.'
	}
	Assert-Equal 'node-runtime-v2' ([IO.File]::ReadAllText($activeNode)) 'A hard interruption must leave a runnable active Node.js runtime'
	$recovered = Install-ArenaCoordinatorRuntime -SourceRoot $sourceRoot -InstalledPackageRoot $installedRoot
	Assert-Equal 'new-runtime-v2' ([IO.File]::ReadAllText((Join-Path $activeCoordinator 'src\dynamic-main.mjs'))) 'Recovery must promote a coherent coordinator version'
	Assert-Equal 'node-runtime-v2' ([IO.File]::ReadAllText($activeNode)) 'Recovery must promote the matching bundled Node.js version'
	if (Test-Path -LiteralPath (Join-Path $installedRoot '.arena-runtime-transaction.json')) {
		throw 'Successful interruption recovery retained its transaction journal.'
	}
	$lastKnownGood = Join-Path $installedRoot 'coordinator.last-known-good'
	Assert-Equal 'new-runtime' ([IO.File]::ReadAllText((Join-Path $lastKnownGood 'src\dynamic-main.mjs'))) 'The immediately previous verified coordinator must be Java-visible for rollback'
	$recoveredState = ConvertFrom-StringData (Get-Content -LiteralPath $generationStatePath -Raw)
	Assert-Equal $installedGeneration $recoveredState.verifiedGeneration 'The update must preserve the previous verified generation identity'
	Assert-Equal $installedGeneration $recoveredState.lastKnownGoodGeneration 'The generation state must point at the retained rollback directory'
	Write-Host 'PASS: an interrupted two-directory promotion recovers on the next install'
	Write-Host 'PASS: the immediate verified predecessor remains available to Java rollback'

	$heldLock = [IO.File]::Open(
		(Join-Path $installedRoot '.arena-runtime-install.lock'),
		[IO.FileMode]::OpenOrCreate,
		[IO.FileAccess]::ReadWrite,
		[IO.FileShare]::None
	)
	$concurrentInstallRejected = $false
	try {
		Install-ArenaCoordinatorRuntime -SourceRoot $sourceRoot -InstalledPackageRoot $installedRoot | Out-Null
	} catch {
		$concurrentInstallRejected = $_.Exception.Message -match 'already in progress'
	} finally {
		$heldLock.Dispose()
	}
	if (-not $concurrentInstallRejected) { throw 'A concurrent runtime installation was not rejected by the exclusive lock.' }
	Assert-Equal 'new-runtime-v2' ([IO.File]::ReadAllText((Join-Path $activeCoordinator 'src\dynamic-main.mjs'))) 'Concurrent rejection must not modify the active coordinator'
	Assert-Equal 'node-runtime-v2' ([IO.File]::ReadAllText($activeNode)) 'Concurrent rejection must not modify the active Node.js runtime'
	Write-Host 'PASS: concurrent runtime installation is rejected without changing active files'

	$second = Install-ArenaCoordinatorRuntime -SourceRoot $sourceRoot -InstalledPackageRoot $installedRoot
	Assert-Equal 'new-runtime-v2' ([IO.File]::ReadAllText((Join-Path $activeCoordinator 'src\dynamic-main.mjs'))) 'A repeated install must promote the newest coordinator'
	Assert-Equal 'node-runtime-v2' ([IO.File]::ReadAllText($activeNode)) 'A repeated install must promote the newest bundled Node.js runtime'
	if ($second.BackupPath -eq $recovered.BackupPath -or $second.BackupPath -eq $result.BackupPath) { throw 'Rapid coordinator updates must use unique backup paths.' }
	if ([string]::IsNullOrWhiteSpace($second.NodeBackupPath) -or -not (Test-Path -LiteralPath $second.NodeBackupPath)) {
		throw 'The replaced bundled Node.js runtime was not retained in a backup.'
	}
	Write-Host 'PASS: coordinator runtime deployment replaces stale files and retains a backup'
	foreach ($version in 3..5) {
		[IO.File]::WriteAllText((Join-Path $sourceCoordinator 'src\dynamic-main.mjs'), "new-runtime-v$version")
		Write-TestCoordinatorManifest $sourceCoordinator
		[IO.File]::WriteAllText($sourceNode, "node-runtime-v$version")
		Install-ArenaCoordinatorRuntime -SourceRoot $sourceRoot -InstalledPackageRoot $installedRoot | Out-Null
	}
	Assert-Equal 'new-runtime-v5' ([IO.File]::ReadAllText((Join-Path $activeCoordinator 'src\dynamic-main.mjs'))) 'Repeated installs must retain the newest coordinator'
	Assert-Equal 'node-runtime-v5' ([IO.File]::ReadAllText($activeNode)) 'Repeated installs must retain the newest Node.js runtime'
	$coordinatorBackups = @(Get-ChildItem -LiteralPath (Join-Path $installedRoot 'coordinator-backups') -Directory -Force)
	$nodeBackups = @(Get-ChildItem -LiteralPath (Join-Path $installedRoot 'node-runtime-backups') -Directory -Force)
	Assert-Equal 1 $coordinatorBackups.Count 'Repeated installs must retain only one coordinator backup'
	Assert-Equal 1 $nodeBackups.Count 'Repeated installs must retain only one Node.js backup'
	Write-Host 'PASS: repeated installs keep one bounded last-known-good runtime generation'
	[IO.File]::WriteAllText((Join-Path $sourceCoordinator 'src\dynamic-main.mjs'), 'new-runtime-v6')
	Write-TestCoordinatorManifest $sourceCoordinator
	[IO.File]::WriteAllText($sourceNode, 'node-runtime-v6')
	$deploymentToUndo = Install-ArenaCoordinatorRuntime -SourceRoot $sourceRoot -InstalledPackageRoot $installedRoot
	Assert-Equal 'new-runtime-v6' ([IO.File]::ReadAllText((Join-Path $activeCoordinator 'src\dynamic-main.mjs'))) 'Rollback fixture must install the candidate coordinator'
	Assert-Equal 'node-runtime-v6' ([IO.File]::ReadAllText($activeNode)) 'Rollback fixture must install the candidate Node.js runtime'
	Undo-ArenaCoordinatorRuntimeInstall -InstalledPackageRoot $installedRoot -Deployment $deploymentToUndo
	Assert-Equal 'new-runtime-v5' ([IO.File]::ReadAllText((Join-Path $activeCoordinator 'src\dynamic-main.mjs'))) 'Outer package rollback must restore the previous coordinator'
	Assert-Equal 'node-runtime-v5' ([IO.File]::ReadAllText($activeNode)) 'Outer package rollback must restore the previous Node.js runtime'
	$restoredGeneration = (Get-FileHash -LiteralPath (Join-Path $activeCoordinator '.arena-agents-bundle-manifest') -Algorithm SHA256).Hash.ToLowerInvariant()
	$restoredState = ConvertFrom-StringData (Get-Content -LiteralPath $generationStatePath -Raw)
	Assert-Equal $restoredGeneration $restoredState.activeGeneration 'Outer package rollback must restore generation state with the previous coordinator'
	Write-Host 'PASS: an outer package transaction can roll back a completed runtime promotion'
	Write-Host 'PASS: bundled Node.js deployment and rapid repeated updates are self-contained'
} finally {
	if (Test-Path -LiteralPath $testRoot) {
		$resolvedTestRoot = (Resolve-Path -LiteralPath $testRoot).Path
		if (-not $resolvedTestRoot.StartsWith(([IO.Path]::GetTempPath()), [StringComparison]::OrdinalIgnoreCase)) {
			throw "Refusing to remove test path outside the temporary directory: $resolvedTestRoot"
		}
		if ($env:OS -eq 'Windows_NT') {
			$currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
			& (Join-Path $env:SystemRoot 'System32\icacls.exe') $resolvedTestRoot '/grant:r' "*$currentUser`:F" '/T' '/C' '/Q' | Out-Null
		}
		Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
	}
}
