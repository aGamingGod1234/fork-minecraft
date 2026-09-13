import copy
import importlib.util
import json
from pathlib import Path
import sys
import unittest
MODULE = Path(__file__).resolve().parents[1] / "water_landuse.py"
SPEC = importlib.util.spec_from_file_location("water_landuse", MODULE)
water = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = water
SPEC.loader.exec_module(water)
def ring(x0, z0, x1, z1):
    return [[x0,z0],[x1,z0],[x1,z1],[x0,z1],[x0,z0]]
def feature(identifier, tags, rings=None):
    return {"type":"Feature", "id":identifier, "properties":tags,
            "geometry":{"type":"Polygon", "coordinates":rings or [ring(0,0,8,8)]}}
class WaterLanduseTests(unittest.TestCase):
    def test_holes_are_dry_and_do_not_depend_on_ring_orientation(self):
        f = feature("water", {"natural":"water"}, [ring(0,0,8,8), ring(2,2,6,6)])
        result = water.rasterize([f], (0,0,8,8))
        self.assertEqual(len(result.cells), 48)
        self.assertNotIn((3,3), result.cells)
        reversed_f = copy.deepcopy(f)
        reversed_f["geometry"]["coordinates"] = [r[::-1] for r in f["geometry"]["coordinates"]]
        self.assertEqual(result.to_dict(), water.rasterize([reversed_f], (0,0,8,8)).to_dict())
    def test_adjacent_core_tiles_equal_single_core_with_no_duplicate_cells(self):
        f = feature("water", {"natural":"water"}, [ring(-2,-2,10,10), ring(3,3,6,6)])
        whole = water.rasterize([f], (0,0,8,8)).cells
        left = water.rasterize([f], (0,0,4,8)).cells
        right = water.rasterize([f], (4,0,8,8)).cells
        self.assertFalse(set(left) & set(right))
        self.assertEqual(whole, left | right)
        self.assertTrue(all(0 <= x < 8 and 0 <= z < 8 for x,z in whole))
    def test_multipolygon_includes_disconnected_part(self):
        f = feature("ponds", {"natural":"water"})
        f["geometry"] = {"type":"MultiPolygon",
                         "coordinates":[[ring(0,0,2,2)], [ring(6,6,8,8)]]}
        self.assertEqual(len(water.rasterize([f], (0,0,8,8)).cells), 8)
    def test_admin_mask_only_clips_and_never_creates_land(self):
        mask = {"type":"Polygon", "coordinates":[ring(0,0,4,8)]}
        self.assertEqual(water.rasterize([], (0,0,8,8), mask).cells, {})
        f = feature("water", {"natural":"water"})
        self.assertEqual(len(water.rasterize([f], (0,0,8,8), mask).cells), 32)
        self.assertEqual(len(water.rasterize([f], (0,0,8,8), lambda x,z:x<2).cells),16)
    def test_unclosed_coastline_and_incomplete_features_are_not_filled(self):
        coastline = feature("coast", {"natural":"coastline"})
        coastline["geometry"] = {"type":"LineString","coordinates":[[0,0],[8,8]]}
        unclosed = feature("bad", {"natural":"water"})
        unclosed["geometry"]["coordinates"][0].pop()
        incomplete = feature("partial", {"natural":"water","references_complete":False})
        result = water.rasterize([coastline,unclosed,incomplete], (0,0,8,8))
        self.assertFalse(result.cells)
        self.assertEqual({d["code"] for d in result.diagnostics},
                         {"coastline_not_land_mask","invalid_geometry","incomplete_geometry"})
    def test_water_precedence_is_input_order_independent(self):
        water_f = feature("canal", {"natural":"water"}, [ring(2,2,4,4)])
        grass = feature("grass", {"landuse":"grass"})
        left = water.rasterize([water_f,grass], (0,0,8,8))
        right = water.rasterize([grass,water_f], (0,0,8,8))
        self.assertEqual(left.to_dict(),right.to_dict())
        self.assertEqual(left.cells[2,2].kind,"mapped_water")
        self.assertEqual(left.cells[0,0].kind,"mapped_landuse")
        self.assertIn("not_surveyed",left.cells[0,0].material_basis)
    def test_invalid_coordinates_reject_entire_feature(self):
        f = feature("nan", {"natural":"water"})
        f["geometry"]["coordinates"][0][1][0] = float("nan")
        result = water.rasterize([f], (0,0,8,8))
        self.assertFalse(result.cells)
        self.assertEqual(result.diagnostics[0]["code"],"invalid_geometry")
    def test_actual_osm_canal_and_promontory_grass(self):
        path = MODULE.parent / "fixtures" / "water_osm.json"
        fixture = json.loads(path.read_text(encoding="utf-8"))
        self.assertEqual(fixture["metadata"]["projection"],"EPSG:3414")
        canal = water.rasterize(fixture, (29340,30030,29410,30110))
        lawn = water.rasterize(fixture, (30240,30600,30400,30740))
        self.assertFalse(canal.diagnostics)
        self.assertFalse(lawn.diagnostics)
        self.assertGreater(len(canal.cells),0)
        self.assertGreater(len(lawn.cells),0)
        self.assertEqual({c.source_id for c in canal.cells.values()},{"way/886588582"})
        self.assertEqual({c.source_id for c in lawn.cells.values()},{"way/116801285"})
        self.assertEqual({c.kind for c in canal.cells.values()},{"mapped_water"})
        self.assertEqual({c.kind for c in lawn.cells.values()},{"mapped_landuse"})
    def test_malformed_properties_are_diagnostics(self):
        for props in ([], "invalid", 4, {"tags": []}):
            f = feature("bad", {})
            f["properties"] = props
            result = water.rasterize([f], (0,0,8,8))
            self.assertFalse(result.cells)
            self.assertEqual(result.diagnostics[0]["code"], "invalid_properties")
    def test_candidate_budget_rejects_country_sized_loop_before_sampling(self):
        f = feature("large", {"natural":"water"}, [ring(0,0,100000,100000)])
        def unexpected_mask(x,z):
            raise AssertionError("budget must fail before raster iteration")
        with self.assertRaisesRegex(ValueError, "budget"):
            water.rasterize([f], (0,0,100000,100000), unexpected_mask)
        for budget in (0, -1, True, 1.5):
            with self.assertRaises(ValueError):
                water.rasterize([], (0,0,8,8), max_candidates=budget)
    def test_successful_candidate_count_includes_mask_skips_and_overlaps(self):
        large = feature("grass", {"landuse":"grass"})
        small = feature("water", {"natural":"water"}, [ring(2,2,4,4)])
        result = water.rasterize([large, small], (0,0,4,4), lambda x,z:x<1)
        self.assertEqual(result.candidate_count, 20)
        self.assertEqual(result.to_dict()["candidate_count"], 20)
        self.assertEqual(len(result.cells), 4)
        self.assertEqual(water.rasterize([], (0,0,4,4)).candidate_count, 0)
    def test_invalid_core_fails_before_writes(self):
        for core in [(0,0,0,8),(0.5,0,8,8),(True,0,8,8)]:
            with self.assertRaises(ValueError):
                water.rasterize([],core)
    def test_active_edges_match_original_predicate_at_eps_boundaries(self):
        for shift in (0.0, water.EPS/2, -water.EPS/2, water.EPS*2, -water.EPS*2):
            shell = [[0.5+shift,0.5],[7.5,0.5],[7.5,7.5],[4.5,4.5],
                     [0.5+shift,7.5],[0.5+shift,7.5],[0.5+shift,0.5]]
            hole = [[2.5,2.5],[4.5,2.5],[3.5,3.5],[2.5,2.5]]
            for rings in ([shell,hole], [shell[::-1],hole[::-1]]):
                polygons = water._polygons({"type":"Polygon","coordinates":rings})
                prepared = water._prepare_edges(polygons)
                for z in range(-1,9):
                    zc=z+0.5
                    row=water._row_edges(prepared,zc)
                    for x in range(-1,9):
                        xc=x+0.5
                        self.assertEqual(water._contains(xc,zc,polygons),
                                         water._contains_edges(xc,zc,row), (shift,x,z))
    def test_geometry_mask_and_multipolygon_keep_original_hole_boundaries(self):
        f=feature("islands",{"natural":"water"})
        f["geometry"]={"type":"MultiPolygon","coordinates":[
            [ring(-2,-2,10,10),ring(1.5,1.5,6.5,6.5)], [ring(2.5,2.5,4.5,4.5)]]}
        mask={"type":"Polygon","coordinates":[ring(-1,-1,9,9),ring(3.5,0.5,7.5,2.5)]}
        fp=water._polygons(f["geometry"]);mp=water._polygons(mask)
        expected={(x,z) for x in range(8) for z in range(8)
                  if water._contains(x+.5,z+.5,fp) and water._contains(x+.5,z+.5,mp)}
        actual=water.rasterize([f],(0,0,8,8),mask)
        self.assertEqual(set(actual.cells),expected)
        self.assertEqual(actual.candidate_count,64)
    def test_active_edges_discard_only_irrelevant_rows(self):
        polygon=water._polygons({"type":"Polygon","coordinates":[ring(0,0,8,8)]})
        prepared=water._prepare_edges(polygon)
        self.assertFalse(water._row_edges(prepared,-2))
        row=water._row_edges(prepared,4.5)
        self.assertEqual(len(row[0][0]),2)
        self.assertTrue(water._contains_edges(4.5,4.5,row))
if __name__ == "__main__":
    unittest.main()
