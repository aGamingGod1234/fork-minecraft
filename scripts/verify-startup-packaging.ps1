[CmdletBinding()]
param(
	[string] $PackageRoot = (Split-Path -Parent $PSScriptRoot)
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

class ArenaStartupPackagingException : System.Exception {
	[string] $Code

	ArenaStartupPackagingException([string] $Code, [string] $Message) : base($Message) {
		$this.Code = $Code
	}
}

function Fail-StartupPackaging([string] $Code, [string] $Message) {
	throw [ArenaStartupPackagingException]::new($Code, $Message)
}

$resolvedRoot = [IO.Path]::GetFullPath($PackageRoot)
$coordinator = Join-Path $resolvedRoot 'coordinator'
foreach ($required in @(
		(Join-Path $coordinator 'src\dynamic-main.mjs'),
		(Join-Path $coordinator 'config\dynamic-agents.json'),
		(Join-Path $coordinator 'package.json'))) {
	if (-not (Test-Path -LiteralPath $required -PathType Leaf)) {
		Fail-StartupPackaging 'COORDINATOR_PACKAGE_MISSING' "Coordinator package prerequisite is missing: $required"
	}
}

$runtimeRelativePath = if ($env:OS -eq 'Windows_NT') {
	'runtime\toolchains\node\node.exe'
} else {
	'runtime/toolchains/node/bin/node'
}
$runtimePath = Join-Path $resolvedRoot $runtimeRelativePath
if (-not (Test-Path -LiteralPath $runtimePath -PathType Leaf)) {
	Fail-StartupPackaging 'NODE_RUNTIME_PACKAGING_MISSING' "A Node.js 22+ runtime must be shipped at '$runtimeRelativePath'; PATH fallback is not self-contained startup."
}
if (-not ((Get-Item -LiteralPath $runtimePath).Mode -match 'x|a')) {
	Fail-StartupPackaging 'NODE_RUNTIME_PACKAGING_INVALID' "The packaged Node.js runtime is not executable: $runtimePath"
}

$versionText = (& $runtimePath --version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $versionText -notmatch '^v?(?<major>\d+)') {
	Fail-StartupPackaging 'NODE_RUNTIME_PACKAGING_INVALID' "Could not read the packaged Node.js version at '$runtimePath'."
}
if ([int]$Matches.major -lt 22) {
	Fail-StartupPackaging 'NODE_RUNTIME_PACKAGING_UNSUPPORTED' "Packaged Node.js must be version 22 or newer; found '$versionText'."
}

Write-Host "Startup packaging preflight passed: bundled Node.js $versionText at $runtimeRelativePath"
