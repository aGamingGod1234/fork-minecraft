[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)] [string] $ProjectRoot,
    [Parameter(Mandatory = $true)] [string] $GameDirectory,
    [ValidateSet('None', 'AfterJarsBackup', 'AfterBackup', 'AfterJarSwap', 'AfterCoordinatorSwap', 'AfterNodeSwap', 'AfterGenerationStateSwap')]
    [string] $FailurePoint = 'None',
    [switch] $TestProcessClassification
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Resolve-ContainedPath([string] $Base, [string] $Child, [string] $Label) {
    $basePath = [IO.Path]::GetFullPath($Base).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    $childPath = [IO.Path]::GetFullPath($Child)
    if (-not $childPath.StartsWith($basePath, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label escapes its validated target: $childPath"
    }
    return $childPath
}

function Assert-NoReparse([string] $Path, [string] $Label) {
    $current = [IO.Path]::GetFullPath($Path)
    while ($null -ne $current -and $current.Length -gt 2) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "$Label contains a reparse point: $current" }
        }
        $parent = Split-Path -Parent $current
        if ($parent -eq $current) { break }
        $current = $parent
    }
}

function Assert-NoReparseTree([string] $Path, [string] $Label) {
    if (-not (Test-Path -LiteralPath $Path)) { return }
    foreach ($item in @(Get-Item -LiteralPath $Path -Force) + @(Get-ChildItem -LiteralPath $Path -Recurse -Force -ErrorAction Stop)) {
        if (($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw "$Label contains a reparse point: $($item.FullName)" }
    }
}

function Ensure-CurrentUserRuntimeAccess([string] $Path) {
    $currentUser = [Security.Principal.WindowsIdentity]::GetCurrent().User
    $icacls = Join-Path $env:SystemRoot 'System32\icacls.exe'
    $directGrant = "*$($currentUser.Value):F"
    & $icacls $Path '/grant:r' $directGrant '/T' '/C' '/Q' | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "Could not repair the installed runtime tree for the current Windows user. Run the updater from that user's elevated PowerShell session."
    }
    $inheritableGrant = "*$($currentUser.Value):(OI)(CI)F"
    & $icacls $Path '/grant:r' $inheritableGrant '/Q' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not make repaired runtime access inheritable.' }
    foreach ($required in @(
        $Path,
        (Join-Path $Path 'runtime\coordinator-generation.properties'),
        (Join-Path $Path 'runtime\dynamic-agents.json'),
        (Join-Path $Path 'coordinator\src\dynamic-main.mjs'),
        (Join-Path $Path 'runtime\toolchains\node\node.exe')
    )) {
        if (-not (Test-Path -LiteralPath $required)) { continue }
        $acl = Get-Acl -LiteralPath $required -ErrorAction Stop
        $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
        $hasFullControl = @($rules | Where-Object {
            $_.IdentityReference -eq $currentUser -and $_.AccessControlType -eq [Security.AccessControl.AccessControlType]::Allow -and ($_.FileSystemRights -band [Security.AccessControl.FileSystemRights]::FullControl) -eq [Security.AccessControl.FileSystemRights]::FullControl
        }).Count -gt 0
        if (-not $hasFullControl) { throw "Could not verify current-user access to installed runtime path: $required" }
    }
}

function Test-UnsafeJavaProcess([string] $Name, [string] $CommandLine) {
    if ($Name -ieq 'javaw.exe') { return $true }
    if ($Name -ine 'java.exe') { return $false }
    if ([string]::IsNullOrWhiteSpace($CommandLine)) { return $true }
    return $CommandLine -match '(?i)(net\.minecraft\.client\.main\.Main|net\.minecraft\.server\.Main|KnotClient|KnotServer|fabric-server-launch|minecraft_server)'
}

if ($TestProcessClassification) {
    if (-not (Test-UnsafeJavaProcess 'javaw.exe' '')) { throw 'javaw.exe must be unsafe even without a command line.' }
    if (-not (Test-UnsafeJavaProcess 'java.exe' 'net.minecraft.client.main.Main')) { throw 'Minecraft client main was not classified unsafe.' }
    if (-not (Test-UnsafeJavaProcess 'java.exe' 'KnotServer')) { throw 'KnotServer was not classified unsafe.' }
    if (-not (Test-UnsafeJavaProcess 'java.exe' 'GradleDaemon')) { } else { throw 'Gradle daemon was incorrectly classified unsafe.' }
    exit 0
}

