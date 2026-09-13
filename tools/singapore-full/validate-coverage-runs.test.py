import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from shapely.geometry import Polygon, box, mapping

spec = importlib.util.spec_from_file_location("run_oracle", Path(__file__).with_name("validate-coverage-runs.py"))
oracle = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oracle)


def run(x, z, layer="building"):
    return dict(x=x, z=z, yMin=0, yMax=12, layer=layer, block="minecraft:stone")


def geometries():
    shell = box(1000.5, 1000.5, 1010.5, 1010.5)
    ordinary = box(1002.5, 1002.5, 1004.5, 1004.5)
    foreign = box(1006.5, 1006.5, 1008.5, 1008.5)
    return Polygon(shell.exterior.coords, [ordinary.exterior.coords, foreign.exterior.coords]), foreign


class RunOracleTests(unittest.TestCase):
    def fixture(self, folder, inputs, outputs, mode="exact-filter", limit=1000):
        root = Path(folder)
        country, foreign = geometries()
        cp, fp = root/"country.json", root/"foreign.json"
        cp.write_text(json.dumps(mapping(country)))
        fp.write_text(json.dumps(mapping(foreign)))
        predecessor, consumed = root/"raw.jsonl", root/"masked.jsonl"
        payload = lambda rows, end: "".join(json.dumps(r, sort_keys=True, separators=(",", ":"))+end for r in rows).encode()
        predecessor.write_bytes(payload(inputs, "\r\n"))
        consumed.write_bytes(payload(outputs, "\n"))
        return SimpleNamespace(mode=mode, inputs=[(str(predecessor), oracle.digest(predecessor))] if mode=="exact-filter" else [],
            output_runs=str(consumed), output_sha256=oracle.digest(consumed), country_mask=str(cp),
            country_sha256=oracle.digest(cp), foreign_exclusions=str(fp), foreign_sha256=oracle.digest(fp),
            foreign_crs="world-xz", max_records=limit, max_bytes=1024*1024)

    def test_outer_and_ordinary_hole_boundaries_use_covers_exclusion_wins(self):
        country, foreign = geometries()
        classify = oracle.membership(country, foreign)
        self.assertIsNone(classify(1000, 1001))
        self.assertIsNone(classify(1002, 1003))
        self.assertEqual(classify(1003, 1003), "outsideCountry")
        self.assertEqual(classify(1006, 1007), "foreignExclusion")
        self.assertEqual(classify(1007, 1007), "foreignExclusion")
        self.assertEqual(classify(999, 1001), "outsideCountry")

    def test_exact_filter_allows_reordering_and_preserves_duplicate_counts(self):
        with tempfile.TemporaryDirectory() as folder:
            a, b, blocked = run(1001,1001), run(1002,1003,"road"), run(1006,1007,"road")
            result = oracle.audit(self.fixture(folder,[a,a,b,blocked],[b,a,a]))
            self.assertEqual(result["status"], "PASS")
            self.assertTrue(result["complete"])
            self.assertTrue(result["exactFilterOnProvidedInputsAccepted"])
            self.assertEqual(result["expectedRetainedRuns"], 3)
            self.assertEqual(result["blockedInputReasons"], {"foreignExclusion":1})
            self.assertFalse(result["countrySemanticsAccepted"])
            self.assertFalse(result["rendererAccepted"])

    def test_deleted_duplicate_and_changed_height_are_detected(self):
        with tempfile.TemporaryDirectory() as folder:
            a = run(1001,1001)
            result = oracle.audit(self.fixture(folder,[a,a],[a]))
            self.assertEqual(result["missingExpectedRuns"],1)
            self.assertEqual(result["status"],"FAIL")
            changed = dict(a,yMax=99)
            result = oracle.audit(self.fixture(folder,[a],[changed]))
            self.assertEqual((result["missingExpectedRuns"],result["unexpectedOutputRuns"]),(1,1))

    def test_containment_rejects_foreign_boundary(self):
        with tempfile.TemporaryDirectory() as folder:
            result = oracle.audit(self.fixture(folder,[],[run(1006,1007,"road")],mode="containment"))
            self.assertEqual(result["outsideOutputRuns"],1)
            self.assertFalse(result["containmentAccepted"])
            self.assertFalse(result["exactFilterOnProvidedInputsAccepted"])

    def test_empty_output_cannot_prove_containment_or_delete_valid_predecessors(self):
        with tempfile.TemporaryDirectory() as folder:
            result = oracle.audit(self.fixture(folder,[],[],mode="containment"))
            self.assertEqual(result["status"],"FAIL")
            self.assertFalse(result["containmentAccepted"])
            result = oracle.audit(self.fixture(folder,[run(1001,1001)],[]))
            self.assertEqual(result["missingExpectedRuns"],1)
            self.assertFalse(result["exactFilterOnProvidedInputsAccepted"])

    def test_hash_mismatch_never_accepts(self):
        with tempfile.TemporaryDirectory() as folder:
            args = self.fixture(folder,[],[run(1001,1001)],mode="containment")
            args.output_sha256 = "0"*64
            result = oracle.audit(args)
            self.assertEqual(result["status"],"FAIL")
            self.assertFalse(result["output"]["sha256Verified"])
            self.assertFalse(result["containmentAccepted"])

    def test_record_limit_tail_is_incomplete_for_output_and_input(self):
        with tempfile.TemporaryDirectory() as folder:
            rows = [run(1001,1001),run(1001,1002),run(999,1001)]
            result = oracle.audit(self.fixture(folder,[],rows,mode="containment",limit=2))
            self.assertEqual(result["status"],"INCOMPLETE")
            self.assertFalse(result["output"]["complete"])
            self.assertFalse(result["output"]["sha256Verified"])
            result = oracle.audit(self.fixture(folder,rows,rows[:2],limit=2))
            self.assertEqual(result["status"],"INCOMPLETE")
            self.assertFalse(result["inputs"][0]["sha256Verified"])
            self.assertFalse(result["exactFilterOnProvidedInputsAccepted"])

    def test_byte_limit_at_valid_prefix_is_not_complete(self):
        with tempfile.TemporaryDirectory() as folder:
            args = self.fixture(folder,[],[run(1001,1001),run(999,1001)],mode="containment")
            args.max_bytes = len(Path(args.output_runs).read_bytes().splitlines(keepends=True)[0])
            result = oracle.audit(args)
            self.assertEqual(result["status"],"INCOMPLETE")
            self.assertFalse(result["output"]["sha256Verified"])

    def test_exact_record_cap_with_actual_eof_is_valid(self):
        with tempfile.TemporaryDirectory() as folder:
            result = oracle.audit(self.fixture(folder,[],[run(1001,1001)],mode="containment",limit=1))
            self.assertEqual(result["status"],"PASS")
            self.assertTrue(result["output"]["sha256Verified"])
            self.assertFalse(result["exactFilterOnProvidedInputsAccepted"])
            self.assertFalse(result["upstreamSourceCompletenessAccepted"])

    def test_no_tolerance_expands_country_or_foreign_boundary(self):
        edge = 1000.5 - 5e-10
        remote_foreign = box(2000,2000,2010,2010)
        classify = oracle.membership(box(999,1000,edge,1002),remote_foreign)
        self.assertEqual(classify(1000,1001),"outsideCountry")
        classify = oracle.membership(box(999,1000,1002,1002),box(999,1000,edge,1002))
        self.assertIsNone(classify(1000,1001))

    def test_boolean_coordinates_are_not_integer_run_coordinates(self):
        with tempfile.TemporaryDirectory() as folder:
            result = oracle.audit(self.fixture(folder,[],[run(True,1001)],mode="containment"))
            self.assertEqual(result["status"],"FAIL")
            self.assertFalse(result["containmentAccepted"])

    def test_unprojected_mask_is_rejected_as_world_xz(self):
        with tempfile.TemporaryDirectory() as folder:
            p = Path(folder)/"wgs.json"
            p.write_text(json.dumps(mapping(box(103,1,104,2))))
            with self.assertRaisesRegex(oracle.AuditFailure,"WGS84"):
                oracle.load_geometry(p,oracle.digest(p),"world-xz")


if __name__=="__main__":
    unittest.main()
