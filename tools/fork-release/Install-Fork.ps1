[CmdletBinding()]
param(
 [Parameter(Mandatory=$true)][string]$Instance,
 [string]$WorldName='FORK-MarketStreet',
 [string]$NodePath,
 [switch]$WithGraphics
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath($PSScriptRoot)
$target=[IO.Path]::GetFullPath($Instance).TrimEnd('\')
if(!(Test-Path -LiteralPath $target -PathType Container)){throw 'Choose an existing dedicated Fabric 26.1.2 instance.'}
if(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue|Where-Object{$_.Name -match '^javaw?\.exe$' -and $_.CommandLine -and $_.CommandLine.Contains($target)}){throw 'Close Minecraft before installing.'}
if($WorldName -match '[\\/:*?"<>|]' -or $WorldName -in @('.','..','')){throw 'Choose a simple new world folder name.'}
$manifest=Get-Content -LiteralPath (Join-Path $root 'package-manifest.json') -Raw|ConvertFrom-Json
foreach($file in $manifest.files){
 $source=Join-Path $root $file.path
 if((Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash -ine $file.sha256){throw ('Package hash mismatch: '+$file.path)}
}
$graphics=Get-Content -LiteralPath (Join-Path $root 'graphics-manifest.json') -Raw|ConvertFrom-Json
$allowed=@($manifest.mods)
$mods=Join-Path $target 'mods'
foreach($file in Get-ChildItem -LiteralPath $mods -Filter '*.jar' -File -ErrorAction SilentlyContinue){
 if($file.Name -in $allowed){
  if((Get-FileHash -LiteralPath $file.FullName).Hash -ne (Get-FileHash -LiteralPath (Join-Path (Join-Path $root 'mods') $file.Name)).Hash){throw ('Existing mod differs; use a fresh instance: '+$file.Name)}
 }else{
  $extra=@($graphics.files|Where-Object{$_.filename -eq $file.Name -and $_.kind -eq 'mod'})
  if($extra.Count -ne 1 -or (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA512).Hash -ine $extra[0].sha512){throw ('Unverified extra mod; use a dedicated instance: '+$file.Name)}
 }
}
$world=Join-Path (Join-Path $target 'saves') $WorldName
if(Test-Path -LiteralPath $world){throw 'Destination world exists; choose a new WorldName.'}
$journalSource=Join-Path $root 'data\presentation-journal'
$journalTarget=Join-Path $target 'config\fork\presentation-journal'
$journalAlreadyInstalled=$false
if(Test-Path -LiteralPath $journalSource){
 $journalFiles=@(Get-ChildItem -LiteralPath $journalSource -File)
 if($journalFiles.Count -ne 14 -or @($journalFiles|Where-Object{$_.Name -notmatch '^(receipt-|archive-|INITIAL-).*\.json$'}).Count){throw 'Unexpected presentation-journal package files.'}
 if(Test-Path -LiteralPath $journalTarget){
  $installed=@(Get-ChildItem -LiteralPath $journalTarget -Recurse -File)
  if($installed.Count -ne $journalFiles.Count){throw 'Existing presentation journal differs. Use a fresh dedicated instance; it will not be overwritten.'}
  foreach($file in $journalFiles){
   $destination=Join-Path $journalTarget $file.Name
   if(!(Test-Path -LiteralPath $destination -PathType Leaf) -or (Get-FileHash -LiteralPath $destination).Hash -ne (Get-FileHash -LiteralPath $file.FullName).Hash){throw 'Existing presentation journal differs. Use a fresh dedicated instance; it will not be overwritten.'}
  }
  $journalAlreadyInstalled=$true
 }
}
$copies=@()
foreach($name in $allowed){$copies+=@{source=(Join-Path (Join-Path $root 'mods') $name);destination=(Join-Path $mods $name)}}
$copies+=@{source=(Join-Path $root 'data\court-v1.json');destination=(Join-Path $target 'config\fork-court.json')}
$camera=Join-Path $root 'data\camera-paths.json'
if(Test-Path -LiteralPath $camera){$copies+=@{source=$camera;destination=(Join-Path $target 'config\arenaagents\camera-paths.json')}}
foreach($copy in $copies){
 if((Test-Path -LiteralPath $copy.destination) -and (Get-FileHash -LiteralPath $copy.destination).Hash -ne (Get-FileHash -LiteralPath $copy.source).Hash){throw ('Existing file differs; use a fresh instance: '+$copy.destination)}
}
if(!$NodePath){$found=Get-Command node.exe -ErrorAction SilentlyContinue;if($found){$NodePath=$found.Source}}
$nodeCopy=$null
if($NodePath){
 $NodePath=[IO.Path]::GetFullPath($NodePath)
 $version=& $NodePath --version
 if($LASTEXITCODE -ne 0 -or $version -notmatch '^v(\d+)\.' -or [int]$Matches[1] -lt 22){throw 'Node 22 or newer is required.'}
 $license=Join-Path (Split-Path -Parent $NodePath) 'LICENSE'
 if(Test-Path -LiteralPath $license){$nodeCopy=@{executable=$NodePath;license=$license;target=(Join-Path $target 'arena-agents-runtime\runtime\toolchains\node')}}
}
foreach($copy in $copies){New-Item -ItemType Directory -Force -Path (Split-Path -Parent $copy.destination)|Out-Null;if(!(Test-Path -LiteralPath $copy.destination)){Copy-Item -LiteralPath $copy.source -Destination $copy.destination}}
$pristine=Join-Path $root 'world\INITIAL'
if(Test-Path -LiteralPath $pristine){New-Item -ItemType Directory -Force -Path (Split-Path -Parent $world)|Out-Null;Copy-Item -LiteralPath $pristine -Destination $world -Recurse}
if(Test-Path -LiteralPath $journalSource){
 if($journalAlreadyInstalled){Write-Output 'Matching historical LIVE journal already installed; keeping its verified files.'}
 else{New-Item -ItemType Directory -Path $journalTarget -Force|Out-Null;foreach($file in $journalFiles){Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $journalTarget $file.Name)}}
 Write-Output 'Historical LIVE replay journal installed at config/fork/presentation-journal; no JVM argument is required.'
}
if($nodeCopy){
 New-Item -ItemType Directory -Force -Path $nodeCopy.target|Out-Null
 foreach($pair in @(@{source=$nodeCopy.executable;name='node.exe'},@{source=$nodeCopy.license;name='LICENSE'})){
  $dst=Join-Path $nodeCopy.target $pair.name
  if(Test-Path -LiteralPath $dst){if((Get-FileHash -LiteralPath $dst).Hash -ne (Get-FileHash -LiteralPath $pair.source).Hash){throw 'Existing Node runtime differs; keep it and use the documented JVM option.'}}
  else{Copy-Item -LiteralPath $pair.source -Destination $dst}
 }
}elseif($NodePath){Write-Output ('Set this launcher JVM argument: -Darenaagents.nodePath="'+$NodePath+'"')}
else{Write-Output 'Node not found. Fixture play is available; Live needs Node22+ and your own Codex sign-in. See README-INSTALL.md.'}
if($WithGraphics){& (Join-Path $root 'Install-Graphics.ps1') -Instance $target}
Write-Output ('Installed verified FORK. Open world FORK - Market Street (folder '+$WorldName+'), then /fork start live or explicitly /fork start fixture. Press G for controls.')
