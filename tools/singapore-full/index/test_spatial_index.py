import json
import tempfile
import unittest
from pathlib import Path
import spatial_index as si


def inverse(east,north):
    lon,lat = 103.83333333333333,1.3666666666666667
    for _ in range(5):
        e,n = si.svy21(lon,lat)
        a,b = si.svy21(lon+1e-6,lat)
        c,d = si.svy21(lon,lat+1e-6)
        a,b,c,d = (a-e)/1e-6,(b-n)/1e-6,(c-e)/1e-6,(d-n)/1e-6
        det = a*d-b*c
        lon += (d*(east-e)-c*(north-n))/det
        lat += (a*(north-n)-b*(east-e))/det
    return lon,lat


class SpatialIndexTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.elements = []
        self.next_node = 1

    def node(self,x,y,tags=None):
        lon,lat = inverse(28000+x,38000+y)
        n = {"type":"node","id":self.next_node,"lat":lat,"lon":lon}
        self.next_node += 1
        if tags:
            n["tags"] = tags
        self.elements.append(n)
        return n["id"]

    def way(self,identifier,points,tags=None):
        ids = [self.node(x,y) for x,y in points]
        if points[0] == points[-1]:
            self.elements.pop(); self.next_node -= 1
            ids[-1] = ids[0]
        w = {"type":"way","id":identifier,"nodes":ids}
        if tags:
            w["tags"] = tags
        self.elements.append(w)
        return w

    def square(self,identifier,x,y,size,tags):
        return self.way(identifier,[(x,y),(x+size,y),(x+size,y+size),(x,y+size),(x,y)],tags)

    def relation(self,identifier,ways,type="multipolygon",tags=None):
        r = {"type":"relation","id":identifier,"members":[{"type":"way","ref":w,"role":"outer"} for w in ways],"tags":{"type":type,**(tags or {})}}
        self.elements.append(r)
        return r

    def build(self):
        self.source = self.root/"source.json"
        self.source.write_text(json.dumps({"version":0.6,"elements":self.elements}),encoding="utf-8")
        self.index = self.root/"source.sqlite"
        return si.build(self.source,self.index)

    def export(self,bounds,name="tile.json"):
        output = self.root/name
        report = si.export(self.index,[28000+bounds[0],38000+bounds[1],28000+bounds[2],38000+bounds[3]],output)
        elements = json.loads(output.read_text(encoding="utf-8"))["elements"]
        refs = {(e["type"],e["id"]):e for e in elements}
        for e in elements:
            for ref in e.get("nodes",[]):
                self.assertIn(("node",ref),refs)
            for m in e.get("members",[]):
                self.assertIn((m["type"],m["ref"]),refs)
        return report,refs

    def test_projection_origin(self):
        e,n = si.svy21(103+50/60,1+22/60)
        self.assertAlmostEqual(e,28001.642,places=7)
        self.assertAlmostEqual(n,38744.572,places=7)

    def test_stream_split_everywhere(self):
        self.node(0,0,{"name":'Singapore \\" [test] é'})
        self.build()
        for size in (1,2,3,7,31):
            self.assertEqual(list(si.elements_stream(self.source,size)),self.elements)

    def test_crossing_way_includes_nodes_outside_tile(self):
        road = self.way(100,[(-50,0),(50,0)],{"highway":"primary","name":"Unmodified road"})
        self.build()
        report,refs = self.export((-1,-1,1,1))
        self.assertEqual(refs["way",100],road)
        self.assertEqual(report["counts"],{"node":2,"way":1})

    def test_full_relation_and_no_route_expansion(self):
        self.square(100,0,0,10,{"building":"yes","height":"17.5"})
        self.square(101,100,0,10,None)
        self.way(102,[(500,500),(600,600)],{"highway":"primary"})
        self.relation(200,[100,101],tags={"building":"yes"})
        self.relation(201,[100,102],type="route")
        self.build()
        _,refs = self.export((1,1,2,2))
        self.assertIn(("relation",200),refs)
        self.assertIn(("way",101),refs)
        self.assertNotIn(("relation",201),refs)
        self.assertNotIn(("way",102),refs)
        self.assertEqual(refs["way",100]["tags"]["height"],"17.5")

    def test_building_parent_and_sibling_parts(self):
        self.square(100,0,0,100,{"building":"yes"})
        self.square(101,10,10,10,{"building:part":"yes","height":"10"})
        self.square(102,80,80,10,{"building:part":"yes","height":"30"})
        self.square(103,120,120,10,{"building:part":"yes"})
        self.build()
        _,refs = self.export((11,11,12,12))
        self.assertIn(("way",100),refs)
        self.assertIn(("way",102),refs)
        self.assertNotIn(("way",103),refs)

    def test_polygon_hole_does_not_attach_foreign_part(self):
        self.square(100,0,0,100,None)
        self.square(101,20,20,60,None)
        self.square(102,30,30,10,{"building:part":"yes"})
        relation = self.relation(200,[100,101],tags={"building":"yes"})
        relation["members"][1]["role"] = "inner"
        self.build()
        _,refs = self.export((1,1,2,2))
        self.assertNotIn(("way",102),refs)

    def test_missing_references_fail_and_no_index_published(self):
        self.elements = [{"type":"way","id":1,"nodes":[99]}]
        with self.assertRaisesRegex(ValueError,"Missing source references"):
            self.build()
        self.assertFalse(self.index.exists())
        self.assertTrue(self.index.with_suffix(".sqlite.building").exists())

    def test_concave_parent_does_not_attach_crossing_part(self):
        self.way(100,[(0,0),(100,0),(100,100),(70,100),(70,30),(30,30),(30,100),(0,100),(0,0)],{"building":"yes"})
        self.way(101,[(10,80),(90,80),(90,90),(10,90),(10,80)],{"building:part":"yes"})
        self.build()
        _,refs = self.export((1,1,2,2))
        self.assertNotIn(("way",101),refs)

    def test_appended_nodes_and_out_of_order_relations(self):
        self.square(100,0,0,10,{"building":"yes"})
        self.relation(200,[100],tags={"building":"yes"})
        self.elements.reverse()
        self.build()
        _,refs = self.export((1,1,2,2))
        self.assertIn(("relation",200),refs)
        self.assertEqual(len(refs),6)

    def test_real_cache_hit_without_source_file(self):
        self.way(100,[(-50,0),(50,0)],{"highway":"primary"})
        self.build()
        self.assertTrue(si.build(self.source,self.index)["cacheHit"])
        first,_ = self.export((-1,-1,1,1))
        self.source.rename(self.source.with_suffix(".offline"))
        second,_ = self.export((-1,-1,1,1))
        self.assertTrue(second["cacheHit"])
        self.assertEqual(first["closureSha256"],second["closureSha256"])
        third,_ = self.export((-1,-1,1,1),"another.json")
        self.assertEqual(first["subsetSha256"],third["subsetSha256"])

    def test_modified_cache_fails(self):
        self.node(0,0,{"amenity":"cafe"})
        self.build()
        self.export((-1,-1,1,1))
        with open(self.root/"tile.json","a") as f:
            f.write(" ")
        with self.assertRaisesRegex(ValueError,"modified version"):
            self.export((-1,-1,1,1))

    def test_cache_manifest_requires_complete_contract(self):
        self.way(100,[(-50,0),(50,0)],{"highway":"primary"})
        self.build()
        original,_ = self.export((-1,-1,1,1))
        sidecar = self.root/"tile.json.manifest.json"
        bad_manifests = [
            {k:original[k] for k in ("exportIdentitySha256","subsetSha256")},
            {**original,"referenceComplete":False},
            {**original,"closureSha256":"0"*64},
            {**original,"schema":"another-schema"},
            {**original,"sourceSha256":"0"*64},
            {**original,"boundsEPSG3414":[0,0,1,1]},
            {**original,"counts":{"node":99,"way":1}},
            {**original,"sourceClasses":{}},
            {**original,"countryMaskApplied":True},
        ]
        for bad in bad_manifests:
            with self.subTest(manifest=bad):
                sidecar.write_text(json.dumps(bad),encoding="utf-8")
                with self.assertRaises(ValueError):
                    self.export((-1,-1,1,1))
        sidecar.write_text(json.dumps(original),encoding="utf-8")
        self.assertTrue(self.export((-1,-1,1,1))[0]["cacheHit"])

    def test_cache_rechecks_actual_references(self):
        self.way(100,[(-50,0),(50,0)],{"highway":"primary"})
        self.build()
        report,_ = self.export((-1,-1,1,1))
        output = self.root/"tile.json"
        data = json.loads(output.read_text(encoding="utf-8"))
        data["elements"] = data["elements"][1:]
        output.write_text(si.canonical(data),encoding="utf-8")
        report["counts"]["node"] -= 1
        report["subsetSha256"] = report["closureSha256"] = si.sha256(output)
        (self.root/"tile.json.manifest.json").write_text(json.dumps(report),encoding="utf-8")
        with self.assertRaisesRegex(ValueError,"reference closure"):
            self.export((-1,-1,1,1))

    def test_wrong_accepted_source_hash_fails(self):
        self.node(0,0)
        self.build()
        with self.assertRaisesRegex(ValueError,"accepted source"):
            si.build(self.source,self.root/"wrong.sqlite","0"*64)

    def test_dependency_tags_stay_absent(self):
        self.square(100,0,0,10,None)
        self.relation(200,[100],tags={"building":"yes"})
        self.build()
        _,refs = self.export((1,1,2,2))
        self.assertNotIn("tags",refs["way",100])
        self.assertFalse(any("tags" in e for (kind,_),e in refs.items() if kind == "node"))

    def test_exact_rtree_bbox_filter_avoids_float_padding(self):
        self.node(0,0,{"amenity":"cafe"})
        self.build()
        report,refs = self.export((0.0001,0.0001,0.0002,0.0002))
        self.assertEqual(refs,{})
        self.assertTrue(report["referenceComplete"])


if __name__ == "__main__":
    unittest.main()
