[CmdletBinding()]
param(
    [string] $JavaPath,
    [string] $LauncherProfiles = (Join-Path $env:APPDATA '.minecraft\launcher_profiles.json'),
    [string] $GameDirectory = (Join-Path $env:APPDATA '.minecraft-arena-agents'),
	[ValidateSet('None', 'AfterRuntimePromotion', 'AfterModRemoval', 'AfterModsPromotion', 'AfterProfilePromotion')]
	[string] $FailurePoint = 'None'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$PackageRoot = [IO.Path]::GetFullPath((Split-Path -Parent $PSScriptRoot))
$PackageMetadataPath = Join-Path $PackageRoot 'distribution.properties'
if (-not (Test-Path -LiteralPath $PackageMetadataPath -PathType Leaf)) {
	throw "Package metadata is missing: $PackageMetadataPath"
}
$PackageMetadata = [ordered]@{}
foreach ($line in Get-Content -LiteralPath $PackageMetadataPath) {
	$trimmed = $line.Trim()
	if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
	$separator = $trimmed.IndexOf('=')
	if ($separator -le 0 -or $separator -eq $trimmed.Length - 1) { throw "Invalid package metadata: $trimmed" }
	$key = $trimmed.Substring(0, $separator).Trim()
	$value = $trimmed.Substring($separator + 1).Trim()
	if ($PackageMetadata.Contains($key)) { throw "Duplicate package metadata: $key" }
	$PackageMetadata[$key] = $value
}
foreach ($required in @('mod_version', 'minecraft_version', 'loader_version', 'fabric_api_version', 'carpet_version', 'voicechat_version', 'voice_addon_version')) {
	if (-not $PackageMetadata.Contains($required)) { throw "Package metadata is missing '$required'." }
}
$ModVersion = [string] $PackageMetadata.mod_version
$MinecraftVersion = [string] $PackageMetadata.minecraft_version
$LoaderVersion = [string] $PackageMetadata.loader_version
$VersionId = "fabric-loader-$LoaderVersion-$MinecraftVersion"
$ProfileId = 'arena-agents-modpack'
$ProfileName = 'Arena Agents'
$MinimumSecretLength = 32
$SecretByteCount = 32
$ExpectedModNames = @(
	"arena-agents-$ModVersion.jar"
	"arena-agents-voice-$($PackageMetadata.voice_addon_version).jar"
	"fabric-api-$($PackageMetadata.fabric_api_version).jar"
	"fabric-carpet-$($PackageMetadata.carpet_version).jar"
	"voicechat-fabric-$($PackageMetadata.voicechat_version).jar"
)
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
$ModsSource = Join-Path $PackageRoot 'mods'
$RuntimeDeploymentHelper = Join-Path $PSScriptRoot 'distribution-runtime.ps1'
$StartupPackagingPreflight = Join-Path $PSScriptRoot 'verify-startup-packaging.ps1'
$ResolvedGameDirectory = [IO.Path]::GetFullPath($GameDirectory)
$InstalledPackageRoot = Join-Path $ResolvedGameDirectory 'arena-agents-runtime'
$InstalledRuntimeDirectory = Join-Path $InstalledPackageRoot 'runtime'
$SecretPath = Join-Path $InstalledRuntimeDirectory 'bridge-secret.txt'
$VoiceSecretPath = Join-Path $InstalledRuntimeDirectory 'voice-secret.txt'
$VersionMetadata = Join-Path $env:APPDATA ".minecraft\versions\$VersionId\$VersionId.json"

function Set-OwnerOnlyAccess([string] $Path, [bool] $Directory = $false) {
    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().User
	$item = Get-Item -LiteralPath $Path
	$sections = [Security.AccessControl.AccessControlSections]'Access, Owner'
	$acl = $item.GetAccessControl($sections)
    $acl.SetOwner($currentUser)
    $acl.SetAccessRuleProtection($true, $false)
	foreach ($existing in @($acl.Access)) {
		[void]$acl.RemoveAccessRuleAll($existing)
	}
    $inheritance = if ($Directory) {
        [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    } else {
        [Security.AccessControl.InheritanceFlags]::None
    }
    $rule = [Security.AccessControl.FileSystemAccessRule]::new(
        $currentUser,
        [Security.AccessControl.FileSystemRights]::FullControl,
        $inheritance,
        [Security.AccessControl.PropagationFlags]::None,
        [Security.AccessControl.AccessControlType]::Allow
    )
    [void]$acl.AddAccessRule($rule)
	$item.SetAccessControl($acl)
}

function Initialize-PrivateSecret([string] $Path, [string] $Label) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        $bytes = New-Object byte[] $SecretByteCount
        $random = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $random.GetBytes($bytes) } finally { $random.Dispose() }
        $secretValue = (($bytes | ForEach-Object { $_.ToString('x2') }) -join '')
        [IO.File]::WriteAllText($Path, $secretValue, $Utf8NoBom)
    }
    Set-OwnerOnlyAccess $Path
    $value = [IO.File]::ReadAllText($Path).Trim()
    if ($value.Length -lt $MinimumSecretLength) { throw "The package $Label secret is too short." }
    $value
}

