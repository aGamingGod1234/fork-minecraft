# Play FORK

A six-round power dilemma in a compact Minecraft reconstruction of Singapore's Market Street district. Three inhabitants share one generator and two battery charges. Try a future, rewind the starting checkpoint, and compare.

## Install

Use a dedicated **Minecraft Java 26.1.2** instance with **Fabric Loader 0.19.3** and **Java 25**. The mod works with compatible Fabric launchers; it is version-specific. Keep your existing worlds separate. The archive includes FORK, Fabric API 0.150.0+26.1.2, Carpet 26.1+v260402 and the new world. Minecraft itself and accounts are not included.

Close Minecraft, extract the archive, and run from the extracted folder:

```powershell
powershell -File .\Install-Fork.ps1 -Instance "C:\path\to\your\Fabric-instance" -WithGraphics
```

`-WithGraphics` downloads the pinned Sodium 0.9.1, Iris 1.11.3 and MakeUp Ultra Fast 9.5e directly from their official distribution URLs and verifies SHA-512. Omit it for standard graphics. Existing matching graphics mods are accepted. Start with the shader's Low preset; performance depends on the machine. Keep only one Fabric API JAR.

For a manual install, including macOS/Linux:

1. Copy the three `mods/*.jar` files into the instance's `mods` directory.
2. Copy `world/INITIAL` to a **new** `saves/FORK-MarketStreet` folder. Do not copy into an existing world.
3. Copy `data/court-v1.json` to `config/fork-court.json`.
4. Copy `data/camera-paths.json` to `config/arenaagents/camera-paths.json`.
5. Optional graphics: use the exact official files in `graphics-manifest.json`. Sodium/Iris go in `mods`; the shader ZIP stays zipped in `shaderpacks`.

The installer verifies every packaged file before copying and refuses to overwrite an existing world or a differing configuration/mod.

## Runtime and modes

**Live** uses your own supported Codex CLI sign-in and model access. The tested CLI is 0.154.0. Install Node.js 22 or newer, install the official Codex CLI, and run `codex login` using your own account. Confirm `codex login status` and `codex --version` work in the same Windows account that starts Minecraft. The launcher must inherit a PATH containing Codex; restart the launcher after installation. No credentials or model capacity are supplied.

The coordinator is already bundled in the FORK JAR. Do not start a second coordinator. On Windows the installer finds your installed Node and copies its executable and licence into the dedicated profile runtime when the licence is available. You can instead set the launcher JVM argument `-Darenaagents.nodePath="C:\absolute\path\node.exe"`. On other systems use an absolute Node executable path. The game does not search PATH for Node itself.

**Fixture** is a deterministic, explicitly labelled local demonstration and needs no provider account. It is available for checking the mechanics. It must not be presented as live model behavior. The film's mode is recorded separately in its manifest.

## First run

Open **FORK - Market Street**. Enable commands for your local world if your launcher asks. Then:

```text
/fork start live
/fork return
```

Use `/fork start fixture` instead only when deliberately choosing the local deterministic demonstration. Wait for Medic, Engineer and Courier. Press **G** for the Field Console: choose Clinic or Workshop, then Advance. Each successful advance commits one round. In Live, wait for READY before advancing again; the whole attempt has a 20-second limit. Cancel remains available. After an error, Retry allows one further attempt; do not silently change modes.

A complete comparison:

1. Choose Clinic and advance to round 6. Keep the result.
2. Rewind. Confirm **INITIAL verified**, round 0 and two charges.
3. Choose Workshop and advance to round 6.
4. Open Compare. Both branches show six service cells, downtime, repairs, grid activation and charge. Live outcomes depend on role choices.

Commands are `/fork power clinic`, `/fork power workshop`, `/fork advance`, `/fork cancel`, `/fork retry`, `/fork rewind`, `/fork inspect` and `/fork compare`.

For the controlled facade demonstration, use `/fork demolish` while paused between rounds, then `/fork rewind`. Only the bounded decorative mask is removed; no TNT is used. A full 16-cube including air, actor anchors and outside sentinels is verified on restore.

At round 0 or 6, `/fork visit market-street-view` visits the checked nearby street viewpoint. `/fork return` returns to the game. Travel does not advance a round or consume charge.

After quitting and reopening a world, use **`/fork new live`** (or explicitly `new fixture`) to start a new session. Earlier receipts remain on disk. This release does not resume the previous round or reload its comparison into memory.

## Camera and capture

Director is in the G console. `/camera path play fork_intro` runs the opening move; `fork_city_crane` runs the longer version. Playback hides HUD, chat and the first-person hand, then restores the previous view/settings. `/camera path stop-playback` stops early.

Record only the game at 1920x1080, 30 fps. Use a game-only OBS source rather than Display Capture. Scene/character shots use clean cameras; results/inspector footage must retain readable actual mode and values. The separate Singapore locator in the film is an orientation map, not a full-island Minecraft world.

## Coverage and verification

The world covers a **256 by 256 metre Market/Cecil/Cross Street district**, at one block per metre. It uses OpenStreetMap footprints and source heights. Facade materials were adjusted using architectural references; missing heights, partial edge buildings and approximate facade/roof detail are documented. It is not a photographic reconstruction of all Singapore. The clinic/workshop is an authored game installation.

Automated verification completed on 13 September: exact fixture A/B outcomes, three bounded restores, installed-source checks, custom skin identity binding and loaded Visit/Return in the corrected world. One earlier Laptop Live round completed in 11 seconds. Full current Laptop Live/camera/shader acceptance and final film playback are recorded separately; do not infer them from automated checks.

See `notices/`, `world/INITIAL/world-sources/` and `package-manifest.json` for source, licences, changes and hashes. Map data © OpenStreetMap contributors: https://www.openstreetmap.org/copyright . Graphics downloads retain their upstream licences and source; see `graphics-manifest.json`.

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft. Publisher: aGamingGod1234 (Lucas), contact through the GitHub repository.
