import tempfile,unittest
from pathlib import Path
from unittest.mock import patch
import grow_preview as g

class EmptyScan(unittest.TestCase):
    def check(self,core_runs,layer_runs):
        with tempfile.TemporaryDirectory() as temp:
            runs=Path(temp)/'roads.runs.jsonl';runs.write_bytes(b'x')
            bounds=[0,0,1024,1024]
            evidence={'emptyInlandSubset':True,'sourceWaterAbsenceProven':False,'sourceCoverageComplete':False,
                'featureCount':0,'emittedFeatureCount':0,'emittedFeatureIds':[],'runsPath':str(runs)}
            scan={'kind':'fork-consumed-component-run-scan','schemaVersion':1,'status':'PASS','synthetic':False,
                'coreBounds':bounds,'runsPath':str(runs),'runsBytes':1,'allRuns':10,'coreRuns':core_runs,
                'layers':{'40':layer_runs},'inlandWater':{'emittedFeatureIds':[],'componentRuns':0,'componentVoxels':0},
                'sourceComplete':False,'fullWorldAccepted':False}
            with patch.object(g,'_bound_json',return_value=scan):
                return g._validate_empty_inland_scan(evidence,{'runCount':10},bounds)
    def test_halo_runs_are_scanned_but_not_counted_as_core_water(self): self.check(6,6)
    def test_exact_core_input_still_passes(self):self.check(10,10)
    def test_unaccounted_core_layer_is_rejected(self):
        with self.assertRaises(g.PreviewEvidenceError):self.check(6,5)
    def test_core_cannot_exceed_entire_file(self):
        with self.assertRaises(g.PreviewEvidenceError):self.check(11,11)

if __name__=='__main__':unittest.main()
