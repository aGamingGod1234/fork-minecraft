Set-StrictMode -Version Latest

function Assert-ArenaRuntimeChildPath {
	param(
		[Parameter(Mandatory)] [string] $InstalledRoot,
		[Parameter(Mandatory)] [string] $CandidatePath
	)
	$root = [IO.Path]::GetFullPath($InstalledRoot).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar)
	$candidate = [IO.Path]::GetFullPath($CandidatePath)
	$prefix = $root + [IO.Path]::DirectorySeparatorChar
	if (-not $candidate.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
		throw "Refusing to modify runtime path outside the installed package root: $candidate"
	}
	return $candidate
}

function Restore-ArenaRuntimeTransaction {
	param(
		[Parameter(Mandatory)] [string] $InstalledRoot,
		[Parameter(Mandatory)] [string] $JournalPath
	)
	if (-not (Test-Path -LiteralPath $JournalPath -PathType Leaf)) { return }
	try {
		$journal = Get-Content -LiteralPath $JournalPath -Raw | ConvertFrom-Json -ErrorAction Stop
		$runtimes = @(
			[pscustomobject]@{
				Active = Assert-ArenaRuntimeChildPath $InstalledRoot ([string] $journal.Node.Active)
				Backup = Assert-ArenaRuntimeChildPath $InstalledRoot ([string] $journal.Node.Backup)
				Staging = Assert-ArenaRuntimeChildPath $InstalledRoot ([string] $journal.Node.Staging)
				HadActive = [bool] $journal.Node.HadActive
				Label = 'Node.js'
			},
			[pscustomobject]@{
				Active = Assert-ArenaRuntimeChildPath $InstalledRoot ([string] $journal.Coordinator.Active)
				Backup = Assert-ArenaRuntimeChildPath $InstalledRoot ([string] $journal.Coordinator.Backup)
				Staging = Assert-ArenaRuntimeChildPath $InstalledRoot ([string] $journal.Coordinator.Staging)
				HadActive = [bool] $journal.Coordinator.HadActive
				Label = 'coordinator'
			}
		)
		$lastKnownGoodProperty = $journal.PSObject.Properties['LastKnownGood']
		if ($null -ne $lastKnownGoodProperty) {
			$lastKnownGood = $lastKnownGoodProperty.Value
			$runtimes += [pscustomobject]@{
				Active = Assert-ArenaRuntimeChildPath $InstalledRoot ([string] $lastKnownGood.Active)
				Backup = Assert-ArenaRuntimeChildPath $InstalledRoot ([string] $lastKnownGood.Backup)
				Staging = Assert-ArenaRuntimeChildPath $InstalledRoot ([string] $lastKnownGood.Staging)
				HadActive = [bool] $lastKnownGood.HadActive
				Label = 'last-known-good coordinator'
			}
		}
		$failures = [Collections.Generic.List[string]]::new()
		foreach ($runtime in $runtimes) {
			try {
				if (Test-Path -LiteralPath $runtime.Backup) {
					if (Test-Path -LiteralPath $runtime.Active) {
						Remove-Item -LiteralPath $runtime.Active -Recurse -Force -ErrorAction Stop
					}
					New-Item -ItemType Directory -Force -Path (Split-Path -Parent $runtime.Active) | Out-Null
					Move-Item -LiteralPath $runtime.Backup -Destination $runtime.Active -ErrorAction Stop
				} elseif (-not $runtime.HadActive -and (Test-Path -LiteralPath $runtime.Active)) {
					Remove-Item -LiteralPath $runtime.Active -Recurse -Force -ErrorAction Stop
				} elseif ($runtime.HadActive -and -not (Test-Path -LiteralPath $runtime.Active)) {
					throw "The previous active $($runtime.Label) runtime and its backup are both missing."
				}
				if (Test-Path -LiteralPath $runtime.Staging) {
					Remove-Item -LiteralPath $runtime.Staging -Recurse -Force -ErrorAction Stop
				}
			} catch {
				$failures.Add("$($runtime.Label): $($_.Exception.Message)")
			}
		}
		$stateProperty = $journal.PSObject.Properties['GenerationState']
		if ($null -ne $stateProperty) {
			$state = $stateProperty.Value
			$activeState = Assert-ArenaRuntimeChildPath $InstalledRoot ([string] $state.Active)
			$backupState = Assert-ArenaRuntimeChildPath $InstalledRoot ([string] $state.Backup)
			$stagingState = Assert-ArenaRuntimeChildPath $InstalledRoot ([string] $state.Staging)
			try {
				if (Test-Path -LiteralPath $backupState -PathType Leaf) {
					Move-Item -LiteralPath $backupState -Destination $activeState -Force -ErrorAction Stop
				} elseif (-not [bool] $state.HadActive -and (Test-Path -LiteralPath $activeState)) {
					Remove-Item -LiteralPath $activeState -Force -ErrorAction Stop
				} elseif ([bool] $state.HadActive) {
					throw 'The previous coordinator generation state and its backup are both missing.'
				}
				if (Test-Path -LiteralPath $stagingState) {
					Remove-Item -LiteralPath $stagingState -Force -ErrorAction Stop
				}
			} catch {
				$failures.Add("generation state: $($_.Exception.Message)")
			}
		}
		if ($failures.Count -ne 0) {
			throw "Runtime transaction recovery was incomplete: $($failures -join '; ')"
		}
		Remove-Item -LiteralPath $JournalPath -Force -ErrorAction Stop
	} catch {
		throw "Could not recover the interrupted runtime transaction: $($_.Exception.Message)"
	}
}

