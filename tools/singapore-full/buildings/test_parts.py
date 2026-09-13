import unittest
from parts import (Footprint, BuildingPart, RoofSpec, owner_tile, intersecting_tiles,
                   feature_identity, parse_roof, roof_height_at, column_segments, select_part_at)


def rectangle(x0,z0,x1,z1):
    return Footprint(((x0,z0),(x1,z0),(x1,z1),(x0,z1)))


class OwnershipTests(unittest.TestCase):
    def test_cross_border_building_is_owned_once_but_emitted_in_both_tiles(self):
        footprint = rectangle(500,10,530,30)
        self.assertEqual(owner_tile(footprint), (0,0))
        self.assertEqual(intersecting_tiles(footprint), ((0,0),(1,0)))

    def test_negative_coordinates_and_exact_boundaries(self):
        self.assertEqual(owner_tile(rectangle(-512,-512,0,0)), (-1,-1))
        self.assertEqual(intersecting_tiles(rectangle(-512,-512,0,0)), ((-1,-1),))
        self.assertEqual(intersecting_tiles(rectangle(-1,-1,1,1)), ((-1,-1),(-1,0),(0,-1),(0,0)))

    def test_concavity_does_not_add_bbox_only_tiles(self):
        l_shape = Footprint(((0,0),(20,0),(20,4),(4,4),(4,20),(0,20)))
        self.assertEqual(intersecting_tiles(l_shape,10), ((0,0),(0,1),(1,0)))

    def test_tile_entirely_in_courtyard_is_excluded(self):
        courtyard = Footprint(((0,0),(30,0),(30,30),(0,30)), (((10,10),(20,10),(20,20),(10,20)),))
        self.assertNotIn((1,1), intersecting_tiles(courtyard,10))
        self.assertEqual(len(intersecting_tiles(courtyard,10)),8)

    def test_large_coordinates_preserve_small_overlap(self):
        self.assertEqual(intersecting_tiles(rectangle(40000,30000,40000.1,30000.1),10), ((4000,3000),))

    def test_source_types_and_numeric_normalization_are_preserved(self):
        self.assertEqual(feature_identity("relation", "0042"),"relation/42")
        self.assertNotEqual(feature_identity("way",42),feature_identity("relation",42))
        with self.assertRaises(ValueError): feature_identity("relation","../42")

    def test_bad_geometry_and_tile_size_fail_early(self):
        with self.assertRaises(ValueError): rectangle(0,0,0,1)
        with self.assertRaises(ValueError): intersecting_tiles(rectangle(0,0,1,1),0)


class RoofTests(unittest.TestCase):
    def test_gabled_roof_does_not_reset_at_tile_boundary(self):
        footprint = rectangle(500,0,540,80)
        roof = RoofSpec("gabled",10,"z",True,"whole footprint estimate")
        samples = {(x,z):roof_height_at(footprint,x,z,roof)
                   for x in range(500,540) for z in (1,20,79)}
        tiled = {}
        for tx,tz in intersecting_tiles(footprint):
            for x,z in samples:
                if tx*512 <= x < (tx+1)*512 and tz*512 <= z < (tz+1)*512:
                    tiled[x,z] = roof_height_at(footprint,x,z,roof)
        self.assertEqual(samples,tiled)
        self.assertEqual(samples[512,20],6.0)
        self.assertEqual(samples[520,20],10.0)

    def test_hipped_roof_has_full_length_ridge(self):
        footprint = rectangle(0,0,40,10)
        roof = RoofSpec("hipped",5)
        self.assertEqual(roof_height_at(footprint,5,5,roof),5)
        self.assertEqual(roof_height_at(footprint,35,5,roof),5)
        self.assertEqual(roof_height_at(footprint,1,5,roof),1)
        self.assertEqual(roof_height_at(footprint,20,0,roof),0)

    def test_unknown_shape_and_missing_height_are_explicit_estimates(self):
        for tags in ({"roof:shape":"dome","roof:height":"6"}, {"roof:shape":"gabled"}):
            roof = parse_roof(tags)
            self.assertEqual(roof.shape,"flat")
            self.assertTrue(roof.estimated)
            self.assertIn("estimated flat",roof.provenance)

    def test_tag_height_and_cardinal_downslope_direction(self):
        roof = parse_roof({"roof:shape":"gabled","roof:height":"4 m","roof:direction":"90"})
        self.assertEqual(roof.height_m,4)
        self.assertEqual(roof.ridge_axis,"z")
        self.assertTrue(roof.estimated)
        self.assertEqual(parse_roof({"roof:shape":"hipped","roof:height":"4","roof:direction":"45"}).shape,"flat")

    def test_flat_roof_does_not_add_tag_height_twice(self):
        roof = parse_roof({"roof:shape":"flat","roof:height":"2"})
        self.assertEqual(roof.height_m,0)
        self.assertEqual(roof_height_at(rectangle(0,0,10,10),5,5,roof),0)


