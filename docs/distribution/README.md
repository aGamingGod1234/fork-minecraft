# Arena Agents Windows package

This ZIP contains the Arena Agents mod pack, its local coordinator, and a pinned
Node.js runtime. It does not contain a Minecraft account, Java, the Minecraft
launcher, provider logins, worlds, logs, or credentials.

## Before installation

- Install the official Minecraft Launcher and start Minecraft 26.1.2 once.
- Install Java 25 and set `JAVA_HOME`, or pass its `javaw.exe` path to the installer.
- Install Fabric Loader 0.19.3 for Minecraft 26.1.2 in the normal launcher directory.
- Close Minecraft and the launcher.
- Sign in to each supported local provider CLI that you plan to use. Provider credentials stay outside this package.

## Install or update

Extract the complete ZIP to a local directory. In PowerShell, run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\install-distribution.ps1
```

The installer creates the separate `%APPDATA%\.minecraft-arena-agents` game
directory and the `Arena Agents` launcher profile. It preserves unrelated mods,
replaces the package-owned mod JARs, removes older `arena-agents-*.jar` files,
and installs the matching coordinator and Node.js runtime. It generates local
bridge and voice secrets in the installed runtime directory.

An update stages and verifies files before it changes the active installation.
If an update fails, the installer restores the previous package-owned JARs,
coordinator runtime, Node.js runtime, and launcher profile. It keeps one
last-known-good coordinator and Node.js backup.
Run the same command again after an interrupted update. The runtime installer
recovers its recorded transaction before it starts the new update.

The ZIP includes Arena Agents, Arena Agents Voice, Fabric API, Fabric Carpet, and Simple Voice Chat.
