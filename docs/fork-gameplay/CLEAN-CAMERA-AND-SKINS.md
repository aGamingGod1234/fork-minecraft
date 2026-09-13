# Clean camera and FORK role skins

Camera playback now captures the prior `hideGui` and chat-visibility settings, hides them for the take, and restores them through the existing natural-finish, manual-stop, disconnect and world-loss cleanup. Both typed playback and the Director button use this behavior. The independent unpaused client tick counter is unchanged. Switching presets restores the first take before capturing settings for the next take. Playback no longer posts its own start-status message over the film.

Two narrow client mixins guard the entire vanilla HUD extraction and first-person hand renderer during playback. That removes the crosshair, hotbar, chat history, actionbar, locator and status. The guards cease immediately on stop and do not replace the user's settings. The existing saved camera entity/type restoration remains in place. Cinematic owns path size, timing and the installed `camera-paths.json`; no path or World file was edited here.

Three original 64x64 RGBA skins use the existing Minecraft `ClientAsset.ResourceTexture` and classic/wide `PlayerSkin` render path:

| Role | Exact engine AgentId | Exact Carpet profile | Appearance |
| --- | --- | --- | --- |
| Medic | `464f524b-0000-0000-0000-000000000001` | `FORK_MEDIC` | Teal cap, white clinic coat, teal medical symbol |
| Engineer | `464f524b-0000-0000-0000-000000000002` | `FORK_ENGINEER` | Amber hard hat and safety vest, navy workwear, tool belt |
| Courier | `464f524b-0000-0000-0000-000000000003` | `FORK_COURIER` | Cobalt jacket/cap, diagonal strap, sand messenger bag on the back |

Carpet's rendered player UUID is the standard offline UUID of the exact profile name, as already used by Arena's `OfflineAgentPlayers`. It is different from the engine AgentId. The new binding requires both that offline UUID and the case-sensitive name, and only runs with an active FORK client snapshot. It explicitly excludes `LocalPlayer`, even if the local human matches a bot name/UUID. Unrelated players retain the normal skin pipeline. No Mojang account, remote skin, server resource, or Live provider setting changes.

Assets are `src/main/resources/assets/fork/textures/entity/{medic,engineer,courier}.png`. The reproducible authored pixel-art source is `tools/fork-gameplay/generate-role-skins.mjs`. Run it with Node to regenerate the three atlases and `.work/fork/gameplay/role-skins-preview.png` (front/back, Medic/Engineer/Courier). The preview was visually checked, including the Courier's back bag and opaque base UV faces.

Main integration: retain all current manifest entries and register this new **client-only** mixin config as `src/client/resources/fork.client.mixins.json`:

```json
{
  "required": true,
  "package": "dev.fork.gameplay.mixin",
  "compatibilityLevel": "JAVA_25",
  "client": [
    "ForkCameraGuiMixin",
    "ForkCameraHandMixin",
    "ForkRoleSkinMixin"
  ],
  "injectors": { "defaultRequire": 1 }
}
```

Add `{ "config": "fork.client.mixins.json", "environment": "client" }` to the Fabric manifest's `mixins` list. Main owns those registration files. No new entrypoint is needed; the accepted `dev.fork.gameplay.ForkClient` stays registered. Do not copy this worktree's older root manifest or ForkSession over canonical. Main's config-loading and ChunkPos fixes in canonical ForkSession were read and preserved by leaving that file untouched.

Verification: Node atlas checks pass and the front/back preview was inspected. The dependency-free `ForkRoleIdentityVerification` compiles. Running it reached the known local JDK `java.security` access restriction during UUID digest initialization, so it has **not** passed locally; no retry or security-file change was attempted. Main must run that verification and compile the Minecraft client/mixins with its approved build.

Laptop checks still needed: with HUD initially visible, start a preset and confirm no hand, crosshair, chat, locator or actionbar; finish, manually stop and disconnect, checking the prior HUD/chat/camera settings return each time. Repeat with HUD initially hidden and a non-default chat setting. Switch between presets and confirm restoration still uses the original settings. Inspect the three live role bodies from front/back and verify Lucas retains his own skin. Main's reported three-role Live R1 completion in 11 seconds is accepted evidence; this delivery does not change or retest the Live runner.
