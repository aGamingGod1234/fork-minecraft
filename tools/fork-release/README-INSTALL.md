# Installing FORK
This package is for a dedicated Minecraft Java 26.1.2 instance with Fabric Loader0.19.3 and Java25. It contains exactly the FORK derivative, Fabric API and Carpet. It is not compatible with every Minecraft/Fabric version.

Close the game before installation. On Windows run:
`powershell -File Install-Fork.ps1 -Instance "<your dedicated instance>"`
Or copy the three files in mods/ into your dedicated instance's mods directory and data/court-v1.json to config/fork-court.json. The installer never overwrites a world or a differing mod.

The existing Arena coordinator is bundled inside the FORK JAR. Use one bundled supervisor. Its Node22+ runtime must be installed in the profile runtime location or selected using the JVM argument `-Darenaagents.nodePath=<absolute Node executable>`. Do not run a second manual coordinator with the supervisor. A live run requires the recipient's own supported Codex CLI authentication and actual provider access. No developer credentials or paid capacity are supplied. Fixture/recorded mode, if admitted for the release, is labeled explicitly.

The game's accepted world, command sequence, mode, provider proof and recipient verification will be documented with the accepted release. A diagnostic package is not a verified deliverable. A pristine world is included only after the game is stopped and its checkpoint bundle passes validation.

Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft. Publisher: aGamingGod1234 (Lucas); contact through the GitHub repository. See notices/ for code, fonts and map attribution.
