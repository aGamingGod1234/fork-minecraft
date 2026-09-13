[CmdletBinding()]
param(
    [string] $ProjectRoot,
    [string] $SourceWorld = (Join-Path $env:APPDATA '.minecraft\saves\New World (76)'),
    [switch] $SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }
. (Join-Path $PSScriptRoot 'offline-server-policy.ps1')
. (Join-Path $PSScriptRoot 'project-metadata.ps1')

$MinecraftVersion = '26.1.2'
$LoaderVersion = '0.19.3'
$FabricApiVersion = '0.150.0+26.1.2'
$InstallerVersion = '1.1.1'
$InstallerSha256 = '2487A69DD6F9D9C2605265A7142D77C26AB62EDC620E6BCF810D581D2EE31B79'
$InstallerUrl = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/$InstallerVersion/fabric-installer-$InstallerVersion.jar"
$Utf8NoBom = [Text.UTF8Encoding]::new($false)

$Project = [IO.Path]::GetFullPath($ProjectRoot)
$Minecraft = [IO.Path]::GetFullPath((Join-Path $env:APPDATA '.minecraft'))
$ExpectedSourceWorld = [IO.Path]::GetFullPath((Join-Path $Minecraft 'saves\New World (76)'))
$ResolvedSourceWorld = (Resolve-Path -LiteralPath $SourceWorld).Path
$Runtime = Join-Path $Project 'runtime'
$Server = Join-Path $Runtime 'server'
$TargetWorld = [IO.Path]::GetFullPath((Join-Path $Server 'world'))
$Evidence = Join-Path $Runtime 'evidence'
$Downloads = Join-Path $Runtime 'downloads'
$Agent55 = [IO.Path]::GetFullPath((Join-Path $env:APPDATA '.minecraft-agent-55'))
$Agent56 = [IO.Path]::GetFullPath((Join-Path $env:APPDATA '.minecraft-agent-56'))
$JavaHome = Join-Path $Runtime 'toolchains\temurin-25\jdk-25.0.3+9'
$Java = Join-Path $JavaHome 'bin\java.exe'
$AgentJar = Resolve-ArenaModJar $Project
$CarpetJar = Resolve-ArenaCarpetJar $Project
$FabricApi = Join-Path $Minecraft "mods\fabric-api-$FabricApiVersion.jar"
$Installer = Join-Path $Downloads "fabric-installer-$InstallerVersion.jar"
$WorldEvidence = Join-Path $Evidence 'world-copy.json'

