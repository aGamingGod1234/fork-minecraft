import copy
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from translation import (CoordinateError, GRID, TileTransform, plan_ownership,
                         require_seam_gate, svy21_to_block)


def tile(name="a", origin=(29712, 30496), size=128, halo=32):
    return TileTransform(name, origin, size, halo, tuple(c - halo for c in origin), size + 2 * halo)


def evidence():
    t = tile()
    points = [{"renderX": x, "renderZ": z, "globalX": x + t.render_origin[0],
               "globalZ": z + t.render_origin[1]} for z in (0, 192) for x in (0, 192)]
    return ({"schemaVersion": 1, "status": "generated", "id": "a", "grid": dict(GRID),
             "tile": {"coreOrigin": [29712, 30496], "coreSize": 128, "halo": 32,
                      "renderOrigin": [29680, 30464], "renderSize": 192},
             "mapping": {"adapter": "global-grid-to-Arnis-local", "controlPoints": points},
             "qualityGates": {"coordinateAgreement": True}},
            {"minMcX": 0, "maxMcX": 192, "minMcZ": 0, "maxMcZ": 192,
             "projection": "local", "scale": 1.0})


class TranslationTests(unittest.TestCase):
    def test_real_shape_mapping_core_and_padding(self):
        t = TileTransform.from_manifest(*evidence())
        self.assertEqual(t.delta_chunks, (1855, 1904))
        self.assertEqual(t.local_to_global(32, 32), (29712, 30496))
        self.assertEqual(t.translate_chunk(2, 2), (1857, 1906))
        self.assertEqual(t.translate_chunk(9, 9), (1864, 1913))
        self.assertEqual(len(tuple(t.core_source_chunks())), 64)
        # Arnis writes a whole 512-block region; only these 64 of 1024 chunks belong.
        accepted = 0
        for cz in range(32):
            for cx in range(32):
                try:
                    t.translate_chunk(cx, cz)
                    accepted += 1
                except CoordinateError:
                    pass
        self.assertEqual(accepted, 64)

    def test_half_open_adjacent_core_no_duplicate_or_gap(self):
        a, b = tile(), tile("b", (29840, 30496))
        self.assertTrue(a.owns_block(29839, 30496))
        self.assertFalse(a.owns_block(29840, 30496))
        self.assertTrue(b.owns_block(29840, 30496))
        self.assertFalse(b.owns_block(29968, 30496))
        records = plan_ownership([b, a])
        self.assertEqual(records, plan_ownership([a, b]))
        self.assertEqual(len(records), 128)
        self.assertEqual({a.destination for a in records},
                         {(x, z) for x in range(1857, 1873) for z in range(1906, 1914)})

    def test_negative_origin_region_and_slot_floor_division(self):
        records = plan_ownership([tile("negative", (-32, -16), size=32, halo=16)])
        a = next(a for a in records if a.destination == (-1, -1))
        self.assertEqual(a.region, (-1, -1))
        self.assertEqual(a.region_slot, (31, 31))
        self.assertEqual(tile("negative", (-32, -16), 32, 16).delta_chunks, (-3, -2))

    def test_core_crosses_region_boundary_without_rebasing(self):
        assignments = plan_ownership([tile("boundary", (496, 496), 32, 16)])
        self.assertEqual({a.destination for a in assignments}, {(31, 31), (32, 31), (31, 32), (32, 32)})
        self.assertEqual({a.region for a in assignments}, {(0, 0), (1, 0), (0, 1), (1, 1)})

    def test_overlapping_core_rejected_even_same_content(self):
        with self.assertRaisesRegex(CoordinateError, "overlapping"):
            plan_ownership([tile(), tile("b", (29728, 30496))])
        with self.assertRaisesRegex(CoordinateError, "duplicate"):
            plan_ownership([tile(), tile()])

    def test_no_rounding_alignment_or_origins(self):
        for kwargs in ({"origin": (29713, 30496)}, {"size": 127}, {"halo": 31},
                       {"origin": (29712.0, 30496)}, {"origin": (True, 30496)}):
            with self.subTest(kwargs=kwargs), self.assertRaises(CoordinateError):
                tile(**kwargs)
        with self.assertRaisesRegex(CoordinateError, "renderOrigin"):
            TileTransform("bad", (32, 32), 32, 16, (0, 16), 64)

    def test_metadata_and_control_evidence_fail_closed(self):
        for change in (lambda m, d: d.update(minMcX=16), lambda m, d: d.update(maxMcZ=191),
                       lambda m, d: d.update(scale=2), lambda m, d: m["grid"].update(originNorthing=0),
                       lambda m, d: m["mapping"]["controlPoints"][0].update(globalX=29679),
                       lambda m, d: m["mapping"].update(controlPoints=[]),
                       lambda m, d: m["qualityGates"].update(coordinateAgreement=False)):
            m, d = evidence()
            change(m, d)
            with self.assertRaises(CoordinateError):
                TileTransform.from_manifest(m, d)

    def test_transform_preserves_one_meter_and_north_direction(self):
        self.assertEqual(svy21_to_block(29712, 29504), (29712, 30496))
        self.assertEqual(svy21_to_block(29713, 29505), (29713, 30495))
        with self.assertRaises(CoordinateError):
            svy21_to_block(29712.5, 29504)

    def test_descriptor_stable_integration_contract(self):
        d = tile().to_descriptor()
        self.assertEqual(d["sourceCoreChunks"], [2, 2, 10, 10])
        self.assertEqual(d["deltaChunks"], [1855, 1904])
        self.assertEqual(d["destinationCoreChunks"], [1857, 1906, 1865, 1914])

    def test_actual_seam_gate_must_pass_and_match_pair(self):
        receipt = {"kind": "actual-shared-halo-block-comparison", "status": "PASS",
                   "assemblyAccepted": True, "compared": 2654208, "mismatches": 0,
                   "coreBoundaryMismatches": 0, "occupancyMismatches": 0, "left": "a", "right": "b"}
        require_seam_gate(receipt, "a", "b")
        require_seam_gate(receipt, "b", "a")
        for changes in ({"status": "FAIL"}, {"assemblyAccepted": False}, {"mismatches": 45240},
                        {"compared": 0}, {"mismatches": False}, {"coreBoundaryMismatches": 1},
                        {"occupancyMismatches": 1}, {"right": "stale"}, {"kind": "synthetic"}):
            with self.subTest(changes=changes), self.assertRaises(CoordinateError):
                require_seam_gate({**receipt, **changes}, "a", "b")


if __name__ == "__main__":
    unittest.main()
