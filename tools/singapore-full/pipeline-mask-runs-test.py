import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('mask_runs', Path(__file__).with_name('pipeline-mask-runs.py'))
mask = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mask)


def box(x0, z0, x1, z1):
    return [[x0,z0],[x1,z0],[x1,z1],[x0,z1],[x0,z0]]


class MaskTests(unittest.TestCase):
    def test_holes_exclusions_shared_boundary_all_layers_and_union(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            country = root/'country.json'
            country.write_text(json.dumps({'type':'MultiPolygon','coordinates':[[box(1000.5,1000.5,1003.5,1005.5),box(1001.25,1001.25,1002.75,1002.75)],[box(1003.5,1000.5,1005.5,1005.5)]]}))
            foreign = root/'foreign.json'
            foreign.write_text(json.dumps({'type':'Polygon','coordinates':[box(1004.5,1003.5,1005.5,1005.5)]}))
            records = [{'x':x,'z':z,'yMin':-20,'yMax':242,'featureId':f'{layer}/{x}/{z}','layer':layer,'block':'minecraft:stone'} for layer in ('building','road','terrain') for x in range(999,1007) for z in range(999,1007)]
            original = ''.join(json.dumps(r, separators=(', ', ': '))+'\r\n' for r in records).encode()
            runs = root/'runs.jsonl'; runs.write_bytes(original)
            receipt = mask.filter_runs([runs],country,root/'all.jsonl',root/'all-manifest.json',foreign)
            kept = [json.loads(line) for line in (root/'all.jsonl').read_text().splitlines()]
            expected = [r for r in records if 1000 <= r['x'] <= 1005 and 1000 <= r['z'] <= 1005 and not(1001 <= r['x'] <= 1002 and 1001 <= r['z'] <= 1002) and not(1004 <= r['x'] <= 1005 and 1003 <= r['z'] <= 1005)]
            self.assertEqual(kept,expected)
            self.assertEqual((root/'all.jsonl').read_bytes(),b''.join(line for line in original.splitlines(keepends=True) if json.loads(line) in expected))
            self.assertEqual(runs.read_bytes(),original)
            self.assertEqual(set(receipt['layers']),{'building','road','terrain'})
            self.assertEqual(receipt['blockedReasons']['foreignExclusion'],18)
            union = []
            for name, predicate in [('left',lambda r:r['x']<1003),('right',lambda r:r['x']>=1003)]:
                source=root/(name+'.jsonl'); source.write_text(''.join(json.dumps(r)+'\n' for r in records if predicate(r)))
                mask.filter_runs([source],country,root/(name+'-out.jsonl'),root/(name+'-manifest.json'),foreign)
                union.extend(json.loads(line) for line in (root/(name+'-out.jsonl')).read_text().splitlines())
            self.assertEqual(sorted(r['featureId'] for r in union),sorted(r['featureId'] for r in kept))
            with self.assertRaises(ValueError):
                mask.filter_runs([runs],country,runs,root/'bad-manifest.json')

    def test_raw_wgs84_rejected(self):
        with self.assertRaisesRegex(ValueError,'WGS84'):
            mask.mask_geometry({'type':'Polygon','coordinates':[box(103,1,104,2)]})

    def test_reverse_winding_and_foreign_hole(self):
        contains=mask.mask_geometry({'type':'Polygon','coordinates':[box(1000,1000,1010,1010)[::-1],box(1002,1002,1004,1004)[::-1]]})
        self.assertTrue(contains(1000,1001))
        self.assertTrue(contains(1002,1003))
        self.assertFalse(contains(1003,1003))
        self.assertTrue(contains(1005,1005))
        foreign=mask.mask_geometry({'type':'Polygon','coordinates':[box(1000,1000,1010,1010),box(1002,1002,1004,1004)]})
        self.assertTrue(foreign(1002,1003))
        self.assertFalse(foreign(1003,1003))

    def test_country_hole_boundary_retained_until_foreign_covers_it(self):
        with tempfile.TemporaryDirectory() as tmp:
            root=Path(tmp)
            country=root/'country.json'
            country.write_text(json.dumps({'type':'Polygon','coordinates':[box(1000,1000,1010,1010),box(1002.5,1002.5,1004.5,1004.5)]}))
            foreign=root/'foreign.json'
            foreign.write_text(json.dumps({'type':'Polygon','coordinates':[box(1002.5,1002.5,1004.5,1004.5)]}))
            runs=root/'runs.jsonl'
            records=[{'x':1002,'z':1003,'yMin':0,'yMax':242,'layer':'building','featureId':'hole-edge'},{'x':1003,'z':1003,'yMin':0,'yMax':1,'layer':'terrain','featureId':'hole-interior'},{'x':1001,'z':1003,'yMin':0,'yMax':1,'layer':'road','featureId':'country-interior'}]
            runs.write_text(''.join(json.dumps(r)+'\n' for r in records))
            without=mask.filter_runs([runs],country,root/'without.jsonl',root/'without-manifest.json')
            self.assertEqual(without['retainedRuns'],2)
            self.assertEqual([json.loads(line)['featureId'] for line in (root/'without.jsonl').read_text().splitlines()],['hole-edge','country-interior'])
            with_foreign=mask.filter_runs([runs],country,root/'with.jsonl',root/'with-manifest.json',foreign)
            self.assertEqual(with_foreign['retainedRuns'],1)
            self.assertEqual(with_foreign['blockedReasons']['foreignExclusion'],1)
            self.assertEqual([json.loads(line)['featureId'] for line in (root/'with.jsonl').read_text().splitlines()],['country-interior'])

    def test_block_centre_five_tenths_nanometre_outside_is_excluded(self):
        contains=mask.mask_geometry({'type':'Polygon','coordinates':[box(1000.5+5e-10,1000,1010,1010)]})
        self.assertFalse(contains(1000.5,1002.5))
        contains=mask.mask_geometry({'type':'Polygon','coordinates':[box(1000.5,1000,1010,1010)]})
        self.assertTrue(contains(1000.5,1002.5))


if __name__=='__main__':
    unittest.main()
