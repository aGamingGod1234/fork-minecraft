# Full Singapore tile generation

Run on Desktop. This is a separate, resumable generation lane. It writes only beneath the private `FORK-Tools/fork-singapore-full/tiles` root and never installs into a Minecraft instance.

Run `pipeline-setup.ps1` once to install pinned `proj4@2.22.0` without package install scripts. Run `node tools/singapore-full/pipeline-test.mjs` to check the projection adapter. Arnis 3.2.0 must already be installed; its exact executable digest is checked.

The shared grid is Singapore SVY21 / EPSG:3414: X = easting in meters; Z = 60,000 minus northing in meters. One block represents one projected meter. A sphere approximation was rejected. WGS84 source coordinates are projected before quantization to the nearest global block. Each Arnis tile receives an adapter JSON; the original OSM coordinates and height tags remain unchanged in `source.json`. The adapter compensates for Arnis's local-origin and span-flooring behavior.

Example, using an existing reference-complete Overpass JSON source:

```powershell
node tools/singapore-full/pipeline.mjs --source <source.json> --x 29712 --z 30496 --size 128 --halo 32 --max-seconds 180 --run
```

Omit `--run` to prepare source and settings only. Production defaults are a 1,024-block core with a 128-block halo, but multi-tile production requires the independent fidelity and actual halo-block gates first. The initial smoke uses 128-block cores. The single global lock and one Rayon worker preserve resources for the hackathon. Resume verifies source, configuration and all output hashes; it never appends to an existing world. Failed attempts are retained in separate directories. A stale lock requires inspecting its recorded PID before manual recovery.

This initial pass uses flat provisional ground at Y=0. It preserves supplied building-height tags but does not establish their accuracy. Arnis may supply estimated buildings, generic facades and ESA WorldCover 2021 land cover. Its ancillary cache is not yet pinned. Terrain, photograph agreement, interior coverage and exact whole-island completion remain unaccepted. The generator writes padded region chunks; chunk count is not playable real-data coverage. The manifest's core size and source bounds define that coverage.

Every tile has `tile-manifest.json`, source hashes, complete file inventory, execution arguments, control points and quality flags. Outputs are local Arnis worlds pending validated core cropping and global-coordinate assembly. Use `pipeline-seam.mjs` to compare actual generated shared-halo blocks before permitting assembly.

OSM data requires attribution to OpenStreetMap contributors and ODbL terms. Keep the source manifest and omitted/incomplete relation records with any distributable output.
