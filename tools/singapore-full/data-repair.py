"""Repair an otherwise verified extract with separately sourced missing references.
Preserves the input JSON and PBF. Emits JSON plus an explicit compositional validation receipt.
"""
import argparse,hashlib,json,pathlib,time,xml.etree.ElementTree as ET
import osmium
p=argparse.ArgumentParser();p.add_argument("--input-dir",required=True);p.add_argument("--regional-pbf",required=True);p.add_argument("--repair-xml",required=True);p.add_argument("--output-dir",required=True)
a=p.parse_args();d=pathlib.Path(a.input_dir);out=pathlib.Path(a.output_dir);out.mkdir(parents=True,exist_ok=True)
target=out/"singapore-overpass.json"
if target.exists():raise SystemExit("Output exists")
def sha(path):
    with open(path,"rb") as f:return hashlib.file_digest(f,"sha256").hexdigest()
report=json.load(open(d/"extract-report.json",encoding="utf-8"))
if sha(a.regional_pbf)!=report["sourceSha256"]:raise SystemExit("Regional source hash mismatch")
source=d/"singapore-overpass.json";pbf=d/"singapore-reference-complete.osm.pbf"
for f in report["files"]:
    if sha(d/f["name"])!=f["sha256"]:raise SystemExit("Input hash mismatch")
pool=osmium.io.ThreadPool(2,4);bits={"node":osmium.osm.NODE,"way":osmium.osm.WAY,"relation":osmium.osm.RELATION}
existing={"node":set(),"way":set(),"relation":set()}; extras={k:{} for k in bits}
for kind in ("way","relation"):
    existing[kind]={o.id for o in osmium.FileProcessor(str(pbf),bits[kind],thread_pool=pool)}
def convert(o):
    kind={"n":"node","w":"way","r":"relation"}[o.type_str()]; item={"type":kind,"id":o.id}
    if kind=="node":item.update(lat=o.lat,lon=o.lon)
    if kind=="way":item["nodes"]=[n.ref for n in o.nodes]
    if kind=="relation":item["members"]=[{"type":{"n":"node","w":"way","r":"relation"}[m.type],"ref":m.ref,"role":m.role} for m in o.members]
    return item
xml=ET.parse(a.repair_xml).getroot();versions=[]
for o in xml:
    if o.tag not in ("node","way","relation"):continue
    item={"type":o.tag,"id":int(o.attrib["id"])}
    if o.tag=="node":item.update(lat=float(o.attrib["lat"]),lon=float(o.attrib["lon"]))
    elif o.tag=="way":item["nodes"]=[int(x.attrib["ref"]) for x in o.findall("nd")]
    else:raise SystemExit("Unexpected repair relation")
    extras[o.tag][item["id"]]=item
    versions.append({"type":o.tag,"id":item["id"],"version":int(o.attrib["version"]),"timestamp":o.attrib["timestamp"]})
# Include building container relations whose parts already intersect the country mask.
building_containers=[]
for o in osmium.FileProcessor(a.regional_pbf,osmium.osm.RELATION,thread_pool=pool):
    if o.id not in existing["relation"] and o.tags.get("type")=="building" and any(m.type=="w" and m.ref in existing["way"] for m in o.members):
        item=convert(o);item["tags"]=dict(o.tags);extras["relation"][o.id]=item;building_containers.append(o.id)
tested_nodes=set()
def needed():
    n={"node":set(),"way":set(),"relation":set()}
    for item in extras["way"].values():n["node"].update(item["nodes"])
    for item in extras["relation"].values():
        for m in item["members"]:n[m["type"]].add(m["ref"])
    return n
unresolved={}
for iteration in range(10):
    refs=needed();node_candidates=(refs["node"]|set(extras["node"]))-tested_nodes
    if node_candidates:
        for o in osmium.FileProcessor(str(pbf),osmium.osm.NODE,thread_pool=pool).with_filter(osmium.filter.IdFilter(node_candidates)):existing["node"].add(o.id)
        tested_nodes.update(node_candidates)
    missing={k:refs[k]-existing[k]-set(extras[k]) for k in bits}
    if not any(missing.values()):break
    found=0
    for kind in bits:
        if not missing[kind]:continue
        for o in osmium.FileProcessor(a.regional_pbf,bits[kind],thread_pool=pool).with_filter(osmium.filter.IdFilter(missing[kind])):
            extras[kind][o.id]=convert(o);found+=1
    if not found:unresolved={k:sorted(v) for k,v in missing.items()};break
else:raise SystemExit("Reference closure exceeded 10 passes")
for kind in bits:
    for oid in list(extras[kind]):
        if oid in existing[kind]:del extras[kind][oid]
initial=report["missingReferences"]
remaining={k:sorted(set(initial[k])-set(extras[k])) for k in bits}
if unresolved or any(remaining.values()):raise SystemExit(json.dumps({"unresolved":unresolved,"remainingOriginal":remaining}))
with source.open("rb") as i,target.open("xb") as f:
    i.seek(-2,2)
    if i.read()!=b"]}":raise SystemExit("Unexpected JSON ending")
    i.seek(0);left=source.stat().st_size-2
    while left:
        block=i.read(min(left,1048576));f.write(block);left-=len(block)
    for kind in bits:
        for item in extras[kind].values():
            f.write(b",");f.write(json.dumps(item,separators=(",",":")).encode())
    f.write(b"]}")
result=dict(report);result.update(schemaVersion=3,referenceComplete=True,missingReferences={k:[] for k in bits},repair={"retrievedAtUtc":time.strftime("%Y-%m-%dT%H:%M:%SZ",time.gmtime()),"url":"https://api.openstreetmap.org/api/0.6/way/182076251/full","sha256":sha(a.repair_xml),"objects":versions,"personalContributorMetadataStripped":True,"addedBuildingContainers":building_containers,"appendedCounts":{k:len(v) for k,v in extras.items()},"validation":"Verified immutable input digests; every initial missing reference supplied; recursively closed all appended references against original PBF or pinned regional source. New objects append to JSON; order is not OSM PBF sort order."},parentExtractFiles=report["files"],files=[{"name":target.name,"bytes":target.stat().st_size,"sha256":sha(target)}])
for kind in bits:result["counts"][kind]+=len(extras[kind])
(out/"extract-report.json").write_text(json.dumps(result,indent=2),encoding="utf-8");print(json.dumps(result),flush=True)
