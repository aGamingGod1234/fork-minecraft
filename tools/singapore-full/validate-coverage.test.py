import importlib.util, json, struct, tempfile, unittest
from pathlib import Path
from types import SimpleNamespace
from shapely.geometry import MultiPolygon, Polygon, box

spec=importlib.util.spec_from_file_location("coverage",Path(__file__).with_name("validate-coverage.py"))
coverage=importlib.util.module_from_spec(spec); spec.loader.exec_module(coverage)

class CoverageTests(unittest.TestCase):
    def test_adjacent_cores_do_not_count_halos_or_region_padding(self):
        cores=[coverage.rect(dict(coreOrigin=[29712,30496],coreSize=128,halo=32)),
               coverage.rect(dict(coreOrigin=[29840,30496],coreSize=128,halo=32))]
        union,report=coverage.geometry_summary(cores)
        self.assertEqual(report["unionCoreAreaM2"],32768)
        self.assertEqual(report["overlapPairCount"],0)
        self.assertNotEqual(union.area,2*512*512)

    def test_duplicate_and_partial_overlap_union(self):
        union,report=coverage.geometry_summary([box(0,0,10,10),box(0,0,10,10),box(5,0,15,10)])
        self.assertEqual(union.area,150)
        self.assertEqual(report["duplicateOverlapAreaM2"],150)
        self.assertTrue(any(p["duplicateCore"] for p in report["overlapPairs"]))

    def test_offshore_component_and_hole_are_preserved(self):
        mainland=Polygon([(0,0),(10,0),(10,10),(0,10)],holes=[[(2,2),(4,2),(4,4),(2,4)]])
        boundary=MultiPolygon([mainland,box(20,20,22,22)])
        report=coverage.boundary_measurement(boundary,box(0,0,10,10))
        self.assertEqual(report["areaM2"],100)
        self.assertEqual(report["coveredM2"],96)
        self.assertEqual(report["missingM2"],4)
        self.assertEqual(report["components"][1]["coveragePercent"],0)

    def test_expected_partial_core_remains_missing(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/"expected.json"
            p.write_text(json.dumps({"expectedCores":[{"id":"a","tile":[0,0],"coreOrigin":[0,0],"coreSize":10},{"id":"b","coreOrigin":[10,0],"coreSize":10}]}))
            report=coverage.expected_coverage(p,box(0,0,5,10),box(0,0,20,10))
            self.assertEqual(report["missingTileCount"],2)
            self.assertEqual(report["plannedExtentMissingFromGeneratedM2"],150)
            self.assertEqual(report["boundaryMissingFromPlanM2"],0)

    def test_boundary_without_frozen_hash_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/"boundary.json"; p.write_text('{"type":"Polygon","coordinates":[]}')
            with self.assertRaisesRegex(ValueError,"SHA256"): coverage.load_boundary(p,None)

    def test_padded_headers_cannot_hide_missing_core_chunk(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); (root/"world/region").mkdir(parents=True)
            header=bytearray(struct.pack(">I",513)*1024)
            struct.pack_into(">I",header,(2+2*32)*4,0)
            (root/"world/region/r.0.0.mca").write_bytes(header)
            report=coverage.core_chunks({"tile":{"coreSize":128,"halo":32},"output":{"worldPath":"world"}},root/"manifest.json")
            self.assertEqual(report["expectedCoreChunks"],64)
            self.assertEqual(report["missingCoreChunks"],[[2,2]])

    def test_raw_maritime_mask_is_semantically_rejected(self):
        result=coverage.boundary_semantics(coverage.RAW_MASK_SHA256)
        self.assertEqual(result["semanticStatus"],"REJECTED_FOREIGN_LAND")
        self.assertFalse(result["countryLandFilterAccepted"])

    def test_unreviewed_mask_is_not_country_accepted(self):
        result=coverage.boundary_semantics("a"*64)
        self.assertEqual(result["semanticStatus"],"UNREVIEWED")
        self.assertFalse(result["sovereigntyAccepted"])

    def test_pinned_geometry_audit_does_not_accept_renderer_or_sovereignty(self):
        with tempfile.TemporaryDirectory() as folder:
            p=Path(folder)/"audit.json"
            checks={k:True for k in ("originalMaskUnchanged","derivedValid","exactlyTwoMiddleRocksPolygons",
                "exclusionsEqualUnionOfPinnedSourceMiddleRocksPolygons","derivedEqualsOriginalDifferenceExclusions",
                "middleRocksActualSourceCentroidsRejected","pedraBrancaEntireSourcePolygonRetained")}
            checks["remainingForeignInteriorOverlapAreaDegreesSquared"]=0
            report=dict(schemaVersion=1,auditKind="independent-derived-exclusion-audit",status="PASS",
                artifacts={"derivedMask":{"sha256":"a"*64}},checks=checks,
                boundaryPrecedence={"eachTestedVertexRejectedWithExclusionPrecedence":True},
                rendererAdapterVerified=False,SouthLedgeSovereigntyVerified=False)
            p.write_text(json.dumps(report))
            result=coverage.boundary_semantics("a"*64,p,coverage.digest(p))
            self.assertTrue(result["independentArtifactAuditAccepted"])
            self.assertFalse(result["countryLandFilterAccepted"])
            self.assertFalse(result["sovereigntyAccepted"])
            with self.assertRaisesRegex(ValueError,"different mask"):
                coverage.boundary_semantics("b"*64,p,coverage.digest(p))
            with self.assertRaisesRegex(ValueError,"SHA256"):
                coverage.boundary_semantics("a"*64,p,"0"*64)

    def joined_fixture(self,folder):
        root=Path(folder); (root/"region").mkdir()
        header=bytearray(12288); struct.pack_into(">I",header,0,513)
        (root/"region/r.1.1.mca").write_bytes(header)
        (root/"level.dat").write_bytes(b"fixture")
        outputs=[dict(path=name,bytes=(root/name).stat().st_size,sha256=coverage.digest(root/name)) for name in ("region/r.1.1.mca","level.dat")]
        manifest=dict(schemaVersion=1,kind="global-block-run-world",bounds=[512,512,528,528],
            coordinateFrame=dict(blocksPerMeter=1,crs="EPSG:3414",x="easting",z="60000-northing"),
            verticalRange=[-64,320],chunkCount=1,outputs=outputs)
        mp=root/"writer-manifest.json"; mp.write_text(json.dumps(manifest))
        gate=dict(kind="independent-joined-strip-structural-gate",status="PASS",bounds=manifest["bounds"],
            writerManifestSha256=coverage.digest(mp),chunkCount=1,comparedCells=256*384,mismatchedCells=0,
            seamComparedCells=100,seamMismatchedCells=0,heightmapMismatches=0,spawnClear=True,fileHashErrors=[],
            oracleSha256="a"*64,seamSha256="b"*64,pipelineResultSha256="c"*64,joinReceiptSha256="d"*64)
        gp=root/"gate.json"; gp.write_text(json.dumps(gate))
        return mp,gp,manifest,gate

    def test_joined_pass_counts_global_core_and_keeps_runtime_unaccepted(self):
        with tempfile.TemporaryDirectory() as folder:
            mp,gp,_,_=self.joined_fixture(folder)
            row,g=coverage.verify_joined(mp,coverage.digest(mp),gp,coverage.digest(gp))
            self.assertTrue(row["eligible"])
            self.assertEqual(g.area,256)
            self.assertEqual(row["coreChunks"]["expectedCoreChunks"],1)
            self.assertFalse(row["runtimeLoadAccepted"])
            self.assertFalse(row["actualTerrainAccepted"])
            self.assertFalse(row["fullWorldAccepted"])

    def test_joined_rejects_unrelated_pinned_pass_gate(self):
        with tempfile.TemporaryDirectory() as folder:
            mp,gp,m,gate=self.joined_fixture(folder)
            gate["writerManifestSha256"]="0"*64; gp.write_text(json.dumps(gate))
            row,g=coverage.verify_joined(mp,coverage.digest(mp),gp,coverage.digest(gp))
            self.assertFalse(row["eligible"]); self.assertIsNone(g)
            self.assertIn("different writer",row["error"])

    def test_joined_rejects_wrong_grid_with_refreshed_hashes(self):
        with tempfile.TemporaryDirectory() as folder:
            mp,gp,m,gate=self.joined_fixture(folder)
            m["coordinateFrame"]["z"]="northing"; mp.write_text(json.dumps(m))
            gate["writerManifestSha256"]=coverage.digest(mp); gp.write_text(json.dumps(gate))
            row,g=coverage.verify_joined(mp,coverage.digest(mp),gp,coverage.digest(gp))
            self.assertFalse(row["eligible"]); self.assertIn("wrong global grid",row["error"])

    def test_joined_rejects_output_mutation_after_pass_gate(self):
        with tempfile.TemporaryDirectory() as folder:
            mp,gp,_,_=self.joined_fixture(folder)
            (Path(folder)/"level.dat").write_bytes(b"changed")
            row,g=coverage.verify_joined(mp,coverage.digest(mp),gp,coverage.digest(gp))
            self.assertFalse(row["eligible"]); self.assertIn("SHA256 mismatch",row["error"])

    def test_joined_rejects_wrong_global_chunk_even_with_same_count(self):
        with tempfile.TemporaryDirectory() as folder:
            mp,gp,m,gate=self.joined_fixture(folder)
            region=Path(folder)/"region/r.1.1.mca"
            header=bytearray(region.read_bytes()); struct.pack_into(">I",header,0,0); struct.pack_into(">I",header,4,513)
            region.write_bytes(header); m["outputs"][0]["sha256"]=coverage.digest(region)
            mp.write_text(json.dumps(m)); gate["writerManifestSha256"]=coverage.digest(mp); gp.write_text(json.dumps(gate))
            row,g=coverage.verify_joined(mp,coverage.digest(mp),gp,coverage.digest(gp))
            self.assertFalse(row["eligible"]); self.assertIn("global core chunk headers",row["error"])

    def test_joined_rejects_aliased_chunk_sector_ranges(self):
        with tempfile.TemporaryDirectory() as folder:
            mp,gp,m,gate=self.joined_fixture(folder)
            region=Path(folder)/"region/r.1.1.mca"; header=bytearray(region.read_bytes())
            struct.pack_into(">I",header,4,513); region.write_bytes(header)
            m["outputs"][0]["sha256"]=coverage.digest(region); m["bounds"]=[512,512,544,528]; m["chunkCount"]=2
            mp.write_text(json.dumps(m)); gate.update(writerManifestSha256=coverage.digest(mp),bounds=m["bounds"],chunkCount=2,comparedCells=512*384)
            gp.write_text(json.dumps(gate))
            row,g=coverage.verify_joined(mp,coverage.digest(mp),gp,coverage.digest(gp))
            self.assertFalse(row["eligible"]); self.assertIn("overlapping MCA chunk sector ranges",row["error"])

    def test_joined_rejects_unlisted_mca_outside_terrain_region(self):
        with tempfile.TemporaryDirectory() as folder:
            mp,gp,_,_=self.joined_fixture(folder)
            extra=Path(folder)/"entities"; extra.mkdir()
            (extra/"r.0.0.mca").write_bytes(bytes(8192))
            row,g=coverage.verify_joined(mp,coverage.digest(mp),gp,coverage.digest(gp))
            self.assertFalse(row["eligible"]); self.assertIn("region inventory",row["error"])

    def test_joined_supports_declared_modern_overworld_region_directory(self):
        with tempfile.TemporaryDirectory() as folder:
            mp,gp,m,gate=self.modern_joined_fixture(folder)
            row,g=coverage.verify_joined(mp,coverage.digest(mp),gp,coverage.digest(gp))
            self.assertTrue(row["eligible"]); self.assertEqual(g.area,256)
            self.assertEqual(row["metadataProofSha256"],gate["metadataProofSha256"])
            self.assertFalse(row["runtimeLoadAccepted"])

    def modern_joined_fixture(self,folder):
        mp,gp,m,gate=self.joined_fixture(folder)
        root=Path(folder); destination=root/"dimensions/minecraft/overworld/region"; destination.mkdir(parents=True)
        old=root/"region/r.1.1.mca"; new=destination/old.name; old.replace(new)
        m.update(regionDirectory="dimensions/minecraft/overworld/region",dataVersion=4790,
            minecraftTarget="26.1.2",dataPacks=["vanilla"],spawn=[512,1,512])
        m["outputs"][0]["path"]=new.relative_to(root).as_posix()
        settings=root/"data/minecraft/world_gen_settings.dat"; settings.parent.mkdir(parents=True); settings.write_bytes(b"settings")
        m["outputs"].append(dict(path=settings.relative_to(root).as_posix(),bytes=settings.stat().st_size,sha256=coverage.digest(settings)))
        mp.write_text(json.dumps(m))
        for key in ("oracleSha256","seamSha256","pipelineResultSha256","joinReceiptSha256","fileHashErrors"): gate.pop(key)
        gate.update(writerManifestSha256=coverage.digest(mp),priorGeometryOracleSha256="a"*64,
            metadataProofSha256="b"*64,spawn=m["spawn"],spawnSchema="modern Data.spawn.pos",errors=[],
            regionDirectory=m["regionDirectory"],regionIdentity=[dict(newPath=m["outputs"][0]["path"],sha256=m["outputs"][0]["sha256"])])
        gp.write_text(json.dumps(gate))
        return mp,gp,m,gate

    def test_joined_rejects_unbound_modern_settings(self):
        with tempfile.TemporaryDirectory() as folder:
            mp,gp,m,gate=self.modern_joined_fixture(folder)
            m["outputs"]=m["outputs"][:-1]; mp.write_text(json.dumps(m))
            gate["writerManifestSha256"]=coverage.digest(mp); gp.write_text(json.dumps(gate))
            row,g=coverage.verify_joined(mp,coverage.digest(mp),gp,coverage.digest(gp))
            self.assertFalse(row["eligible"]); self.assertIn("settings output is unbound",row["error"])

    def test_joined_rejects_modern_region_identity_mismatch(self):
        with tempfile.TemporaryDirectory() as folder:
            mp,gp,m,gate=self.modern_joined_fixture(folder)
            gate["regionIdentity"][0]["sha256"]="0"*64; gp.write_text(json.dumps(gate))
            row,g=coverage.verify_joined(mp,coverage.digest(mp),gp,coverage.digest(gp))
            self.assertFalse(row["eligible"]); self.assertIn("current region identity",row["error"])

    def test_missing_boundary_never_passes(self):
        result=coverage.audit(SimpleNamespace(tiles=[],boundary=None,boundary_sha256=None,expected=None,source_manifest=None))
        self.assertFalse(result["coverageGateAccepted"])
        self.assertFalse(result["fullWorldAccepted"])
        self.assertEqual(result["frozenBoundary"]["status"],"MISSING_UNACCEPTED")
if __name__=="__main__": unittest.main()
