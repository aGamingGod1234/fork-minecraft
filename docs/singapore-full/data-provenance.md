# Singapore source provenance and redistribution

Verified 2026-09-13 against the primary source pages linked below. This is an engineering record, not an expert legal review. Data licences remain separate from the project's software licence.

## OpenStreetMap

The pinned Geofabrik regional extract is malaysia-singapore-brunei-260912.osm.pbf, 250727913 bytes, SHA256 01e16a33157689db74c401f4a6e9204526883369b999e3c92a561ac4a5a78699. It includes neighbouring countries: the Singapore administrative mask and reference-complete extraction must be applied before generating Singapore.

Credit: © OpenStreetMap contributors. Data available under the Open Database License 1.0. Extract supplied by Geofabrik.
Sources: https://www.openstreetmap.org/copyright and https://download.geofabrik.de/asia/malaysia-singapore-brunei.html
Licence: https://opendatacommons.org/licenses/odbl/1-0/ (retained in data-licenses/ODbL-1.0.html).

Retain attribution and the ODbL notice when distributing OSM data. A publicly used derivative database is subject to the ODbL share-alike and access requirements, including when publicly using a produced work from it. Keep the masked/extracted geographic database and its reproducible change process available under ODbL as required by sections 4.4 and 4.6. A rendered film or image is a produced work and needs the source notice; this does not automatically license all game code or independently authored assets under ODbL. The generated world's database/produced-work classification must not be assumed to remove the underlying derivative-database obligations.

## Copernicus terrain

The two retained AWS GLO-30 Public DSM tiles are N01E103 and N01E104, with complete bytes and receipt hashes in data-provenance.json. They come from the AWS 2021 public release. The public AWS route permits unsigned access; this record does not assert that current Copernicus browser/API services are anonymous. Those services have their own registration and access conditions.

Primary registry: https://registry.opendata.aws/copernicus-dem/
Full public GLO-30-F licence: https://docs.sentinel-hub.com/api/latest/static/files/data/dem/resources/license/License-COPDEM-30.pdf
Current service and citation page: https://dataspace.copernicus.eu/explore-data/data-collections/copernicus-contributing-missions/collections-description/COP-DEM

Retain data-licenses/Copernicus-GLO30-Public.pdf and data-licenses/NOTICE.txt with distributed terrain. The public licence grants reproduction, distribution and adaptation subject to its obligations; it does not grant rights to the separately restricted 10m product. The 30m DSM includes vegetation/buildings. Resampling to 1 block/metre does not create surveyed 1m bare-earth accuracy.

## ESA WorldCover

Primary product and licence page: https://esa-worldcover.org/en/data-access
WorldCover 2021 v200 uses CC BY 4.0, retained as data-licenses/CC-BY-4.0.txt.
Dataset citation: Zanaga et al. (2022), ESA WorldCover 10 m 2021 v200, https://doi.org/10.5281/zenodo.7254221
Preserve attribution, licence link and a description of modifications for generated landcover.

Only two pre-existing Arnis cache fragments were present: a 65536-byte COG header and a 44029-byte compressed tile named tile_21_20. These do NOT prove full-island landcover coverage. A bounded HTTP Range request verified that the header exactly matches bytes 0-65535 of the 36505930-byte official object at:
https://esa-worldcover.s3.eu-central-1.amazonaws.com/v200/2021/map/ESA_WorldCover_10m_2021_v200_N00E102_Map.tif
The tile fragment's exact HTTP byte range and coverage were not independently verified. Its filename association is evidence of cache identity only. No whole WorldCover tile was downloaded by this audit.

## Exclusions and portable audit

No OneMap 3D models, imagery or textures were acquired or licensed by this work. API availability is not permission to extract or redistribute such assets. Planning height controls are not observations of existing building heights.

Run the standard-library audit from the repository:
python tools/singapore-full/data-cache-audit.py --data-dir <raw-data-cache> --landcover-dir <arnis-landcover-cache>
The audit reads only the listed source files, receipts and retained licence documents. It hashes bytes without downloading, modifying caches or scanning environments. A pass proves the recorded files match, not complete geographic coverage or legal clearance. The manifest uses logical roots rather than machine-specific paths.
