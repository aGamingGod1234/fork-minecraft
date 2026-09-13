"""Coverage-only admission of pinned benchmark cores; never replays source or voxel scans."""
import argparse, importlib.util, json, sys
from pathlib import Path
from datetime import datetime, timezone
from shapely.ops import unary_union
from shapely.geometry import GeometryCollection
spec=importlib.util.spec_from_file_location("coverage",Path(__file__).with_name("validate-coverage.py"))
coverage=importlib.util.module_from_spec(spec);spec.loader.exec_module(coverage)

def require(condition,message):
    if not condition: raise ValueError(message)

def pinned(path,sha):
    path=Path(path).resolve()
    require(path.stat().st_size<=4*1024*1024,"metadata exceeds 4 MiB bound")
    require(coverage.digest(path).lower()==sha.lower(),"pinned metadata SHA256 mismatch: "+str(path))
    return coverage.read_json(path)

def file_map(records):
    result={}
    for r in records:
        key=r["path"].replace("\\","/").lower()
        require(key not in result,"duplicate output/source path")
        result[key]=(r["sha256"].lower(),r["bytes"])
    return result

def check_proof(gate,summary,receipt,gate_sha):
    require(summary.get("kind")=="measured-independent-benchmark-validation" and summary.get("status")=="PASS","summary not accepted typed benchmark evidence")
    require(summary.get("gateSha256","").lower()==gate_sha.lower(),"summary binds a different gate")
    require(gate.get("schemaVersion")==1 and gate.get("kind")=="independent-benchmark-result-gate" and gate.get("status")=="PASS","benchmark gate type/status invalid")
    require(gate.get("experimentalMeasurementAccepted") is True,"experimental core measurement not accepted")
    require(receipt.get("mode")=="real" and receipt.get("status")=="WRITTEN_UNACCEPTED","not a real experimental receipt")
    require(receipt.get("coreSize")==1024 and type(receipt.get("halo")) is int and receipt["halo"]>=0,"expected 1024m owned core and explicit halo")
    geometry=coverage.rect(dict(coreOrigin=receipt["coreOrigin"],coreSize=receipt["coreSize"]))
    bounds=list(geometry.bounds)
    require(gate.get("comparisonBounds")==bounds and gate.get("comparisonScope")=="full-volume","gate does not compare exact owned core")
    require(receipt.get("coreAreaM2")==geometry.area and gate.get("comparedCells")==int(geometry.area)*384 and gate.get("mismatchedCells")==0,"full-volume comparison count/mismatch failure")
    require(all(gate.get("checks",{}).get(k) is True for k in ("globalChunkCoordinates","metadata","heightmaps")) and gate.get("fileHashErrors")==[],"independent core checks did not pass")
    require(receipt.get("output",{}).get("contentHashed") is True and gate.get("outputInventorySha256")==receipt["output"].get("inventorySha256"),"prior inventory binding mismatch")
    return geometry

