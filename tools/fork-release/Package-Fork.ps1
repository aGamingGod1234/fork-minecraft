[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)][string]$ModJar,
 [Parameter(Mandatory=$true)][string]$FabricApiJar,
 [Parameter(Mandatory=$true)][string]$CarpetJar,
 [Parameter(Mandatory=$true)][string]$Output,
 [string]$PristineWorld,[switch]$WorldStopped,[string]$CourtJson,
 [string]$CameraJson,[string]$CameraSha256,
 [string]$PresentationJournalDirectory,[string]$PresentationJournalDigest
)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$out=[IO.Path]::GetFullPath($Output)
if(Test-Path -LiteralPath $out){throw 'Use a new immutable package directory.'}
foreach($p in @($ModJar,$FabricApiJar,$CarpetJar)){if(!(Test-Path -LiteralPath $p -PathType Leaf)){throw ('Missing dependency: '+$p)}}
if($PristineWorld -and !$WorldStopped){throw 'Stop and verify the world writer before packaging.'}
if(!$CourtJson){$CourtJson=Join-Path $repo 'data\fork-world\court-v1.json'}
# Legacy callers intentionally retain the old camera default; new candidates pin explicit bytes.
if($CameraJson){
 if($CameraSha256 -notmatch '^[a-fA-F0-9]{64}$' -or (Get-FileHash -LiteralPath $CameraJson).Hash -ine $CameraSha256){throw 'Explicit camera input requires its matching SHA256.'}
}elseif($CameraSha256){throw 'CameraSha256 requires CameraJson.'}
else{$CameraJson=Join-Path $repo 'media\edit\camera-paths-city-1024.json'}
$journalFiles=@();$journalHashes=@{}
if($PresentationJournalDigest -and !$PresentationJournalDirectory){throw 'Journal digest requires its source directory.'}
if($PresentationJournalDirectory){
 if($PresentationJournalDigest -notmatch '^[a-fA-F0-9]{64}$'){throw 'Explicit journals require the reviewed source digest.'}
 $journalFiles=@(Get-ChildItem -LiteralPath $PresentationJournalDirectory -File|Where-Object{$_.Name -match '^(receipt-|archive-|INITIAL-).*\.json$'})
 if($journalFiles.Count -ne 14 -or @($journalFiles|Where-Object Name -like 'receipt-*').Count -ne 12 -or @($journalFiles|Where-Object Name -like 'archive-*').Count -ne 1 -or @($journalFiles|Where-Object Name -like 'INITIAL-*').Count -ne 1){throw 'Expected twelve receipts, one archive and one INITIAL.'}
 [string[]]$names=@($journalFiles.Name);[Array]::Sort($names,[StringComparer]::Ordinal)
 $evidence=''
 foreach($name in $names){
  $file=Get-Item -LiteralPath (Join-Path $PresentationJournalDirectory $name)
  if($file.Length -gt 1000000 -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Invalid journal file.'}
  $raw=[IO.File]::ReadAllText($file.FullName);$null=$raw|ConvertFrom-Json
  if($raw -match '(?i)"(password|secret|authorization|access.?token|refresh.?token|api.?key|credential|email|account|username)"\s*:' -or $raw -match '(?i)([a-z]:[\\/]+|/(Users|home)/|Bearer\s+[a-z0-9]|sk-[a-z0-9]{16,}|[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,})'){throw ('Private data in selected journal: '+$name)}
  $journalHashes[$name]=(Get-FileHash -LiteralPath $file.FullName).Hash.ToLowerInvariant()
  $evidence+=$name+' '+$journalHashes[$name]+[char]10
 }
 $sha=[Security.Cryptography.SHA256]::Create()
 try{$actual=([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($evidence)))).Replace('-','')}finally{$sha.Dispose()}
 if($actual -ine $PresentationJournalDigest){throw 'Selected journal digest differs from reviewed evidence.'}
}
New-Item -ItemType Directory -Path $out,(Join-Path $out 'mods'),(Join-Path $out 'data'),(Join-Path $out 'notices')|Out-Null
$mods=@()
foreach($p in @($ModJar,$FabricApiJar,$CarpetJar)){
 $name=[IO.Path]::GetFileName($p)
 if($name -match '(?i)voice|minecraft_server|client'){throw 'Forbidden dependency.'}
 Copy-Item -LiteralPath $p -Destination (Join-Path (Join-Path $out 'mods') $name)
 $mods+=$name
}
Copy-Item -LiteralPath $CourtJson -Destination (Join-Path $out 'data\court-v1.json')
Copy-Item -LiteralPath $CameraJson -Destination (Join-Path $out 'data\camera-paths.json')
if($CameraSha256 -and (Get-FileHash -LiteralPath (Join-Path $out 'data\camera-paths.json')).Hash -ine $CameraSha256){throw 'Camera input changed during packaging.'}
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Install-Fork.ps1'),(Join-Path $PSScriptRoot 'Install-Graphics.ps1'),(Join-Path $PSScriptRoot 'graphics-manifest.json'),(Join-Path $PSScriptRoot 'README-INSTALL.md') -Destination $out
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
if($journalFiles.Count){
 $journalOut=Join-Path $out 'data\presentation-journal'
 New-Item -ItemType Directory -Path $journalOut|Out-Null
 foreach($file in $journalFiles){$dest=Join-Path $journalOut $file.Name;Copy-Item -LiteralPath $file.FullName -Destination $dest;if((Get-FileHash -LiteralPath $dest).Hash -ine $journalHashes[$file.Name]){throw 'Journal input changed during packaging.'}}
 $notice='Historical LIVE replay: these fourteen game-state files preserve twelve previously committed LIVE rounds and their INITIAL/archive evidence. Playback projects those recorded outcomes; it does not claim new provider activity. No provider logs, accounts or credentials are included. Source digest: '+$PresentationJournalDigest.ToLowerInvariant()
 [IO.File]::WriteAllText((Join-Path $out 'PRESENTATION-REPLAY.txt'),$notice,[Text.UTF8Encoding]::new($false))
}
$files=@(Get-ChildItem -LiteralPath $out -Recurse -File|ForEach-Object{
 $rel=$_.FullName.Substring($out.Length+1).Replace('\','/')
 if($rel -match '(?i)(^|/)(launcher_accounts|launcher_profiles|credentials|secrets|session\.lock|usercache\.json|whitelist\.json|ops\.json)|\.(key|pem)$'){throw ('Private/runtime file: '+$rel)}
 [ordered]@{path=$rel;bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
})
$manifest=[ordered]@{schema='fork-package-1';releaseCommit=(git -C $repo rev-parse HEAD);createdUtc=[DateTime]::UtcNow.ToString('o');minecraft='26.1.2';fabricLoader='0.19.3';java='25';mods=$mods;pristineWorldIncluded=[bool]$PristineWorld;runtimeTopology='one bundled supervisor';providerCredentialsIncluded=$false;cameraSha256=(Get-FileHash -LiteralPath (Join-Path $out 'data\camera-paths.json')).Hash.ToLowerInvariant();presentationJournalDigest=$PresentationJournalDigest;presentationJournalFileCount=$journalFiles.Count;acceptance='UNRUN until recipient test';files=$files}
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
