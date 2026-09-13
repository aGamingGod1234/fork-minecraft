import contextlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).resolve().parent))
spec=importlib.util.spec_from_file_location('pipeline_roads',Path(__file__).with_name('pipeline-roads.py'))
module=importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def polygon(outer,holes=()):
    return {'type':'Polygon','coordinates':[outer,*holes]}


def square(x0,z0,x1,z1):
    return [[x0,z0],[x1,z0],[x1,z1],[x0,z1],[x0,z0]]


class CoversTests(unittest.TestCase):
    def test_country_hole_boundary_is_covered_but_interior_is_not(self):
        covers=module.mask_geometry(polygon(square(0.5,0.5,8.5,8.5),[square(2.5,2.5,6.5,6.5)]))
        self.assertTrue(covers(0.5,4.5))
        self.assertTrue(covers(2.5,4.5))
        self.assertTrue(covers(2.5,2.5))
        self.assertFalse(covers(3.5,4.5))
        self.assertFalse(covers(9.5,4.5))

    def test_foreign_covers_boundary_has_precedence(self):
        country=module.mask_geometry(polygon(square(0,0,10,10)))
        foreign=module.mask_geometry(polygon(square(2.5,2.5,7.5,7.5),[square(4.5,4.5,5.5,5.5)]))
        accepted=lambda x,z:country(x,z) and not foreign(x,z)
        self.assertFalse(accepted(2.5,3.5))
        self.assertFalse(accepted(4.5,5.0))
        self.assertTrue(accepted(5.0,5.0))
        self.assertTrue(accepted(1.5,3.5))

    def test_multipolygon_union_preserves_shared_boundary(self):
        covers=module.mask_geometry({'type':'MultiPolygon','coordinates':[
            [square(-2.5,-1.5,0.5,1.5)],[square(0.5,-1.5,3.5,1.5)]]})
        self.assertTrue(covers(0.5,0.5))
        self.assertTrue(covers(-2.5,-1.5))
        self.assertFalse(covers(-3.5,0.5))
        reversed_mask=module.mask_geometry(polygon(list(reversed(square(-2,-2,2,2)))))
        self.assertTrue(reversed_mask(0,0))
        self.assertTrue(reversed_mask(2,0))

    def test_component_within_other_components_hole_remains_covered(self):
        covers=module.mask_geometry({'type':'MultiPolygon','coordinates':[
            [square(0,0,10,10),square(3,3,7,7)],[square(4,4,6,6)]]})
        self.assertTrue(covers(5,5))
        self.assertFalse(covers(3.5,3.5))

    def test_near_boundary_has_no_epsilon_expansion(self):
        covers=module.mask_geometry(polygon(square(0,0,10,10),[square(3,3,7,7)]))
        self.assertFalse(covers(-1e-12,5))
        self.assertFalse(covers(3+1e-12,5))
        self.assertTrue(covers(3-1e-12,5))

    def test_manifest_preserves_emitter_ownership_summary(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            def save(name,value):
                target=root/name
                target.write_text(json.dumps(value))
                return str(target)
            source=save('source.json',{'elements':[]})
            projected=save('nodes.json',[])
            mask=save('mask.json',polygon(square(0,0,8,8)))
            ownership={'policy':'deterministic-source-owner','resolvedVoxelCount':7,'sourceOwners':['way/1']}
            emitted={'runs':[],'diagnostics':[],'ownership':ownership,'fullFidelityAccepted':False}
            argv=['pipeline-roads.py','--input',source,'--projected-nodes',projected,
                '--country-mask',mask,'--tile','0','0','8','8','--output',str(root/'runs.jsonl'),
                '--manifest',str(root/'manifest.json')]
            with patch.object(sys,'argv',argv),patch.object(module,'emit_surface_runs',return_value=emitted),contextlib.redirect_stdout(io.StringIO()):
                module.main()
            receipt=json.loads((root/'manifest.json').read_text())
            self.assertEqual(receipt['ownership'],ownership)
            self.assertNotIn('runs',receipt)
            self.assertFalse(receipt['fullFidelityAccepted'])


if __name__=='__main__':
    unittest.main()
