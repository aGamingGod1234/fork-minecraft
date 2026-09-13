"""Derive land/sea/unknown from directed OSM shorelines, never from admin areas."""
import argparse,collections,datetime,hashlib,json,math,os,pathlib,time
from pyproj import Transformer
from shapely.geometry import LineString,Point,box,mapping,shape
from shapely.ops import polygonize_full,transform,unary_union
from shapely.strtree import STRtree
EN=Transformer.from_crs("EPSG:4326","EPSG:3414",always_xy=True)
def sha(p):
    with open(p,"rb") as f:return hashlib.file_digest(f,"sha256").hexdigest()
def dump(p,v):p.write_text(json.dumps(v,separators=(",",":"))+"\n",encoding="utf-8")
def fcgeom(doc):
    if doc["type"]=="FeatureCollection":return unary_union([shape(f["geometry"]) for f in doc["features"]])
    return shape(doc.get("geometry",doc))
def segments(g):
    if g.is_empty:return
    if g.geom_type=="LineString":
        c=list(g.coords)
        for a,b in zip(c,c[1:]):
            if a!=b:yield a,b
    elif hasattr(g,"geoms"):
        for sub in g.geoms:yield from segments(sub)
def extract_json(path,bounds,expected):
    if sha(path)!=expected:raise ValueError("Frozen source hash mismatch")
    manifest_path=path.with_name(path.name+".manifest.json")
    manifest=json.loads(manifest_path.read_text(encoding="utf-8-sig"))
    if manifest.get("subsetSha256")!=expected or not manifest.get("referenceComplete"):
        raise ValueError("Spatial export receipt/hash/reference gate failed")
    if not box(*manifest["boundsEPSG3414"]).covers(box(*bounds)):
        raise ValueError("Requested domain exceeds source spatial-export coverage")
    doc=json.loads(path.read_text(encoding="utf-8-sig"));objs=doc["elements"]
    nodes={o["id"]:(o["lon"],o["lat"]) for o in objs if o["type"]=="node"}
    ways=[{"id":o["id"],"node_ids":o["nodes"],"tags":o.get("tags",{})} for o in objs
          if o["type"]=="way" and o.get("tags",{}).get("natural")=="coastline"]
    return project_source(ways,nodes,bounds,{"kind":"complete Overpass tile export","sha256":expected,"bytes":path.stat().st_size,"spatialExportReceiptSha256":sha(manifest_path),"spatialExportBoundsEN":manifest["boundsEPSG3414"]})
def extract_pbf(path,bounds,expected):
    import osmium
    if sha(path)!=expected:raise ValueError("Frozen regional source hash mismatch")
    class Ways(osmium.SimpleHandler):
        def __init__(self):super().__init__();self.ways=[];self.refs=set()
        def way(self,o):
            if o.tags.get("natural")=="coastline":
                ns=[n.ref for n in o.nodes];self.refs.update(ns)
                if len(self.refs)>4000000:raise MemoryError("Bounded coastline node budget exceeded")
                self.ways.append({"id":o.id,"node_ids":ns,"tags":dict(o.tags)})
    scan=Ways();scan.apply_file(str(path),locations=False)
    class Nodes(osmium.SimpleHandler):
        def __init__(self,refs):super().__init__();self.refs=refs;self.nodes={}
        def node(self,o):
            if o.id in self.refs:self.nodes[o.id]=(o.location.lon,o.location.lat)
    ns=Nodes(scan.refs);ns.apply_file(str(path),locations=False)
    return project_source(scan.ways,ns.nodes,bounds,{"kind":"pinned regional OSM PBF","sha256":expected,"bytes":path.stat().st_size,
            "regionalCoastlineWays":len(scan.ways),"regionalCoastlineNodes":len(ns.nodes)})
def project_source(ways,nodes,bounds,source):
    domain=box(*bounds);accepted=[];missing=[]
    for w in ways:
        absent=[n for n in w["node_ids"] if n not in nodes]
        if absent:missing.append({"wayId":w["id"],"missingNodes":absent});continue
        raw=[nodes[n] for n in w["node_ids"]]
        # Regional PBF extends far outside Singapore; discard only safely remote bboxes.
        if max(p[0] for p in raw)<103.4 or min(p[0] for p in raw)>104.8 or max(p[1] for p in raw)<0.9 or min(p[1] for p in raw)>1.8:continue
        xy=[list(EN.transform(*p)) for p in raw]
        if len(xy)>=2 and LineString(xy).intersects(domain):
            accepted.append({**w,"coordinates":xy})
    return {"schemaVersion":1,"crs":"EPSG:3414","coordinate_space":"east_north","bounds":bounds,"ways":accepted,
            "source":{**source,"missingReferences":missing,"referenceComplete":not missing,
            "licence":"https://opendatacommons.org/licenses/odbl/1-0/","attribution":"OpenStreetMap contributors"}}