function Get-ExpectedCoordinatorFiles([string] $CoordinatorRoot) {
    $developmentOnly = @(
        'config/headless-provider-matrix.json',
        'config/latency-acceptance.json',
        'config/latency-experiment-matrix.json',
        'config/latency-headless-matrix.json',
        'config/latency-matrix.json',
        'config/task9-performance-matrix.json',
        'src/benchmark/',
        'src/simulator/',
        'src/headless-matrix.mjs',
        'src/headless-rcon.mjs',
        'src/headless-world.mjs',
        'src/headless-world-spawn.mjs',
        'src/headless-config.mjs',
        'src/headless-capabilities.mjs',
        'src/player-capability-probe.mjs',
        'src/native-tool-ab-runner.mjs',
        'src/native-tool-ab-trial.mjs',
        'src/native-tool-cli-boundary.mjs',
        'src/native-tool-load-probe.mjs',
        'src/native-tool-probe.mjs'
    )
    $paths = [Collections.Generic.List[string]]::new()
    foreach ($fixed in @('package.json', 'package-lock.json')) {
        $file = Join-Path $CoordinatorRoot $fixed
        if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Missing coordinator runtime file: $file" }
        $paths.Add($fixed)
    }
    foreach ($root in @('config', 'src', 'node_modules\acorn')) {
        $rootPath = Join-Path $CoordinatorRoot $root
        if (-not (Test-Path -LiteralPath $rootPath -PathType Container)) { throw "Missing coordinator runtime root: $rootPath" }
        foreach ($file in Get-ChildItem -LiteralPath $rootPath -Recurse -File) {
            $relative = $file.FullName.Substring($CoordinatorRoot.Length + 1).Replace('\', '/')
            if ($relative -match '(^|/)__pycache__(/|$)' -or $relative -match '\.pyc$') { continue }
            if ($developmentOnly | Where-Object { $relative -ieq $_ -or $relative.StartsWith($_, [StringComparison]::OrdinalIgnoreCase) }) { continue }
            $paths.Add($relative)
        }
    }
    return @($paths | Sort-Object -Unique)
}

function Get-BytesHash([byte[]] $Bytes) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return (($sha.ComputeHash($Bytes) | ForEach-Object { $_.ToString('x2') }) -join '').ToUpperInvariant() }
    finally { $sha.Dispose() }
}

