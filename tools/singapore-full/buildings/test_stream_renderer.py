import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from renderer import render_features
from stream_renderer import render_stream


def feature(identity, ring, **tags):
    return {"type":"Feature","id":identity,
            "geometry":{"type":"Polygon","coordinates":[ring]},
            "properties":{"building":"yes","height":"12",**tags}}


class StreamRendererTests(unittest.TestCase):
    def assert_stream_equals_whole(self, features, box, subcore=8, policy="error"):
        runs, expected=render_features(features,box,ground_y=0,
                                       ground_source_class="flat-provisional-y0-v1",
                                       invalid_feature_policy=policy)
        with tempfile.TemporaryDirectory(prefix="fork-stream-test-") as directory:
            output=Path(directory)/"runs.jsonl"
            manifest=Path(directory)/"manifest.json"
            result=render_stream(features,box,output,manifest,ground_y=0,
                                 ground_source_class="flat-provisional-y0-v1",
                                 subcore_size=subcore,invalid_feature_policy=policy)
            actual=[json.loads(line) for line in output.read_text().splitlines()]
            self.assertEqual(actual,runs)
            for key,value in expected.items():
                self.assertEqual(result[key],value,key)
            self.assertEqual(result["outputSha256"],hashlib.sha256(output.read_bytes()).hexdigest())
            self.assertEqual(output.read_bytes(),"".join(json.dumps(r,sort_keys=True)+"\n" for r in runs).encode())
            self.assertFalse(result["streaming"]["wholeRunListRetained"])
            self.assertFalse(list(Path(directory).glob("fork-building-stripe-*")))
            self.assertEqual(json.loads(manifest.read_text()),json.loads(json.dumps(result)))

    def test_crossing_roof_hole_and_negative_partial_edges(self):
        building=feature("way/1",[[-9,-3],[19,-3],[19,21],[-9,21]],
                         **{"roof:shape":"gabled","roof:height":"3"})
        building["geometry"]["coordinates"].append([[0,4],[4,4],[4,9],[0,9]])
        self.assert_stream_equals_whole([building],(-13,-7,24,25),8)

    def test_whole_256_fixture_exact_semantic_and_file_equality(self):
        a=feature("way/1",[[59,50],[75,50],[75,80],[59,80]],height="26")
        b=feature("way/2",[[123,120],[137,120],[137,145],[123,145]],
                  **{"roof:shape":"hipped","roof:height":"4"})
        c=feature("way/3",[[190,199],[202,199],[202,218],[190,218]],height="18")
        self.assert_stream_equals_whole([a,b,c],(0,0,256,256),64)

    def test_parts_fractional_boundaries_and_distant_siblings(self):
        shell=feature("way/1",[[0,0],[31,0],[31,31],[0,31]])
        low=feature("way/2",[[2,2],[12,2],[12,12],[2,12]],height="5.4",
                    **{"building:part":"yes","parent_identity":"way/1"})
        high=feature("way/3",[[2,2],[12,2],[12,12],[2,12]],height="12",min_height="5.4",
                     **{"building:part":"yes","parent_identity":"way/1"})
        far=feature("way/4",[[24,24],[29,24],[29,29],[24,29]],height="9",
                    **{"building:part":"yes","parent_identity":"way/1"})
        self.assert_stream_equals_whole([far,high,low,shell],(0,0,32,32),8)

    def test_overlap_samples_and_exclusions_are_global_and_unique(self):
        a=feature("way/1",[[0,0],[18,0],[18,18],[0,18]])
        b=feature("way/2",[[2,2],[20,2],[20,20],[2,20]],height="16")
        invalid=feature("way/3",[[5,5],[16,5],[16,16],[5,16]],height="10",min_height="11")
        self.assert_stream_equals_whole([invalid,b,a],(0,0,24,24),8,"report")

    def test_empty_subcores_and_no_features(self):
        self.assert_stream_equals_whole([],(0,0,19,17),8)

    def test_failure_preserves_existing_outputs_and_removes_spools(self):
        invalid=feature("way/1",[[8,0],[12,0],[12,4],[8,4]],height="500")
        with tempfile.TemporaryDirectory(prefix="fork-stream-test-") as directory:
            output=Path(directory)/"runs.jsonl"
            manifest=Path(directory)/"manifest.json"
            output.write_text("previous output")
            manifest.write_text("previous manifest")
            with self.assertRaises(ValueError):
                render_stream([invalid],(0,0,16,8),output,manifest,ground_y=0,
                              ground_source_class="flat-provisional",subcore_size=8)
            self.assertEqual(output.read_text(),"previous output")
            self.assertEqual(manifest.read_text(),"previous manifest")
            self.assertEqual(sorted(p.name for p in Path(directory).iterdir()),["manifest.json","runs.jsonl"])


if __name__ == "__main__":
    unittest.main()
