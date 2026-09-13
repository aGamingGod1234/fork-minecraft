[CmdletBinding()]
param([string] $ProjectRoot)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) { $ProjectRoot = Split-Path -Parent $PSScriptRoot }

$Project = [IO.Path]::GetFullPath($ProjectRoot)

# These paths are the explicit retention boundary for cleanup work. Keep this
# audit independent from deletion candidates so it remains useful after a cut.
$retainedFiles = [ordered]@{
    'camera director' = 'src/client/java/dev/agaminggod/arenaagents/client/camera/CameraDirectorClient.java'
    'spectator presentation' = 'src/client/java/dev/agaminggod/arenaagents/client/presentation/ArenaSpectatorState.java'
    'console control screen' = 'src/client/java/dev/agaminggod/arenaagents/client/gui/AgentControlScreen.java'
    'skit runtime' = 'src/main/java/dev/agaminggod/arenaagents/server/SkitModeRuntime.java'
    'voice runtime' = 'src/main/java/dev/agaminggod/arenaagents/server/voice/VoiceSubsystemRuntime.java'
    'shared server pathfinder' = 'src/main/java/dev/agaminggod/arenaagents/server/runtime/controller/ServerPathPlanner.java'
    'visual identity' = 'src/main/java/dev/agaminggod/arenaagents/agent/AgentVisualIdentity.java'
    'visual manifest' = 'src/main/resources/assets/arenaagents/identity/agent_visual_manifest.json'
    'camera verification' = 'src/test/java/dev/agaminggod/arenaagents/client/camera/CameraPathVerification.java'
    'presentation verification' = 'src/test/java/dev/agaminggod/arenaagents/client/control/AgentClientPresentationVerification.java'
    'spectator verification' = 'src/test/java/dev/agaminggod/arenaagents/client/ArenaSpectatorStateVerification.java'
    'skit verification' = 'src/test/java/dev/agaminggod/arenaagents/server/SkitModeVerification.java'
    'voice verification' = 'src/test/java/dev/agaminggod/arenaagents/server/voice/VoiceSubsystemVerification.java'
    'shared navigation verification' = 'src/test/java/dev/agaminggod/arenaagents/server/runtime/controller/ServerPathPlannerVerification.java'
}

$missing = [Collections.Generic.List[string]]::new()
foreach ($entry in $retainedFiles.GetEnumerator()) {
    $path = Join-Path $Project $entry.Value
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        $missing.Add("$($entry.Key): $($entry.Value)")
    } else {
        Write-Host "RETAINED $($entry.Key): $($entry.Value)"
    }
}

$verificationMainPath = Join-Path $Project 'src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java'
if (-not (Test-Path -LiteralPath $verificationMainPath -PathType Leaf)) {
    $missing.Add('core verification entrypoint: src/test/java/dev/agaminggod/arenaagents/verification/VerificationMain.java')
} else {
    $verificationMain = Get-Content -LiteralPath $verificationMainPath -Raw
    foreach ($check in @(
        'CameraPathVerification.verify()',
        'AgentClientPresentationVerification.verify()',
        'ArenaSpectatorStateVerification.verify()',
        'SkitModeVerification.verify()',
        'VoiceSubsystemVerification.verify()',
        'ServerPathPlannerVerification.verify()'
    )) {
        if ($verificationMain.IndexOf($check, [StringComparison]::Ordinal) -lt 0) {
            $missing.Add("core verification wiring: $check")
        } else {
            Write-Host "WIRED $check"
        }
    }
}

if ($missing.Count -gt 0) {
    throw "Retained-feature audit failed:`n - $($missing -join "`n - ")"
}

Write-Host ("Retained-feature audit passed: {0} files and six core verification calls." -f $retainedFiles.Count)
