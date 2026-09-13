"""CLI adapter: complete OSM objects to masked global-coordinate surface runs.

The country boundary only limits writes. It never creates ground, water or roads.
All surface heights are explicitly provisional Y=0, not surveyed terrain.
Mask predicates require Shapely (validated 2.1.2) and its NumPy dependency
(validated 2.4.6). Freeze both packages, native libraries and dist-info for jobs.
"""
import argparse
import hashlib
import json
import math
from importlib.metadata import version
from pathlib import Path
from roads import adapt_osm, emit_surface_runs


def load(path):
    raw = Path(path).read_bytes()
    return json.loads(raw.decode('utf-8-sig')), hashlib.sha256(raw).hexdigest()


def mask_geometry(document):
    from shapely.geometry import Point, Polygon
    from shapely.prepared import prep
    polygons = []
    def visit(item):
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
            raise ValueError('Mask requires projected global X/Z Polygon or MultiPolygon')
    visit(document)
    bounds = []
    for polygon in polygons:
        if not polygon or any(len(ring) < 3 for ring in polygon):
            raise ValueError('Invalid mask ring')
        if any(not all(isinstance(v,(int,float)) and math.isfinite(v) for v in point[:2]) or len(point)<2 for ring in polygon for point in ring):
            raise ValueError('Non-finite mask coordinate')
        shell = polygon[0]
        geometry=Polygon(shell,polygon[1:])
        if not geometry.is_valid:
            raise ValueError('Invalid mask polygon topology')
        bounds.append((min(p[0] for p in shell), min(p[1] for p in shell),
                       max(p[0] for p in shell), max(p[1] for p in shell), prep(geometry)))
    def contains(x,z):
        # GeoJSON polygon boundaries include hole boundaries. Match covers(),
        # then apply a separate foreign exclusion's covers() with precedence.
        point=Point(x,z)
        return any(x0 <= x <= x1 and z0 <= z <= z1 and geometry.covers(point)
                   for x0,z0,x1,z1,geometry in bounds)
    return contains


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', required=True)
    parser.add_argument('--projected-nodes', required=True)
    parser.add_argument('--tile', nargs=4, type=int, required=True, metavar=('X0','Z0','X1','Z1'))
    parser.add_argument('--output', required=True)
    parser.add_argument('--manifest', required=True)
    parser.add_argument('--country-mask', required=True)
    parser.add_argument('--foreign-exclusions')
    parser.add_argument('--max-candidates', type=int, default=1000000)
    args=parser.parse_args()
    output, manifest = Path(args.output).resolve(), Path(args.manifest).resolve()
    inputs={Path(p).resolve() for p in [args.input,args.projected_nodes,args.country_mask,args.foreign_exclusions] if p}
    if output==manifest or output in inputs or manifest in inputs or output.exists() or manifest.exists():
        raise ValueError('Output and manifest must be distinct new files outside input paths')
    source, source_sha=load(args.input)
    projected, projection_sha=load(args.projected_nodes)
    lookup={}
    if not isinstance(projected,list):
        raise ValueError('Projected nodes must be [[lon,lat,E,N],...]')
    for row in projected:
        if len(row)!=4 or not all(isinstance(v,(int,float)) and math.isfinite(v) for v in row):
            raise ValueError('Invalid projected node row')
        key=tuple(row[:2])
        if key in lookup and lookup[key]!=tuple(row[2:]):
            raise ValueError('Inconsistent duplicate projected node')
        lookup[key]=tuple(row[2:])
    def project(lon,lat):
        try:
            return lookup[(lon,lat)]
        except KeyError as error:
            raise ValueError(f'Missing projected source node ({lon}, {lat})') from error
    country, country_sha=load(args.country_mask)
    country_contains=mask_geometry(country)
    foreign_sha=None
    if args.foreign_exclusions:
        foreign, foreign_sha=load(args.foreign_exclusions)
        foreign_contains=mask_geometry(foreign)
    else:
        foreign_contains=lambda x,z:False
    adapted=adapt_osm(source,project,source_sha256=source_sha,northing_origin=60000.0)
    result=emit_surface_runs(adapted.features,tuple(args.tile),provisional_surface_y=0,
        clip_mask=lambda x,z:country_contains(x,z) and not foreign_contains(x,z),
        max_candidates=args.max_candidates)
    payload=''.join(json.dumps(run,sort_keys=True,separators=(',',':'),allow_nan=False)+'\n' for run in result['runs'])
    diagnostics=adapted.diagnostics + result['diagnostics']
    # Retain the emitter's ownership/collision summary and any future evidence
    # fields verbatim; only the potentially large runs array is stored separately.
    receipt={key:value for key,value in result.items() if key!='runs'}
    receipt.update({'schema':'fork.roads-runs.v1','tile':args.tile,'runCount':len(result['runs']),
        'sourceSha256':source_sha,'projectedNodesSha256':projection_sha,'countryMaskSha256':country_sha,
        'foreignExclusionsSha256':foreign_sha,'outputSha256':hashlib.sha256(payload.encode()).hexdigest(),
        'grid':{'crs':'EPSG:3414','x':'easting','z':'60000-northing','blocksPerMeter':1},
        'sampling':'block centres; half-open global tile','maskPredicate':'country.covers(point) AND NOT foreign.covers(point)',
        'maskDependencies':{'shapely':version('shapely'),'numpy':version('numpy')},
        'wholeSourceGeometryPreserved':True,
        'countryMaskCreatesLand':False,'provisionalSurfaceY':0,'fullFidelityAccepted':False,
        'diagnostics':diagnostics,'featureCount':len(adapted.features),
        'blockedDiagnostics':sum('blocked' in d.get('code','') for d in diagnostics)})
    output.parent.mkdir(parents=True,exist_ok=True)
    manifest.parent.mkdir(parents=True,exist_ok=True)
    with output.open('x',encoding='utf-8',newline='\n') as stream:
        stream.write(payload)
    with manifest.open('x',encoding='utf-8',newline='\n') as stream:
        json.dump(receipt,stream,sort_keys=True,indent=2,allow_nan=False)
        stream.write('\n')
    print(json.dumps({'runCount':receipt['runCount'],'featureCount':receipt['featureCount'],
        'blockedDiagnostics':receipt['blockedDiagnostics'],'outputSha256':receipt['outputSha256']}))


if __name__=='__main__':
    main()
