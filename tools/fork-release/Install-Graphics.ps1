[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Instance)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath($PSScriptRoot)
$target=[IO.Path]::GetFullPath($Instance).TrimEnd('\')
if(!(Test-Path -LiteralPath $target -PathType Container)){throw 'Existing instance required.'}
if(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue|Where-Object{$_.Name -match '^javaw?\.exe$' -and $_.CommandLine -and $_.CommandLine.Contains($target)}){throw 'Close Minecraft first.'}
$manifest=Get-Content -LiteralPath (Join-Path $root 'graphics-manifest.json') -Raw|ConvertFrom-Json
$stage=Join-Path ([IO.Path]::GetTempPath()) ('fork-graphics-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage|Out-Null
foreach($file in $manifest.files){
 if([IO.Path]::GetFileName($file.filename) -ne $file.filename -or !$file.url.StartsWith('https://cdn.modrinth.com/')){throw 'Invalid pinned graphics source.'}
 $folder=if($file.kind -eq 'mod'){'mods'}else{'shaderpacks'}
 $dest=Join-Path (Join-Path $target $folder) $file.filename
 if(Test-Path -LiteralPath $dest){
  if((Get-FileHash -LiteralPath $dest -Algorithm SHA512).Hash -ine $file.sha512){throw ('Existing graphics file differs: '+$file.filename)}
  continue
 }
 $download=Join-Path $stage $file.filename
 Invoke-WebRequest -UseBasicParsing -Uri $file.url -OutFile $download
 if((Get-FileHash -LiteralPath $download -Algorithm SHA512).Hash -ine $file.sha512){throw ('Graphics download checksum mismatch: '+$file.filename)}
 New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest)|Out-Null
 Copy-Item -LiteralPath $download -Destination $dest
}
$config=Join-Path $target 'config\iris.properties'
if(!(Test-Path -LiteralPath $config)){
 New-Item -ItemType Directory -Force -Path (Split-Path -Parent $config)|Out-Null
 [IO.File]::WriteAllLines($config,@('enableShaders=true','shaderPack=MakeUp-UltraFast-9.5e.zip','maxShadowRenderDistance=8','colorSpace=SRGB'),[Text.UTF8Encoding]::new($false))
}
$preset=Join-Path $target 'shaderpacks\MakeUp-UltraFast-9.5e.zip.txt'
if(!(Test-Path -LiteralPath $preset)){[IO.File]::WriteAllLines($preset,@($manifest.lowPreset),[Text.UTF8Encoding]::new($false))}
Write-Output 'Verified Sodium0.9.1, Iris1.11.3 and MakeUp9.5e installed from official downloads. Select MakeUp Low in Video Settings > Shader Packs. Reduce shader quality if capture drops frames.'
