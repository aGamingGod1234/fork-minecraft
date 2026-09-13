import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from collections import defaultdict

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from base_scope import BaseScope, FRAME, PROFILE
from overlay import make_chunk, parse_run, write_overlay
import test_overlay as legacy
from test_overlay import block_at


def polygon(x0, z0, x1, z1, kind=None):
    return {"type": "Feature", "properties": {} if kind is None else {"class": kind},
            "geometry": {"type": "Polygon", "coordinates": [[[x0,z0],[x1,z0],[x1,z1],[x0,z1],[x0,z0]]]}}


def document(features, kind):
    result = {"type": "FeatureCollection", "features": features}
    if kind == "coast":
        result.update(coordinate_space="minecraft_xz",
                      crs={"type": "name", "properties": {"name": "EPSG:3414 derived X=E,Z=60000-N"}})
    else:
        result.update(coordinateSystem="EPSG:3414", axisMapping={"x":"easting","z":"60000-northing"})
    return result


class BaseScopeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.docs = {
            "country": document([polygon(-8,-8,8,8)], "country"),
            "foreignExclusions": document([polygon(2,-8,4,8)], "foreign"),
            "coast": document([polygon(-16,-16,0,16,"land"), polygon(0,-16,6,16,"sea")], "coast"),
        }

    def scope(self):
        desc = {"schemaVersion":1,"kind":"country-coast-base-scope","coordinateFrame":FRAME,"sampling":"block-center"}
        for name, doc in self.docs.items():
            payload = json.dumps(doc).encode()
            path = self.base / (name + ".json")
            path.write_bytes(payload)
            desc[name] = {"path":path.name,"sha256":hashlib.sha256(payload).hexdigest()}
        path = self.base / "scope.json"
        path.write_text(json.dumps(desc))
        return BaseScope(path)

    def chunk(self, scope, cx=0, cz=0, columns=None):
        report = {"crossLayerOverwrittenBlocks":defaultdict(int),"baseScope":scope.receipt()}
        chunk = make_chunk(cx, cz, columns or {}, 3955, report, base_scope=scope)
        return {(cx,cz):chunk}, report

    def test_known_land_sea_foreign_unknown_and_outside(self):
        scope = self.scope()
        classes = scope.classify((-10,0,10,1))
        self.assertEqual(classes, ("outside",)*2 + ("land",)*8 + ("sea",)*2 + ("foreign",)*2 + ("sea",)*2 + ("unknown",)*2 + ("outside",)*2)
        chunks, receipt = self.chunk(scope)
        for x in (2,6,8):
            for y in (-4,-1,0,1):
                self.assertEqual(block_at(chunks,x,y,0),"minecraft:air")
        self.assertEqual(block_at(chunks,0,-4,0),"minecraft:bedrock")
        self.assertEqual(block_at(chunks,0,-1,0),"minecraft:dirt")
        self.assertEqual(block_at(chunks,0,0,0),"minecraft:air")
        self.assertFalse(receipt["baseScope"]["unknownCoastColumnsAccepted"])
        land, _ = self.chunk(scope,-1,0)
        self.assertEqual(block_at(land,-1,0,0),"minecraft:grass_block")
        self.assertEqual(sum(receipt["baseScope"]["columnCounts"].values()),256)

    def test_negative_adjacent_seams_match_monolithic_point_centers(self):
        scope = self.scope()
        whole = scope.classify((-16,-16,16,16))
        for cz in (-1,0):
            for cx in (-1,0):
                part = scope.chunk(cx,cz)
                for z in range(16):
                    for x in range(16):
                        self.assertEqual(part[z*16+x],whole[(cz*16+z+16)*32+(cx*16+x+16)])

    def test_inclusive_foreign_boundary_wins_and_country_hole_is_outside(self):
        self.docs["foreignExclusions"]["features"] = [polygon(0.5,-8,2.5,8)]
        hole = polygon(-2,-2,2,2)["geometry"]["coordinates"][0]
        self.docs["country"]["features"][0]["geometry"]["coordinates"].append(hole)
        scope = self.scope()
        self.assertEqual(scope.classify((0,3,3,4)),("foreign",)*3)
        self.assertEqual(scope.classify((-1,0,0,1)),("outside",))

    def test_conflicting_and_unrecognized_coast_classes_stay_unknown(self):
        self.docs["coast"]["features"].extend([polygon(-2,-8,1,8,"sea"),polygon(-6,-8,-4,8,"unresolved")])
        scope = self.scope()
        self.assertEqual(scope.classify((-2,0,0,1)),("unknown",)*2)
        self.assertEqual(scope.classify((-6,0,-4,1)),("unknown",)*2)

    def test_mask_hash_mismatch_and_wrong_frame_reject(self):
        self.scope()
        (self.base/"coast.json").write_text("{}")
        with self.assertRaisesRegex(ValueError,"SHA256 mismatch"):
            BaseScope(self.base/"scope.json")
        self.docs["coast"]["coordinate_space"] = "EPSG3414_EN"
        with self.assertRaisesRegex(ValueError,"X/Z frame"):
            self.scope()

    def test_invalid_and_nonfinite_geometry_reject_without_repair(self):
        geometry = self.docs["country"]["features"][0]["geometry"]
        geometry["coordinates"] = [[[0,0],[2,2],[2,0],[0,2],[0,0]]]
        with self.assertRaisesRegex(ValueError,"invalid polygon"):
            self.scope()
        geometry["coordinates"] = [[[0,0],[2,float("nan")],[2,0],[0,0]]]
        with self.assertRaises(ValueError):
            self.scope()

    def test_foreign_and_outside_occupied_runs_reject(self):
        scope = self.scope()
        for x in (2,8):
            row = legacy.OverlayTests.row(x,0,1,3,"minecraft:stone")
            with self.assertRaisesRegex(ValueError,"occupied run outside base scope"):
                self.chunk(scope,columns={(x,0):[parse_run(row,(0,0,16,16))]})

    def test_mapped_sea_water_and_bridge_preserved(self):
        scope = self.scope()
        rows = [legacy.OverlayTests.row(0,0,0,1,"minecraft:water","water"),
                legacy.OverlayTests.row(0,0,5,7,"minecraft:stone","bridge")]
        chunks, receipt = self.chunk(scope,columns={(0,0):[parse_run(r,(0,0,16,16)) for r in rows]})
        self.assertEqual(receipt["baseScope"]["seaColumnsWithWaterLayerAtY0"],1)
        self.assertFalse(receipt["baseScope"]["mappedSeaSurfaceCoverageComplete"])
        self.assertEqual(block_at(chunks,0,0,0),"minecraft:water")
        self.assertEqual(block_at(chunks,0,4,0),"minecraft:air")
        self.assertEqual(block_at(chunks,0,5,0),"minecraft:stone")
        maps = chunks[(0,0)].root.value["Heightmaps"].value
        for name in maps:
            # Foreign, unknown and outside have genuinely empty heightmaps.
            for x in (2,6,8):
                self.assertEqual((maps[name].value[x//7] >> ((x%7)*9)) & 511,0)

    def test_streamed_writer_manifest_and_spawn_use_known_land(self):
        fixture = legacy.OverlayTests("test_global_negative_boundaries_palette_and_clean_level")
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.addCleanup(fixture.tearDown)
        source = self.base/"empty.jsonl"
        source.write_text("")
        target = self.base/"world"
        receipt = write_overlay([source],target,(-16,-16,16,16),fixture.template,
                                allowed_root=self.base,base_scope=self.scope())
        self.assertEqual(receipt["groundProfileId"],PROFILE)
        self.assertFalse(receipt["actualGroundAccepted"])
        x,y,z = receipt["spawn"]
        self.assertEqual(self.scope().classify((x,z,x+1,z+1)),("land",))
        self.assertEqual(sum(receipt["baseScope"]["columnCounts"].values()),1024)

    def test_scoped_all_unknown_has_no_invented_spawn(self):
        fixture = legacy.OverlayTests("test_global_negative_boundaries_palette_and_clean_level")
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.docs["coast"]["features"] = []
        scope = self.scope()
        source=self.base/"empty.jsonl"; source.write_text(json.dumps(legacy.OverlayTests.row(0,0,0,1,"minecraft:stone","terrain")))
        with self.assertRaisesRegex(ValueError,"no spawn column"):
            write_overlay([source],self.base/"world",(0,0,16,16),fixture.template,allowed_root=self.base,base_scope=scope)
        self.assertFalse((self.base/"world").exists())

    def test_scoped_spawn_rejects_water_on_known_land(self):
        fixture = legacy.OverlayTests("test_global_negative_boundaries_palette_and_clean_level")
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        self.docs["country"]["features"] = [polygon(0,0,16,16)]
        self.docs["foreignExclusions"]["features"] = []
        self.docs["coast"]["features"] = [polygon(0,0,16,16,"land")]
        rows = [legacy.OverlayTests.row(x,z,0,1,"minecraft:water","water") for z in range(16) for x in range(16)]
        source=self.base/"water.jsonl"
        source.write_text("\n".join(json.dumps(row) for row in rows))
        with self.assertRaisesRegex(ValueError,"no spawn column"):
            write_overlay([source],self.base/"world",(0,0,16,16),fixture.template,allowed_root=self.base,base_scope=self.scope())

    @unittest.skipUnless(os.environ.get("BASE_SCOPE_ACTUAL_FULL"),"optional bounded actual-mask fixture")
    def test_actual_verified_country_edge_and_middle_rocks(self):
        root=Path(os.environ["BASE_SCOPE_ACTUAL_FULL"])
        pins={
            "country":("data/coverage-derived-260912/country-mask-world.geojson","c6f5341c951026e19bee851de79d2438c623785c81c6c93e9bc92e5a7d25403b"),
            "foreignExclusions":("benchmark-inputs-20260913-1739/frozen/masks/foreign-exclusions-world.geojson","b1880b77c8bca9846987cb93339d68700d5d558d343758e027bc433ae4c1185f"),
            "coast":("data/coast-mask/national-v2/coast-mask-xz.geojson","85db7807ad75ea14ab7ef0aaedfd0f195fdd729c97a40c16f200dd3843db676d"),
        }
        desc={"schemaVersion":1,"kind":"country-coast-base-scope","coordinateFrame":FRAME,"sampling":"block-center"}
        for key,(name,sha) in pins.items():
            desc[key]={"path":str(root/name),"sha256":sha}
        path=self.base/"actual-scope.json"; path.write_text(json.dumps(desc))
        scope=BaseScope(path)
        rocks=scope.classify((91728,26208,91760,26240))
        self.assertGreater(rocks.count("foreign"),0)
        edge=scope.classify((89776,28752,89792,28768))
        self.assertGreater(edge.count("outside"),0)
        self.assertLess(edge.count("outside"),len(edge))
        chunks,_=self.chunk(scope,5611,1797)
        for z in range(16):
            for x in range(16):
                if edge[z*16+x] in ("outside","foreign","unknown"):
                    self.assertEqual(block_at(chunks,89776+x,-1,28752+z),"minecraft:air")


if __name__=="__main__":
    unittest.main()
