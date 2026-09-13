$ErrorActionPreference='Stop'
$workspace=(Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location -LiteralPath $workspace
$nodeExe=(Get-Command node.exe).Source
& $nodeExe (Join-Path $PSScriptRoot 'progress-server.mjs') 1>> (Join-Path $workspace '.work\fork\cinematic\durable-preview.stdout') 2>> (Join-Path $workspace '.work\fork\cinematic\durable-preview.stderr')
exit $LASTEXITCODE