function Prune-ArenaRuntimeBackups {
	param(
		[Parameter(Mandatory)] [string] $InstalledRoot,
		[Parameter(Mandatory)] [string] $BackupRoot,
		[string] $KeepPath
	)
	$resolvedBackupRoot = Assert-ArenaRuntimeChildPath $InstalledRoot $BackupRoot
	if (-not (Test-Path -LiteralPath $resolvedBackupRoot -PathType Container)) { return }
	$resolvedKeepPath = if ([string]::IsNullOrWhiteSpace($KeepPath)) {
		$null
	} else {
		Assert-ArenaRuntimeChildPath $InstalledRoot $KeepPath
	}
	foreach ($backup in Get-ChildItem -LiteralPath $resolvedBackupRoot -Directory -Force) {
		$resolvedBackup = Assert-ArenaRuntimeChildPath $InstalledRoot $backup.FullName
		if ($null -ne $resolvedKeepPath -and $resolvedBackup.Equals($resolvedKeepPath, [StringComparison]::OrdinalIgnoreCase)) {
			continue
		}
		Remove-Item -LiteralPath $resolvedBackup -Recurse -Force -ErrorAction Stop
	}
}

function Prune-ArenaRuntimeBackupFiles {
	param(
		[Parameter(Mandatory)] [string] $InstalledRoot,
		[Parameter(Mandatory)] [string] $BackupRoot,
		[string] $KeepPath
	)
	$resolvedBackupRoot = Assert-ArenaRuntimeChildPath $InstalledRoot $BackupRoot
	if (-not (Test-Path -LiteralPath $resolvedBackupRoot -PathType Container)) { return }
	$resolvedKeepPath = if ([string]::IsNullOrWhiteSpace($KeepPath)) {
		$null
	} else {
		Assert-ArenaRuntimeChildPath $InstalledRoot $KeepPath
	}
	foreach ($backup in Get-ChildItem -LiteralPath $resolvedBackupRoot -File -Force) {
		$resolvedBackup = Assert-ArenaRuntimeChildPath $InstalledRoot $backup.FullName
		if ($null -ne $resolvedKeepPath -and $resolvedBackup.Equals($resolvedKeepPath, [StringComparison]::OrdinalIgnoreCase)) {
			continue
		}
		Remove-Item -LiteralPath $resolvedBackup -Force -ErrorAction Stop
	}
}