function Assert-ExactPath([string] $Actual, [string] $Expected, [string] $Label) {
    if (-not ([IO.Path]::GetFullPath($Actual)).Equals([IO.Path]::GetFullPath($Expected), [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label resolved to an unexpected path: $Actual"
    }
}

function Assert-UnderRoot([string] $Path, [string] $Root, [string] $Label) {
    $fullPath = [IO.Path]::GetFullPath($Path)
    $fullRoot = [IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
    if (-not $fullPath.StartsWith($fullRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "$Label escaped its allowed root: $fullPath"
    }
}

function Get-RelativeWorldPath([string] $Root, [string] $Path) {
    $rootPrefix = [IO.Path]::GetFullPath($Root).TrimEnd('\') + '\'
    $fullPath = [IO.Path]::GetFullPath($Path)
    if (-not $fullPath.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Manifest file escaped the world root: $fullPath"
    }
    $fullPath.Substring($rootPrefix.Length)
}

function Get-WorldManifest([string] $Root) {
    @(Get-ChildItem -LiteralPath $Root -File -Recurse -Force |
        Where-Object Name -ne 'session.lock' |
        Sort-Object FullName |
        ForEach-Object {
            [pscustomobject]@{
                Path = Get-RelativeWorldPath $Root $_.FullName
                Bytes = $_.Length
                SHA256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash
            }
        })
}

function Write-AgentConfig([string] $GameDirectory, [string] $AgentId, [int] $Port) {
    $configDirectory = Join-Path $GameDirectory 'config'
    New-Item -ItemType Directory -Force -Path (Join-Path $GameDirectory 'mods'),$configDirectory | Out-Null
    $path = Join-Path $configDirectory 'arenaagents.json'
    $expected = [ordered]@{ agentId=$AgentId; bridgePort=$Port; observationRadius=12; enabled=$false }
    if (Test-Path -LiteralPath $path -PathType Leaf) {
        $existing = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
        if ($existing.agentId -ne $AgentId -or [int]$existing.bridgePort -ne $Port -or
            [int]$existing.observationRadius -ne 12 -or [bool]$existing.enabled) {
            throw "Existing agent config differs from the required isolated profile: $path"
        }
        Write-Host "Verified existing config: $path"
        return
    }
    [IO.File]::WriteAllText($path, ($expected | ConvertTo-Json -Compress), $Utf8NoBom)
    Write-Host "Created agent config: $path"
}

Assert-ExactPath $ResolvedSourceWorld $ExpectedSourceWorld 'Source world'
Assert-UnderRoot $TargetWorld $Server 'Copied world'

if (Get-Process -Name MinecraftLauncher,Minecraft,javaw -ErrorAction SilentlyContinue) {
    throw 'Close Minecraft Launcher and all Minecraft clients before preparing runtime files.'
}
foreach ($required in @($Java, $FabricApi, $CarpetJar)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing required file: $required" }
}

if (-not $SkipBuild) {
    $env:JAVA_HOME = $JavaHome
    Push-Location $Project
    try {
        & .\gradlew.bat clean build verifyCore --no-daemon --console=plain
        if ($LASTEXITCODE -ne 0) { throw "Gradle verification failed with exit code $LASTEXITCODE" }
    } finally { Pop-Location }
}
if (-not (Test-Path -LiteralPath $AgentJar -PathType Leaf)) { throw "Missing final mod JAR: $AgentJar" }

New-Item -ItemType Directory -Force -Path $Runtime,$Evidence,$Downloads | Out-Null
if (-not (Test-Path -LiteralPath $Installer -PathType Leaf)) {
    Invoke-WebRequest -UseBasicParsing -Uri $InstallerUrl -OutFile $Installer
}
$installerHash = (Get-FileHash -LiteralPath $Installer -Algorithm SHA256).Hash
if (-not $installerHash.Equals($InstallerSha256, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Fabric installer SHA-256 mismatch. Expected $InstallerSha256, got $installerHash."
}

$versionJson = Join-Path $Minecraft "versions\fabric-loader-$LoaderVersion-$MinecraftVersion\fabric-loader-$LoaderVersion-$MinecraftVersion.json"
if (-not (Test-Path -LiteralPath $versionJson -PathType Leaf)) {
    & $Java -jar $Installer client -dir $Minecraft -mcversion $MinecraftVersion -loader $LoaderVersion -noprofile
    if ($LASTEXITCODE -ne 0) { throw "Fabric client install failed with exit code $LASTEXITCODE" }
}
if (-not (Test-Path -LiteralPath $versionJson -PathType Leaf)) { throw "Fabric version metadata was not created: $versionJson" }

Write-AgentConfig $Agent55 'agent-55' 25571
Write-AgentConfig $Agent56 'agent-56' 25572
foreach ($gameDirectory in @($Agent55, $Agent56)) {
    Copy-Item -LiteralPath $FabricApi -Destination (Join-Path $gameDirectory "mods\fabric-api-$FabricApiVersion.jar") -Force
    Copy-Item -LiteralPath $CarpetJar -Destination (Join-Path $gameDirectory "mods\$([IO.Path]::GetFileName($CarpetJar))") -Force
    Copy-Item -LiteralPath $AgentJar -Destination (Join-Path $gameDirectory "mods\$([IO.Path]::GetFileName($AgentJar))") -Force
}

New-Item -ItemType Directory -Force -Path $Server,(Join-Path $Server 'mods') | Out-Null
$serverLauncher = Join-Path $Server 'fabric-server-launch.jar'
if (-not (Test-Path -LiteralPath $serverLauncher -PathType Leaf)) {
    & $Java -jar $Installer server -dir $Server -mcversion $MinecraftVersion -loader $LoaderVersion -downloadMinecraft
    if ($LASTEXITCODE -ne 0) { throw "Fabric server install failed with exit code $LASTEXITCODE" }
}
if (-not (Test-Path -LiteralPath $serverLauncher -PathType Leaf)) { throw "Fabric server launcher is missing: $serverLauncher" }
Copy-Item -LiteralPath $FabricApi -Destination (Join-Path $Server "mods\fabric-api-$FabricApiVersion.jar") -Force
Copy-Item -LiteralPath $CarpetJar -Destination (Join-Path $Server "mods\$([IO.Path]::GetFileName($CarpetJar))") -Force
Copy-Item -LiteralPath $AgentJar -Destination (Join-Path $Server "mods\$([IO.Path]::GetFileName($AgentJar))") -Force

if (-not (Test-Path -LiteralPath $TargetWorld)) {
    New-Item -ItemType Directory -Path $TargetWorld | Out-Null
    & robocopy $ResolvedSourceWorld $TargetWorld /E /COPY:DAT /DCOPY:DAT /R:1 /W:1 /XJ /XF session.lock
    $robocopyExit = $LASTEXITCODE
    if ($robocopyExit -gt 7) { throw "World copy failed with robocopy exit code $robocopyExit" }
    $sourceManifest = Get-WorldManifest $ResolvedSourceWorld
    $targetManifest = Get-WorldManifest $TargetWorld
    $manifestDifference = Compare-Object $sourceManifest $targetManifest -Property Path,Bytes,SHA256
    if ($manifestDifference) { throw 'Copied-world manifest does not match the source.' }
    $worldRecord = [ordered]@{
        source = $ResolvedSourceWorld
        target = $TargetWorld
        files = $sourceManifest.Count
        sourceLevelDatSha256 = (Get-FileHash -LiteralPath (Join-Path $ResolvedSourceWorld 'level.dat') -Algorithm SHA256).Hash
        targetLevelDatSha256 = (Get-FileHash -LiteralPath (Join-Path $TargetWorld 'level.dat') -Algorithm SHA256).Hash
        copiedAt = (Get-Date).ToUniversalTime().ToString('o')
    }
    [IO.File]::WriteAllText($WorldEvidence, ($worldRecord | ConvertTo-Json), $Utf8NoBom)
} else {
    if (-not (Test-Path -LiteralPath $WorldEvidence -PathType Leaf)) {
        $sourceManifest = Get-WorldManifest $ResolvedSourceWorld
        $targetManifest = Get-WorldManifest $TargetWorld
        $manifestDifference = Compare-Object $sourceManifest $targetManifest -Property Path,Bytes,SHA256
        if ($manifestDifference) {
            throw 'Copied world exists without evidence and does not match the source; refusing to overwrite or trust it.'
        }
        $worldRecord = [ordered]@{
            source = $ResolvedSourceWorld
            target = $TargetWorld
            files = $sourceManifest.Count
            sourceLevelDatSha256 = (Get-FileHash -LiteralPath (Join-Path $ResolvedSourceWorld 'level.dat') -Algorithm SHA256).Hash
            targetLevelDatSha256 = (Get-FileHash -LiteralPath (Join-Path $TargetWorld 'level.dat') -Algorithm SHA256).Hash
            copiedAt = (Get-Date).ToUniversalTime().ToString('o')
        }
        [IO.File]::WriteAllText($WorldEvidence, ($worldRecord | ConvertTo-Json), $Utf8NoBom)
        Write-Host "Recovered and verified interrupted world copy: $TargetWorld"
    } else {
        $record = Get-Content -LiteralPath $WorldEvidence -Raw | ConvertFrom-Json
        Assert-ExactPath ([string]$record.source) $ResolvedSourceWorld 'Recorded source world'
        Assert-ExactPath ([string]$record.target) $TargetWorld 'Recorded copied world'
        $sourceLevelHash = (Get-FileHash -LiteralPath (Join-Path $ResolvedSourceWorld 'level.dat') -Algorithm SHA256).Hash
        if (-not $sourceLevelHash.Equals([string]$record.sourceLevelDatSha256, [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Source world changed after the verified copy; prepare a new isolated runtime deliberately.'
        }
        Write-Host "Using existing verified copied world: $TargetWorld"
    }
}

$eulaPath = Join-Path $Server 'eula.txt'
if (-not (Test-Path -LiteralPath $eulaPath)) { [IO.File]::WriteAllText($eulaPath, "eula=true`n", $Utf8NoBom) }
$propertiesPath = Join-Path $Server 'server.properties'
if (-not (Test-Path -LiteralPath $propertiesPath)) {
    [IO.File]::WriteAllText($propertiesPath, "online-mode=false`nserver-ip=127.0.0.1`nserver-port=25565`nlevel-name=world`nenable-command-block=false`npause-when-empty-seconds=-1`n", $Utf8NoBom)
} else {
    $properties = Get-Content -LiteralPath $propertiesPath -Raw
    foreach ($requiredSetting in @('online-mode=false','server-ip=127.0.0.1','server-port=25565','level-name=world')) {
        if ($properties -notmatch "(?m)^$([regex]::Escape($requiredSetting))\r?$") {
            throw "Existing server.properties must contain $requiredSetting"
        }
    }
    if ($properties -match '(?m)^pause-when-empty-seconds=.*\r?$') {
        $properties = [regex]::Replace($properties, '(?m)^pause-when-empty-seconds=.*\r?$', 'pause-when-empty-seconds=-1')
    } else {
        $properties = $properties.TrimEnd("`r", "`n") + "`npause-when-empty-seconds=-1`n"
    }
    [IO.File]::WriteAllText($propertiesPath, $properties, $Utf8NoBom)
}
Assert-ArenaOfflineServerLoopback $propertiesPath -RequireOffline

$buildEvidence = [ordered]@{
    commit = (& git -C $Project rev-parse HEAD).Trim()
    jar = $AgentJar
    jarSha256 = (Get-FileHash -LiteralPath $AgentJar -Algorithm SHA256).Hash
    fabricInstallerSha256 = $installerHash
    preparedAt = (Get-Date).ToUniversalTime().ToString('o')
}
[IO.File]::WriteAllText((Join-Path $Evidence 'runtime-build.json'), ($buildEvidence | ConvertTo-Json), $Utf8NoBom)
Write-Host 'Runtime preparation complete. Re-running this script will verify and reuse the same isolated artifacts.'
