# Singapore locator handoff

Use `assets/fork-world/locator/singapore-locator.svg` as the static locator. It is a 1920 x 1080 vector asset with a separate fictional-court panel and visible Natural Earth credit. Cinematic owns rendering and film integration on Laptop. No video is produced here.

The outline is the complete SGP feature from Natural Earth Admin 0 Countries v5.1.1 at 1:10m: one polygon, one ring, 40 vertices. The derived GeoJSON preserves every source geometry coordinate. The source feature and upstream Git blob/SHA256 pins are retained. The source version is not a coastline survey date.

Source bounds in longitude/latitude order: `[103.640391, 1.264309, 104.003429, 1.448635]`. CRS is OGC:CRS84/WGS84. Latitude-first bbox: `[1.264309, 103.640391, 1.448635, 104.003429]`. The SVG uses a local equirectangular projection with north up and standard parallel 1.356472 degrees latitude. These are locator coordinates, not Minecraft coordinates.

Public wording: **Singapore: generalized main-island locator. Scored play: one fictional 16 x 16 block court. No accepted geographic court position.** The one-polygon source does not individually depict Pulau Ubin, Pulau Tekong, Sentosa or Pedra Branca. No offshore inset is included. Bounds describe this source outline, not national maritime limits, all islands, current reclamation or playable Minecraft coverage. No authentic floorplans are claimed.

The accepted court shape remains unchanged at origin `[0,64,0]` in `minecraft:overworld`. Geographic coordinates are null and no marker is drawn. Actual game placement is unrun in this World delivery. Only a separately accepted geographic anchor may authorize adding a real court marker; never use the proposed candidate sampling coordinate.

Rebuild: `node tools/fork-world/build-locator.mjs data/fork-world/locator/source-feature.geojson`.
Check: `node tools/fork-world/test-locator.mjs`.
Checks cover upstream pins, exact geometry preservation, every projected coordinate and inverse transform, bounds, hashes, absent court marker and unchanged court. A headless 1080p SVG preview was inspected for layout; film playback remains Cinematic's responsibility.

## District candidate status

`data/fork-world/district-candidate.json` is a review proposal, not an admitted job. It pins the existing Arnis executable/help and settings, proposes an approximately 256 m sample and nested 64 m compatibility test, and lists missing timing/resource measurements. Source input is not ready and measured durations are unknown. Admission is denied. No acquisition, compatibility test or generation has started; the shared compact 20-minute clock has not started.

The retained Arnis 3.2.0 help has no `--benchmark`; the template omits it. Main must reconcile settings and grant a measured lease after actual adapter proof. Required runtime work takes priority. No country coverage or linear timing forecast follows from a small compatibility test. Arnis is not bundled; its official v3.2.0 Apache-2.0 license is retained under `docs/fork-world/licenses/`. OSM data, if later used, needs its own attribution, provenance and underlying-data offer.