function Grant-ArenaRuntimeCurrentUserAccess {
	param([Parameter(Mandatory)] [string] $Path)
	if ($env:OS -ne 'Windows_NT') { return }
	$resolved = [IO.Path]::GetFullPath($Path)
	$items = @(Get-Item -LiteralPath $resolved -Force) + @(Get-ChildItem -LiteralPath $resolved -Recurse -Force -ErrorAction Stop)
	foreach ($item in $items) {
		if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
			throw "Refusing to normalize a promoted runtime that contains a reparse point: $($item.FullName)"
		}
	}

	$currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().User
	$icacls = Join-Path $env:SystemRoot 'System32\icacls.exe'
	& $icacls $resolved '/grant:r' "*$($currentUser.Value):F" '/T' '/Q' | Out-Null
	if ($LASTEXITCODE -ne 0) { throw "Could not grant the current Windows user access to promoted runtime: $resolved" }
	& $icacls $resolved '/grant:r' "*$($currentUser.Value):(OI)(CI)F" '/Q' | Out-Null
	if ($LASTEXITCODE -ne 0) { throw "Could not make current-user access inheritable for promoted runtime: $resolved" }

	foreach ($item in $items) {
		$acl = Get-Acl -LiteralPath $item.FullName -ErrorAction Stop
		$rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
		$hasFullControl = @($rules | Where-Object {
			$_.IdentityReference -eq $currentUser -and
			$_.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and
			($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -eq [Security.AccessControl.FileSystemRights]::FullControl
		}).Count -gt 0
		if (-not $hasFullControl) { throw "Could not verify current-user access to promoted runtime path: $($item.FullName)" }
	}
}