if (Get-Process -Name MinecraftLauncher,Minecraft -ErrorAction SilentlyContinue) {
    throw 'Close Minecraft and Minecraft Launcher before installing Arena Agents.'
}
if ([string]::IsNullOrWhiteSpace($JavaPath)) {
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $JavaPath = Join-Path $env:JAVA_HOME 'bin\javaw.exe'
    } else {
        $javaCommand = Get-Command javaw.exe -ErrorAction SilentlyContinue
        if ($null -ne $javaCommand) { $JavaPath = $javaCommand.Source }
    }
}
if ([string]::IsNullOrWhiteSpace($JavaPath) -or -not (Test-Path -LiteralPath $JavaPath -PathType Leaf)) {
    throw 'Java 25 javaw.exe was not found. Set JAVA_HOME or pass -JavaPath explicitly.'
}
$ResolvedJava = (Resolve-Path -LiteralPath $JavaPath).Path
$savedErrorPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $javaVersion = (& $ResolvedJava -version 2>&1 | Out-String)
} finally {
    $ErrorActionPreference = $savedErrorPreference
}
if ($LASTEXITCODE -ne 0) { throw "Java version check failed with code $LASTEXITCODE." }
if ($javaVersion -notmatch 'version "25(\.|")') {
    throw "Arena Agents requires Java 25. Detected: $($javaVersion.Trim())"
}
foreach ($required in @($LauncherProfiles, $VersionMetadata, $ModsSource, $PackageMetadataPath, $RuntimeDeploymentHelper, $StartupPackagingPreflight)) {
	if (-not (Test-Path -LiteralPath $required)) { throw "Missing installation prerequisite: $required" }
}

. $RuntimeDeploymentHelper
& $StartupPackagingPreflight -PackageRoot $PackageRoot

$includedMods = @(Get-ChildItem -LiteralPath $ModsSource -Filter '*.jar' -File)
$unexpectedMods = @(Compare-Object -ReferenceObject $ExpectedModNames -DifferenceObject @($includedMods.Name) -PassThru)
if ($unexpectedMods.Count -ne 0) {
    throw "Packaged mod JAR allowlist mismatch: $($unexpectedMods -join ', ')."
}

$resolvedProfiles = (Resolve-Path -LiteralPath $LauncherProfiles).Path
$document = Get-Content -LiteralPath $resolvedProfiles -Raw | ConvertFrom-Json
if ($null -eq $document.profiles) {
    $document | Add-Member -MemberType NoteProperty -Name profiles -Value ([pscustomobject]@{})
}
$javaArguments = "-Xms1G -Xmx4G -Darenaagents.bridgeSecretFile=`"$SecretPath`" -Darenaagents.voiceSecretFile=`"$VoiceSecretPath`" -Darenaagents.packageRoot=`"$InstalledPackageRoot`""
$expected = [ordered]@{
    gameDir = $ResolvedGameDirectory
    javaArgs = $javaArguments
    javaDir = $ResolvedJava
    lastVersionId = $VersionId
    name = $ProfileName
    type = 'custom'
}
$existing = $document.profiles.PSObject.Properties[$ProfileId]
$changed = $false
if ($null -ne $existing) {
	foreach ($identityField in @('gameDir', 'name', 'type')) {
		if ([string]$existing.Value.$identityField -ne [string]$expected[$identityField]) {
			throw "Existing launcher profile '$ProfileId' is not owned by this installation: field '$identityField' differs."
		}
	}
	foreach ($field in $expected.Keys) {
		if ([string]$existing.Value.$field -ne [string]$expected[$field]) {
			$profileField = $existing.Value.PSObject.Properties[$field]
			if ($null -eq $profileField) { $existing.Value | Add-Member -MemberType NoteProperty -Name $field -Value $expected[$field] }
			else { $profileField.Value = $expected[$field] }
			$changed = $true
		}
	}
} else {
    $timestamp = (Get-Date).ToUniversalTime().ToString('o')
    $profile = [pscustomobject][ordered]@{
        created = $timestamp
        gameDir = $expected.gameDir
        icon = 'Furnace'
        javaArgs = $expected.javaArgs
        javaDir = $expected.javaDir
        lastUsed = $timestamp
        lastVersionId = $expected.lastVersionId
        name = $expected.name
        type = $expected.type
    }
    $document.profiles | Add-Member -MemberType NoteProperty -Name $ProfileId -Value $profile
    $changed = $true
}

