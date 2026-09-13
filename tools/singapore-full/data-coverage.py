"""Derive pending generation jobs from every frozen administrative-mask component."""
import argparse, hashlib, json, math, pathlib
import pyproj, shapely
from shapely.geometry import shape, box, mapping
from shapely.ops import transform
p=argparse.ArgumentParser();p.add_argument("--mask",required=True);p.add_argument("--output-dir",required=True);p.add_argument("--core-size",type=int,default=1024);p.add_argument("--halo",type=int,default=128)
a=p.parse_args();d=pathlib.Path(a.output_dir);d.mkdir(parents=True,exist_ok=True)
f=json.load(open(a.mask,encoding="utf-8"));geo=shapely.union_all([shape(x["geometry"]) for x in f["features"]])
project=pyproj.Transformer.from_crs(4326,3414,always_xy=True)
sv=transform(project.transform,geo);world=transform(lambda e,n:(e,60000-n),sv)
size=a.core_size; xmin,zmin,xmax,zmax=world.bounds; jobs=[];total=0
for z in range(math.floor(zmin/size)*size,math.ceil(zmax/size)*size,size):
    for x in range(math.floor(xmin/size)*size,math.ceil(xmax/size)*size,size):
        clipped=world.intersection(box(x,z,x+size,z+size))
        if clipped.is_empty or clipped.area<=0:continue
        area=clipped.area;total+=area
        jobs.append({"id":f"sg-{x//size}-{z//size}","tile":[x//size,z//size],"coreOrigin":[x,z],"coreSize":size,"halo":a.halo,"maskAreaSquareMetres":area,"maskFraction":area/(size*size),"components":[i for i,c in enumerate(world.geoms) if c.intersects(clipped)],"status":"planned","generated":False,"verified":False})
digest=hashlib.sha256(pathlib.Path(a.mask).read_bytes()).hexdigest()
result={"schemaVersion":1,"maskSha256":digest,"sourceRelation":"osm/relation/536780","maskKind":"administrative including territorial waters; NOT a land-fill mask","projection":"EPSG:3414","worldTransform":{"x":"easting","z":"60000-northing","blocksPerMetre":1},"coreSize":size,"halo":a.halo,"worldBounds":list(world.bounds),"components":len(world.geoms),"jobCount":len(jobs),"expectedCores":jobs,"coverageCheck":{"maskAreaSquareMetres":world.area,"sumClippedCoreAreaSquareMetres":total,"absoluteDifferenceSquareMetres":abs(world.area-total),"pass":abs(world.area-total)<1},"generationGate":"blocked until source reference, deterministic seams and terrain/coastline gates pass"}
(d/"country-jobs.json").write_text(json.dumps(result,indent=2),encoding="utf-8")
(d/"country-mask-world.geojson").write_text(json.dumps({"type":"FeatureCollection","features":[{"type":"Feature","properties":{"crs":"Minecraft metric X/Z from EPSG3414","sourceSha256":digest},"geometry":mapping(world)}]},separators=(",",":")),encoding="utf-8")
print(json.dumps({k:v for k,v in result.items() if k!="expectedCores"}))
if not result["coverageCheck"]["pass"]:raise SystemExit("Coverage sum failed")
