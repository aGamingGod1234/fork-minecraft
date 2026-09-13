import copy
import importlib.util
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from osm_adapter import adapt_osm


def node(i, x, z):
    return {"type": "node", "id": i, "lon": x, "lat": 60000-z}


def way(i, refs, tags=None):
    return {"type": "way", "id": i, "nodes": refs, "tags": tags or {}}


def projection(lon, lat):
    return lon, lat


class AdapterTests(unittest.TestCase):
    def test_complete_road_keeps_geometry_ids_and_fractional_global_coordinates(self):
        data = {"elements": [node(1, -1.25, 0.2), node(2, 300.4, 9.2), way(10, [1, 2], {"highway": "residential", "lanes": "2"})]}
        result = adapt_osm(data, projection).to_dict()
        road = result["features"][0]
        self.assertEqual(road["properties"]["nodes"], [1, 2])
        self.assertEqual(road["geometry"]["coordinates"][0][0], -1.25)
        self.assertAlmostEqual(road["geometry"]["coordinates"][1][1], 9.2)
        self.assertTrue(result["metadata"]["wholeFeaturesPreserved"])

    def test_missing_way_node_discards_entire_feature(self):
        data = {"elements": [node(1, 1, 1), way(10, [1, 2], {"highway": "footway"})]}
        result = adapt_osm(data, projection)
        self.assertFalse(result.features)
        self.assertIn("node/2", result.diagnostics[0]["detail"])

    def test_multipolygon_joins_reversed_fragments_and_retains_hole(self):
        elements = [node(1, 0, 0), node(2, 10, 0), node(3, 10, 10), node(4, 0, 10), node(5, 2, 2), node(6, 4, 2), node(7, 4, 4), node(8, 2, 4)]
        elements += [way(10, [1, 2, 3]), way(11, [1, 4, 3]), way(12, [5, 6, 7, 8, 5])]
        relation = {"type": "relation", "id": 20, "tags": {"type": "multipolygon", "natural": "water"}, "members": [{"type": "way", "ref": 10, "role": "outer"}, {"type": "way", "ref": 11, "role": "outer"}, {"type": "way", "ref": 12, "role": "inner"}]}
        elements.append(relation)
        original = adapt_osm({"elements": elements}, projection).to_dict()
        shuffled = copy.deepcopy(elements)
        shuffled.reverse()
        shuffled[0]["members"].reverse()
        repeated = adapt_osm({"elements": shuffled}, projection).to_dict()
        self.assertEqual(original, repeated)
        self.assertEqual(len(original["features"][0]["geometry"]["coordinates"][0]), 2)
        self.assertFalse(original["diagnostics"])

    def test_missing_relation_member_never_closes_partial_outline(self):
        rel = {"type": "relation", "id": 1, "tags": {"type": "multipolygon", "natural": "water"}, "members": [{"type": "way", "ref": 404, "role": "outer"}]}
        result = adapt_osm({"elements": [rel]}, projection)
        self.assertFalse(result.features)
        self.assertEqual(result.diagnostics[0]["code"], "incomplete_or_unsupported_multipolygon")

    def test_independently_tagged_inner_island_is_preserved(self):
        elements = [node(1, 0, 0), node(2, 10, 0), node(3, 10, 10), node(4, 0, 10),
                    node(5, 2, 2), node(6, 4, 2), node(7, 4, 4), node(8, 2, 4),
                    way(100, [1, 2, 3, 4, 1], {"natural": "water"}),
                    way(101, [5, 6, 7, 8, 5], {"landuse": "grass"}),
                    {"type": "relation", "id": 200, "tags": {"type": "multipolygon", "natural": "water"},
                     "members": [{"type": "way", "ref": 100, "role": "outer"}, {"type": "way", "ref": 101, "role": "inner"}]}]
        result = adapt_osm({"elements": elements}, projection)
        self.assertEqual([f["id"] for f in result.features], ["relation/200", "way/101"])
        self.assertFalse(result.diagnostics)

    def test_admin_boundary_does_not_become_land_and_crossing_is_preserved(self):
        point = node(1, 1, 2)
        point["tags"] = {"highway": "crossing", "crossing:markings": "zebra"}
        result = adapt_osm({"elements": [point, {"type": "relation", "id": 2, "tags": {"type": "boundary", "boundary": "administrative"}, "members": []}]}, projection)
        self.assertEqual(len(result.features), 1)
        self.assertEqual(result.features[0]["geometry"]["type"], "Point")

    def test_conflicting_objects_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "conflicting duplicate"):
            adapt_osm({"elements": [node(1, 1, 1), node(1, 2, 2)]}, projection)


if __name__ == "__main__":
    unittest.main()
