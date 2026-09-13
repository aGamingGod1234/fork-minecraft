"""Filter every layer's vertical runs by a projected Minecraft X/Z country mask.

Sampling is at (x+.5,z+.5). Polygon covers semantics include shell and hole
boundaries; explicit foreign coverage takes precedence. Records are unchanged.
"""
import argparse
import hashlib
import json
import math
from collections import Counter
from functools import lru_cache
from importlib.metadata import version
from pathlib import Path


def digest(path):
    value = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def mask_geometry(document):
    from shapely.geometry import Point, Polygon
    from shapely.prepared import prep
    polygons = []
    def visit(item):
        if not isinstance(item, dict):
            raise ValueError('Mask geometry must be an object')
        kind = item.get('type')
        if kind == 'FeatureCollection':
            for feature in item['features']:
                visit(feature)
        elif kind == 'Feature':
            visit(item['geometry'])
        elif kind == 'Polygon':
            polygons.append(item['coordinates'])
        elif kind == 'MultiPolygon':
            polygons.extend(item['coordinates'])
        else:
            raise ValueError('Mask requires projected Minecraft X/Z Polygon or MultiPolygon')
    visit(document)
    if not polygons:
        raise ValueError('Empty mask')
    all_points = []
    indexed = []
    for polygon in polygons:
        if not polygon or any(len(ring) < 3 for ring in polygon):
            raise ValueError('Invalid polygon ring')
        for ring in polygon:
            for p in ring:
                if not isinstance(p, (list, tuple)) or len(p) < 2 or any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) for v in p[:2]):
                    raise ValueError('Non-finite mask coordinate')
                all_points.append(p)
        shell = polygon[0]
        geometry = Polygon(shell, polygon[1:])
        if not geometry.is_valid:
            raise ValueError('Invalid mask polygon topology')
        indexed.append((min(p[0] for p in shell), min(p[1] for p in shell), max(p[0] for p in shell), max(p[1] for p in shell), prep(geometry)))
    if all(-180 <= p[0] <= 180 and -90 <= p[1] <= 90 for p in all_points):
        raise ValueError('Mask looks like raw WGS84; project it to global Minecraft X/Z first')
    if '4326' in json.dumps(document.get('crs', {})):
        raise ValueError('WGS84 mask is not a projected Minecraft X/Z mask')
    def contains(x, z):
        point = Point(x, z)
        return any(x0 <= x <= x1 and z0 <= z <= z1 and geometry.covers(point)
                   for x0, z0, x1, z1, geometry in indexed)
    return contains


def filter_runs(inputs, country_path, output_path, manifest_path, foreign_path=None):
    inputs = [Path(p).resolve() for p in inputs]
    country_path = Path(country_path).resolve()
    foreign_path = Path(foreign_path).resolve() if foreign_path else None
    output_path, manifest_path = Path(output_path).resolve(), Path(manifest_path).resolve()
    sources = inputs + [country_path] + ([foreign_path] if foreign_path else [])
    if not inputs or len(set(inputs)) != len(inputs):
        raise ValueError('At least one unique run input is required')
    if output_path == manifest_path or output_path in sources or manifest_path in sources or output_path.exists() or manifest_path.exists():
        raise ValueError('Output and manifest must be distinct NEW files, never input files')
    country = mask_geometry(json.loads(country_path.read_text(encoding='utf-8-sig')))
    foreign = mask_geometry(json.loads(foreign_path.read_text(encoding='utf-8-sig'))) if foreign_path else lambda x, z: False
    source_receipts = [{'path': str(p), 'sha256': digest(p)} for p in sources]
    @lru_cache(maxsize=262144)
    def reason(x, z):
        if not country(x + .5, z + .5):
            return 'outsideCountry'
        if foreign(x + .5, z + .5):
            return 'foreignExclusion'
        return None
    counts = Counter(inputRuns=0, retainedRuns=0, blockedRuns=0)
    layers, reasons, samples = {}, Counter(), []
    output_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open('xb') as output:
        for source in inputs:
            with source.open('rb') as stream:
                for line_number, raw in enumerate(stream, 1):
                    if not raw.strip():
                        continue
                    run = json.loads(raw.decode('utf-8-sig'))
                    if not isinstance(run, dict) or any(type(run.get(k)) is not int for k in ('x', 'z')):
                        raise ValueError(f'Run needs integer x/z: {source}:{line_number}')
                    layer = str(run.get('layer', 'unspecified'))
                    layer_counts = layers.setdefault(layer, Counter(inputRuns=0, retainedRuns=0, blockedRuns=0))
                    counts['inputRuns'] += 1
                    layer_counts['inputRuns'] += 1
                    blocked = reason(run['x'], run['z'])
                    if blocked:
                        counts['blockedRuns'] += 1
                        layer_counts['blockedRuns'] += 1
                        reasons[blocked] += 1
                        if len(samples) < 32:
                            samples.append({'reason': blocked, 'featureId': run.get('featureId', run.get('sourceId', run.get('id'))), 'layer': layer, 'x': run['x'], 'z': run['z'], 'inputIndex': inputs.index(source), 'line': line_number})
                    else:
                        counts['retainedRuns'] += 1
                        layer_counts['retainedRuns'] += 1
                        output.write(raw)
                        if not raw.endswith(b'\n'):
                            output.write(b'\n')
    for source in source_receipts:
        if digest(source['path']) != source['sha256']:
            raise ValueError('Input changed during filtering: ' + source['path'])
    receipt = {'schema': 'fork.masked-runs.v1', **counts, 'layers': layers, 'blockedReasons': reasons,
               'maskKernel': 'Shapely prepared Polygon.covers, no tolerance expansion or fallback',
               'dependencyVersions': {'shapely': version('shapely'), 'numpy': version('numpy')},
               'blockedSamples': samples, 'inputs': source_receipts[:len(inputs)],
               'countryMaskSha256': source_receipts[len(inputs)]['sha256'],
               'foreignExclusionsSha256': source_receipts[-1]['sha256'] if foreign_path else None,
               'outputSha256': digest(output_path), 'outputBytes': output_path.stat().st_size,
               'sampling': 'global block centre (x+.5,z+.5), independent of tile origin',
               'boundaryPolicy': 'country.covers(blockCentre) AND NOT foreignExclusions.covers(blockCentre); covers includes outer and hole boundaries',
               'order': 'input argument order, then original record order; no sorting or deduplication',
               'retainedRecordPayloadUnchanged': True, 'heightGeometryUnchanged': True,
               'allLayersFiltered': True, 'sourcesUnchanged': True}
    with manifest_path.open('x', encoding='utf-8', newline='\n') as stream:
        json.dump(receipt, stream, sort_keys=True, indent=2, allow_nan=False)
        stream.write('\n')
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', action='append', required=True)
    parser.add_argument('--country-mask', required=True)
    parser.add_argument('--foreign-exclusions')
    parser.add_argument('--output', required=True)
    parser.add_argument('--manifest', required=True)
    args = parser.parse_args()
    receipt = filter_runs(args.runs, args.country_mask, args.output, args.manifest, args.foreign_exclusions)
    print(json.dumps({k: receipt[k] for k in ('inputRuns', 'retainedRuns', 'blockedRuns', 'outputSha256')}))


if __name__ == '__main__':
    main()
