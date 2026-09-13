# Map sources and acquisition policy

Bundled third-party-derived geometry requires explicit permission to modify and
redistribute, retained license text, immutable source metadata, and a recorded
archive checksum. The machine-readable authority is
[`maps/source-ledger.json`](maps/source-ledger.json). Raw archives and extracted
worlds stay outside version control under `runtime/map-research/`.

`scripts/maps/fetch_map_source.ps1` accepts a source key from that ledger and a
source-specific destination under the ignored research root. It has no arbitrary
URL input. Each successful acquisition records SHA-256 evidence and later runs
fail if a locked checksum changes. Archives are data only and are never executed.

## Eligible source

Re-Structured v1.2 by RonosMods is the only currently bundle-eligible source.
Modrinth identifies it as project `ShB7QWuY`, version `NNsq5KuW`, and MIT. Its
official repository retains the same MIT license. The exact license text and
source hashes are recorded in the ledger. Eligibility permits review and
adaptation; it does not mean unreviewed archive content may ship.

## Provisional sources

The official CurseForge pages for Parkour Masters, BigYous' MineGPT worlds, and
Bunker Survival display MIT as the platform-declared license. Retainable license
text has not yet been recovered from their authors or archives. They therefore
remain `bundleEligible: false`; no archive URL is approved, and none of their
geometry may be downloaded by the task-one tool, derived, or bundled.

## Safe archive inspection

`scripts/maps/archive_reader.py` validates the complete ZIP member table before
writing. It rejects traversal, rooted or drive-qualified paths, alternate data
streams, link and reparse-like entries, duplicate outputs, member-count and size
overflows, and extreme compression ratios. Valid archives are extracted through
a temporary directory and moved into place only after every member succeeds.

## Previous authored map references

The built-in arenas are deterministic, version-compatible adaptations. They do
not redistribute third-party world saves.

## Thinking Tower parkour

- **Floor is Lava**, by Killercraft CPM:
  <https://www.planetminecraft.com/project/floor-is-lava-5525212/>
- **Parkour Ravine**, by Jerrah95, with checkpoint/timer datapack credited to
  TheBlueMan003:
  <https://www.planetminecraft.com/project/parkour-ravine-1-18-speedrunning-parkour-map/>

The arena adapts their readable checkpoint rhythm: progressive jump stages,
glowing checkpoint islands, adventure-mode contestants, a lethal lava floor,
and death recovery at the most recently reached checkpoint. Geometry and code
in this repository are original and sized dynamically for 2-16 contestants.

## Survival Games arena

- **Sandstorm map for Hunger Games**, by Jarel (Creative Commons Attribution):
  <https://www.planetminecraft.com/project/sandstorm-map-for-hunger-games/>

The arena adapts Sandstorm's 16-player radial start and landmark/resource
grammar into a deterministic vanilla-only arena: a central cornucopia, multiple
outer routes, ruins, woodland cover, water crossings, hidden caches, and seeded
loot. Planet Minecraft's download endpoint rejected automated retrieval, so no
original world-save data is bundled.

## Non-bundled research reference

- **PopularMMOs Challenge Games Arena + Villagers**, Public Domain:
  <https://www.curseforge.com/minecraft/worlds/popularmmos-challenge-games-arena-villagers>

This modded world was inspected only as a layout reference. It is not included
in the mod or used as runtime data.
