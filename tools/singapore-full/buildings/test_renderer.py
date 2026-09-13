import unittest

from renderer import render_features


def feature(identity, ring, **tags):
    return {"type": "Feature", "id": identity,
            "geometry": {"type": "Polygon", "coordinates": [ring]},
            "properties": {"building": "yes", "height": "12", **tags}}


def compile_buildings(features, box):
    return render_features(features, box, ground_y=0,
                           ground_source_class="flat-provisional")


def voxels(runs):
    return {(r["x"], y, r["z"], r["block"], r["featureId"], r["geometryKind"])
            for r in runs for y in range(r["yMin"], r["yMax"])}


class RendererTests(unittest.TestCase):
    def test_cross_tile_exact_union(self):
        building = feature("way/1", [[-3,-2],[7,-2],[7,5],[-3,5]],
                           **{"roof:shape": "gabled", "roof:height": "3",
                              "building:material": "concrete"})
        whole, _ = compile_buildings([building], (-8,-8,8,8))
        left, _ = compile_buildings([building], (-8,-8,0,8))
        right, _ = compile_buildings([building], (0,-8,8,8))
        self.assertEqual(voxels(whole), voxels(left) | voxels(right))
        # Tile borders never create internal vertical walls.
        self.assertFalse(any(x == 0 and z == 0 and 1 < y < 8
                             for x,y,z,*_ in voxels(whole)))

    def test_parts_suppress_entire_shell_and_preserve_gap(self):
        shell = feature("way/1", [[0,0],[8,0],[8,8],[0,8]])
        part = feature("way/2", [[2,2],[4,2],[4,4],[2,4]],
                       **{"building:part": "yes", "height": "9", "min_height": "4",
                          "parent_identity": "way/1"})
        runs, meta = compile_buildings([shell, part], (0,0,8,8))
        self.assertTrue(meta["features"][0]["outlineSuppressed"])
        self.assertTrue(all(2 <= r["x"] < 4 and 2 <= r["z"] < 4 for r in runs))
        self.assertTrue(all(r["yMin"] >= 4 for r in runs))
        self.assertEqual({r["featureId"] for r in runs}, {"way/2"})

    def test_hole_and_duplicate_identity(self):
        building = feature("way/1", [[0,0],[8,0],[8,8],[0,8]])
        building["geometry"]["coordinates"].append([[2,2],[6,2],[6,6],[2,6]])
        runs, _ = compile_buildings([building], (0,0,8,8))
        self.assertFalse(any(2 <= r["x"] < 6 and 2 <= r["z"] < 6 for r in runs))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            compile_buildings([building, building], (0,0,8,8))

    def test_overflow_is_not_clipped(self):
        building = feature("way/1", [[0,0],[2,0],[2,2],[0,2]], height="500")
        with self.assertRaises(ValueError):
            compile_buildings([building], (0,0,8,8))

    def test_report_is_explicit_and_unrelated_bad_feature_is_not_compiled(self):
        valid = feature("way/1", [[0,0],[2,0],[2,2],[0,2]])
        invalid = feature("way/2", [[100,100],[102,100],[102,102],[100,102]], height="280", min_height="281")
        runs, meta = compile_buildings([valid, invalid], (0,0,8,8))
        self.assertTrue(runs)
        self.assertEqual(meta["selectedFeatureCount"], 1)
        runs, meta = render_features([valid,invalid], (0,0,128,128), ground_y=0,
                                    ground_source_class="flat-provisional", invalid_feature_policy="report")
        self.assertFalse(meta["completeSourceGeometryAccepted"])
        self.assertEqual(meta["exclusions"][0]["featureId"], "way/2")

    def test_numeric_height_and_flat_override_survive(self):
        building = feature("way/1", [[0,0],[2,0],[2,2],[0,2]], height=290)
        building["properties"]["tags"] = {"height": "6", "building:material": "glass"}
        runs, meta = compile_buildings([building], (0,0,8,8))
        self.assertEqual(max(r["yMax"] for r in runs), 290)
        self.assertFalse(meta["evidence"][0]["height"]["estimated"])

    def test_fractional_stacked_parts_have_unique_voxels(self):
        ring = [[0,0],[2,0],[2,2],[0,2]]
        shell = feature("way/1", ring, height="10")
        low = feature("way/2", ring, height="5.4", **{"building:part":"yes","parent_identity":"way/1"})
        high = feature("way/3", ring, height="10", min_height="5.4", **{"building:part":"yes","parent_identity":"way/1"})
        runs, _ = compile_buildings([shell,low,high], (0,0,4,4))
        coordinates = [(r["x"], y, r["z"]) for r in runs for y in range(r["yMin"],r["yMax"])]
        self.assertEqual(len(coordinates),len(set(coordinates)))
        self.assertEqual({r["featureId"] for r in runs if r["yMin"] <= 5 < r["yMax"]},{"way/3"})

    def test_ambiguous_independent_source_overlap_is_reported(self):
        a = feature("way/1", [[0,0],[2,0],[2,2],[0,2]], height="10")
        b = feature("way/2", [[0,0],[2,0],[2,2],[0,2]], height="10")
        with self.assertRaisesRegex(ValueError,"Ambiguous"):
            compile_buildings([a,b], (0,0,4,4))
        runs, meta = render_features([b,a], (0,0,4,4), ground_y=0,
                                    ground_source_class="flat-provisional", invalid_feature_policy="report")
        self.assertGreater(meta["resolvedOverlappingVoxels"],0)
        self.assertFalse(meta["completeSourceGeometryAccepted"])
        coords=[(r["x"],y,r["z"]) for r in runs for y in range(r["yMin"],r["yMax"])]
        self.assertEqual(len(coords),len(set(coords)))

    def test_order_and_source_provenance(self):
        a = feature("way/1", [[0,0],[2,0],[2,2],[0,2]])
        b = feature("way/2", [[3,0],[5,0],[5,2],[3,2]])
        first, meta = compile_buildings([a,b], (0,0,8,8))
        second, again = compile_buildings([b,a], (0,0,8,8))
        self.assertEqual(first, second)
        self.assertEqual(meta["runsSha256"], again["runsSha256"])
        self.assertEqual(meta["groundSourceClass"], "flat-provisional")
        self.assertTrue(meta["requiresCleanTerrainLayer"])
        self.assertTrue(all(r["layer"] == "building" for r in first))


if __name__ == "__main__":
    unittest.main()