function Test-PackageOwnedModName([string] $Name) {
	if ($Name -match '^(?i:fabric-api|fabric-carpet|voicechat-fabric)-.+\.jar$') { return $true }
	if ($Name -match '^(?i:arena-agents-voice)-.+\.jar$') { return $true }
	return $Name -match '^(?i:arena-agents)-(?!voice-).+\.jar$'
}

$modsDirectory = Join-Path $ResolvedGameDirectory 'mods'
$backupRootParent = Join-Path $InstalledPackageRoot 'distribution-backups'
$transactionId = [Guid]::NewGuid().ToString('N')
$stagingRoot = Join-Path $InstalledPackageRoot ".distribution-staging-$transactionId"
$stagingMods = Join-Path $stagingRoot 'mods'
$backupRoot = Join-Path $backupRootParent "distribution-$transactionId"
$backupMods = Join-Path $backupRoot 'mods'
$backupProfiles = Join-Path $backupRoot 'launcher_profiles.json'
$profileTemporary = "$resolvedProfiles.arena-agents-$transactionId.tmp"
$installLock = $null
$runtimeDeployment = $null
$modsMutationStarted = $false
$profileReplaced = $false

New-Item -ItemType Directory -Force -Path $modsDirectory, $InstalledPackageRoot | Out-Null
try {
	$lockPath = Join-Path $InstalledPackageRoot '.arena-distribution-install.lock'
	try {
		$installLock = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
	} catch {
		throw "Another Arena Agents package installation is already in progress: $($_.Exception.Message)"
	}

	New-Item -ItemType Directory -Force -Path $stagingMods, $backupMods | Out-Null
	foreach ($mod in $includedMods) {
		$staged = Join-Path $stagingMods $mod.Name
		Copy-Item -LiteralPath $mod.FullName -Destination $staged -Force
		if ((Get-FileHash -LiteralPath $mod.FullName -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $staged -Algorithm SHA256).Hash) {
			throw "Staged mod hash differs from the package: $($mod.Name)"
		}
	}
	$oldOwnedMods = @(Get-ChildItem -LiteralPath $modsDirectory -Filter '*.jar' -File -ErrorAction SilentlyContinue |
		Where-Object { Test-PackageOwnedModName $_.Name })
	foreach ($old in $oldOwnedMods) {
		$backupFile = Join-Path $backupMods $old.Name
		Copy-Item -LiteralPath $old.FullName -Destination $backupFile -Force
		if ((Get-FileHash -LiteralPath $old.FullName -Algorithm SHA256).Hash -ne (Get-FileHash -LiteralPath $backupFile -Algorithm SHA256).Hash) {
			throw "Mod backup hash differs from the active file: $($old.Name)"
		}
	}
	if ($changed) { Copy-Item -LiteralPath $resolvedProfiles -Destination $backupProfiles -Force }

	New-Item -ItemType Directory -Force -Path $InstalledRuntimeDirectory | Out-Null
	Set-OwnerOnlyAccess $InstalledRuntimeDirectory $true
	$secret = Initialize-PrivateSecret $SecretPath 'bridge'
	$voiceSecret = Initialize-PrivateSecret $VoiceSecretPath 'voice'
	if ($secret -eq $voiceSecret) { throw 'Bridge and voice secrets must be distinct.' }
	$runtimeDeployment = Install-ArenaCoordinatorRuntime -SourceRoot $PackageRoot -InstalledPackageRoot $InstalledPackageRoot
	if ($FailurePoint -eq 'AfterRuntimePromotion') { throw 'Injected failure after runtime promotion.' }

	$modsMutationStarted = $true
	foreach ($old in $oldOwnedMods) { Remove-Item -LiteralPath $old.FullName -Force }
	if ($FailurePoint -eq 'AfterModRemoval') { throw 'Injected failure after old mod removal.' }
	foreach ($modName in $ExpectedModNames) {
		Copy-Item -LiteralPath (Join-Path $stagingMods $modName) -Destination (Join-Path $modsDirectory $modName) -Force
	}
	if ($FailurePoint -eq 'AfterModsPromotion') { throw 'Injected failure after mod promotion.' }
	$activeOwnedMods = @(Get-ChildItem -LiteralPath $modsDirectory -Filter '*.jar' -File |
		Where-Object { Test-PackageOwnedModName $_.Name })
	if (@(Compare-Object ($ExpectedModNames | Sort-Object) @($activeOwnedMods.Name | Sort-Object)).Count -ne 0) {
		throw 'Installed package-owned mod set does not match the release metadata.'
	}
	foreach ($modName in $ExpectedModNames) {
		if ((Get-FileHash -LiteralPath (Join-Path $stagingMods $modName) -Algorithm SHA256).Hash -ne
				(Get-FileHash -LiteralPath (Join-Path $modsDirectory $modName) -Algorithm SHA256).Hash) {
			throw "Installed mod hash verification failed: $modName"
		}
	}

	if ($changed) {
		[IO.File]::WriteAllText($profileTemporary, ($document | ConvertTo-Json -Depth 64), $Utf8NoBom)
		Move-Item -LiteralPath $profileTemporary -Destination $resolvedProfiles -Force
		$profileReplaced = $true
		if ($FailurePoint -eq 'AfterProfilePromotion') { throw 'Injected failure after launcher profile promotion.' }
		Copy-Item -LiteralPath $backupProfiles -Destination "$resolvedProfiles.arena-agents.backup" -Force
	}

	foreach ($oldBackup in Get-ChildItem -LiteralPath $backupRootParent -Directory -Force) {
		if ($oldBackup.FullName -ne $backupRoot) { Remove-Item -LiteralPath $oldBackup.FullName -Recurse -Force }
	}
	Write-Host "Arena Agents $ModVersion installed to $ResolvedGameDirectory"
	Write-Host "Launcher profile: $ProfileName"
	if ($null -ne $runtimeDeployment.BackupPath) { Write-Host "Previous coordinator backup: $($runtimeDeployment.BackupPath)" }
	Write-Host "Previous package-owned files: $backupRoot"
	Write-Host 'The bundled coordinator now starts and reconnects automatically in a world.'
} catch {
	$installationFailure = $_
	$rollbackFailures = [Collections.Generic.List[string]]::new()
	if ($profileReplaced) {
		try { Copy-Item -LiteralPath $backupProfiles -Destination $resolvedProfiles -Force }
		catch { $rollbackFailures.Add("launcher profile: $($_.Exception.Message)") }
	}
	if ($modsMutationStarted) {
		try {
			foreach ($active in @(Get-ChildItem -LiteralPath $modsDirectory -Filter '*.jar' -File -ErrorAction SilentlyContinue |
				Where-Object { Test-PackageOwnedModName $_.Name })) {
				Remove-Item -LiteralPath $active.FullName -Force
			}
			foreach ($backup in @(Get-ChildItem -LiteralPath $backupMods -Filter '*.jar' -File -ErrorAction SilentlyContinue)) {
				Copy-Item -LiteralPath $backup.FullName -Destination (Join-Path $modsDirectory $backup.Name) -Force
			}
		} catch { $rollbackFailures.Add("mods: $($_.Exception.Message)") }
	}
	if ($null -ne $runtimeDeployment) {
		try { Undo-ArenaCoordinatorRuntimeInstall -InstalledPackageRoot $InstalledPackageRoot -Deployment $runtimeDeployment }
		catch { $rollbackFailures.Add("runtime: $($_.Exception.Message)") }
	}
	if ($rollbackFailures.Count -ne 0) {
		throw "Arena Agents install failed: $($installationFailure.Exception.Message). Rollback was incomplete: $($rollbackFailures -join '; '). Backup: $backupRoot"
	}
	throw $installationFailure
} finally {
	if (Test-Path -LiteralPath $profileTemporary) { Remove-Item -LiteralPath $profileTemporary -Force }
	if (Test-Path -LiteralPath $stagingRoot) { Remove-Item -LiteralPath $stagingRoot -Recurse -Force }
	if ($null -ne $installLock) { $installLock.Dispose() }
}