function Assert-ArenaCoordinatorGeneration {
	param([Parameter(Mandatory)] [string] $CoordinatorRoot)
	$root = [IO.Path]::GetFullPath($CoordinatorRoot)
	$manifestPath = Join-Path $root '.arena-agents-bundle-manifest'
	if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
		throw 'Coordinator generation manifest is missing.'
	}
	$manifestItem = Get-Item -LiteralPath $manifestPath -Force
	if (($manifestItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0 -or $manifestItem.Length -gt 1MB) {
		throw 'Coordinator generation manifest is unsafe.'
	}
	$entries = [Collections.Generic.Dictionary[string,string]]::new([StringComparer]::Ordinal)
	foreach ($line in @(Get-Content -LiteralPath $manifestPath)) {
		if ([string]::IsNullOrWhiteSpace($line)) { continue }
		if ($line -cnotmatch '^(?<Hash>[0-9a-f]{64}) (?<Path>[^\\]+)$') {
			throw "Coordinator generation manifest contains an invalid entry: $line"
		}
		$relative = $Matches.Path
		if ([IO.Path]::IsPathRooted($relative) -or $relative -match '(^|/)\.\.(/|$)' -or $relative.Contains('//')) {
			throw "Coordinator generation manifest contains an unsafe path: $relative"
		}
		if ($entries.ContainsKey($relative)) { throw "Coordinator generation manifest contains a duplicate path: $relative" }
		$entries.Add($relative, $Matches.Hash)
	}
	if ($entries.Count -eq 0) { throw 'Coordinator generation manifest is empty.' }

	$actualFiles = @(
		Get-ChildItem -LiteralPath $root -Recurse -File -Force | Where-Object {
			$_.FullName -ne $manifestPath -and $_.FullName -notmatch '[\\/]__pycache__[\\/]' -and $_.Name -notmatch '\.pyc$'
		} | ForEach-Object { $_.FullName.Substring($root.Length + 1).Replace('\', '/') } | Sort-Object
	)
	$manifestFiles = @($entries.Keys | Sort-Object)
	if (@(Compare-Object -ReferenceObject $manifestFiles -DifferenceObject $actualFiles).Count -ne 0) {
		throw 'Coordinator generation files differ from its manifest.'
	}
	foreach ($relative in $manifestFiles) {
		$file = Join-Path $root $relative.Replace('/', '\')
		$item = Get-Item -LiteralPath $file -Force
		if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) {
			throw "Coordinator generation contains a linked file: $relative"
		}
		$hash = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
		if ($hash -cne $entries[$relative]) { throw "Coordinator generation hash mismatch: $relative" }
	}
}

function Get-ArenaCoordinatorGenerationId {
	param([Parameter(Mandatory)] [string] $CoordinatorRoot)
	Assert-ArenaCoordinatorGeneration -CoordinatorRoot $CoordinatorRoot
	return (Get-FileHash -LiteralPath (Join-Path $CoordinatorRoot '.arena-agents-bundle-manifest') -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-ArenaCoordinatorGenerationState {
	param(
		[Parameter(Mandatory)] [string] $InstalledRoot,
		[Parameter(Mandatory)] [string] $CoordinatorRoot,
		[Parameter(Mandatory)] [string] $Destination
	)
	$generation = Get-ArenaCoordinatorGenerationId -CoordinatorRoot $CoordinatorRoot
	$retained = ''
	$verified = ''
	$lastKnownGood = Join-Path $InstalledRoot 'coordinator.last-known-good'
	if (Test-Path -LiteralPath $lastKnownGood -PathType Container) {
		$retained = Get-ArenaCoordinatorGenerationId -CoordinatorRoot $lastKnownGood
		$currentState = Join-Path $InstalledRoot 'runtime\coordinator-generation.properties'
		if (Test-Path -LiteralPath $currentState -PathType Leaf) {
			$properties = ConvertFrom-StringData (Get-Content -LiteralPath $currentState -Raw)
			if ([string] $properties.verifiedGeneration -ceq $retained) { $verified = $retained }
		}
	}
	$candidate = if ($generation -ceq $verified) { '' } else { $generation }
	$lines = @(
		'# Arena Agents coordinator generation state',
		'phase=ready',
		"activeGeneration=$generation",
		"verifiedGeneration=$verified",
		"candidateGeneration=$candidate",
		"lastKnownGoodGeneration=$retained",
		'stagingDirectory=',
		'previousActiveGeneration=',
		'rejectedGeneration='
	)
	New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
	[IO.File]::WriteAllText($Destination, (($lines -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
}

function Install-ArenaCoordinatorRuntime {
	[CmdletBinding()]
	param(
		[Parameter(Mandatory)] [string] $SourceRoot,
		[Parameter(Mandatory)] [string] $InstalledPackageRoot,
		[ValidateSet('None', 'AfterCoordinatorPromotion', 'AfterNodePromotion', 'AfterGenerationStatePromotion', 'CrashAfterCoordinatorPromotion')]
		[string] $FailurePoint = 'None'
	)

	$resolvedSourceRoot = [IO.Path]::GetFullPath($SourceRoot)
	$resolvedInstalledRoot = [IO.Path]::GetFullPath($InstalledPackageRoot)
	$sourceCoordinator = Join-Path $resolvedSourceRoot 'coordinator'
	$sourceNodeDirectory = Join-Path $resolvedSourceRoot 'runtime\toolchains\node'
	$sourceNode = Join-Path $sourceNodeDirectory $(if ($env:OS -eq 'Windows_NT') { 'node.exe' } else { 'bin/node' })
	foreach ($requiredRelativePath in @('.arena-agents-bundle-manifest', 'src\dynamic-main.mjs', 'config\dynamic-agents.json', 'package.json')) {
		if (-not (Test-Path -LiteralPath (Join-Path $sourceCoordinator $requiredRelativePath) -PathType Leaf)) {
			throw "Coordinator source is missing required file '$requiredRelativePath'."
		}
	}
	Assert-ArenaCoordinatorGeneration -CoordinatorRoot $sourceCoordinator
	if (-not (Test-Path -LiteralPath $sourceNode -PathType Leaf)) {
		throw "Coordinator source is missing its bundled Node.js runtime: $sourceNode"
	}
	$sourceNodeHash = (Get-FileHash -LiteralPath $sourceNode -Algorithm SHA256).Hash

	New-Item -ItemType Directory -Force -Path $resolvedInstalledRoot | Out-Null
	$lockPath = Join-Path $resolvedInstalledRoot '.arena-runtime-install.lock'
	$journalPath = Join-Path $resolvedInstalledRoot '.arena-runtime-transaction.json'
	$journalTempPath = Join-Path $resolvedInstalledRoot '.arena-runtime-transaction.json.tmp'
	$lock = $null
	try {
		try {
			$lock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
		} catch {
			throw "Another Arena Agents runtime installation is already in progress: $($_.Exception.Message)"
		}
		Restore-ArenaRuntimeTransaction -InstalledRoot $resolvedInstalledRoot -JournalPath $journalPath
		if (Test-Path -LiteralPath $journalTempPath) {
			Remove-Item -LiteralPath $journalTempPath -Force -ErrorAction Stop
		}

		$activeCoordinator = Join-Path $resolvedInstalledRoot 'coordinator'
		$activeNodeDirectory = Join-Path $resolvedInstalledRoot 'runtime\toolchains\node'
		$activeGenerationState = Join-Path $resolvedInstalledRoot 'runtime\coordinator-generation.properties'
		$activeLastKnownGood = Join-Path $resolvedInstalledRoot 'coordinator.last-known-good'
		$transactionId = [Guid]::NewGuid().ToString('N')
		$stagingCoordinator = "$activeCoordinator.staging-$transactionId"
		$stagingNodeDirectory = "$activeNodeDirectory.staging-$transactionId"
		$backupPath = Join-Path $resolvedInstalledRoot "coordinator-backups\coordinator-$transactionId"
		$nodeBackupPath = Join-Path $resolvedInstalledRoot "node-runtime-backups\node-$transactionId"
		$generationStateBackupPath = Join-Path $resolvedInstalledRoot "generation-state-backups\state-$transactionId.properties"
		$generationStateStagingPath = Join-Path $resolvedInstalledRoot "runtime\coordinator-generation.properties.staging-$transactionId"
		$lastKnownGoodBackupPath = Join-Path $resolvedInstalledRoot "last-known-good-backups\coordinator-$transactionId"
		$lastKnownGoodStagingPath = "$activeLastKnownGood.staging-$transactionId"
		$hadCoordinator = Test-Path -LiteralPath $activeCoordinator
		$hadNode = Test-Path -LiteralPath $activeNodeDirectory
		$hadGenerationState = Test-Path -LiteralPath $activeGenerationState -PathType Leaf
		$hadLastKnownGood = Test-Path -LiteralPath $activeLastKnownGood -PathType Container
		$retainPreviousVerified = $false
		if ($hadCoordinator -and $hadGenerationState) {
			try {
				$previousState = ConvertFrom-StringData (Get-Content -LiteralPath $activeGenerationState -Raw)
				$previousGeneration = Get-ArenaCoordinatorGenerationId -CoordinatorRoot $activeCoordinator
				$retainPreviousVerified = [string] $previousState.phase -ceq 'ready' -and
					[string] $previousState.activeGeneration -ceq $previousGeneration -and
					[string] $previousState.verifiedGeneration -ceq $previousGeneration
			} catch {
				$retainPreviousVerified = $false
			}
		}
		$journal = [ordered]@{
			Version = 1
			TransactionId = $transactionId
			Coordinator = [ordered]@{
				Active = $activeCoordinator; Backup = $backupPath; Staging = $stagingCoordinator; HadActive = $hadCoordinator
			}
			Node = [ordered]@{
				Active = $activeNodeDirectory; Backup = $nodeBackupPath; Staging = $stagingNodeDirectory; HadActive = $hadNode
			}
			GenerationState = [ordered]@{
				Active = $activeGenerationState; Backup = $generationStateBackupPath; Staging = $generationStateStagingPath; HadActive = $hadGenerationState
			}
			LastKnownGood = [ordered]@{
				Active = $activeLastKnownGood; Backup = $lastKnownGoodBackupPath; Staging = $lastKnownGoodStagingPath; HadActive = $hadLastKnownGood
			}
		}
		if ($hadGenerationState) {
			New-Item -ItemType Directory -Force -Path (Split-Path -Parent $generationStateBackupPath) | Out-Null
			Copy-Item -LiteralPath $activeGenerationState -Destination $generationStateBackupPath -Force -ErrorAction Stop
		}
		[IO.File]::WriteAllText($journalTempPath, ($journal | ConvertTo-Json -Depth 4 -Compress))
		Move-Item -LiteralPath $journalTempPath -Destination $journalPath -Force -ErrorAction Stop

		$leaveInterrupted = $false
		try {
			New-Item -ItemType Directory -Path $stagingCoordinator | Out-Null
			foreach ($entry in Get-ChildItem -LiteralPath $sourceCoordinator -Force) {
				Copy-Item -LiteralPath $entry.FullName -Destination $stagingCoordinator -Recurse -Force
			}
			New-Item -ItemType Directory -Force -Path (Split-Path -Parent $activeNodeDirectory) | Out-Null
			Copy-Item -LiteralPath $sourceNodeDirectory -Destination $stagingNodeDirectory -Recurse -Force
			foreach ($requiredRelativePath in @('.arena-agents-bundle-manifest', 'src\dynamic-main.mjs', 'config\dynamic-agents.json', 'package.json')) {
				if (-not (Test-Path -LiteralPath (Join-Path $stagingCoordinator $requiredRelativePath) -PathType Leaf)) {
					throw "Coordinator staging is missing required file '$requiredRelativePath'."
				}
			}
			Assert-ArenaCoordinatorGeneration -CoordinatorRoot $stagingCoordinator
			$stagedNode = Join-Path $stagingNodeDirectory $(if ($env:OS -eq 'Windows_NT') { 'node.exe' } else { 'bin/node' })
			if (-not (Test-Path -LiteralPath $stagedNode -PathType Leaf)) {
				throw "Coordinator staging is missing its bundled Node.js runtime: $stagedNode"
			}
			if ((Get-FileHash -LiteralPath $stagedNode -Algorithm SHA256).Hash -ne $sourceNodeHash) {
				throw 'Staged Node.js runtime differs from the bundled source runtime.'
			}

			if ($hadNode) {
				New-Item -ItemType Directory -Force -Path (Split-Path -Parent $nodeBackupPath) | Out-Null
				Move-Item -LiteralPath $activeNodeDirectory -Destination $nodeBackupPath -ErrorAction Stop
			}
			Move-Item -LiteralPath $stagingNodeDirectory -Destination $activeNodeDirectory -ErrorAction Stop
			Grant-ArenaRuntimeCurrentUserAccess -Path $activeNodeDirectory
			$activeNode = Join-Path $activeNodeDirectory $(if ($env:OS -eq 'Windows_NT') { 'node.exe' } else { 'bin/node' })
			if ((Get-FileHash -LiteralPath $activeNode -Algorithm SHA256).Hash -ne $sourceNodeHash) {
				throw 'Installed Node.js runtime differs from the bundled source runtime.'
			}
			if ($FailurePoint -eq 'AfterNodePromotion') { throw 'Injected failure after Node.js promotion.' }
			if ($hadCoordinator) {
				New-Item -ItemType Directory -Force -Path (Split-Path -Parent $backupPath) | Out-Null
				Move-Item -LiteralPath $activeCoordinator -Destination $backupPath -ErrorAction Stop
			}
			if ($retainPreviousVerified) {
				Copy-Item -LiteralPath $backupPath -Destination $lastKnownGoodStagingPath -Recurse -Force -ErrorAction Stop
				Assert-ArenaCoordinatorGeneration -CoordinatorRoot $lastKnownGoodStagingPath
				if ($hadLastKnownGood) {
					New-Item -ItemType Directory -Force -Path (Split-Path -Parent $lastKnownGoodBackupPath) | Out-Null
					Move-Item -LiteralPath $activeLastKnownGood -Destination $lastKnownGoodBackupPath -ErrorAction Stop
				}
				Move-Item -LiteralPath $lastKnownGoodStagingPath -Destination $activeLastKnownGood -ErrorAction Stop
			}
			Move-Item -LiteralPath $stagingCoordinator -Destination $activeCoordinator -ErrorAction Stop
			Grant-ArenaRuntimeCurrentUserAccess -Path $activeCoordinator
			Assert-ArenaCoordinatorGeneration -CoordinatorRoot $activeCoordinator
			if ($FailurePoint -eq 'CrashAfterCoordinatorPromotion') {
				$leaveInterrupted = $true
				throw 'Injected hard interruption after coordinator promotion.'
			}
			if ($FailurePoint -eq 'AfterCoordinatorPromotion') { throw 'Injected failure after coordinator promotion.' }
			Write-ArenaCoordinatorGenerationState -InstalledRoot $resolvedInstalledRoot -CoordinatorRoot $activeCoordinator -Destination $generationStateStagingPath
			Move-Item -LiteralPath $generationStateStagingPath -Destination $activeGenerationState -Force -ErrorAction Stop
			if ($FailurePoint -eq 'AfterGenerationStatePromotion') { throw 'Injected failure after coordinator generation state promotion.' }
			Remove-Item -LiteralPath $journalPath -Force -ErrorAction Stop
			try {
				Prune-ArenaRuntimeBackups -InstalledRoot $resolvedInstalledRoot `
					-BackupRoot (Join-Path $resolvedInstalledRoot 'coordinator-backups') `
					-KeepPath $(if ($hadCoordinator) { $backupPath } else { $null })
				Prune-ArenaRuntimeBackups -InstalledRoot $resolvedInstalledRoot `
					-BackupRoot (Join-Path $resolvedInstalledRoot 'node-runtime-backups') `
					-KeepPath $(if ($hadNode) { $nodeBackupPath } else { $null })
				Prune-ArenaRuntimeBackupFiles -InstalledRoot $resolvedInstalledRoot `
					-BackupRoot (Join-Path $resolvedInstalledRoot 'generation-state-backups') `
					-KeepPath $(if ($hadGenerationState) { $generationStateBackupPath } else { $null })
				Prune-ArenaRuntimeBackups -InstalledRoot $resolvedInstalledRoot `
					-BackupRoot (Join-Path $resolvedInstalledRoot 'last-known-good-backups') `
					-KeepPath $(if ($hadLastKnownGood) { $lastKnownGoodBackupPath } else { $null })
			} catch {
				Write-Warning "Runtime update committed, but old backup cleanup will be retried later: $($_.Exception.Message)"
			}
		} catch {
			$promotionFailure = $_
			if (-not $leaveInterrupted) {
				Restore-ArenaRuntimeTransaction -InstalledRoot $resolvedInstalledRoot -JournalPath $journalPath
			}
			throw $promotionFailure
		} finally {
			if (-not $leaveInterrupted -and (Test-Path -LiteralPath $journalTempPath)) {
				Remove-Item -LiteralPath $journalTempPath -Force -ErrorAction SilentlyContinue
			}
		}

		return [PSCustomObject]@{
			ActivePath = $activeCoordinator
			BackupPath = $(if ($hadCoordinator) { $backupPath } else { $null })
			HadCoordinator = $hadCoordinator
			NodePath = Join-Path $activeNodeDirectory $(if ($env:OS -eq 'Windows_NT') { 'node.exe' } else { 'bin/node' })
			NodeDirectory = $activeNodeDirectory
			NodeBackupPath = $(if ($hadNode) { $nodeBackupPath } else { $null })
			HadNode = $hadNode
			GenerationStatePath = $activeGenerationState
			GenerationStateBackupPath = $(if ($hadGenerationState) { $generationStateBackupPath } else { $null })
			HadGenerationState = $hadGenerationState
			LastKnownGoodPath = $(if ($retainPreviousVerified) { $activeLastKnownGood } else { $null })
			LastKnownGoodBackupPath = $(if ($hadLastKnownGood) { $lastKnownGoodBackupPath } else { $null })
			HadLastKnownGood = $hadLastKnownGood
			LastKnownGoodChanged = [bool] $retainPreviousVerified
		}
	} finally {
		if ($null -ne $lock) { $lock.Dispose() }
	}
}

function Undo-ArenaCoordinatorRuntimeInstall {
	[CmdletBinding()]
	param(
		[Parameter(Mandatory)] [string] $InstalledPackageRoot,
		[Parameter(Mandatory)] [psobject] $Deployment
	)

	$resolvedInstalledRoot = [IO.Path]::GetFullPath($InstalledPackageRoot)
	$activeCoordinator = Assert-ArenaRuntimeChildPath $resolvedInstalledRoot ([string] $Deployment.ActivePath)
	$activeNodeDirectory = Assert-ArenaRuntimeChildPath $resolvedInstalledRoot ([string] $Deployment.NodeDirectory)
	$backupCoordinator = if ([string]::IsNullOrWhiteSpace([string] $Deployment.BackupPath)) { $null } else {
		Assert-ArenaRuntimeChildPath $resolvedInstalledRoot ([string] $Deployment.BackupPath)
	}
	$backupNode = if ([string]::IsNullOrWhiteSpace([string] $Deployment.NodeBackupPath)) { $null } else {
		Assert-ArenaRuntimeChildPath $resolvedInstalledRoot ([string] $Deployment.NodeBackupPath)
	}
	$lockPath = Join-Path $resolvedInstalledRoot '.arena-runtime-install.lock'
	$lock = $null
	try {
		try {
			$lock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
		} catch {
			throw "Another Arena Agents runtime installation is already in progress: $($_.Exception.Message)"
		}
		$runtimes = @(
			[pscustomobject]@{ Active = $activeCoordinator; Backup = $backupCoordinator; HadActive = [bool] $Deployment.HadCoordinator; Label = 'coordinator' },
			[pscustomobject]@{ Active = $activeNodeDirectory; Backup = $backupNode; HadActive = [bool] $Deployment.HadNode; Label = 'Node.js' }
		)
		foreach ($runtime in $runtimes) {
			if ($runtime.HadActive -and ($null -eq $runtime.Backup -or -not (Test-Path -LiteralPath $runtime.Backup -PathType Container))) {
				throw "Cannot restore the previous $($runtime.Label) runtime because its backup is missing."
			}
		}
		foreach ($runtime in $runtimes) {
			if (Test-Path -LiteralPath $runtime.Active) {
				Remove-Item -LiteralPath $runtime.Active -Recurse -Force -ErrorAction Stop
			}
			if ($null -ne $runtime.Backup) {
				New-Item -ItemType Directory -Force -Path (Split-Path -Parent $runtime.Active) | Out-Null
				Move-Item -LiteralPath $runtime.Backup -Destination $runtime.Active -ErrorAction Stop
			}
		}

		$generationStatePath = if ([string]::IsNullOrWhiteSpace([string] $Deployment.GenerationStatePath)) {
			Join-Path $resolvedInstalledRoot 'runtime\coordinator-generation.properties'
		} else {
			Assert-ArenaRuntimeChildPath $resolvedInstalledRoot ([string] $Deployment.GenerationStatePath)
		}
		$generationStateBackup = if ([string]::IsNullOrWhiteSpace([string] $Deployment.GenerationStateBackupPath)) {
			$null
		} else {
			Assert-ArenaRuntimeChildPath $resolvedInstalledRoot ([string] $Deployment.GenerationStateBackupPath)
		}
		if (-not [string]::IsNullOrWhiteSpace([string] $generationStateBackup) -and (Test-Path -LiteralPath $generationStateBackup -PathType Leaf)) {
			New-Item -ItemType Directory -Force -Path (Split-Path -Parent $generationStatePath) | Out-Null
			Move-Item -LiteralPath $generationStateBackup -Destination $generationStatePath -Force -ErrorAction Stop
		} elseif (-not [bool] $Deployment.HadGenerationState -and (Test-Path -LiteralPath $generationStatePath -PathType Leaf)) {
			Remove-Item -LiteralPath $generationStatePath -Force -ErrorAction Stop
		} elseif ([bool] $Deployment.HadGenerationState) {
			throw 'Cannot restore the previous coordinator generation state because its backup is missing.'
		}

		if ([bool] $Deployment.LastKnownGoodChanged) {
			$activeLastKnownGood = if ([string]::IsNullOrWhiteSpace([string] $Deployment.LastKnownGoodPath)) {
				Join-Path $resolvedInstalledRoot 'coordinator.last-known-good'
			} else {
				Assert-ArenaRuntimeChildPath $resolvedInstalledRoot ([string] $Deployment.LastKnownGoodPath)
			}
			$lastKnownGoodBackup = if ([string]::IsNullOrWhiteSpace([string] $Deployment.LastKnownGoodBackupPath)) {
				$null
			} else {
				Assert-ArenaRuntimeChildPath $resolvedInstalledRoot ([string] $Deployment.LastKnownGoodBackupPath)
			}
			if (Test-Path -LiteralPath $activeLastKnownGood) {
				Remove-Item -LiteralPath $activeLastKnownGood -Recurse -Force -ErrorAction Stop
			}
			if (-not [string]::IsNullOrWhiteSpace([string] $lastKnownGoodBackup) -and (Test-Path -LiteralPath $lastKnownGoodBackup)) {
				New-Item -ItemType Directory -Force -Path (Split-Path -Parent $activeLastKnownGood) | Out-Null
				Move-Item -LiteralPath $lastKnownGoodBackup -Destination $activeLastKnownGood -ErrorAction Stop
			} elseif ([bool] $Deployment.HadLastKnownGood) {
				throw 'Cannot restore the previous last-known-good coordinator because its backup is missing.'
			}
		}
	} finally {
		if ($null -ne $lock) { $lock.Dispose() }
	}
}
