[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$ModJar,[Parameter(Mandatory=$true)][string]$FabricApiJar,[Parameter(Mandatory=$true)][string]$CarpetJar,[Parameter(Mandatory=$true)][string]$Output,[string]$PristineWorld,[switch]$WorldStopped)
$ErrorActionPreference='Stop'
$repo=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$out=[IO.Path]::GetFullPath($Output)
if(Test-Path -LiteralPath $out){throw 'Output exists; use an immutable new package directory.'}
foreach($path in @($ModJar,$FabricApiJar,$CarpetJar)){if(!(Test-Path -LiteralPath $path -PathType Leaf)){throw ('Missing dependency: '+$path)}}
if($PristineWorld -and !$WorldStopped){throw 'A pristine world can only be copied after its writer/server is stopped and verified.'}
New-Item -ItemType Directory -Path $out,(Join-Path $out 'mods'),(Join-Path $out 'data'),(Join-Path $out 'notices')|Out-Null
$mods=@()
foreach($path in @($ModJar,$FabricApiJar,$CarpetJar)){
 $name=[IO.Path]::GetFileName($path)
 if($name -match '(?i)voice|minecraft_server|client'){throw 'Forbidden package dependency.'}
 Copy-Item -LiteralPath $path -Destination (Join-Path (Join-Path $out 'mods') $name)
 $mods+=$name
}
Copy-Item -LiteralPath (Join-Path $repo 'data\fork-world\court-v1.json') -Destination (Join-Path $out 'data\court-v1.json')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Install-Fork.ps1'),(Join-Path $PSScriptRoot 'README-INSTALL.md') -Destination $out
foreach($notice in @('LICENSE','NOTICE-FONTS.md','MAP-SOURCES.md')){Copy-Item -LiteralPath (Join-Path $repo $notice) -Destination (Join-Path $out 'notices')}
Copy-Item -LiteralPath (Join-Path $repo 'docs\fork-world\CREDITS.txt') -Destination (Join-Path $out 'notices\WORLD-CREDITS.txt')
Copy-Item -LiteralPath (Join-Path $repo 'libs\README.md') -Destination (Join-Path $out 'notices\DEPENDENCIES.md') -ErrorAction SilentlyContinue
if($PristineWorld){
 $worldTarget=Join-Path $out 'world'
 New-Item -ItemType Directory -Path $worldTarget|Out-Null
 Copy-Item -LiteralPath $PristineWorld -Destination (Join-Path $worldTarget 'INITIAL') -Recurse
}
$files=@(Get-ChildItem -LiteralPath $out -Recurse -File|ForEach-Object{
 $relative=$_.FullName.Substring($out.Length+1).Replace('\','/')
 if($relative -match '(?i)(^|/)(launcher_accounts|launcher_profiles|credentials|secrets|session\.lock|usercache\.json|whitelist\.json|ops\.json)|\.(key|pem)$'){throw ('Private/runtime file in package: '+$relative)}
 [ordered]@{path=$relative;bytes=$_.Length;sha256=(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()}
})
$manifest=[ordered]@{schema='fork-package-1';releaseCommit=(git -C $repo rev-parse HEAD);createdUtc=[DateTime]::UtcNow.ToString('o');minecraft='26.1.2';fabricLoader='0.19.3';java='25';mods=$mods;pristineWorldIncluded=[bool]$PristineWorld;runtimeTopology='one bundled supervisor';providerCredentialsIncluded=$false;acceptance='UNRUN until recipient test';files=$files}
[IO.File]::WriteAllText((Join-Path $out 'package-manifest.json'),($manifest|ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))
$zip=$out+'.zip'
if(Test-Path -LiteralPath $zip){throw 'Immutable ZIP already exists.'}
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$writer=[IO.Compression.ZipFile]::Open($zip,[IO.Compression.ZipArchiveMode]::Create)
try {
 foreach($file in Get-ChildItem -LiteralPath $out -Recurse -File) {
  $entryName=$file.FullName.Substring($out.Length+1).Replace('\','/')
  [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($writer,$file.FullName,$entryName,[IO.Compression.CompressionLevel]::Optimal)
 }
} finally { $writer.Dispose() }
Add-Type -AssemblyName System.IO.Compression, System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::OpenRead($zip)
try{
 $entries=@($archive.Entries|Where-Object{!$_.FullName.EndsWith('/')})
 if($entries.Count -ne $files.Count+1){throw 'ZIP file count mismatch.'}
 $modEntries=@($entries|Where-Object{$_.FullName -like 'mods/*.jar'})
 if($modEntries.Count -ne 3){throw 'ZIP must contain exactly the FORK/API/Carpet mod allowlist.'}
}finally{$archive.Dispose()}
[ordered]@{zip=$zip;sha256=(Get-FileHash -LiteralPath $zip -Algorithm SHA256).Hash;bytes=(Get-Item -LiteralPath $zip).Length;fileCount=$files.Count+1}|ConvertTo-Json
