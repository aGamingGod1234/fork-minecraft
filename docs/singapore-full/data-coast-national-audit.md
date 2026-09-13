# Indexed national coastline audit

This independent gate reads a supplied national coastline bundle and the earlier Changi face mask. It checks the supplied raw coastline graph and the project's land/sea/unknown mask. It does not open the original regional PBF, acquire data, edit source masks, or access a Minecraft world.

## Command and API

```text
python tools/singapore-full/data-coast-national-audit.py --self-test
python tools/singapore-full/data-coast-national-audit.py --bundle NATIONAL_DIRECTORY --changi-bundle CHANGI_DIRECTORY --report NEW_REPORT.json --mask NEW_RING_MASK.geojson
```

The national directory must contain `coast-source.json`, `coast-mask-en.geojson`, `coast-mask-xz.geojson`, and `coast-receipt.json`. The Changi directory supplies `coast-mask-en.geojson`. Output paths must be new, distinct files and cannot overwrite any input.

`audit_bundle(national_directory, changi_directory)` reads those inputs and returns `(report, ring_mask)` without writing files. `audit_documents(source, en_mask, xz_mask, receipt, changi_mask, input_bytes)` performs the same checks on supplied documents. Its default requires the identified Pedra Branca and Middle Rocks source ways; `verify_named_islands=False` is reserved for small synthetic fixtures.

The CLI pins its process to one logical CPU and enforces a 90-second wall-time limit, reducible with `--max-seconds`. Inputs are bounded to 32 MiB. It records elapsed time and peak Windows working-set memory. Python API callers manage CPU and time limits themselves.

## Evidence and limits

- The existing sibling ring audit accounts for every supplied raw way, checks directed node identity, rings, nesting and unresolved components. Open endpoints inside the domain fail this resolved national gate.
- STRtree queries limit segment intersection checks and probe-clearance calculations to spatial candidates. There is no all-pairs national segment scan.
- Three offsets on both sides of every clipped source segment test land-left and sea-right direction. Opposite known classes fail. Points in the candidate's unknown area are reported separately; they are not relabelled sea.
- EN and XZ features must be valid positive-area Polygon or MultiPolygon geometries. Zero-area lines left by scope intersection are rejected; the builder must remove those remnants before certification.
- The gate checks polygon coverage and overlap, class areas against the receipt, known-ring contradictions, stored witness direction, source intersections, source-file hashes and source/receipt identity.
- The original EN geometry, raw source coordinates and node IDs remain unchanged. EN-to-XZ comparison uses the exact reflection E=X, N=60000-Z.
- A GEOS internal exception during an independent-ring area intersection permits a documented **1 micrometre precision grid for that audit calculation only**. This fallback is recorded in the report. It does not affect source coordinates, raw connectivity, probes, accepted masks or the XZ transformation.
- Known land/sea overlaps must agree with the earlier Changi mask. Its source differs from the national PBF; a future mismatch requires checking source versions before attributing a cause.
- Pedra Branca way `469394519` must retain its complete supplied polygon as land. Middle Rocks ways `1359256910` and `1359256911` must remain wholly unknown with no land/sea overlap. These are project-scope checks, not sovereignty determinations.
- Frozen PBF way/node totals and complete-reference statements remain producer assertions. This bounded audit does not independently verify that the 415-way source subset omits no relevant PBF coastline.
- The canonical project-scope geometry is not reconstructed here. Unknown area is checked against the scope receipt; physical ring area clipped to unknown is reported separately. OSM coast geometry and an administrative scope are not proof of sovereign land.
- Vector island retention does not establish retention after block-center sampling, rasterization, or world generation.

## Accepted national run

The supplied national bundle produced 103 complete directed rings containing 390 ways, plus two open chains containing 19 and 6 ways. All four open endpoints lay outside the extraction domain.

The indexed check evaluated 101,304 probes: 58,611 agreed with known classes and 42,693 fell in unknown/outside-scope space. It found no opposite known-class contradiction, no Changi disagreement, and no EN/XZ difference. All 260 saved witnesses retained correct source direction; 252 remained in retained faces and eight were clipped outside scope. The run took about eight seconds on one CPU and used about 100 MiB peak working-set memory.

The frozen private audit outputs were not rewritten when this command was added.
