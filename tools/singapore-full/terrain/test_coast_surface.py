import unittest

from coast_surface import iter_water_runs


def feature(label, ring, holes=(), identifier=None):
    return {"type": "Feature", "id": identifier or label, "properties": {"class": label},
            "geometry": {"type": "Polygon", "coordinates": [ring, *holes]}}


def rectangle(x0, z0, x1, z1):
    return [[x0,z0],[x1,z0],[x1,z1],[x0,z1],[x0,z0]]


def mask(*features):
    return {"type": "FeatureCollection", "coordinate_space": "minecraft_xz", "features": list(features)}


class CoastSurfaceTests(unittest.TestCase):
    def test_half_tile_union_is_exact(self):
        document = mask(feature("sea", rectangle(-2,-2,4,2)))
        whole = list(iter_water_runs(document, (-2,-2,4,2)))
        split = list(iter_water_runs(document, (-2,-2,1,2))) + list(iter_water_runs(document, (1,-2,4,2)))
        self.assertEqual(sorted(whole, key=lambda r:(r["z"],r["x"])), sorted(split, key=lambda r:(r["z"],r["x"])))
        self.assertEqual(len(whole),24)
        self.assertTrue(all(r["yMin"]==0 and r["yMax"]==1 and r["layer"]==30 for r in whole))

    def test_shared_boundary_conflict_is_unknown(self):
        document = mask(feature("sea", rectangle(0,0,1.5,2)), feature("land", rectangle(1.5,0,3,2)))
        stats = {}
        runs = list(iter_water_runs(document,(0,0,3,2),stats))
        self.assertEqual({r["x"] for r in runs},{0})
        self.assertEqual(stats,{"totalCells":6,"landCells":2,"seaCells":2,"unknownCells":2})

    def test_holes_unknown_and_uncovered_are_never_water(self):
        document = mask(feature("sea", rectangle(0,0,4,4), [rectangle(1,1,3,3)]),
                        feature("unknown",rectangle(0,0,1,4)))
        runs = list(iter_water_runs(document, (0,0,5,4)))
        self.assertEqual(len(runs),8)
        self.assertTrue(all(r["x"] != 0 and r["x"] != 4 for r in runs))

    def test_same_label_overlap_is_sea_not_conflict(self):
        document = mask(feature("sea",rectangle(0,0,2,2),identifier="b"),feature("sea",rectangle(0,0,2,2),identifier="a"))
        runs=list(iter_water_runs(document,(0,0,2,2)))
        self.assertEqual(len(runs),4)
        self.assertTrue(all(r["featureId"]=="coastline/a" for r in runs))

    def test_reject_wrong_coordinates_and_huge_extent(self):
        document=mask(feature("sea",rectangle(0,0,2,2)))
        document["coordinate_space"]="EPSG:3414"
        with self.assertRaises(ValueError): list(iter_water_runs(document,(0,0,2,2)))
        document["coordinate_space"]="minecraft_xz"
        with self.assertRaises(ValueError): list(iter_water_runs(document,(0,0,2001,2000)))


if __name__ == "__main__":
    unittest.main()
