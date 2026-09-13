import importlib.util,unittest
from pathlib import Path
spec=importlib.util.spec_from_file_location("bench_coverage",Path(__file__).with_name("validate-coverage-benchmarks.py"))
adapter=importlib.util.module_from_spec(spec);spec.loader.exec_module(adapter)
class BenchmarkCoverageTests(unittest.TestCase):
    def fixture(self):
        sha="a"*64
        summary=dict(kind="measured-independent-benchmark-validation",status="PASS",gateSha256=sha)
        receipt=dict(mode="real",status="WRITTEN_UNACCEPTED",coreOrigin=[1000,2000],coreSize=1024,halo=128,coreAreaM2=1048576,
            output=dict(contentHashed=True,inventorySha256="b"*64))
        gate=dict(schemaVersion=1,kind="independent-benchmark-result-gate",status="PASS",experimentalMeasurementAccepted=True,
            comparisonBounds=[1000,2000,2024,3024],comparisonScope="full-volume",comparedCells=402653184,mismatchedCells=0,
            checks=dict(globalChunkCoordinates=True,metadata=True,heightmaps=True),fileHashErrors=[],outputInventorySha256="b"*64)
        return gate,summary,receipt,sha
    def test_admitted_rectangle_excludes_halo(self):
        g,s,r,h=self.fixture()
        self.assertEqual(adapter.check_proof(g,s,r,h).area,1048576)
        self.assertNotEqual(adapter.check_proof(g,s,r,h).area,(1024+256)**2)
    def test_render_extent_cannot_replace_accepted_core(self):
        g,s,r,h=self.fixture();g["comparisonBounds"]=[872,1872,2152,3152]
        with self.assertRaisesRegex(ValueError,"exact owned core"):adapter.check_proof(g,s,r,h)
    def test_summary_cannot_reuse_unrelated_gate(self):
        g,s,r,h=self.fixture();s["gateSha256"]="0"*64
        with self.assertRaisesRegex(ValueError,"different gate"):adapter.check_proof(g,s,r,h)
    def test_partial_volume_and_failed_heightmaps_rejected(self):
        g,s,r,h=self.fixture();g["comparedCells"]-=384
        with self.assertRaisesRegex(ValueError,"full-volume"):adapter.check_proof(g,s,r,h)
        g,s,r,h=self.fixture();g["checks"]["heightmaps"]=False
        with self.assertRaisesRegex(ValueError,"checks"):adapter.check_proof(g,s,r,h)
if __name__=="__main__":unittest.main()
