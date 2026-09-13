$ErrorActionPreference = 'Stop'
$tooling = Join-Path $env:LOCALAPPDATA 'FORK-Tools\fork-singapore-full\pipeline-tooling'
New-Item -ItemType Directory -Force -Path $tooling | Out-Null
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'pipeline-package.json') -Destination (Join-Path $tooling 'package.json')
Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'pipeline-package-lock.json') -Destination (Join-Path $tooling 'package-lock.json')
& npm ci --prefix $tooling --ignore-scripts --no-fund --no-audit
if ($LASTEXITCODE -ne 0) { throw 'Pinned projection dependency install failed' }