function Assert-ArchiveParity([string] $JarPath, [string] $CoordinatorRoot, [string[]] $Expected) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $prefix = 'arena-agents/coordinator/'
        $files = @($zip.Entries | Where-Object { $_.FullName.StartsWith($prefix) -and -not $_.FullName.EndsWith('/') -and $_.FullName -ne ($prefix + 'coordinator-manifest.txt') } | ForEach-Object { $_.FullName.Substring($prefix.Length) } | Sort-Object)
        $differences = @(Compare-Object -ReferenceObject $Expected -DifferenceObject $files)
        if ($differences.Count -ne 0) {
            $missing = @($differences | Where-Object SideIndicator -eq '<=' | ForEach-Object InputObject)
            $unexpected = @($differences | Where-Object SideIndicator -eq '=>' | ForEach-Object InputObject)
            throw "Embedded coordinator entries differ from the independently derived source set. Missing: $($missing -join ', '); unexpected: $($unexpected -join ', ')."
        }
        $manifestEntry = $zip.GetEntry($prefix + 'coordinator-manifest.txt')
        if ($null -eq $manifestEntry) { throw 'Embedded coordinator manifest is missing.' }
        $manifestReader = [IO.StreamReader]::new($manifestEntry.Open())
        try { $manifestLines = @($manifestReader.ReadToEnd() -split "\r?\n" | Where-Object { $_ -ne '' }) }
        finally { $manifestReader.Dispose() }
		$manifestRecords = @($manifestLines | ForEach-Object {
			if ($_ -notmatch '^(?<Hash>[0-9a-f]{64}) (?<Path>.+)$') { throw "Invalid embedded coordinator manifest entry: $_" }
			[pscustomobject]@{ Hash = $Matches.Hash.ToUpperInvariant(); Path = $Matches.Path }
		})
		$manifest = @($manifestRecords.Path | Sort-Object)
        if (@(Compare-Object -ReferenceObject $Expected -DifferenceObject $manifest).Count -ne 0) { throw 'Embedded coordinator manifest differs from the independently derived source set.' }
        foreach ($relative in $Expected) {
            $source = Join-Path $CoordinatorRoot ($relative.Replace('/', '\'))
            $entry = $zip.GetEntry($prefix + $relative)
            $stream = $entry.Open()
            try {
                $memory = [IO.MemoryStream]::new()
                try { $stream.CopyTo($memory); $archiveHash = Get-BytesHash $memory.ToArray() }
                finally { $memory.Dispose() }
            } finally { $stream.Dispose() }
            $sourceHash = (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToUpperInvariant()
            if ($sourceHash -ne $archiveHash) { throw "Coordinator hash mismatch: $relative" }
			$manifestHash = ($manifestRecords | Where-Object { $_.Path -ceq $relative }).Hash
			if ($manifestHash -cne $archiveHash) { throw "Coordinator manifest hash mismatch: $relative" }
        }
    } finally { $zip.Dispose() }
}

function Copy-ExpectedCoordinator([string] $Source, [string] $Destination, [string[]] $Expected) {
    foreach ($relative in $Expected) {
        $target = Join-Path $Destination ($relative.Replace('/', '\'))
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $target) | Out-Null
        Copy-Item -LiteralPath (Join-Path $Source ($relative.Replace('/', '\'))) -Destination $target -Force
    }
}

function Copy-InstalledCoordinatorManifest([string] $JarPath, [string] $Destination) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $entry = $zip.GetEntry('arena-agents/coordinator/coordinator-manifest.txt')
        if ($null -eq $entry) { throw 'Embedded coordinator manifest is missing.' }
        $target = Join-Path $Destination '.arena-agents-bundle-manifest'
        $input = $entry.Open()
        try {
            $output = [IO.File]::Open($target, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
            try { $input.CopyTo($output) } finally { $output.Dispose() }
        } finally { $input.Dispose() }
    } finally { $zip.Dispose() }
}

$project = [IO.Path]::GetFullPath($ProjectRoot)
$game = [IO.Path]::GetFullPath($GameDirectory)
$gradlePropertiesPath = Join-Path $project 'gradle.properties'
if (-not (Test-Path -LiteralPath $gradlePropertiesPath -PathType Leaf)) { throw "Missing Gradle properties: $gradlePropertiesPath" }
$gradleProperties = [ordered]@{}
foreach ($line in Get-Content -LiteralPath $gradlePropertiesPath) {
	$trimmed = $line.Trim()
	if ($trimmed.Length -eq 0 -or $trimmed.StartsWith('#')) { continue }
	$separator = $trimmed.IndexOf('=')
	if ($separator -le 0) { throw "Invalid Gradle property: $trimmed" }
	$gradleProperties[$trimmed.Substring(0, $separator).Trim()] = $trimmed.Substring($separator + 1).Trim()
}

function Test-CoreArenaModName([string] $Name) {
	return $Name -match '^(?i:arena-agents)-(?!voice-).+\.jar$'
}

function Test-ArenaVoiceModName([string] $Name) {
	return $Name -match '^(?i:arena-agents-voice)-.+\.jar$'
}
foreach ($requiredProperty in @('mod_version', 'fabric_api_version', 'carpet_version')) {
	if (-not $gradleProperties.Contains($requiredProperty)) { throw "Missing Gradle property '$requiredProperty'." }
}
$modJarName = "arena-agents-$($gradleProperties.mod_version).jar"
$voiceJarName = "arena-agents-voice-$($gradleProperties.mod_version).jar"
$fabricApiJarName = "fabric-api-$($gradleProperties.fabric_api_version).jar"
$carpetJarName = "fabric-carpet-$($gradleProperties.carpet_version).jar"
Assert-NoReparse $project 'project root'
Assert-NoReparse $game 'game directory'
$mods = Resolve-ContainedPath $game (Join-Path $game 'mods') 'mods target'
$runtime = Resolve-ContainedPath $game (Join-Path $game 'arena-agents-runtime') 'runtime target'
$jar = Join-Path $project ("build\libs\" + $modJarName)
$voiceJar = Join-Path $project ("voice-addon\build\libs\" + $voiceJarName)
$coordinator = Join-Path $project 'coordinator'
$nodeDirectory = Join-Path $project 'runtime\toolchains\node'
$node = Join-Path $nodeDirectory 'node.exe'
$runtimeInstaller = Join-Path $project 'scripts\distribution-runtime.ps1'
$startupVerifier = Join-Path $project 'scripts\verify-startup-packaging.ps1'
Assert-NoReparse $mods 'mods target'
Assert-NoReparse $runtime 'runtime target'
Assert-NoReparse $coordinator 'coordinator root'
Assert-NoReparse $nodeDirectory 'bundled Node.js root'
$runtimeState = Resolve-ContainedPath $runtime (Join-Path $runtime 'runtime') 'runtime state target'
Assert-NoReparse $runtimeState 'runtime state target'
Assert-NoReparseTree $coordinator 'coordinator root'
Assert-NoReparseTree $nodeDirectory 'bundled Node.js root'
Assert-NoReparseTree $mods 'mods target'
foreach ($required in @($jar, $voiceJar, (Join-Path $coordinator 'package.json'), (Join-Path $coordinator 'src'), $node, $runtimeInstaller, $startupVerifier)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Missing packaging prerequisite: $required" }
}
if (Get-Process -Name MinecraftLauncher, Minecraft -ErrorAction SilentlyContinue) { throw 'Close Minecraft and Minecraft Launcher before updating the normal profile.' }
try { $javaProcesses = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe' OR Name = 'javaw.exe'" -ErrorAction Stop) }
catch { throw "Unable to inspect Java process command lines; refusing to update: $($_.Exception.Message)" }
foreach ($process in $javaProcesses) {
    $commandLine = [string]$process.CommandLine
    if (Test-UnsafeJavaProcess ([string]$process.Name) $commandLine) { throw "A Minecraft/Fabric Java process is active (PID $($process.ProcessId)); refusing to update." }
}
foreach ($dependency in @($fabricApiJarName, $carpetJarName)) {
    if (-not (Test-Path -LiteralPath (Join-Path $mods $dependency) -PathType Leaf)) { throw "Required dependency is missing from target mods: $dependency" }
}
Assert-NoReparseTree $runtime 'runtime target'
if (Test-Path -LiteralPath $runtime -PathType Container) { Ensure-CurrentUserRuntimeAccess $runtime }

& $startupVerifier -PackageRoot $project
. $runtimeInstaller
$expected = Get-ExpectedCoordinatorFiles $coordinator
$secret = Join-Path $runtime 'runtime\bridge-secret.txt'
if (-not (Test-Path -LiteralPath $secret -PathType Leaf)) { throw "Installed bridge secret is missing: $secret" }
$secretHash = (Get-FileHash -LiteralPath $secret -Algorithm SHA256).Hash
Assert-ArchiveParity $jar $coordinator $expected
$stage = Join-Path $game ('.arena-agents-update-' + [guid]::NewGuid().ToString('N'))
$backup = Join-Path $game ('.arena-agents-backup-' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + [guid]::NewGuid().ToString('N'))
$stageMods = Join-Path $stage 'mods'
$stagePackage = Join-Path $stage 'package'
$stageCoordinator = Join-Path $stagePackage 'coordinator'
$stageNodeDirectory = Join-Path $stagePackage 'runtime\toolchains\node'
$installedJar = Join-Path $mods $modJarName
$installedVoiceJar = Join-Path $mods $voiceJarName
$backupMade = $false
$oldArena = @()
try {
    Assert-NoReparse $stage 'staging path'
    Assert-NoReparse $backup 'backup path'
    New-Item -ItemType Directory -Force -Path $stageMods, $stageCoordinator, $stageNodeDirectory | Out-Null
    Copy-Item -LiteralPath $jar -Destination (Join-Path $stageMods $modJarName) -Force
    Copy-Item -LiteralPath $voiceJar -Destination (Join-Path $stageMods $voiceJarName) -Force
    Copy-ExpectedCoordinator $coordinator $stageCoordinator $expected
    Copy-InstalledCoordinatorManifest $jar $stageCoordinator
    foreach ($entry in Get-ChildItem -LiteralPath $nodeDirectory -Force) {
        Copy-Item -LiteralPath $entry.FullName -Destination $stageNodeDirectory -Recurse -Force
    }
    foreach ($relative in $expected) {
        $sourceHash = (Get-FileHash (Join-Path $coordinator ($relative.Replace('/', '\'))) -Algorithm SHA256).Hash
        $stageHash = (Get-FileHash (Join-Path $stageCoordinator ($relative.Replace('/', '\'))) -Algorithm SHA256).Hash
        if ($sourceHash -ne $stageHash) { throw "Staged coordinator hash mismatch: $relative" }
    }
    if ((Get-FileHash (Join-Path $stageNodeDirectory 'node.exe') -Algorithm SHA256).Hash -ne (Get-FileHash $node -Algorithm SHA256).Hash) {
        throw 'Staged Node.js runtime hash mismatch.'
    }
    New-Item -ItemType Directory -Force -Path $backup | Out-Null
    $oldArena = @(Get-ChildItem -LiteralPath $mods -File -ErrorAction SilentlyContinue |
            Where-Object { (Test-CoreArenaModName $_.Name) -or (Test-ArenaVoiceModName $_.Name) })
    foreach ($old in $oldArena) { Assert-NoReparseTree $old.FullName "Arena JAR $($old.Name)" }
    foreach ($old in $oldArena) { Copy-Item -LiteralPath $old.FullName -Destination (Join-Path $backup $old.Name) -Force }
    if ($FailurePoint -eq 'AfterJarsBackup') { throw 'Injected failure after JAR backup.' }
    Assert-NoReparseTree $backup 'backup path'
    foreach ($item in @(Get-ChildItem -LiteralPath $backup -Recurse -File)) {
        $relative = $item.FullName.Substring($backup.Length + 1)
        $source = Join-Path $mods $relative
        if ((Get-FileHash $source -Algorithm SHA256).Hash -ne (Get-FileHash $item.FullName -Algorithm SHA256).Hash) { throw "Backup hash mismatch: $relative" }
    }
    if ((Get-FileHash $secret -Algorithm SHA256).Hash -ne $secretHash) { throw 'Bridge secret changed during backup.' }
    $backupMade = $true
    if ($FailurePoint -eq 'AfterBackup') { throw 'Injected failure after full backup.' }
    Assert-NoReparse $game 'game directory before mutation'
    Assert-NoReparseTree $mods 'mods target before mutation'
    Assert-NoReparseTree $runtime 'runtime target before mutation'
    Assert-NoReparseTree $stage 'staging path before mutation'
    Assert-NoReparseTree $backup 'backup path before mutation'
    foreach ($old in $oldArena) { Remove-Item -LiteralPath $old.FullName -Force }
    Copy-Item -LiteralPath (Join-Path $stageMods $modJarName) -Destination $installedJar -Force
    Copy-Item -LiteralPath (Join-Path $stageMods $voiceJarName) -Destination $installedVoiceJar -Force
    if ($FailurePoint -eq 'AfterJarSwap') { throw 'Injected failure after JAR swap.' }
    if ((Get-FileHash $installedJar -Algorithm SHA256).Hash -ne (Get-FileHash $jar -Algorithm SHA256).Hash) { throw 'Installed JAR hash verification failed.' }
    if ((Get-FileHash $installedVoiceJar -Algorithm SHA256).Hash -ne (Get-FileHash $voiceJar -Algorithm SHA256).Hash) { throw 'Installed voice-addon JAR hash verification failed.' }
    $runtimeFailurePoint = switch ($FailurePoint) {
        'AfterCoordinatorSwap' { 'AfterCoordinatorPromotion' }
        'AfterNodeSwap' { 'AfterNodePromotion' }
        'AfterGenerationStateSwap' { 'AfterGenerationStatePromotion' }
        default { 'None' }
    }
    Install-ArenaCoordinatorRuntime -SourceRoot $stagePackage -InstalledPackageRoot $runtime -FailurePoint $runtimeFailurePoint | Out-Null
    Write-Host "Normal profile updated: $game"
    Write-Host "Jar SHA-256: $((Get-FileHash $installedJar -Algorithm SHA256).Hash)"
    Write-Host "Voice addon SHA-256: $((Get-FileHash $installedVoiceJar -Algorithm SHA256).Hash)"
} catch {
    if ($backupMade) {
        if (Test-Path -LiteralPath $installedJar) { Remove-Item -LiteralPath $installedJar -Force }
        if (Test-Path -LiteralPath $installedVoiceJar) { Remove-Item -LiteralPath $installedVoiceJar -Force }
        foreach ($old in $oldArena) { $saved = Join-Path $backup $old.Name; if (Test-Path -LiteralPath $saved) { Copy-Item -LiteralPath $saved -Destination $old.FullName -Force } }
        foreach ($old in $oldArena) { if ((Get-FileHash $old.FullName -Algorithm SHA256).Hash -ne (Get-FileHash (Join-Path $backup $old.Name) -Algorithm SHA256).Hash) { throw "Rollback hash verification failed; preserved backup: $backup" } }
        if ((Get-FileHash $secret -Algorithm SHA256).Hash -ne $secretHash) { throw "Rollback secret verification failed; preserved backup: $backup" }
        Write-Host "Rollback completed; verified backup preserved at $backup"
    }
    throw
} finally {
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage -Recurse -Force }
}
