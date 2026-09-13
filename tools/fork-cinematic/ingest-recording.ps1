param([Parameter(Mandatory=$true)][string]$RecordingName,[ValidateSet('Prepared','Videos')][string]$RecordingRoot='Prepared')
$ErrorActionPreference = 'Stop'
if ($env:COMPUTERNAME -ine 'LAPTOP') { throw 'Laptop media lease only' }
if ([IO.Path]::GetFileName($RecordingName) -ne $RecordingName -or [IO.Path]::GetExtension($RecordingName) -notin @('.mkv','.mp4','.mov')) { throw 'Supply one recording filename only' }
$workspace = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$recordFolder = if($RecordingRoot -eq 'Videos'){Join-Path $env:USERPROFILE 'Videos'}else{Join-Path $env:LOCALAPPDATA 'FORK-Tools\preflight-20260913\obs'}
$original = Join-Path $recordFolder $RecordingName
$before = Get-Item -LiteralPath $original
if($before.LastWriteTimeUtc -lt [DateTimeOffset]::Parse('2026-09-13T02:30:00Z').UtcDateTime){throw 'Not a current hackathon recording; do not ingest unrelated old media'}
Start-Sleep -Seconds 3
$after = Get-Item -LiteralPath $original
if ($before.Length -ne $after.Length -or $before.LastWriteTimeUtc -ne $after.LastWriteTimeUtc -or $after.Length -eq 0) { throw 'Recording is still changing; leave it intact and wait for Stop Recording' }
$destinationFolder = Join-Path $workspace 'media\source'
[IO.Directory]::CreateDirectory($destinationFolder) | Out-Null
$destination = Join-Path $destinationFolder $RecordingName
# An exclusive read handle rejects an open writer and keeps the original stable
# throughout copy and hashing. The original is never renamed or modified.
$inputStream = [IO.File]::Open($original,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::None)
try {
    $outputStream = [IO.File]::Open($destination,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None)
    try { $inputStream.CopyTo($outputStream) } finally { $outputStream.Dispose() }
    $inputStream.Position=0
    $originalHash = ([BitConverter]::ToString([Security.Cryptography.SHA256]::Create().ComputeHash($inputStream))).Replace('-','')
} finally { $inputStream.Dispose() }
$copiedHash = (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash
if ($originalHash -ne $copiedHash) { throw 'Copy hash mismatch; do not use this media' }
$evidence = [ordered]@{schema='fork-real-ingest-1';utc=[DateTime]::UtcNow.ToString('o');device='Laptop';original=$original;source=('media/source/'+$RecordingName);bytes=$after.Length;sha256=$copiedHash;closedExclusiveRead=$true;stableSeconds=3;originalPreserved=$true;humanVoiceConfirmed=$false;gameViewConfirmed=$false}
$evidencePath = Join-Path $workspace ('.work\fork\cinematic\ingest-'+[IO.Path]::GetFileNameWithoutExtension($RecordingName)+'.json')
$evidence | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $evidencePath -Encoding UTF8
$evidence | ConvertTo-Json -Depth 5