class PartTests(unittest.TestCase):
    def setUp(self):
        self.shell = BuildingPart("relation/100",rectangle(0,0,20,20),0,30)

    def test_overlapping_parts_have_order_independent_disjoint_output(self):
        a = BuildingPart("way/1",rectangle(1,1,15,15),0,10,parent_identity="relation/100")
        b = BuildingPart("way/2",rectangle(5,5,10,10),5,20,parent_identity="relation/100")
        forward = column_segments(self.shell,[a,b],7,7)
        self.assertEqual(forward,column_segments(self.shell,[b,a],7,7))
        self.assertEqual([(s.min_height,s.max_height,s.identity) for s in forward],[(0,5,"way/1"),(5,20,"way/2")])
        self.assertTrue(all(s.is_part for s in forward))

    def test_elevated_part_does_not_get_filled_by_outline(self):
        elevated = BuildingPart("way/3",rectangle(1,1,10,10),10,15)
        self.assertEqual([(s.min_height,s.max_height) for s in column_segments(self.shell,[elevated],5,5)],[(10,15)])
        self.assertEqual(column_segments(self.shell,[elevated],19,19),())
        self.assertIsNone(select_part_at(self.shell,[elevated],19,19))

    def test_parts_keep_vertical_air_gaps(self):
        low = BuildingPart("way/1",rectangle(1,1,10,10),0,5)
        high = BuildingPart("way/2",rectangle(1,1,10,10),10,15)
        self.assertEqual([(s.min_height,s.max_height) for s in column_segments(self.shell,[low,high],5,5)],[(0,5),(10,15)])

    def test_priority_then_identity_breaks_ties(self):
        fp = rectangle(1,1,10,10)
        a = BuildingPart("way/1",fp,0,10)
        b = BuildingPart("way/2",fp,0,10,priority=1)
        self.assertEqual(select_part_at(self.shell,[a,b],5,5).identity,"way/2")
        self.assertEqual(select_part_at(self.shell,[BuildingPart("way/2",fp,0,10),a],5,5).identity,"way/1")

    def test_other_relation_parts_cannot_suppress_shell(self):
        unrelated = BuildingPart("way/3",rectangle(1,1,10,10),0,5,parent_identity="relation/101")
        self.assertEqual(column_segments(self.shell,[unrelated],5,5)[0].identity,"relation/100")

    def test_duplicate_relation_member_requires_upstream_normalization(self):
        part = BuildingPart("way/3",rectangle(1,1,10,10),0,5)
        with self.assertRaises(ValueError): column_segments(self.shell,[part,part],5,5)

    def test_osm_total_height_includes_roof(self):
        roof = parse_roof({"roof:shape":"gabled","roof:height":"4"})
        part = BuildingPart("way/1",rectangle(0,0,10,10),0,20-roof.height_m,roof)
        self.assertEqual(part.top_at(5,5),20)
        self.assertEqual(part.top_at(0,5),20)  # ridge along x
        self.assertEqual(part.top_at(5,0),16)

    def test_across_orientation_is_honored(self):
        roof = parse_roof({"roof:shape":"gabled","roof:height":"4","roof:orientation":"across"})
        fp = rectangle(0,0,40,10)
        self.assertEqual(roof_height_at(fp,20,0,roof),4)
        self.assertEqual(roof_height_at(fp,0,5,roof),0)


if __name__ == "__main__":
    unittest.main()
