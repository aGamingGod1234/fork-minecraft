"""Retain the OSM Singapore administrative boundary as an explicit generation mask."""
import json, pathlib, sys, osmium
source, dest=sys.argv[1:]; d=pathlib.Path(dest); d.mkdir(parents=True,exist_ok=True)
mask=d/"singapore-admin-mask.osm.pbf"; selected=[]
with osmium.BackReferenceWriter(str(mask),ref_src=source,remove_tags=False,relation_depth=8) as writer:
    for o in osmium.FileProcessor(source,osmium.osm.RELATION):
        t=dict(o.tags)
        if t.get("admin_level")=="2" and (t.get("ISO3166-1")=="SG" or t.get("ISO3166-1:alpha2")=="SG"):
            selected.append({"id":o.id,"tags":t});writer.add_relation(o)
factory=osmium.geom.GeoJSONFactory(); features=[]
class Areas(osmium.SimpleHandler):
    def area(self,o):
        if o.from_way() or o.orig_id() not in [x["id"] for x in selected]:return
        features.append({"type":"Feature","id":"relation/"+str(o.orig_id()),"properties":dict(o.tags),"geometry":json.loads(factory.create_multipolygon(o))})
Areas().apply_file(str(mask),locations=True)
out=d/"singapore-admin-mask.geojson"
out.write_text(json.dumps({"type":"FeatureCollection","features":features},separators=(",",":")),encoding="utf-8")
print(json.dumps({"selectedRelations":selected,"features":len(features),"path":str(out)}))
if not features:raise SystemExit("Administrative geometry unavailable; do not treat rectangle as Singapore.")
