import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from renderer import render_features
from source_validation import SourceContractError, validate_document, validate_features
from stream_renderer import render_stream


def feature(identity="way/1", **properties):
    return {"type":"Feature","id":identity,
            "geometry":{"type":"Polygon","coordinates":[[[0,0],[4,0],[4,4],[0,4]]]},
            "properties":{"height":"6",**properties}}


def document(features):
    return {"type":"FeatureCollection","features":features,
            "coordinateSystem":{"source":"EPSG:4326","projection":"EPSG:3414",
                                "axes":["x=easting_metres","z=60000-northing_metres"],
                                "units":"metres","blocksPerMetre":1}}


def render(features):
    return render_features(features,(0,0,8,8),ground_y=0,
                           ground_source_class="flat-provisional",
                           invalid_feature_policy="report")


class SourceValidationTests(unittest.TestCase):
    def test_malformed_tag_containers_are_quarantined(self):
        for tags in (42,[],{"height":["bad"]}):
            with self.subTest(tags=tags):
                runs,meta=render([feature(tags=tags)])
                self.assertEqual(runs,[])
                self.assertEqual(meta["exclusions"][0]["featureId"],"way/1")
                self.assertIn("tag_validation:",meta["exclusions"][0]["reason"])
                self.assertFalse(meta["completeSourceGeometryAccepted"])

    def test_malformed_flat_tag_container_is_quarantined(self):
        runs,meta=render([feature(height={"bad":True})])
        self.assertFalse(runs)
        self.assertIn("height must be a scalar",meta["exclusions"][0]["reason"])

    def test_existing_scalar_warning_policy_is_unchanged(self):
        _,meta=render([feature(height="unknown")])
        self.assertEqual(meta["exclusions"],[])
        self.assertEqual(meta["evidence"][0]["height"]["value_m"],6)
        self.assertTrue(meta["evidence"][0]["height"]["warnings"])

    def test_nonfinite_or_invalid_geometry_fails_even_outside_requested_tile(self):
        bad=feature()
        bad["geometry"]["coordinates"][0][0]=[float("nan"),1000]
        with self.assertRaises(SourceContractError):
            render([bad])
        bad["geometry"]={"type":"Point","coordinates":[1000,1000]}
        with self.assertRaises(SourceContractError):
            render([bad])

    def test_invalid_feature_structure_and_duplicate_identity_fail(self):
        for features in ([{"type":"Feature","geometry":{}}],
                         [dict(feature(),properties=["height","6"])],
                         [feature(),feature()]):
            with self.subTest(features=features),self.assertRaises(SourceContractError):
                render(features)

    def test_explicit_missing_parent_invalid_type_and_cycles_fail(self):
        cases=[[feature(parent_identity="way/missing")],
               [feature(parent_identity=["way/2"])],
               [feature(parent_identity="way/1")],
               [feature("way/1",parent_identity="way/2"),
                feature("way/2",parent_identity="way/1")]]
        for features in cases:
            with self.subTest(features=features),self.assertRaises(SourceContractError):
                render(features)

    def test_absent_parent_is_not_a_broken_explicit_reference(self):
        self.assertEqual(validate_features([feature(**{"building:part":"yes"})]),{})

    def test_wrong_or_missing_crs_axes_scale_fail(self):
        good=document([feature()])
        self.assertEqual(validate_document(good),good["features"])
        mutations=[("projection","EPSG:4326"),("axes",["x=easting_metres","z=northing_metres"]),
                   ("blocksPerMetre",2),("blocksPerMetre",True),("units","feet")]
        for key,value in mutations:
            bad=copy.deepcopy(good)
            bad["coordinateSystem"][key]=value
            with self.subTest(key=key,value=value),self.assertRaises(SourceContractError):
                validate_document(bad)
        del good["coordinateSystem"]
        with self.assertRaises(SourceContractError):
            validate_document(good)

    def test_streaming_report_propagates_source_failures_without_publication(self):
        with tempfile.TemporaryDirectory(prefix="fork-source-guard-") as directory:
            output,manifest=Path(directory)/"runs.jsonl",Path(directory)/"manifest.json"
            with self.assertRaises(SourceContractError):
                render_stream([feature(parent_identity="missing")],(0,0,8,8),output,manifest,
                              ground_y=0,ground_source_class="flat-provisional",
                              invalid_feature_policy="report")
            self.assertFalse(output.exists())
            self.assertFalse(manifest.exists())

    def test_both_cli_entrypoints_reject_wrong_crs(self):
        with tempfile.TemporaryDirectory(prefix="fork-source-cli-") as directory:
            root=Path(directory)
            source=document([feature()])
            source["coordinateSystem"]["projection"]="EPSG:4326"
            (root/"source.json").write_text(json.dumps(source))
            for entrypoint in ("renderer.py","stream_renderer.py"):
                result=subprocess.run([sys.executable,str(Path(__file__).with_name(entrypoint)),
                    "--input",str(root/"source.json"),"--tile","0","0","8","8",
                    "--ground-y","0","--ground-source-class","flat-provisional",
                    "--output",str(root/"runs.jsonl"),"--manifest",str(root/"manifest.json"),
                    "--invalid-feature-policy","report"],capture_output=True,text=True)
                self.assertNotEqual(result.returncode,0)
                self.assertIn("CRS contract",result.stderr)
                self.assertFalse((root/"runs.jsonl").exists())


if __name__ == "__main__":
    unittest.main()
