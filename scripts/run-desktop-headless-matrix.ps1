[CmdletBinding()]
param(
	[Parameter(Mandatory = $true)] [string] $ProjectRoot,
	[string] $MatrixPath,
	[string] $ScenarioId,
	[string] $ServerTemplate,
	[switch] $RequireAll,
	[switch] $KeepArtifacts
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Keep the plan's Desktop-facing name while sharing the bounded lifecycle
# implementation. The wrapper itself never chooses a provider or model.
$delegate = Join-Path $PSScriptRoot 'run-headless-provider-matrix.ps1'
if (-not (Test-Path -LiteralPath $delegate -PathType Leaf)) { throw "Missing headless provider wrapper: $delegate" }
& $delegate @PSBoundParameters
