[CmdletBinding()]
param(
    [string] $ProjectRoot,
    [string] $LauncherProfiles = (Join-Path $env:APPDATA '.minecraft\launcher_profiles.json')
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }

$MinecraftVersion = '26.1.2'
$LoaderVersion = '0.19.3'
$VersionId = "fabric-loader-$LoaderVersion-$MinecraftVersion"
$Utf8NoBom = [Text.UTF8Encoding]::new($false)
$Project = [IO.Path]::GetFullPath($ProjectRoot)
$Java = Join-Path $Project 'runtime\toolchains\temurin-25\jdk-25.0.3+9\bin\javaw.exe'
$VersionMetadata = Join-Path $env:APPDATA ".minecraft\versions\$VersionId\$VersionId.json"
$ResolvedProfiles = (Resolve-Path -LiteralPath $LauncherProfiles).Path
$SecretPath = Join-Path $Project 'runtime\bridge-secret.txt'
$MinimumBridgeSecretLength = 32
$MaximumBridgeSecretLength = 256
$BridgeSecretBytes = 32
$LegacyJavaArgs = '-Xms1G -Xmx4G'
$JavaArguments = "$LegacyJavaArgs -Darenaagents.bridgeSecretFile=`"$SecretPath`""
$Timestamp = (Get-Date).ToUniversalTime().ToString('o')

if (Get-Process -Name MinecraftLauncher,Minecraft -ErrorAction SilentlyContinue) {
    throw 'Close Minecraft Launcher before changing its installation profiles.'
}
foreach ($required in @($Java, $VersionMetadata, $ResolvedProfiles)) {
    if (-not (Test-Path -LiteralPath $required -PathType Leaf)) { throw "Missing launcher-profile prerequisite: $required" }
}

$secretDirectory = Split-Path -Parent $SecretPath
New-Item -ItemType Directory -Force -Path $secretDirectory | Out-Null
if (-not (Test-Path -LiteralPath $SecretPath -PathType Leaf)) {
    $bytes = New-Object byte[] $BridgeSecretBytes
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    $generatedSecret = [Convert]::ToHexString($bytes).ToLowerInvariant()
    try {
        $stream = [IO.File]::Open($SecretPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try {
            $writer = [IO.StreamWriter]::new($stream, [Text.UTF8Encoding]::new($false))
            try { $writer.Write($generatedSecret) } finally { $writer.Dispose() }
        } finally { if ($null -ne $stream) { $stream.Dispose() } }
    } catch [IO.IOException] {
        if (-not (Test-Path -LiteralPath $SecretPath -PathType Leaf)) { throw }
    }
}
$secret = [IO.File]::ReadAllText($SecretPath).Trim()
if ($secret.Length -lt $MinimumBridgeSecretLength -or $secret.Length -gt $MaximumBridgeSecretLength) {
    throw "Bridge secret must contain $MinimumBridgeSecretLength-$MaximumBridgeSecretLength characters: $SecretPath"
}

$document = Get-Content -LiteralPath $ResolvedProfiles -Raw | ConvertFrom-Json
if ($null -eq $document.profiles) { throw 'launcher_profiles.json does not contain a profiles object.' }
$changed = $false

function Add-OrVerifyAgentProfile(
    [string] $Key,
    [string] $Name,
    [string] $GameDirectory
) {
    $expected = [ordered]@{
        name = $Name
        type = 'custom'
        lastVersionId = $VersionId
        gameDir = $GameDirectory
        javaDir = $Java
        javaArgs = $JavaArguments
    }
    $property = $document.profiles.PSObject.Properties[$Key]
    if ($null -ne $property) {
        foreach ($field in $expected.Keys) {
            $currentValue = [string]$property.Value.$field
            if ($field -ceq 'javaArgs' -and $currentValue -ceq $LegacyJavaArgs) {
                $property.Value.javaArgs = $expected.javaArgs
                $script:changed = $true
                Write-Host "Migrated bridge-secret JVM argument: $Name"
                continue
            }
            if ($currentValue -cne [string]$expected[$field]) {
                throw "Existing launcher profile '$Key' differs at '$field'; refusing to overwrite it."
            }
        }
        Write-Host "Verified launcher installation: $Name"
        return
    }

    $profile = [pscustomobject][ordered]@{
        created = $Timestamp
        gameDir = $expected.gameDir
        icon = 'Furnace'
        javaArgs = $expected.javaArgs
        javaDir = $expected.javaDir
        lastUsed = $Timestamp
        lastVersionId = $expected.lastVersionId
        name = $expected.name
        type = $expected.type
    }
    $document.profiles | Add-Member -MemberType NoteProperty -Name $Key -Value $profile
    $script:changed = $true
    Write-Host "Added launcher installation: $Name"
}

Add-OrVerifyAgentProfile 'arena-agent-55' 'Arena Agent 55' ([IO.Path]::GetFullPath((Join-Path $env:APPDATA '.minecraft-agent-55')))
Add-OrVerifyAgentProfile 'arena-agent-56' 'Arena Agent 56' ([IO.Path]::GetFullPath((Join-Path $env:APPDATA '.minecraft-agent-56')))

if ($changed) {
    $temporaryPath = "$ResolvedProfiles.arena-agents.tmp"
    $backupPath = "$ResolvedProfiles.arena-agents.backup"
    [IO.File]::WriteAllText($temporaryPath, ($document | ConvertTo-Json -Depth 100), $Utf8NoBom)
    Copy-Item -LiteralPath $ResolvedProfiles -Destination $backupPath -Force
    Move-Item -LiteralPath $temporaryPath -Destination $ResolvedProfiles -Force
    Write-Host "Updated launcher profiles; backup stored at $backupPath"
}