def areal(g):
    if g.geom_type in ("Polygon","MultiPolygon"):return g
    return unary_union([areal(part) for part in getattr(g,"geoms",[]) if part.area>0])
def build(doc,scope=None):
    if not doc["source"]["referenceComplete"]:raise ValueError("Coastline source references incomplete")
    domain=box(*doc["bounds"]);linework=[];rawsegments=[];invalid=[]
    for w in doc["ways"]:
        line=LineString(w["coordinates"])
        if not line.is_simple:invalid.append(w["id"])
        clipped=line.intersection(domain)
        if not clipped.is_empty:linework.append(clipped);rawsegments.extend(segments(clipped))
    network=unary_union([domain.boundary,*linework])
    polys,cuts,dangles,invalid_lines=polygonize_full(network)
    faces=[p for p in polys.geoms if domain.covers(p.representative_point())]
    tree=STRtree(faces);votes=[collections.Counter() for _ in faces];witness=[[] for _ in faces]
    # Rectangle edges only clip the domain. Actual oriented shore segments supply every vote.
    for a,b in rawsegments:
        dx,dy=b[0]-a[0],b[1]-a[1];length=math.hypot(dx,dy)
        if length<1e-8:continue
        mx,my=(a[0]+b[0])/2,(a[1]+b[1])/2;eps=min(.05,length*.1)
        midpoint=Point(mx,my)
        for side,sign in (("land",1),("sea",-1)):
            probe=Point(mx-sign*dy/length*eps,my+sign*dx/length*eps)
            for index in tree.query(probe,predicate="within"):
                index=int(index)
                if faces[index].boundary.distance(midpoint)>1e-6:continue
                votes[index][side]+=1
                if len(witness[index])<4:witness[index].append({"class":side,"pointEN":[probe.x,probe.y],"shoreMidpointEN":[mx,my]})
    scope=domain if scope is None else scope.intersection(domain)
    features=[];totals=collections.Counter();zero_area_remnants=0
    for i,p in enumerate(faces):
        clipped=p.intersection(scope)
        if not clipped.is_empty and clipped.area==0:zero_area_remnants+=1
        clipped=areal(clipped)
        if clipped.is_empty:continue
        labels=set(votes[i]);label=next(iter(labels)) if len(labels)==1 else "unknown"
        # Invalid original linework cannot be certified by a polygonizer repair.
        if invalid:label="unknown"
        totals[label]+=clipped.area
        features.append({"type":"Feature","id":"coast-face/"+str(i),"properties":{"class":label,"votes":dict(votes[i]),
                "witnesses":witness[i],"evidence":"directed OSM coastline; land left, sea right",
                "reason":None if label!="unknown" else "conflicting-or-absent-shore-evidence-or-invalid-source"},
                "geometry":mapping(clipped)})
    outside=domain.difference(scope)
    if not outside.is_empty:
        features.append({"type":"Feature","id":"outside-scope","properties":{"class":"unknown","reason":"outside-approved-administrative-scope"},"geometry":mapping(outside)})
    fc={"type":"FeatureCollection","crs":{"type":"name","properties":{"name":"EPSG:3414"}},"coordinate_space":"east_north","features":features}
    coverage=unary_union([shape(f["geometry"]) for f in features])
    overlap=sum(shape(f["geometry"]).area for f in features)-coverage.area
    if domain.difference(coverage).area>1e-5 or overlap>1e-5:raise ValueError("Face coverage/overlap gate failed")
    report={"schemaVersion":1,"status":"PASS" if not totals["unknown"] else "PARTIAL_UNKNOWN",
            "discardedZeroAreaScopeRemnants":zero_area_remnants,"sourceWays":len(doc["ways"]),"sourceSegments":len(rawsegments),"faces":len(faces),"areaSquareMetres":dict(totals),
            "scopeAreaSquareMetres":scope.area,"outsideScopeAreaSquareMetres":outside.area,"unknownSourceWays":invalid,
            "unpolygonizedCutLength":cuts.length,"unpolygonizedDangleLength":dangles.length,"invalidRingLineLength":invalid_lines.length,
            "domainCoverageGapSquareMetres":domain.difference(coverage).area,"overlapSquareMetres":max(0,overlap),
            "shorelineDefinition":"OSM mean high-water springs; inland lakes and bathymetry are separate sources",
            "noArtificialCoastlineClosure":True,"rectangleUsedOnlyAsClippingBoundary":True,
            "source":doc["source"],"boundsEN":doc["bounds"],"classificationPointConvention":"global block center X+0.5,Z+0.5; E=X,N=60000-Z"}
    return fc,report
def to_xz(fc):
    out={**fc,"coordinate_space":"minecraft_xz","crs":{"type":"name","properties":{"name":"EPSG:3414 derived X=E,Z=60000-N"}}}
    out["features"]=[{**f,"geometry":mapping(transform(lambda x,y,z=None:(x,60000-y),shape(f["geometry"])))} for f in fc["features"]]
    return out
