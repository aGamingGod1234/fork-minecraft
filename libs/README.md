# Local dependency provenance

`fabric-carpet-26.1+v260402.jar` comes from the upstream Fabric Carpet `v26.1` GitHub release:

https://github.com/gnembon/fabric-carpet/releases/download/v26.1/fabric-carpet-26.1%2Bv260402.jar

GitHub published the asset with SHA-256 digest `59bd225d12423a7d7a635ca0c94fa786f97ccebb116922b16d76072da4ee67e7`. The tracked file matches that digest.

`voicechat-fabric-2.6.21+26.1.2.jar` comes from the Modrinth Simple Voice Chat release for Minecraft 26.1.2 / SVC 2.6.21 (version id `BvX8YEGO`):

https://cdn.modrinth.com/data/9eGKb6K1/versions/BvX8YEGO/voicechat-fabric-2.6.21%2B26.1.2.jar

The tracked file SHA-256 digest is `9ee4859edaa9391653d3836885f1f6bc89c0e81096a065c400e61d31e05dc7b8`. The voice add-on already depends on the same Modrinth version as `maven.modrinth:9eGKb6K1:BvX8YEGO`. `checksums.sha256` is the build-enforced record for every tracked local JAR.

Verify both files from PowerShell at the repository root:

```powershell
Get-FileHash -Algorithm SHA256 -LiteralPath .\libs\fabric-carpet-26.1+v260402.jar
Get-FileHash -Algorithm SHA256 -LiteralPath .\libs\voicechat-fabric-2.6.21+26.1.2.jar
.\gradlew.bat verifyLocalDependencies
```

When updating Carpet or Simple Voice Chat, download an exact tagged release asset from the upstream project. Review its published digest, replace the JAR, and update `checksums.sha256` in the same change.
