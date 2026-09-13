Set-StrictMode -Version Latest

function Get-ArenaGradleProperty([string] $ProjectRoot, [string] $Name) {
	$propertiesPath = Join-Path $ProjectRoot 'gradle.properties'
	if (-not (Test-Path -LiteralPath $propertiesPath -PathType Leaf)) { return $null }
	$matches = @(
		Get-Content -LiteralPath $propertiesPath |
			Where-Object { $_ -match "^$([regex]::Escape($Name))=(.+)$" } |
			ForEach-Object { $Matches[1].Trim() }
	)
	if ($matches.Count -ne 1 -or $matches[0] -notmatch '^[0-9A-Za-z][0-9A-Za-z._+-]*$') {
		throw "gradle.properties must contain one valid $Name value: $propertiesPath"
	}
	return $matches[0]
}

function Get-ArenaModVersion([string] $ProjectRoot) {
	return Get-ArenaGradleProperty $ProjectRoot 'mod_version'
}

function Resolve-ArenaModJar([string] $ProjectRoot) {
	$version = Get-ArenaModVersion $ProjectRoot
	$libraryDirectory = Join-Path $ProjectRoot 'build\libs'
	if ($null -ne $version) {
		return Join-Path $libraryDirectory "arena-agents-$version.jar"
	}
	$candidates = @(Get-ChildItem -LiteralPath $libraryDirectory -Filter 'arena-agents-*.jar' -File -ErrorAction SilentlyContinue)
	if ($candidates.Count -ne 1) {
		throw "Could not resolve exactly one Arena Agents JAR under $libraryDirectory"
	}
	return $candidates[0].FullName
}

function Resolve-ArenaCarpetJar([string] $ProjectRoot) {
	$version = Get-ArenaGradleProperty $ProjectRoot 'carpet_version'
	$libraryDirectory = Join-Path $ProjectRoot 'libs'
	if ($null -ne $version) {
		return Join-Path $libraryDirectory "fabric-carpet-$version.jar"
	}
	$candidates = @(Get-ChildItem -LiteralPath $libraryDirectory -Filter 'fabric-carpet-*.jar' -File -ErrorAction SilentlyContinue)
	if ($candidates.Count -ne 1) {
		throw "Could not resolve exactly one Fabric Carpet JAR under $libraryDirectory"
	}
	return $candidates[0].FullName
}
