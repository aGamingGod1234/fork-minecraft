# Isolated runtime layout

Everything generated beneath `runtime/` is local evidence or executable state and is ignored by Git except this file.

This layout belongs to a source checkout. The Windows release ZIP uses its own
`install-distribution.ps1` flow and does not ship the preparation scripts below.

## Directories

- `server-template/`: reproducible runnable Fabric server generated from the tracked `server-template/` skeleton by `scripts/prepare-headless-server-template.ps1`; binaries, EULA state, and generated libraries stay ignored.
- `headless-runs/`: isolated per-run server copies, bounded reports, and optionally retained private diagnostics from the real-provider matrix.
- `server/`: legacy interactive Fabric server state.
- `server-offline-smoke/`: optional, clearly labeled offline smoke server; never authenticated evidence.
- `downloads/`: cached SHA-256-verified Fabric installer and Fabric API downloads used by the materializer.
- `evidence/`: world-copy, build, log, screenshot, and live-test summaries with credentials excluded.
- `agent55/traces/agent-55.jsonl` and `agent56/traces/agent-56.jsonl`: separate append-only coordinator traces.
- `bridge-secret.txt`: generated local bridge secret shared by the summonable-NPC server and dynamic coordinator; ignored by Git.
- `toolchains/temurin-25/jdk-25.0.3+9`: project-local Java runtime.

The isolated client directories are outside the project:

- `%APPDATA%\.minecraft-agent-55`
- `%APPDATA%\.minecraft-agent-56`

Only Fabric API and the final Arena Agents JAR are copied into their `mods` directories.

## Official launcher installations

`prepare-runtime.ps1` accepts only `%APPDATA%\.minecraft\saves\New World (76)` as its source world. It requires the project-local Java 25 runtime and an existing Fabric API JAR, installs `fabric-loader-0.19.3-26.1.2`, and copies the world into `runtime/server`. After it completes, create two installations through the official launcher UI:

### Arena Agent 55 (GPT-5.5 xhigh Fast)

- Version: `fabric-loader-0.19.3-26.1.2`
- Game directory: `C:\Users\lucas\AppData\Roaming\.minecraft-agent-55`
- Java executable: `C:\Users\lucas\Desktop\minecraft\5.5 vs 5.6\runtime\toolchains\temurin-25\jdk-25.0.3+9\bin\javaw.exe`
- JVM arguments: `-Xms1G -Xmx4G`

### Arena Agent 56 (GPT-5.6-Sol high Fast)

- Version: `fabric-loader-0.19.3-26.1.2`
- Game directory: `C:\Users\lucas\AppData\Roaming\.minecraft-agent-56`
- Java executable: same project-local `javaw.exe`
- JVM arguments: `-Xms1G -Xmx4G`

Do not add account identifiers or copy launcher credential files. Both installations share launcher-managed assets/libraries but isolate mods, config, logs, saves, and options.

## Summonable NPC mode

The summonable NPC architecture runs inside the server rather than consuming a licensed account per agent. Use either isolated Fabric client installation to join the server, then start:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\start-test-server.ps1
powershell -ExecutionPolicy Bypass -File .\scripts\start-dynamic-coordinator.ps1
```

Both scripts use `runtime\bridge-secret.txt`. `start-test-server.ps1` starts only the prepared Fabric server. `start-dynamic-coordinator.ps1` starts the manual development coordinator and requires the Codex CLI to be authenticated. The packaged launcher profile uses the bundled coordinator supervisor and must not run this second coordinator. Keep the secret file local and never attach it to evidence.

The Fabric mod must be present on both the server and joining client because the custom NPC entity and renderer are mod-defined. All summoned NPCs share one authenticated server bridge. Each provider uses a stable per-agent directory under `runtime/agent-workspaces`. Codex keeps separate threads, Kimi keeps separate ACP processes and sessions, and Cursor resumes its native agent session. Gemini identities remain readable for saved-profile compatibility, but production planning fails closed because Antigravity cannot enforce the required no-tool boundary. Provider credentials remain in their normal CLI locations.

## Evidence classes

Keep these categories distinct in `runtime/evidence/live-test-summary.json`:

1. Automated Java/Node/fake-bridge verification.
2. Sequential single-client authenticated testing for each model profile.
3. Offline two-client smoke, if used.
4. Simultaneous authenticated two-player testing, which requires two licensed accounts and distinct UUIDs.

If only one account is available, record `BLOCKED_SECOND_LICENSED_ACCOUNT` for category 4 without weakening online mode.