def classify(fc,x,z):
    p=Point(x,60000-z)
    labels={f["properties"]["class"] for f in fc["features"] if shape(f["geometry"]).covers(p)}
    return next(iter(labels)) if len(labels)==1 else "unknown"
def self_test():
    def source(ways):return {"bounds":[0,0,10,10],"source":{"referenceComplete":True},"ways":[{"id":i,"coordinates":c} for i,c in enumerate(ways)]}
    f,r=build(source([[(5,-1),(5,11)]]))
    assert classify(f,2,60000-5)=="land" and classify(f,8,60000-5)=="sea"
    f,r=build(source([[(2,2),(8,2),(8,8),(2,8),(2,2)]]))
    assert classify(f,5,60000-5)=="land" and classify(f,1,60000-1)=="sea"
    f,r=build(source([[(5,2),(5,8)]]))
    assert r["status"]=="PARTIAL_UNKNOWN" and classify(f,2,60000-5)=="unknown"
    f,r=build(source([[(2,2),(8,8),(2,8),(8,2),(2,2)]]))
    assert r["status"]=="PARTIAL_UNKNOWN"
    f,r=build(source([]));assert classify(f,5,59995)=="unknown"
    import tempfile
    with tempfile.TemporaryDirectory() as temporary:
        tile=pathlib.Path(temporary)/"tile.json";dump(tile,{"elements":[]});fingerprint=sha(tile)
        receipt=tile.with_name(tile.name+".manifest.json")
        dump(receipt,{"subsetSha256":fingerprint,"referenceComplete":True,"boundsEPSG3414":[0,0,10,10]})
        assert extract_json(tile,[0,0,10,10],fingerprint)["source"]["referenceComplete"]
        try:extract_json(tile,[-1,0,10,10],fingerprint)
        except ValueError:pass
        else:raise AssertionError("Out-of-source domain was accepted")
        dump(receipt,{"subsetSha256":fingerprint,"referenceComplete":False,"boundsEPSG3414":[0,0,10,10]})
        try:extract_json(tile,[0,0,10,10],fingerprint)
        except ValueError:pass
        else:raise AssertionError("Incomplete source receipt was accepted")
    f,r=build(source([[(2,2),(8,2),(8,8),(2,8),(2,2)]]),box(0,0,10,10).difference(box(2,2,8,8)))
    assert all(feature["geometry"]["type"] in ("Polygon","MultiPolygon") for feature in f["features"])
    assert r["discardedZeroAreaScopeRemnants"]==1 and classify(f,5,59995)=="unknown"
    print("9 coastline face/source coverage checks PASS")
def main():
    p=argparse.ArgumentParser();p.add_argument("--self-test",action="store_true")
    p.add_argument("--source",type=pathlib.Path);p.add_argument("--source-sha256");p.add_argument("--source-kind",choices=("json","pbf","coast"),default="json")
    p.add_argument("--bounds-en",type=float,nargs=4);p.add_argument("--scope",type=pathlib.Path);p.add_argument("--out",type=pathlib.Path)
    a=p.parse_args()
    if a.self_test:return self_test()
    if not all((a.source,a.source_sha256,a.bounds_en,a.out)):p.error("source, source-sha256, bounds-en and out required")
    if a.out.exists():raise ValueError("Use a new immutable output directory")
    started=time.time()
    if a.source_kind=="coast":
        if sha(a.source)!=a.source_sha256:raise ValueError("Coast source hash mismatch")
        doc=json.loads(a.source.read_text(encoding="utf-8-sig"))
        if not box(*doc["bounds"]).covers(box(*a.bounds_en)):raise ValueError("Requested domain exceeds frozen coastline coverage")
        doc["bounds"]=a.bounds_en
    else:doc=(extract_pbf if a.source_kind=="pbf" else extract_json)(a.source,a.bounds_en,a.source_sha256)
    scope=None
    if a.scope:scope=transform(EN.transform,fcgeom(json.loads(a.scope.read_text(encoding="utf-8-sig"))))
    fc,receipt=build(doc,scope);a.out.mkdir(parents=True)
    dump(a.out/"coast-source.json",doc);dump(a.out/"coast-mask-en.geojson",fc);dump(a.out/"coast-mask-xz.geojson",to_xz(fc))
    receipt.update({"scopeSha256":sha(a.scope) if a.scope else None,"elapsedSeconds":time.time()-started,"pid":os.getpid(),"toolSha256":sha(pathlib.Path(__file__)),
                    "completedUtc":datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    "files":[{"name":q.name,"bytes":q.stat().st_size,"sha256":sha(q)} for q in a.out.iterdir()]})
    dump(a.out/"coast-receipt.json",receipt)
    print(json.dumps(receipt))
if __name__=="__main__":main()
