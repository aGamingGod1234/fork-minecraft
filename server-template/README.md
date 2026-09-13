# Isolated Fabric server template

This tracked directory contains only the repository-owned template configuration. Generated Fabric libraries, Minecraft server files, mod JARs, worlds, logs, credentials, and EULA state are intentionally excluded from Git.

Materialize the runnable copy at `runtime/server-template` from the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\prepare-headless-server-template.ps1 -AcceptMinecraftEula
```

The command pins Minecraft 26.1.2, Fabric Loader 0.19.3, Fabric Installer 1.1.1, and Fabric API 0.150.0+26.1.2; verifies downloaded hashes; builds Arena Agents; and installs the required Fabric Carpet JAR already tracked under `libs/`.

The headless matrix copies this clean runnable template for every scenario, creates a bounded temporary platform in that scenario's new world, and removes the generated server and private evidence after the run unless `-KeepArtifacts` is requested.

The Reliability workflow also copies this template to a temporary directory,
replaces `mods` with the exact three JARs extracted from the Windows release ZIP,
waits for the server `Done` marker, runs `codex status`, and requires a clean
`stop`. This boot gate does not contact a model provider.

Passing `-AcceptMinecraftEula` confirms that the person running the command accepts the Minecraft EULA. Do not copy a world, launcher credentials, provider credentials, bridge secrets, RCON passwords, or generated server logs into this directory.

The offline gameplay listener is restricted to `127.0.0.1`. Do not remove or broaden `server-ip`.
