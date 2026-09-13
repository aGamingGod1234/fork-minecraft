from pathlib import Path
import json
import math
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from runs import emit_surface_runs


def road(identity="way/1", bridge=False):
    tags = {"highway": "residential", "width": "2"}
    if bridge:
        tags["bridge"] = "yes"
    return {"type": "Feature", "id": identity, "properties": {"tags": tags},
            "geometry": {"type": "LineString", "coordinates": [[-2, 1], [8, 1]]}}


def water():
    return {"type": "Feature", "id": "way/2", "properties": {"tags": {"natural": "water"}},
            "geometry": {"type": "Polygon", "coordinates": [[[0, 0], [4, 0], [4, 4], [0, 4], [0, 0]]]}}


class RunTests(unittest.TestCase):
    def test_unknown_ground_never_silently_emits_flat_world(self):
        result = emit_surface_runs([road(), water()], (0, 0, 4, 4))
        self.assertFalse(result["runs"])
        self.assertFalse(result["fullFidelityAccepted"])

    def test_explicit_preview_keeps_shared_layer_contract(self):
        result = emit_surface_runs([road(), water()], (0, 0, 4, 4), provisional_surface_y=0)
        self.assertEqual({r["layer"] for r in result["runs"]}, {30, 40})
        self.assertTrue(all(r["yMin"] == 0 and r["yMax"] == 1 for r in result["runs"]))
        self.assertTrue(all("provisional" in r["sourceClass"] for r in result["runs"]))
        self.assertFalse(result["evidence"][0]["verticalAccepted"])

    def test_unknown_bridge_not_flattened_to_ground(self):
        result = emit_surface_runs([road(bridge=True)], (0, 0, 4, 4), provisional_surface_y=0)
        self.assertFalse(result["runs"])

    def test_shared_budget_blocks_surface_scan_and_counts_success(self):
        blocked = emit_surface_runs([water()], (0, 0, 4, 4), provisional_surface_y=0, max_candidates=1)
        self.assertFalse(blocked["runs"])
        self.assertEqual(blocked["candidateCount"], 0)
        self.assertEqual(blocked["diagnostics"][0]["code"], "surface_raster_blocked")
        valid = emit_surface_runs([water()], (0, 0, 4, 4), provisional_surface_y=0, max_candidates=16)
        self.assertEqual(valid["candidateCount"], 16)
        self.assertEqual(valid["surfaceEvidence"][0]["cellCount"], 16)
        self.assertEqual(valid["surfaceEvidence"][0]["materialBasis"], "stylized_palette_not_surveyed_surface")

    def test_whole_feature_two_cores_equal_union_and_mask_limits_writes(self):
        features = [road(), water()]
        whole = emit_surface_runs(features, (0, 0, 8, 4), provisional_surface_y=0)["runs"]
        split = emit_surface_runs(features, (0, 0, 4, 4), provisional_surface_y=0)["runs"]
        split += emit_surface_runs(features, (4, 0, 8, 4), provisional_surface_y=0)["runs"]
        key = lambda r: (r["layer"], r["featureId"], r["x"], r["z"], r["yMin"], r["block"])
        self.assertEqual(whole, sorted(split, key=key))
        masked = emit_surface_runs(features, (0, 0, 8, 4), provisional_surface_y=0, clip_mask=lambda x,z: x < 3)["runs"]
        self.assertTrue(masked)
        self.assertTrue(all(r["x"] < 3 for r in masked))

    def test_real_market_street_emits_measured_geometry_with_inferred_width(self):
        fixture = json.loads((Path(__file__).resolve().parents[1] / "fixtures" / "roads_osm.json").read_text())
        feature = next(f for f in fixture["features"] if f["id"] == "way/22921315")
        points = feature["geometry"]["coordinates"]
        core = (math.floor(min(p[0] for p in points))-5, math.floor(min(p[1] for p in points))-5,
                math.ceil(max(p[0] for p in points))+5, math.ceil(max(p[1] for p in points))+5)
        result = emit_surface_runs([feature], core, provisional_surface_y=0)
        self.assertTrue(result["runs"])
        self.assertTrue(result["evidence"][0]["width"]["inferred"])
        self.assertEqual(result["evidence"][0]["width"]["source"], "inferred:lanes")
        self.assertTrue(all(r["featureId"] == "way/22921315" for r in result["runs"]))

    def test_actual_church_market_junction_has_one_global_owner(self):
        fixture = json.loads((Path(__file__).resolve().parents[1] / "fixtures" / "road_overlap_osm.json").read_text())
        core = tuple(fixture["regression"]["core"])
        result = emit_surface_runs(fixture, core, provisional_surface_y=0)
        voxel = fixture["regression"]["failure_voxel"]
        owners = [r for r in result["runs"] if [r["x"], r["yMin"], r["z"]] == voxel]
        self.assertEqual(len(owners), 1)
        self.assertEqual(owners[0]["featureId"], "way/477933141")
        self.assertEqual(owners[0]["block"], "minecraft:gray_concrete")
        keys = [(r["layer"], r["x"], r["yMin"], r["z"]) for r in result["runs"]]
        self.assertEqual(len(keys), len(set(keys)))
        self.assertTrue(result["ownership"]["differentMaterialVoxelCount"])
        self.assertTrue(any(set(d.get("candidateFeatureIds", [])) == {"way/22921313", "way/477933141", "way/747369958"} for d in result["diagnostics"]))
        self.assertEqual(sum(r["runs"] for r in result["evidence"]), len(result["runs"]))
        self.assertTrue(any(r["occludedRuns"] for r in result["evidence"]))
        reversed_result = emit_surface_runs(list(reversed(fixture["features"])), core, provisional_surface_y=0)
        self.assertEqual(result, reversed_result)
        split_x = 29856
        split = emit_surface_runs(fixture, (core[0], core[1], split_x, core[3]), provisional_surface_y=0)["runs"]
        split += emit_surface_runs(fixture, (split_x, core[1], core[2], core[3]), provisional_surface_y=0)["runs"]
        key = lambda r: (r["layer"], r["featureId"], r["x"], r["z"], r["yMin"], r["block"])
        self.assertEqual(result["runs"], sorted(split, key=key))

    def test_ownership_does_not_coalesce_different_elevations(self):
        upper = road("way/2")
        upper["properties"]["vertical_evidence"] = {"position": [2, 1], "sample_radius_m": 100,
            "semantics": "road_surface", "elevation_m": 5, "datum": "test:local", "source_id": "fixture", "surveyed": True}
        result = emit_surface_runs([road(), upper], (0, 0, 4, 4), provisional_surface_y=0)
        self.assertEqual({r["yMin"] for r in result["runs"]}, {0, 5})
        self.assertEqual(result["ownership"]["contestedVoxelCount"], 0)


if __name__ == "__main__":
    unittest.main()
