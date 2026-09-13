[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)][string]$ModJar,
 [Parameter(Mandatory=$true)][string]$FabricApiJar,
 [Parameter(Mandatory=$true)][string]$CarpetJar,
 [Parameter(Mandatory=$true)][string]$Output,
 [string]$PristineWorld,[switch]$WorldStopped,[string]$CourtJson
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$out=[IO.Path]::GetFullPath($Output)
if(Test-Path -LiteralPath $out){throw 'Use a new immutable package directory.'}
foreach($p in @($ModJar,$FabricApiJar,$CarpetJar)){if(!(Test-Path -LiteralPath $p -PathType Leaf)){throw ('Missing dependency: '+$p)}}
if($PristineWorld -and !$WorldStopped){throw 'Stop and verify the world writer before packaging.'}
if(!$CourtJson){$CourtJson=Join-Path $repo 'data\fork-world\court-v1.json'}
New-Item -ItemType Directory -Path $out,(Join-Path $out 'mods'),(Join-Path $out 'data'),(Join-Path $out 'notices')|Out-Null
$mods=@()
foreach($p in @($ModJar,$FabricApiJar,$CarpetJar)){
 $name=[IO.Path]::GetFileName($p)
 if($name -match '(?i)voice|minecraft_server|client'){throw 'Forbidden dependency.'}
 Copy-Item -LiteralPath $p -Destination (Join-Path (Join-Path $out 'mods') $name)
 $mods+=$name
}
Copy-Item -LiteralPath $CourtJson -Destination (Join-Path $out 'data\court-v1.json')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Install-Fork.ps1'),(Join-Path $PSScriptRoot 'README-INSTALL.md') -Destination $out
foreach($name in @('LICENSE','NOTICE-FONTS.md','MAP-SOURCES.md')){Copy-Item -LiteralPath (Join-Path $repo $name) -Destination (Join-Path $out 'notices')}
Copy-Item -LiteralPath (Join-Path $repo 'docs\fork-world\CREDITS.txt') -Destination (Join-Path $out 'notices\WORLD-CREDITS.txt')
Copy-Item -LiteralPath (Join-Path $repo 'libs\README.md') -Destination (Join-Path $out 'notices\DEPENDENCIES.md') -ErrorAction SilentlyContinue
if($PristineWorld){
 $sourceWorld=[IO.Path]::GetFullPath($PristineWorld).TrimEnd('\')
 $cleanWorld=Join-Path $out 'world\INITIAL'
 New-Item -ItemType Directory -Path $cleanWorld|Out-Null
 foreach($f in Get-ChildItem -LiteralPath $sourceWorld -Recurse -File){
  $rel=$f.FullName.Substring($sourceWorld.Length+1)
  $parts=$rel.Split('\')
  if(@($parts|Where-Object{$_ -in @('playerdata','advancements','stats','fork','entities')}).Count){continue}
  if($f.Name -in @('session.lock','level.dat_old')){continue}
  $dest=Join-Path $cleanWorld $rel
  New-Item -ItemType Directory -Path (Split-Path $dest -Parent) -Force|Out-Null
  Copy-Item -LiteralPath $f.FullName -Destination $dest
 }
}
$files=@(Get-ChildItem -LiteralPath $out -Recurse -File|ForEach-Object{
 $rel=$_.FullName.Substring($out.Length+1).Replace('\','/')
 if($rel -match '(?i)(^|/)(launcher_accounts|launcher_profiles|credentials|secrets|session\.lock|usercache\.json|whitelist\.json|ops\.json)|\.(key|pem)$'){throw ('Private/runtime file: '+$rel)}
 [ordered]@{path=$rel;bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
})
$manifest=[ordered]@{schema='fork-package-1';releaseCommit=(git -C $repo rev-parse HEAD);createdUtc=[DateTime]::UtcNow.ToString('o');minecraft='26.1.2';fabricLoader='0.19.3';java='25';mods=$mods;pristineWorldIncluded=[bool]$PristineWorld;runtimeTopology='one bundled supervisor';providerCredentialsIncluded=$false;acceptance='UNRUN until recipient test';files=$files}
[IO.File]::WriteAllText((Join-Path $out 'package-manifest.json'),($manifest|ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
$zip=$out+'.zip';if(Test-Path -LiteralPath $zip){throw 'Immutable ZIP exists'}
Add-Type -AssemblyName System.IO.Compression,System.IO.Compression.FileSystem
$writer=[IO.Compression.ZipFile]::Open($zip,[IO.Compression.ZipArchiveMode]::Create)
try{foreach($f in Get-ChildItem -LiteralPath $out -Recurse -File){$entry=$f.FullName.Substring($out.Length+1).Replace('\','/');[void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($writer,$f.FullName,$entry,[IO.Compression.CompressionLevel]::Optimal)}}finally{$writer.Dispose()}
$archive=[IO.Compression.ZipFile]::OpenRead($zip)
try{
 $entries=@($archive.Entries|Where-Object{!$_.FullName.EndsWith('/')})
 if($entries.Count -ne $files.Count+1){throw 'ZIP count mismatch'}
 if(@($entries|Where-Object{$_.FullName -like 'mods/*.jar'}).Count -ne 3){throw 'ZIP mod allowlist mismatch'}
}finally{$archive.Dispose()}
[ordered]@{zip=$zip;sha256=(Get-FileHash -LiteralPath $zip).Hash;bytes=(Get-Item -LiteralPath $zip).Length;fileCount=$files.Count+1}|ConvertTo-Json
