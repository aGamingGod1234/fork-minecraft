"""Extract mapped Singapore features with an explicit country mask, never a bbox country claim.
Pinned environment: osmium4.3.1, shapely2.1.2. Two IO workers, bounded queue.
Reference-only objects have tags removed. Consumers MUST clip generated geometry to the mask.
"""
import argparse, collections, hashlib, json, pathlib, time
import osmium, shapely
from shapely.geometry import shape, LineString
p=argparse.ArgumentParser();p.add_argument("--source",required=True);p.add_argument("--mask",required=True);p.add_argument("--output-dir",required=True)
a=p.parse_args(); dest=pathlib.Path(a.output_dir);dest.mkdir(parents=True,exist_ok=True)
out=dest/"singapore-reference-complete.osm.pbf"
if out.exists():raise SystemExit("Select a new output directory; outputs are immutable.")
mask=shapely.union_all([shape(f["geometry"]) for f in json.load(open(a.mask,encoding="utf-8"))["features"]]);shapely.prepare(mask)
bbox=mask.bounds; pool=osmium.io.ThreadPool(2,4); selected_nodes=set();selected_ways=set();selected_relations=set(); stages=[]
def stage(name,**kw):
    row={"stage":name,"time":time.time(),**kw};stages.append(row)
    (dest/"progress.json").write_text(json.dumps(row),encoding="utf-8");print(json.dumps(row),flush=True)
with osmium.BackReferenceWriter(str(out),ref_src=a.source,remove_tags=True,relation_depth=8,thread_pool=pool) as writer:
    stage("select-nodes")
    for o in osmium.FileProcessor(a.source,osmium.osm.NODE,thread_pool=pool):
        if o.location.valid() and bbox[0]<=o.lon<=bbox[2] and bbox[1]<=o.lat<=bbox[3] and shapely.intersects_xy(mask,o.lon,o.lat):
            selected_nodes.add(o.id);writer.add_node(o)
    stage("select-ways",nodes=len(selected_nodes))
    fp=osmium.FileProcessor(a.source,thread_pool=pool).with_locations().with_filter(osmium.filter.EntityFilter(osmium.osm.WAY))
    for o in fp:
        inside=any(n.ref in selected_nodes for n in o.nodes)
        if not inside:
            # Catch a way crossing the mask even when no original vertex lies inside.
            coords=[(n.lon,n.lat) for n in o.nodes if n.location.valid()]
            if len(coords)<2:continue
            if max(x for x,y in coords)<bbox[0] or min(x for x,y in coords)>bbox[2] or max(y for x,y in coords)<bbox[1] or min(y for x,y in coords)>bbox[3]:continue
            inside=mask.intersects(LineString(coords))
        if inside:selected_ways.add(o.id);writer.add_way(o)
    stage("select-multipolygons",ways=len(selected_ways))
    for o in osmium.FileProcessor(a.source,osmium.osm.RELATION,thread_pool=pool):
        if o.tags.get("type") in ("multipolygon","building") and any(m.type=="w" and m.ref in selected_ways for m in o.members):
            selected_relations.add(o.id);writer.add_relation(o)
    stage("complete-references",relations=len(selected_relations))
stats=collections.Counter();ids={"node":set(),"way":set(),"relation":set()};needed={k:set() for k in ids}; bounds=[180,90,-180,-90]
target=dest/"singapore-overpass.json";first=True;missing_objects=[]
stage("write-overpass")
with target.open("w",encoding="utf-8") as f:
    f.write('{"version":0.6,"generator":"FORK Singapore masked source","elements":[')
    for o in osmium.FileProcessor(str(out),thread_pool=pool):
        kind={"n":"node","w":"way","r":"relation"}[o.type_str()]
        item={"type":kind,"id":o.id};ids[kind].add(o.id);stats[kind]+=1
        if kind=="node":
            item.update(lat=o.lat,lon=o.lon);bounds=[min(bounds[0],o.lon),min(bounds[1],o.lat),max(bounds[2],o.lon),max(bounds[3],o.lat)]
        elif kind=="way":
            item["nodes"]=[n.ref for n in o.nodes];needed["node"].update(item["nodes"])
        else:
            item["members"]=[{"type":{"n":"node","w":"way","r":"relation"}[m.type],"ref":m.ref,"role":m.role} for m in o.members]
            for m in item["members"]:needed[m["type"]].add(m["ref"])
        tags=dict(o.tags)
        if tags:item["tags"]=tags
        if tags.get("building") not in (None,"no"):
            stats["buildingObjects"]+=1
            stats["heightTag" if tags.get("height") else "levelsOnly" if tags.get("building:levels") else "heightMissing"]+=1
            stats["facadeMaterialTag" if tags.get("building:material") or tags.get("building:colour") else "facadeMissing"]+=1
        if tags.get("highway"):stats["highwayObjects"]+=1
        if tags.get("natural")=="coastline":stats["coastlineObjects"]+=1
        if not first:f.write(",")
        json.dump(item,f,separators=(",",":"));first=False
    f.write("]}")
missing={k:sorted(needed[k]-ids[k]) for k in ids}
def sha(file):
    with open(file,"rb") as f:return hashlib.file_digest(f,"sha256").hexdigest()
report={"schemaVersion":2,"sourceSha256":sha(a.source),"maskSha256":sha(a.mask),"maskBboxWgs84":list(bbox),"maskComponents":len(mask.geoms),
"referenceBoundsWgs84":bounds,"countryClipRequired":True,"referenceOnlyTagsRemoved":True,
"selected":{"nodes":len(selected_nodes),"ways":len(selected_ways),"relations":len(selected_relations)},
"counts":dict(stats),"missingReferences":missing,"referenceComplete":not any(missing.values()),"stages":stages,
"files":[{"name":x.name,"bytes":x.stat().st_size,"sha256":sha(x)} for x in (out,target)],
"quality":{"footprints":"mapped where present","height":"mapped height tag; inferred from levels; otherwise missing","facade":"missing except partial material/colour tags; never exact","terrain":"not supplied by OSM","completeness":"Object-reference completeness does not prove all real-world features are mapped."}}
(dest/"extract-report.json").write_text(json.dumps(report,indent=2),encoding="utf-8");print(json.dumps(report),flush=True)
if any(missing.values()):raise SystemExit("Missing source references; do not pass completeness gate.")
