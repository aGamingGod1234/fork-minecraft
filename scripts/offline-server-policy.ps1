Set-StrictMode -Version Latest

function ConvertFrom-ArenaJavaPropertyEscapes([string] $Value) {
	$builder = [Text.StringBuilder]::new()
	for ($index = 0; $index -lt $Value.Length; $index += 1) {
		$character = $Value[$index]
		if ($character -ne '\') {
			$null = $builder.Append($character)
			continue
		}
		if ($index + 1 -ge $Value.Length) { throw 'Malformed trailing escape in server.properties key' }
		$index += 1
		$escaped = $Value[$index]
		if ($escaped -eq 'u') {
			if ($index + 4 -ge $Value.Length) { throw 'Malformed Unicode escape in server.properties key' }
			$hex = $Value.Substring($index + 1, 4)
			[uint32] $codePoint = 0
			if (-not [uint32]::TryParse($hex, [Globalization.NumberStyles]::HexNumber, [Globalization.CultureInfo]::InvariantCulture, [ref] $codePoint)) {
				throw 'Malformed Unicode escape in server.properties key'
			}
			$null = $builder.Append([char] $codePoint)
			$index += 4
			continue
		}
		$decoded = switch ($escaped) {
			't' { "`t" }
			'n' { "`n" }
			'r' { "`r" }
			'f' { "`f" }
			default { [string] $escaped }
		}
		$null = $builder.Append($decoded)
	}
	return $builder.ToString()
}

function ConvertTo-ArenaServerPropertyEntry([string] $Line) {
	$start = 0
	while ($start -lt $Line.Length -and [char]::IsWhiteSpace($Line[$start])) { $start += 1 }
	if ($start -ge $Line.Length -or $Line[$start] -eq '#' -or $Line[$start] -eq '!') { return $null }

	$escaped = $false
	$separator = $Line.Length
	$separatorIsWhitespace = $false
	for ($index = $start; $index -lt $Line.Length; $index += 1) {
		$character = $Line[$index]
		if ($escaped) { $escaped = $false; continue }
		if ($character -eq '\') { $escaped = $true; continue }
		if ($character -eq '=' -or $character -eq ':') { $separator = $index; break }
		if ([char]::IsWhiteSpace($character)) { $separator = $index; $separatorIsWhitespace = $true; break }
	}
	$rawKey = $Line.Substring($start, $separator - $start)
	$valueStart = $separator
	while ($valueStart -lt $Line.Length -and [char]::IsWhiteSpace($Line[$valueStart])) { $valueStart += 1 }
	if ($separatorIsWhitespace -and $valueStart -lt $Line.Length -and ($Line[$valueStart] -eq '=' -or $Line[$valueStart] -eq ':')) { $valueStart += 1 }
	if (-not $separatorIsWhitespace -and $valueStart -lt $Line.Length -and ($Line[$valueStart] -eq '=' -or $Line[$valueStart] -eq ':')) { $valueStart += 1 }
	while ($valueStart -lt $Line.Length -and [char]::IsWhiteSpace($Line[$valueStart])) { $valueStart += 1 }
	return [pscustomobject]@{
		Key = ConvertFrom-ArenaJavaPropertyEscapes $rawKey
		Value = if ($valueStart -lt $Line.Length) { $Line.Substring($valueStart) } else { '' }
	}
}

function Get-ArenaServerPropertyLogicalLines([string] $Path) {
	$logicalLines = [Collections.Generic.List[string]]::new()
	$pending = $null
	foreach ($physicalLine in @(Get-Content -LiteralPath $Path)) {
		$line = if ($null -eq $pending) { $physicalLine } else { $pending + $physicalLine.TrimStart() }
		$trailingBackslashes = 0
		for ($index = $line.Length - 1; $index -ge 0 -and $line[$index] -eq '\'; $index -= 1) { $trailingBackslashes += 1 }
		if (($trailingBackslashes % 2) -eq 1) {
			$pending = $line.Substring(0, $line.Length - 1)
			continue
		}
		$logicalLines.Add($line)
		$pending = $null
	}
	if ($null -ne $pending) { $logicalLines.Add($pending) }
	return @($logicalLines)
}

function Get-ArenaServerPropertyValues([string] $Path, [string] $Name) {
	if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
		throw "Missing server properties: $Path"
	}
	$values = [Collections.Generic.List[string]]::new()
	foreach ($line in @(Get-ArenaServerPropertyLogicalLines $Path)) {
		$entry = ConvertTo-ArenaServerPropertyEntry $line
		if ($null -ne $entry -and $entry.Key -ceq $Name) { $values.Add($entry.Value) }
	}
	return @($values)
}

function Assert-ArenaServerMode([string] $Path, [string] $ExpectedMode) {
	$modes = @(Get-ArenaServerPropertyValues $Path 'online-mode')
	if ($modes.Count -ne 1 -or $modes[0] -cne $ExpectedMode) {
		throw "server.properties must contain exactly one effective online-mode=$ExpectedMode setting: $Path"
	}
}

function Assert-ArenaOfflineServerLoopback([string] $Path, [switch] $RequireOffline) {
	$modes = @(Get-ArenaServerPropertyValues $Path 'online-mode')
	if ($modes.Count -gt 1) {
		throw "server.properties contains duplicate online-mode settings: $Path"
	}
	if ($RequireOffline) { Assert-ArenaServerMode $Path 'false' }
	if ($modes.Count -eq 0 -or $modes[0] -cne 'false') { return }

	$addresses = @(Get-ArenaServerPropertyValues $Path 'server-ip')
	if ($addresses.Count -ne 1 -or $addresses[0] -cne '127.0.0.1') {
		throw "Offline Minecraft servers must contain exactly one server-ip=127.0.0.1 setting: $Path"
	}
}