def admit(gate_path,gate_sha,summary_path,summary_sha):
    gate=pinned(gate_path,gate_sha);summary=pinned(summary_path,summary_sha)
    require(summary["benchmarkReceiptSha256"].lower()==gate["benchmarkReceiptSha256"].lower(),"summary/receipt gate linkage mismatch")
    receipt=pinned(summary["receiptPath"],gate["benchmarkReceiptSha256"])
    geometry=check_proof(gate,summary,receipt,gate_sha)
    ev=gate["evidence"];manifest=pinned(ev["writerManifest"]["path"],ev["writerManifest"]["sha256"])
    writer=manifest.get("writer",manifest)
    oracle=pinned(ev["oracleProof"]["path"],ev["oracleProof"]["sha256"])
    require(summary["oracleSha256"].lower()==ev["oracleProof"]["sha256"].lower(),"summary binds different oracle")
    require(oracle.get("status")=="PASS" and oracle.get("bounds")==gate["comparisonBounds"] and oracle.get("comparedCells")==gate["comparedCells"] and oracle.get("mismatchedCells")==0 and oracle.get("errors")==[],"pinned oracle does not prove exact core")
    require(all(oracle.get(k)==0 for k in ("heightmapMismatches","missingColumns","inputErrorCount","metadataErrorCount","sameLayerConflictingCells")),"pinned oracle records core failures")
    require(oracle.get("chunkCount")==geometry.area/256 and oracle.get("comparedCoreChunkCount")==geometry.area/256,"pinned oracle core chunk count mismatch")
    job=pinned(receipt["jobSpecification"]["path"],receipt["jobSpecification"]["sha256"])
    require(len(job.get("tiles",[]))==1,"benchmark job must bind one tile")
    tile=job["tiles"][0]
    require(tile["coreOrigin"]==receipt["coreOrigin"] and tile["coreSize"]==1024 and tile["halo"]==receipt["halo"],"job core/halo differs from receipt")
    output_root=Path(receipt["outputRoot"]).resolve();tile_root=(output_root/tile["id"]).resolve();world=(tile_root/"world").resolve()
    require(tile_root.is_relative_to(output_root) and world.is_relative_to(tile_root),"world path outside pinned output")
    require(Path(ev["writerManifest"]["path"]).resolve()==tile_root/"writer-manifest.json","wrong writer location")
    x,z=receipt["coreOrigin"];size=1024;h=receipt["halo"]
    require(writer.get("kind")=="global-block-run-world" and writer.get("schemaVersion")==1 and writer.get("bounds")==[x-h,z-h,x+size+h,z+size+h],"writer render extent differs from core plus halo")
    require(writer.get("coordinateFrame")==dict(blocksPerMeter=1,crs="EPSG:3414",x="easting",z="60000-northing"),"writer global grid mismatch")
    outputs=writer["outputs"]
    require(file_map(outputs)==file_map(gate["writerOutputHashes"]),"gate/writer output map mismatch")
    region_dir=writer["regionDirectory"]
    require(region_dir=="dimensions/minecraft/overworld/region" and oracle["worldSettings"].get("regionDirectory")==region_dir and oracle["worldSettings"].get("status")=="PASS","modern metadata proof mismatch")
    oracle_outputs=[dict(r,path=region_dir+"/"+r["path"]) for r in oracle["worldFiles"]]+oracle["worldSettings"]["files"]
    require(file_map(outputs)==file_map(oracle_outputs),"oracle/writer output map mismatch")
    source_map={Path(r["path"]).name.lower():r["sha256"].lower() for r in ev["sourceRuns"]}
    require(len(source_map)==len(ev["sourceRuns"]) and source_map=={r["name"].lower():r["sha256"].lower() for r in writer["inputs"]},"prior source evidence/writer hash map mismatch")
    require(source_map=={Path(r["path"]).name.lower():r["sha256"].lower() for r in oracle["sourceFiles"]},"prior oracle source hash map mismatch")
    files=[coverage.verified_world_file(world,r) for r in outputs]
    actual=list(world.rglob("*"))
    require(not any(p.is_symlink() for p in actual),"world contains links")
    require(set(files)==set(p.resolve() for p in actual if p.is_file()),"world file inventory differs from pinned writer")
    regions=[p for p in files if p.suffix.lower()==".mca"]
    require(all(p.parent==(world/region_dir).resolve() for p in regions),"unexpected region directory")
    chunks=coverage.global_core_chunks(world,gate["comparisonBounds"],regions)
    require(not chunks["missingCoreChunks"] and chunks["expectedCoreChunks"]==4096 and chunks["allocatedChunkCount"]==writer["chunkCount"],"actual owned-core headers absent or total render chunk count changed")
    return dict(id=tile["id"],coreBounds=gate["comparisonBounds"],coreAreaM2=geometry.area,
        haloMeters=h,renderBounds=writer["bounds"],gateSha256=gate_sha.lower(),summarySha256=summary_sha.lower(),
        receiptSha256=gate["benchmarkReceiptSha256"],writerManifestSha256=ev["writerManifest"]["sha256"],
        oracleSha256=ev["oracleProof"]["sha256"],actualWorldRoot=str(world),
        actualWorldOutputFilesRehashed=len(files),actualWorldOutputBytesRehashed=sum(r["bytes"] for r in outputs),
        coreChunks=chunks,priorSourceEvidence=ev["sourceRuns"],sourceRunsRehashedDuringCoverage=False,
        renewedSourceAdmission=False,coreCoverageAdmitted=True),geometry

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--benchmark",action="append",nargs=4,required=True,metavar=("GATE","GATE_SHA","SUMMARY","SUMMARY_SHA"))
    p.add_argument("--boundary",required=True);p.add_argument("--boundary-sha256",required=True)
    p.add_argument("--foreign-exclusions",required=True);p.add_argument("--foreign-sha256",required=True)
    p.add_argument("--out",required=True);a=p.parse_args()
    rows=[];gs=[];errors=[]
    for spec in a.benchmark:
        try:
            row,g=admit(*spec);rows.append(row);gs.append(g)
        except (OSError,ValueError,KeyError,TypeError) as e:errors.append(str(e))
    boundary=coverage.load_boundary(a.boundary,a.boundary_sha256)
    foreign=coverage.load_boundary(a.foreign_exclusions,a.foreign_sha256)
    union,totals=coverage.geometry_summary(gs)
    measured=coverage.boundary_measurement(boundary,union)
    effective=union.intersection(boundary).difference(foreign)
    if totals["overlapPairCount"]:errors.append("accepted core extents overlap")
    result=dict(schemaVersion=1,kind="bounded-benchmark-core-coverage",auditedUtc=datetime.now(timezone.utc).isoformat(),
        coverageEvidenceAccepted=not errors and len(rows)==len(a.benchmark),admittedCores=rows,generatedCoreUnion=totals,
        contiguous=not union.is_empty and union.geom_type=="Polygon",connectedCoreComponents=len(union.geoms) if union.geom_type=="MultiPolygon" else (0 if union.is_empty else 1),
        frozenBoundary=dict(sha256=a.boundary_sha256,kind="administrative mask including waters; not surveyed land or sovereignty acceptance",**measured),
        foreignExclusionsSha256=a.foreign_sha256,foreignOverlapM2=union.intersection(foreign).area,effectiveCountryCoreAreaM2=effective.area,
        fullWorldAccepted=False,runtimeAccepted=False,visualAccepted=False,terrainAccepted=False,facadesAccepted=False,
        countrySemanticsAccepted=False,landAreaM2=None,administrativeWaterAreaM2=None,landWaterSplit="UNMEASURED: administrative mask is not a land mask",
        sourceRunsRehashed=False,renewedSourceAdmission=False,errors=errors,
        scope="Current writer bytes and owned-core headers plus pinned prior independently accepted source/voxel proof; source runs and voxels were not rescanned. Noncontiguous experimental core union only.")
    Path(a.out).write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({k:result[k] for k in ("coverageEvidenceAccepted","generatedCoreUnion","effectiveCountryCoreAreaM2","errors")}))
    return 0 if result["coverageEvidenceAccepted"] else 1
if __name__=="__main__":sys.exit(main())
