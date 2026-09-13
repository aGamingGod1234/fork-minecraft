[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$SourceKey,

    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$Destination
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($env:OS -eq 'Windows_NT' -and $null -eq ('ArenaAgents.Maps.PinnedDirectory' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using Microsoft.Win32.SafeHandles;

namespace ArenaAgents.Maps {
    public static class PinnedDirectory {
        private const uint FILE_SHARE_READ = 1;
        private const uint FILE_SHARE_WRITE = 2;
        private const uint OPEN_EXISTING = 3;
        private const uint FILE_FLAG_BACKUP_SEMANTICS = 0x02000000;
        private const uint FILE_FLAG_OPEN_REPARSE_POINT = 0x00200000;
        private const uint FILE_ATTRIBUTE_DIRECTORY = 0x10;
        private const uint FILE_ATTRIBUTE_REPARSE_POINT = 0x400;

        [StructLayout(LayoutKind.Sequential)]
        private struct BY_HANDLE_FILE_INFORMATION {
            public uint FileAttributes;
            public System.Runtime.InteropServices.ComTypes.FILETIME CreationTime;
            public System.Runtime.InteropServices.ComTypes.FILETIME LastAccessTime;
            public System.Runtime.InteropServices.ComTypes.FILETIME LastWriteTime;
            public uint VolumeSerialNumber;
            public uint FileSizeHigh;
            public uint FileSizeLow;
            public uint NumberOfLinks;
            public uint FileIndexHigh;
            public uint FileIndexLow;
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern SafeFileHandle CreateFile(
            string name, uint access, uint share, IntPtr security, uint creation, uint flags, IntPtr template);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool GetFileInformationByHandle(
            SafeFileHandle handle, out BY_HANDLE_FILE_INFORMATION information);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern uint GetFinalPathNameByHandle(
            SafeFileHandle handle, StringBuilder path, uint length, uint flags);

        private static string Normalize(string path) {
            string full = Path.GetFullPath(path);
            string root = Path.GetPathRoot(full);
            return String.Equals(full, root, StringComparison.OrdinalIgnoreCase)
                ? root
                : full.TrimEnd(Path.DirectorySeparatorChar);
        }

        public static SafeFileHandle Open(string expectedPath) {
            string expected = Normalize(expectedPath);
            SafeFileHandle handle = CreateFile(
                expected,
                0,
                FILE_SHARE_READ | FILE_SHARE_WRITE,
                IntPtr.Zero,
                OPEN_EXISTING,
                FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT,
                IntPtr.Zero);
            if (handle.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
            try {
                BY_HANDLE_FILE_INFORMATION information;
                if (!GetFileInformationByHandle(handle, out information)) {
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                }
                if ((information.FileAttributes & FILE_ATTRIBUTE_DIRECTORY) == 0 ||
                    (information.FileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                    throw new IOException("Pinned path is not a regular directory: " + expected);
                }
                StringBuilder resolved = new StringBuilder(32768);
                uint length = GetFinalPathNameByHandle(handle, resolved, (uint)resolved.Capacity, 0);
                if (length == 0 || length >= resolved.Capacity) {
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                }
                string actual = resolved.ToString();
                if (actual.StartsWith(@"\\?\")) actual = actual.Substring(4);
                actual = Normalize(actual);
                if (!String.Equals(expected, actual, StringComparison.OrdinalIgnoreCase)) {
                    throw new IOException("Pinned directory resolved outside its expected path: " + expected);
                }
                return handle;
            } catch {
                handle.Dispose();
                throw;
            }
        }

        public static string GetFileIdentity(string path) {
            SafeFileHandle handle = CreateFile(
                path, 0, FILE_SHARE_READ | FILE_SHARE_WRITE, IntPtr.Zero,
                OPEN_EXISTING, FILE_FLAG_OPEN_REPARSE_POINT, IntPtr.Zero);
            if (handle.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
            using (handle) {
                BY_HANDLE_FILE_INFORMATION information;
                if (!GetFileInformationByHandle(handle, out information)) {
                    throw new Win32Exception(Marshal.GetLastWin32Error());
                }
                if ((information.FileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_REPARSE_POINT)) != 0) {
                    throw new IOException("File identity path is not a regular file: " + path);
                }
                ulong index = ((ulong)information.FileIndexHigh << 32) | information.FileIndexLow;
                return information.VolumeSerialNumber.ToString("x8") + ":" + index.ToString("x16");
            }
        }
    }
}
'@
}

function Get-Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-FileIdentity([string]$Path) {
    if ($env:OS -eq 'Windows_NT') {
        return [ArenaAgents.Maps.PinnedDirectory]::GetFileIdentity($Path)
    }
    return $null
}

function Assert-OfficialDigest([string]$Path, [psobject]$ArchiveRecord) {
    if ($null -ne $ArchiveRecord.sha512 -and -not [string]::IsNullOrWhiteSpace([string]$ArchiveRecord.sha512)) {
        $actualSha512 = (Get-FileHash -LiteralPath $Path -Algorithm SHA512).Hash.ToLowerInvariant()
        if ($actualSha512 -ne ([string]$ArchiveRecord.sha512).ToLowerInvariant()) {
            throw "Archive does not match the official ledger SHA512 digest."
        }
        return
    }
    if ($null -ne $ArchiveRecord.sha1 -and -not [string]::IsNullOrWhiteSpace([string]$ArchiveRecord.sha1)) {
        $actualSha1 = (Get-FileHash -LiteralPath $Path -Algorithm SHA1).Hash.ToLowerInvariant()
        if ($actualSha1 -ne ([string]$ArchiveRecord.sha1).ToLowerInvariant()) {
            throw "Archive does not match the official ledger SHA1 digest."
        }
    }
}

function Test-ReparsePath([string]$Path, [string]$StopAt) {
    $current = [System.IO.Path]::GetFullPath($Path)
    $stop = [System.IO.Path]::GetFullPath($StopAt)
    while ($current.StartsWith($stop, [System.StringComparison]::OrdinalIgnoreCase)) {
        if (Test-Path -LiteralPath $current) {
            $item = Get-Item -LiteralPath $current -Force
            if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Destination contains a link or reparse point: $current"
            }
        }
        if ($current.Equals($stop, [System.StringComparison]::OrdinalIgnoreCase)) {
            return
        }
        $parent = [System.IO.Directory]::GetParent($current)
        if ($null -eq $parent) {
            return
        }
        $current = $parent.FullName
    }
}

function Open-PinnedDirectoryChain([string]$Path) {
    if ($env:OS -ne 'Windows_NT') {
        return @()
    }
    $handles = New-Object System.Collections.Generic.List[System.IDisposable]
    try {
        $fullPath = [System.IO.Path]::GetFullPath($Path)
        $current = [System.IO.Path]::GetPathRoot($fullPath)
        $handles.Add([ArenaAgents.Maps.PinnedDirectory]::Open($current))
        $relative = $fullPath.Substring($current.Length)
        foreach ($component in $relative.Split(@([System.IO.Path]::DirectorySeparatorChar), [System.StringSplitOptions]::RemoveEmptyEntries)) {
            $current = Join-Path $current $component
            $handles.Add([ArenaAgents.Maps.PinnedDirectory]::Open($current))
        }
        return $handles.ToArray()
    } catch {
        foreach ($handle in $handles) {
            $handle.Dispose()
        }
        throw
    }
}

function Assert-SafeRegularFile([string]$Path, [string]$Description) {
    $item = Get-Item -LiteralPath $Path -Force
    if ($item.PSIsContainer -or ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
        throw "$Description must be a regular, non-reparse file: $Path"
    }
}

function Publish-OwnedFile([string]$PartialPath, [string]$FinalPath, [ref]$Owned) {
    Assert-SafeRegularFile -Path $PartialPath -Description 'Acquisition partial path'
    if (Test-Path -LiteralPath $FinalPath) {
        Assert-SafeRegularFile -Path $FinalPath -Description 'Publication collision path'
        throw "Map publication destination already exists: $FinalPath"
    }
    [System.IO.File]::Move($PartialPath, $FinalPath)
    $Owned.Value = $true
}

function Write-AcquisitionJournal([string]$JournalPath, [psobject]$Journal) {
    $temporaryJournalPath = "$JournalPath.$($Journal.token).partial"
    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($temporaryJournalPath, (($Journal | ConvertTo-Json -Depth 6) + "`n"), $utf8WithoutBom)
    if (Test-Path -LiteralPath $JournalPath) {
        Assert-SafeRegularFile -Path $JournalPath -Description 'Acquisition journal path'
        $backupJournalPath = "$JournalPath.$($Journal.token).backup"
        [System.IO.File]::Replace($temporaryJournalPath, $JournalPath, $backupJournalPath, $true)
        Remove-Item -LiteralPath $backupJournalPath -Force
    } else {
        [System.IO.File]::Move($temporaryJournalPath, $JournalPath)
    }
}

function Read-AcquisitionJournal(
    [string]$JournalPath,
    [string]$ExpectedSourceKey,
    [string]$ArchiveFilename,
    [string]$EvidenceFilename
) {
    Assert-SafeRegularFile -Path $JournalPath -Description 'Acquisition journal path'
    $journal = Get-Content -LiteralPath $JournalPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($journal.schemaVersion -ne 1 -or $journal.sourceKey -ne $ExpectedSourceKey -or
        ([string]$journal.token) -notmatch '^[0-9a-f]{32}$') {
        throw "Acquisition journal identity is invalid: $JournalPath"
    }
    $expectedArchivePartial = ".$ArchiveFilename.$($journal.token).partial"
    $expectedEvidencePartial = "$EvidenceFilename.$($journal.token).partial"
    if ($journal.archive.final -ne $ArchiveFilename -or $journal.archive.partial -ne $expectedArchivePartial -or
        $journal.evidence.final -ne $EvidenceFilename -or $journal.evidence.partial -ne $expectedEvidencePartial) {
        throw "Acquisition journal paths do not match the approved source: $JournalPath"
    }
    foreach ($digest in @($journal.archive.sha256, $journal.evidence.sha256)) {
        if ($null -ne $digest -and ([string]$digest) -notmatch '^[0-9a-f]{64}$') {
            throw "Acquisition journal contains an invalid digest: $JournalPath"
        }
    }
    if ($env:OS -eq 'Windows_NT') {
        foreach ($identity in @($journal.archive.identity, $journal.evidence.identity)) {
            if ($null -ne $identity -and ([string]$identity) -notmatch '^[0-9a-f]{8}:[0-9a-f]{16}$') {
                throw "Acquisition journal contains an invalid file identity: $JournalPath"
            }
        }
    }
    return $journal
}

function Remove-JournalOwnedPath(
    [string]$DestinationPath,
    [string]$Filename,
    [object]$ExpectedSha256,
    [object]$ExpectedIdentity
) {
    $path = Join-Path $DestinationPath $Filename
    if (-not (Test-Path -LiteralPath $path)) {
        return
    }
    Assert-SafeRegularFile -Path $path -Description 'Journal-owned path'
    if ($null -eq $ExpectedSha256 -or [string]::IsNullOrWhiteSpace([string]$ExpectedSha256)) {
        throw "Journal-owned path has no recorded digest and was preserved: $path"
    }
    if ((Get-Sha256 -Path $path) -ne ([string]$ExpectedSha256).ToLowerInvariant()) {
        throw "Journal-owned path content changed and was preserved: $path"
    }
    if ($env:OS -eq 'Windows_NT' -and
        ($null -eq $ExpectedIdentity -or (Get-FileIdentity -Path $path) -ne [string]$ExpectedIdentity)) {
        throw "Journal-owned path identity changed and was preserved: $path"
    }
    Remove-Item -LiteralPath $path -Force
    if (Test-Path -LiteralPath $path) {
        throw "Journal-owned path could not be removed: $path"
    }
}

function Remove-OwnedTransaction([string]$DestinationPath, [string]$JournalPath, [psobject]$Journal) {
    # Fail closed before removing any matching path when any extant path has
    # changed. This preserves the whole transaction for review.
    foreach ($record in @($Journal.evidence, $Journal.archive)) {
        foreach ($filename in @([string]$record.final, [string]$record.partial)) {
            $path = Join-Path $DestinationPath $filename
            if (Test-Path -LiteralPath $path) {
                Assert-SafeRegularFile -Path $path -Description 'Journal-owned path'
                if ($null -eq $record.sha256 -or (Get-Sha256 -Path $path) -ne ([string]$record.sha256).ToLowerInvariant()) {
                    throw "Journal-owned path content changed and was preserved: $path"
                }
                if ($env:OS -eq 'Windows_NT' -and
                    ($null -eq $record.identity -or (Get-FileIdentity -Path $path) -ne [string]$record.identity)) {
                    throw "Journal-owned path identity changed and was preserved: $path"
                }
            }
        }
    }
    foreach ($record in @($Journal.evidence, $Journal.archive)) {
        Remove-JournalOwnedPath -DestinationPath $DestinationPath -Filename ([string]$record.final) -ExpectedSha256 $record.sha256 -ExpectedIdentity $record.identity
        Remove-JournalOwnedPath -DestinationPath $DestinationPath -Filename ([string]$record.partial) -ExpectedSha256 $record.sha256 -ExpectedIdentity $record.identity
    }
    Remove-Item -LiteralPath $JournalPath -Force
}

function Enter-MapAcquisitionLock([string]$LockPath, [int]$TimeoutMilliseconds = 30000) {
    $waitTimer = [System.Diagnostics.Stopwatch]::StartNew()
    while ($true) {
        if (Test-Path -LiteralPath $LockPath) {
            $lockItem = Get-Item -LiteralPath $LockPath -Force
            if (($lockItem.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0) {
                throw "Acquisition lock path is a link or reparse point: $LockPath"
            }
        }
        try {
            # The marker persists, but only the OS FileShare.None handle grants
            # ownership. A crashed process therefore cannot leave a stale lock.
            return [System.IO.File]::Open(
                $LockPath,
                [System.IO.FileMode]::OpenOrCreate,
                [System.IO.FileAccess]::ReadWrite,
                [System.IO.FileShare]::None
            )
        } catch [System.IO.IOException] {
            $win32Error = $_.Exception.HResult -band 0xFFFF
            if ($win32Error -ne 32 -and $win32Error -ne 33) {
                throw
            }
            if ($waitTimer.ElapsedMilliseconds -ge $TimeoutMilliseconds) {
                throw "Timed out after $TimeoutMilliseconds ms waiting for the map acquisition lock: $LockPath"
            }
            Start-Sleep -Milliseconds 50
        }
    }
}

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$ledgerPath = Join-Path $repositoryRoot 'maps\source-ledger.json'
$ledger = Get-Content -LiteralPath $ledgerPath -Raw -Encoding UTF8 | ConvertFrom-Json
$sourceProperty = $ledger.sources.PSObject.Properties[$SourceKey]
if ($null -eq $sourceProperty) {
    throw "Source key '$SourceKey' is not present in the source ledger."
}

$source = $sourceProperty.Value
if ($null -eq $source.archive.url -or [string]::IsNullOrWhiteSpace([string]$source.archive.url)) {
    throw "Source '$SourceKey' has no ledger-approved archive URL. Its license or acquisition record is still provisional."
}
if ($source.licenseStatus -ne 'verified' -or -not $source.bundleEligible) {
    throw "Source '$SourceKey' is not approved for acquisition and bundled derivation."
}

$approvedUri = [System.Uri]$source.archive.url
if (-not $approvedUri.IsAbsoluteUri -or $approvedUri.Scheme -ne 'https' -or -not [string]::IsNullOrEmpty($approvedUri.UserInfo)) {
    throw "The ledger-approved archive URL for '$SourceKey' must be an unauthenticated HTTPS URL."
}

$researchRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot ([string]$ledger.researchRoot)))
if ([System.IO.Path]::IsPathRooted($Destination)) {
    $destinationPath = [System.IO.Path]::GetFullPath($Destination)
} else {
    $destinationPath = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $Destination))
}
$researchPrefix = $researchRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
if (-not $destinationPath.StartsWith($researchPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Destination must be a source-specific directory beneath ignored runtime/map-research."
}
Test-ReparsePath -Path $destinationPath -StopAt $repositoryRoot
New-Item -ItemType Directory -Path $destinationPath -Force | Out-Null

$directoryPins = @(Open-PinnedDirectoryChain -Path $destinationPath)
try {
$lockPath = Join-Path $destinationPath '.arenaagents-acquisition.lock'
$acquisitionLock = Enter-MapAcquisitionLock -LockPath $lockPath
try {
$archivePath = Join-Path $destinationPath ([string]$source.archive.filename)
$evidencePath = "$archivePath.sha256.json"
$journalPath = Join-Path $destinationPath '.arenaagents-acquisition.journal.json'
$activeJournal = $null
if (Test-Path -LiteralPath $journalPath) {
    $activeJournal = Read-AcquisitionJournal `
        -JournalPath $journalPath `
        -ExpectedSourceKey $SourceKey `
        -ArchiveFilename ([string]$source.archive.filename) `
        -EvidenceFilename ([System.IO.Path]::GetFileName($evidencePath))
}
$archiveExistedBefore = Test-Path -LiteralPath $archivePath
$evidenceExistedBefore = Test-Path -LiteralPath $evidencePath
if ($archiveExistedBefore) {
    Assert-SafeRegularFile -Path $archivePath -Description 'Expected archive path'
}
if ($evidenceExistedBefore) {
    Assert-SafeRegularFile -Path $evidencePath -Description 'Expected evidence path'
}
if ($archiveExistedBefore -xor $evidenceExistedBefore) {
    if ($null -eq $activeJournal) {
        throw "An unowned archive/evidence orphan exists and was preserved. Resolve it before acquiring '$SourceKey'."
    }
    Remove-OwnedTransaction -DestinationPath $destinationPath -JournalPath $journalPath -Journal $activeJournal
    $activeJournal = $null
    $archiveExistedBefore = $false
    $evidenceExistedBefore = $false
} elseif ($null -ne $activeJournal -and -not $archiveExistedBefore -and -not $evidenceExistedBefore) {
    Remove-OwnedTransaction -DestinationPath $destinationPath -JournalPath $journalPath -Journal $activeJournal
    $activeJournal = $null
}
$lockedSha256 = if ($null -ne $source.archive.sha256) { ([string]$source.archive.sha256).ToLowerInvariant() } else { $null }
$existingEvidence = $null
if ($evidenceExistedBefore) {
    $existingEvidence = Get-Content -LiteralPath $evidencePath -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($existingEvidence.sourceKey -ne $SourceKey -or $existingEvidence.url -ne $source.archive.url) {
        throw "Existing checksum evidence does not match source '$SourceKey' and its approved URL."
    }
    $evidenceSha256 = ([string]$existingEvidence.sha256).ToLowerInvariant()
    if ($null -ne $lockedSha256 -and $lockedSha256 -ne $evidenceSha256) {
        throw "Checksum drift exists between the ledger and retained evidence for '$SourceKey'."
    }
    $lockedSha256 = $evidenceSha256
}

if ($archiveExistedBefore) {
    if ($null -eq $lockedSha256) {
        throw "An archive exists without a locked checksum. Move it aside before acquiring '$SourceKey'."
    }
    $existingFile = Get-Item -LiteralPath $archivePath
    if ($null -ne $source.archive.size -and $existingFile.Length -ne [long]$source.archive.size) {
        throw "Existing archive size $($existingFile.Length) does not match ledger size $($source.archive.size) for '$SourceKey'."
    }
    Assert-OfficialDigest -Path $archivePath -ArchiveRecord $source.archive
    $existingSha256 = Get-Sha256 -Path $archivePath
    if ($existingSha256 -ne $lockedSha256) {
        throw "Checksum drift detected for the existing '$SourceKey' archive."
    }
    if ($null -ne $activeJournal) {
        $existingEvidenceSha256 = Get-Sha256 -Path $evidencePath
        if ($activeJournal.archive.sha256 -ne $existingSha256 -or
            $activeJournal.evidence.sha256 -ne $existingEvidenceSha256 -or
            ($env:OS -eq 'Windows_NT' -and
                ($activeJournal.archive.identity -ne (Get-FileIdentity -Path $archivePath) -or
                 $activeJournal.evidence.identity -ne (Get-FileIdentity -Path $evidencePath)))) {
            throw "Completed acquisition content no longer matches its journal and was preserved."
        }
        Remove-JournalOwnedPath -DestinationPath $destinationPath -Filename ([string]$activeJournal.archive.partial) -ExpectedSha256 $activeJournal.archive.sha256 -ExpectedIdentity $activeJournal.archive.identity
        Remove-JournalOwnedPath -DestinationPath $destinationPath -Filename ([string]$activeJournal.evidence.partial) -ExpectedSha256 $activeJournal.evidence.sha256 -ExpectedIdentity $activeJournal.evidence.identity
        Remove-Item -LiteralPath $journalPath -Force
    }
    [pscustomobject]@{
        sourceKey = $SourceKey
        archive = $archivePath
        evidence = $evidencePath
        sha256 = $existingSha256
        reused = $true
    }
    return
}

$acquisitionId = [System.Guid]::NewGuid().ToString('N')
$partialPath = Join-Path $destinationPath ('.' + [string]$source.archive.filename + '.' + $acquisitionId + '.partial')
$partialEvidencePath = "$evidencePath.$acquisitionId.partial"
$activeJournal = [ordered]@{
    schemaVersion = 1
    sourceKey = $SourceKey
    token = $acquisitionId
    archive = [ordered]@{
        partial = [System.IO.Path]::GetFileName($partialPath)
        final = [System.IO.Path]::GetFileName($archivePath)
        sha256 = $null
        identity = $null
    }
    evidence = [ordered]@{
        partial = [System.IO.Path]::GetFileName($partialEvidencePath)
        final = [System.IO.Path]::GetFileName($evidencePath)
        sha256 = $null
        identity = $null
    }
}
Write-AcquisitionJournal -JournalPath $journalPath -Journal $activeJournal
$archivePublishedByThisRun = $false
$evidencePublishedByThisRun = $false
$publishSucceeded = $false
try {
    Invoke-WebRequest -UseBasicParsing -MaximumRedirection 0 -Uri $approvedUri.AbsoluteUri -OutFile $partialPath
    $downloadedFile = Get-Item -LiteralPath $partialPath
    if ($null -ne $source.archive.size -and $downloadedFile.Length -ne [long]$source.archive.size) {
        throw "Downloaded size $($downloadedFile.Length) does not match ledger size $($source.archive.size) for '$SourceKey'."
    }

    Assert-OfficialDigest -Path $partialPath -ArchiveRecord $source.archive
    $downloadedSha256 = Get-Sha256 -Path $partialPath
    if ($null -ne $lockedSha256 -and $downloadedSha256 -ne $lockedSha256) {
        throw "Checksum drift detected while acquiring '$SourceKey'."
    }
    $activeJournal.archive.sha256 = $downloadedSha256
    $activeJournal.archive.identity = Get-FileIdentity -Path $partialPath
    Write-AcquisitionJournal -JournalPath $journalPath -Journal $activeJournal

    $evidence = [ordered]@{
        schemaVersion = 1
        sourceKey = $SourceKey
        url = $approvedUri.AbsoluteUri
        filename = [string]$source.archive.filename
        retrievedAtUtc = [DateTime]::UtcNow.ToString('o')
        size = $downloadedFile.Length
        sha256 = $downloadedSha256
    }
    $utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
    [System.IO.File]::WriteAllText($partialEvidencePath, (($evidence | ConvertTo-Json -Depth 4) + "`n"), $utf8WithoutBom)
    $activeJournal.evidence.sha256 = Get-Sha256 -Path $partialEvidencePath
    $activeJournal.evidence.identity = Get-FileIdentity -Path $partialEvidencePath
    Write-AcquisitionJournal -JournalPath $journalPath -Journal $activeJournal
    Publish-OwnedFile -PartialPath $partialPath -FinalPath $archivePath -Owned ([ref]$archivePublishedByThisRun)
    Publish-OwnedFile -PartialPath $partialEvidencePath -FinalPath $evidencePath -Owned ([ref]$evidencePublishedByThisRun)
    if ((Get-Sha256 -Path $archivePath) -ne $activeJournal.archive.sha256 -or
        (Get-Sha256 -Path $evidencePath) -ne $activeJournal.evidence.sha256) {
        throw "Published acquisition pair changed before validation."
    }
    $publishSucceeded = $true
    Remove-Item -LiteralPath $journalPath -Force

    [pscustomobject]@{
        sourceKey = $SourceKey
        archive = $archivePath
        evidence = $evidencePath
        sha256 = $downloadedSha256
        reused = $false
    }
} finally {
    if (-not $publishSucceeded) {
        if ($null -eq $activeJournal.archive.sha256 -and (Test-Path -LiteralPath $partialPath)) {
            $activeJournal.archive.sha256 = Get-Sha256 -Path $partialPath
            $activeJournal.archive.identity = Get-FileIdentity -Path $partialPath
            Write-AcquisitionJournal -JournalPath $journalPath -Journal $activeJournal
        }
        if ($null -eq $activeJournal.evidence.sha256 -and (Test-Path -LiteralPath $partialEvidencePath)) {
            $activeJournal.evidence.sha256 = Get-Sha256 -Path $partialEvidencePath
            $activeJournal.evidence.identity = Get-FileIdentity -Path $partialEvidencePath
            Write-AcquisitionJournal -JournalPath $journalPath -Journal $activeJournal
        }
        Remove-OwnedTransaction -DestinationPath $destinationPath -JournalPath $journalPath -Journal $activeJournal
    }
}
} finally {
    $acquisitionLock.Dispose()
}
} finally {
    for ($index = $directoryPins.Count - 1; $index -ge 0; $index--) {
        $directoryPins[$index].Dispose()
    }
}
