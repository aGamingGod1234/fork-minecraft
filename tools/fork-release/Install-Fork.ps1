[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$Instance,
    [string]$WorldName='FORK-INITIAL'
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath($PSScriptRoot)
$target=[IO.Path]::GetFullPath($Instance)
if(!(Test-Path -LiteralPath $target -PathType Container)){throw 'Choose an existing dedicated Fabric Minecraft 26.1.2 instance.'}
if(Test-Path -LiteralPath (Join-Path $target 'session.lock')){throw 'Stop Minecraft before installing.'}
$manifest=Get-Content -LiteralPath (Join-Path $root 'package-manifest.json') -Raw|ConvertFrom-Json
foreach($file in $manifest.files){
 $source=Join-Path $root $file.path
 if((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ine $file.sha256){throw ('Package hash mismatch: '+$file.path)}
}
$mods=Join-Path $target 'mods'
New-Item -ItemType Directory -Path $mods -Force|Out-Null
$existing=Get-ChildItem -LiteralPath $mods -Filter '*.jar' -File
$allowed=@($manifest.mods)
foreach($file in $existing){
 if($file.Name -notin $allowed){throw ('Dedicated FORK instance contains an unrelated mod: '+$file.Name)}
}
foreach($name in $allowed){
 $source=Join-Path (Join-Path $root 'mods') $name
 $destination=Join-Path $mods $name
 if(Test-Path -LiteralPath $destination){
  if((Get-FileHash -LiteralPath $destination).Hash -ne (Get-FileHash -LiteralPath $source).Hash){throw ('Existing mod differs; use a fresh dedicated instance: '+$name)}
 }else{Copy-Item -LiteralPath $source -Destination $destination}
}
$config=Join-Path $target 'config'
New-Item -ItemType Directory -Path $config -Force|Out-Null
$court=Join-Path $config 'fork-court.json'
$sourceCourt=Join-Path $root 'data\court-v1.json'
if(Test-Path -LiteralPath $court){
 if((Get-FileHash -LiteralPath $court).Hash -ne (Get-FileHash -LiteralPath $sourceCourt).Hash){throw 'Existing court differs; choose a fresh dedicated instance.'}
}else{Copy-Item -LiteralPath $sourceCourt -Destination $court}
$pristine=Join-Path $root 'world\INITIAL'
if(Test-Path -LiteralPath $pristine){
 if($WorldName -match '[\\/:*?"<>|]' -or $WorldName -in @('.','..')){throw 'Choose a simple world folder name.'}
 $saves=Join-Path $target 'saves'
 New-Item -ItemType Directory -Path $saves -Force|Out-Null
 $destination=Join-Path $saves $WorldName
 if(Test-Path -LiteralPath $destination){throw 'Destination world already exists; never overwrite a world.'}
 Copy-Item -LiteralPath $pristine -Destination $destination -Recurse
}
Write-Output 'Installed verified FORK files. Requires Minecraft26.1.2, Fabric0.19.3, Java25. See README-INSTALL.md for provider/runtime and launch instructions.'
