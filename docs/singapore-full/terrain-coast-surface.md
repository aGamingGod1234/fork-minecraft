# Coast surface adapter

`tools/singapore-full/terrain/coast_surface.py` streams water column JSONL from the explicitly classified coast-mask GeoJSON. Coordinates must already be global Minecraft X/Z. It does not reproject them or flip Z again.

Use `--mask FILE --bounds MIN_X MIN_Z MAX_X MAX_Z --output NEW_JSONL --manifest NEW_JSON`, optionally pinning `--expected-sha256 HASH`. Bounds are half-open and capped at two million cells per invocation. NumPy/Shapely process one row at a time. Output paths must be new.

Classification samples `(x+0.5,z+0.5)`. Exactly one distinct covering label determines the class. Two sea polygons still mean sea; land/sea conflicts, any unknown overlap and uncovered points remain unknown. Only exclusive sea cells produce runs. Land and unknown cells are not modified. Shared shoreline boundaries can therefore remain unknown.

Each run has `yMin:0`, exclusive `yMax:1`, `minecraft:water`, water `level:0` and `layer:30`. This replaces the existing base ground at Y=0. Mapped coastline provenance and estimated water height are separate: `shorelineSourceClass:mapped`, `sourceClass:osm-coastline-sea-level-provisional`, `elevationSourceClass:provisional-flat`. A one-block depth and flat surface are rendering choices; neither measured bathymetry nor an accepted water elevation is claimed.

The output manifest records hashes, counts, bounds and these limitations. This adapter does **not** apply the country mask or named foreign-land exclusions. The pipeline must apply its common country/exclusion clip before the writer, preserving exclusions on their boundaries too. Do not count raw emitted water runs as accepted country coverage.

On Windows, the manifest also records the current process's peak working set through `GetProcessMemoryInfo`. No other process is inspected or controlled. Only row-sized sample arrays are retained; JSONL is streamed directly to disk.

Focused tests: `python -m unittest discover -s tools/singapore-full/terrain -p test_coast_surface.py`. They cover signed coordinates, exact union of adjacent tiles, shared boundaries, holes, unknown regions and duplicate same-class overlap.
