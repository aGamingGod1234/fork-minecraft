"""Behavioral tests for grade separation and conservative map evidence."""
import importlib.util
import json
from pathlib import Path
import sys
import unittest

MODULE = Path(__file__).resolve().parents[1] / "topology.py"
if not MODULE.exists():  # Standalone staging is useful for an isolated review.
    MODULE = Path(__file__).with_name("topology.py")
spec = importlib.util.spec_from_file_location("road_topology", MODULE)
topology = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = topology
spec.loader.exec_module(topology)


def way(fid, coords, nodes=None, **tags):
    return {"type": "Feature", "id": str(fid), "properties": {
        "tags": {"highway": "residential", **tags}, **({"nodes": nodes} if nodes else {})},
        "geometry": {"type": "LineString", "coordinates": coords}}


def crossing(fid, point, **tags):
    return {"type": "Feature", "id": "node/" + str(fid), "properties": {
        "highway": "crossing", **tags}, "geometry": {"type": "Point", "coordinates": point}}


class TopologyTests(unittest.TestCase):
    def test_shared_node_connects_but_geometric_crossing_does_not(self):
        a = way("way/a", [[0, 0], [10, 0], [20, 0]], [1, 2, 3])
        b = way("way/b", [[10, -10], [10, 0], [10, 10]], [4, 2, 5])
        result = topology.analyze_topology([a, b])
        self.assertEqual(len(result.intersections), 1)
        self.assertEqual(result.intersections[0].relation, "at_grade")
        self.assertEqual(result.intersections[0].node_refs, ("2",))
        b["properties"].pop("nodes")
        result = topology.analyze_topology([a, b])
        self.assertEqual(result.intersections[0].relation, "geometric_candidate")

    def test_bridge_separation_and_shared_abutment(self):
        bridge = way("bridge", [[0, 0], [10, 0]], [1, 2], bridge="yes", layer="1")
        road = way("road", [[5, -10], [5, 10]], [3, 4])
        self.assertEqual(topology.analyze_topology([bridge, road]).intersections[0].relation, "grade_separated")
        approach = way("approach", [[10, 0], [20, 0]], [2, 5])
        self.assertEqual(topology.analyze_topology([bridge, approach]).intersections[0].relation, "transition")
        conflict = way("conflict", [[0, -1], [0, 0], [0, 1]], [6, 1, 7])
        self.assertEqual(topology.analyze_topology([bridge, conflict]).intersections[0].relation, "unresolved")

    def test_layer_is_not_height_and_tunnel_is_never_surface_filled(self):
        for tags in ({"bridge": "yes", "layer": "5"}, {"tunnel": "yes", "layer": "-2"}, {"layer": "-1"}):
            policy = topology.vertical_policy(way("x", [[0, 0], [1, 1]], **tags), ground_y=64)
            self.assertIsNone(policy.y)
            self.assertFalse(policy.allow_surface)
            self.assertFalse(policy.accepted_geometry)
        tunnel = way("t", [[0, 0], [1, 0]], tunnel="yes", layer="-1")
        tunnel["properties"]["vertical_evidence"] = {"elevation_m": -3, "datum": "local-survey", "source_id": "s1",
            "semantics": "tunnel_floor", "surveyed": True, "position": [0, 0], "sample_radius_m": 0}
        policy = topology.vertical_policy(tunnel, y_offset=64)
        self.assertEqual(policy.y, 61)
        self.assertTrue(policy.accepted_geometry)
        self.assertFalse(policy.allow_surface)

    def test_bridge_sample_is_not_extended_beyond_measured_radius(self):
        bridge = way("b", [[0, 0], [100, 0]], bridge="yes", layer="1")
        bridge["properties"]["vertical_evidence"] = {"elevation_m": 9.5, "datum": "local-survey", "source_id": "s1",
            "semantics": "bridge_deck", "surveyed": True, "position": [0, 0], "sample_radius_m": 0.5}
        at_sample = topology.vertical_policy(bridge, x=0, z=0, y_offset=64)
        self.assertEqual(at_sample.y, 74)
        self.assertTrue(at_sample.accepted_geometry)
        self.assertIsNone(topology.vertical_policy(bridge, x=1, z=0).y)

    def test_dsm_withheld_and_inferred_ground_labeled(self):
        road = way("r", [[0, 0], [10, 0]])
        sample = {"elevation_m": 83, "datum": "EGM2008", "source_id": "Copernicus", "surveyed": False, "semantics": "surface_dsm"}
        policy = topology.vertical_policy(road, terrain_sampler=lambda x, z: sample)
        self.assertIsNone(policy.y)
        policy = topology.vertical_policy(road, terrain_sampler=lambda x, z: sample, ground_y=64)
        self.assertEqual(policy.y, 64)
        self.assertTrue(policy.allow_surface)
        self.assertFalse(policy.accepted_geometry)
        sample.update(semantics="bare_earth", surveyed=True)
        self.assertTrue(topology.vertical_policy(road, terrain_sampler=lambda x, z: sample).accepted_geometry)
        passage = way("p", [[0, 0], [1, 0]], tunnel="building_passage")
        self.assertEqual(topology.vertical_policy(passage, ground_y=64).mode, "covered_passage")
        self.assertTrue(topology.vertical_policy(passage, ground_y=64).allow_surface)

    def test_no_crosswalk_is_invented_at_a_road_junction(self):
        a = way("a", [[0, 0], [2, 0]], [1, 2])
        b = way("b", [[1, -1], [1, 1]], [3, 4])
        self.assertEqual(topology.analyze_topology([a, b]).crossings, ())

    def test_mapped_crossings_preserve_marking_and_extent_uncertainty(self):
        road = way("road", [[0, 0], [10, 0], [20, 0]], [1, 2, 3])
        signal = crossing(2, [10, 0], crossing="traffic_signals")
        result = topology.analyze_topology([road, signal]).crossings[0]
        self.assertEqual(result.markings, "unknown")
        self.assertEqual(result.road_ids, ("road",))
        self.assertFalse(result.paint_geometry_available)
        signal["properties"]["crossing:markings"] = "zebra"
        self.assertEqual(topology.analyze_topology([road, signal]).crossings[0].markings, "zebra")
        signal["properties"]["crossing"] = "no"
        self.assertEqual(topology.analyze_topology([road, signal]).crossings, ())
        stripe = way("cross", [[10, -3], [10, 3]], highway="footway", footway="crossing", **{"crossing:markings": "zebra", "width": "3"})
        marked = topology.analyze_topology([road, stripe]).crossings[0]
        self.assertTrue(marked.paint_geometry_available)
        self.assertEqual(marked.road_ids, ("road",))

    def test_collinear_overlap_does_not_create_fake_junctions(self):
        a = way("a", [[0, 0], [20, 0]])
        b = way("b", [[5, 0], [10, 0]])
        self.assertEqual(topology.analyze_topology([a, b]).intersections, ())

    def test_ground_crosswalk_does_not_attach_to_overhead_bridge(self):
        bridge = way("bridge", [[0, 0], [10, 0]], bridge="yes", layer="1")
        marked = crossing(2, [5, 0], **{"crossing:markings": "zebra"})
        self.assertEqual(topology.analyze_topology([bridge, marked]).crossings[0].road_ids, ())

    def test_order_and_spatial_bucket_size_do_not_change_result(self):
        features = [way("a", [[-150, 0], [150, 0]]), way("b", [[0, -150], [0, 150]]), crossing(10, [0, 0])]
        a = topology.analyze_topology(features).to_dict()
        b = topology.analyze_topology(reversed(features), spatial_cell_m=25).to_dict()
        self.assertEqual(a, b)
        json.dumps(a, allow_nan=False)

    def test_reject_incomplete_reference_arrays(self):
        with self.assertRaises(ValueError):
            topology.analyze_topology([way("a", [[0, 0], [1, 0]], [1])])
        with self.assertRaises(ValueError):
            topology.analyze_topology([way("a", [[0, 0], [float("nan"), 0]])])


if __name__ == "__main__":
    unittest.main()
