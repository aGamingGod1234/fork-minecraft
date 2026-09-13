#!/usr/bin/env python3
"""Coverage is verified tile-core union, never halo or padded MCA area."""
import argparse, hashlib, json, math, struct, subprocess, sys
from datetime import datetime, timezone
from pathlib import Path
from pyproj import Transformer
from shapely.geometry import GeometryCollection, box, shape
from shapely.ops import transform, unary_union
from shapely.strtree import STRtree
EPS=0.000001
GRID=dict(kind="EPSG:3414",originEasting=0,originNorthing=60000,blocksPerMeter=1,xDirection="east",zDirection="south")
def read_json(p): return json.loads(Path(p).read_text(encoding="utf-8-sig"))
def digest(p):
    h=hashlib.sha256()
    with open(p,"rb") as f:
        for chunk in iter(lambda:f.read(1024*1024),b""): h.update(chunk)
    return h.hexdigest()
RAW_MASK_SHA256="5783cb68a03f3dcae77baf8c37ce96222944c03024007df45a627900d9d61978"
def boundary_semantics(boundary_sha256,audit_path=None,audit_sha256=None):
    result=dict(semanticStatus="UNREVIEWED",independentArtifactAuditAccepted=False,
        rendererAdapterVerified=False,SouthLedgeSovereigntyVerified=False,
        countryLandFilterAccepted=False,sovereigntyAccepted=False)
    if boundary_sha256.lower()==RAW_MASK_SHA256:
        result.update(semanticStatus="REJECTED_FOREIGN_LAND",
            reason="Raw OSM maritime mask includes two Malaysian Middle Rocks source polygons; historical geometry is not an accepted country land filter.")
        return result
    if not audit_path:
        result["reason"]="No pinned independent boundary artifact audit supplied."
        return result
    if not audit_sha256 or digest(audit_path).lower()!=audit_sha256.lower():
        raise ValueError("independent boundary audit SHA256 absent or mismatched")
    audit=read_json(audit_path)
    if audit.get("schemaVersion")!=1 or audit.get("auditKind")!="independent-derived-exclusion-audit":
        raise ValueError("unsupported independent boundary audit schema")
    if audit.get("artifacts",{}).get("derivedMask",{}).get("sha256","").lower()!=boundary_sha256.lower():
        raise ValueError("independent boundary audit is bound to a different mask")
    checks=audit.get("checks",{})
    required=("originalMaskUnchanged","derivedValid","exactlyTwoMiddleRocksPolygons",
        "exclusionsEqualUnionOfPinnedSourceMiddleRocksPolygons","derivedEqualsOriginalDifferenceExclusions",
        "middleRocksActualSourceCentroidsRejected","pedraBrancaEntireSourcePolygonRetained")
    accepted=(audit.get("status")=="PASS" and all(checks.get(k) is True for k in required)
        and checks.get("remainingForeignInteriorOverlapAreaDegreesSquared")==0
        and audit.get("boundaryPrecedence",{}).get("eachTestedVertexRejectedWithExclusionPrecedence") is True)
    result.update(auditSha256=digest(audit_path),independentArtifactAuditAccepted=accepted,
        semanticStatus="ARTIFACT_VERIFIED_RENDER_AND_SOVEREIGNTY_UNACCEPTED" if accepted else "INDEPENDENT_AUDIT_UNACCEPTED",
        rendererAdapterVerified=audit.get("rendererAdapterVerified") is True,
        SouthLedgeSovereigntyVerified=audit.get("SouthLedgeSovereigntyVerified") is True,
        exclusionSha256=audit.get("artifacts",{}).get("exclusions",{}).get("sha256"),
        reason="Geometry artifact audit does not certify renderer exclusion precedence or territorial sovereignty.")
    # This geometry audit has no authority to grant renderer or sovereignty acceptance.
    return result
def rect(t):
    o,s=t["coreOrigin"],t["coreSize"]
    if len(o)!=2 or any(type(n) is not int for n in o) or type(s) is not int or s<=0: raise ValueError("invalid integer core origin/size")
    return box(o[0],o[1],o[0]+s,o[1]+s)
def geometry_summary(gs):
    union=unary_union(gs) if gs else GeometryCollection(); pairs=[]
    if gs:
        tree=STRtree(gs)
        for i,g in enumerate(gs):
            for j in tree.query(g):
                j=int(j)
                if j>i and (area:=g.intersection(gs[j]).area)>EPS:
                    pairs.append(dict(a=i,b=j,overlapM2=area,duplicateCore=g.equals(gs[j])))
    return union,dict(sumCoreAreaM2=sum(g.area for g in gs),unionCoreAreaM2=union.area,
        duplicateOverlapAreaM2=sum(g.area for g in gs)-union.area,overlapPairCount=len(pairs),overlapPairs=pairs)
def load_boundary(p,sha):
    if not sha or digest(p).lower()!=sha.lower(): raise ValueError("frozen boundary SHA256 absent or mismatched")
    d=read_json(p); fs=d.get("features",[]) if d.get("type")=="FeatureCollection" else [d]; gs=[]
    for f in fs:
        g=shape(f["geometry"] if f.get("type")=="Feature" else f)
        if g.geom_type not in ("Polygon","MultiPolygon") or g.is_empty or not g.is_valid: raise ValueError("boundary must contain valid nonempty polygons")
        gs.append(g)
    if not gs: raise ValueError("empty boundary")
    projected=transform(Transformer.from_crs("EPSG:4326","EPSG:3414",always_xy=True).transform,unary_union(gs))
    return transform(lambda e,n,z=None:(e,60000-n),projected)
def boundary_measurement(boundary,generated):
    cs=list(boundary.geoms) if boundary.geom_type=="MultiPolygon" else [boundary]; rows=[]
    for i,c in enumerate(cs):
        covered=c.intersection(generated).area
        rows.append(dict(component=i,boundsXZ=list(c.bounds),areaM2=c.area,generatedCoreCoveredM2=covered,
            missingM2=c.difference(generated).area,coveragePercent=100*covered/c.area))
    return dict(areaM2=boundary.area,coveredM2=boundary.intersection(generated).area,
        missingM2=boundary.difference(generated).area,coveragePercent=100*boundary.intersection(generated).area/boundary.area,
        outsideBoundaryCoreM2=generated.difference(boundary).area,components=rows)
def core_chunks(m,p):
    t=m["tile"]; s,h=t["coreSize"],t["halo"]; root=p.parent/m["output"]["worldPath"]; headers={}; missing=[]
    # Inspect only local chunks intersecting the core. Other headers and halos add no area.
    first,last=math.floor(h/16),math.ceil((h+s)/16)
    for z in range(first,last):
        for x in range(first,last):
            name="region/r.%d.%d.mca"%(x//32,z//32)
            if name not in headers:
                with open(root/name,"rb") as f: headers[name]=f.read(4096)
            head=headers[name]; slot=x%32+(z%32)*32
            if len(head)!=4096 or struct.unpack_from(">I",head,slot*4)[0]==0: missing.append([x,z])
    return dict(expectedCoreChunks=(last-first)**2,missingCoreChunks=missing)
def verify_tile(p):
    p=Path(p).resolve(); row=dict(manifest=str(p),eligible=False)
    try:
        m=read_json(p); row.update(id=m.get("id",p.parent.name),manifestSha256=digest(p),core=m["tile"])
        if any(m.get("grid",{}).get(k)!=v for k,v in GRID.items()): raise ValueError("grid differs from required national grid")
        if m.get("status")!="generated": raise ValueError("tile status is not generated")
        g=rect(m["tile"])
        run=subprocess.run(["node",str(Path(__file__).with_name("validate-fidelity.mjs")),"manifest",str(p)],capture_output=True,text=True,timeout=60)
        result=json.loads(run.stdout); row["fidelityStructuralErrors"]=result.get("errors",[])
        if run.returncode or result.get("initialMilestoneAccepted") is not True: raise ValueError("independent manifest validation failed")
        if m.get("mapping",{}).get("adapter")!="global-grid-to-Arnis-local": raise ValueError("unsupported coordinate adapter")
        row["coreChunks"]=core_chunks(m,p)
        if row["coreChunks"]["missingCoreChunks"]: raise ValueError("core chunk headers absent")
        row.update(regionCount=m["output"]["regionCount"],allocatedChunkCount=m["output"]["chunkCount"],eligible=True)
        return row,g
    except (OSError,ValueError,KeyError,TypeError,subprocess.SubprocessError) as e: row["error"]=str(e); return row,None
def verified_world_file(root,record):
    relative=Path(record["path"])
    filename=(root/relative).resolve()
    if relative.is_absolute() or not filename.is_relative_to(root.resolve()):
        raise ValueError("world output path escapes candidate root")
    if type(record.get("bytes")) is not int or filename.stat().st_size!=record["bytes"]:
        raise ValueError("world output byte count mismatch: "+record["path"])
    if digest(filename).lower()!=record.get("sha256","").lower():
        raise ValueError("world output SHA256 mismatch: "+record["path"])
    return filename

def global_core_chunks(root,bounds,region_files):
    headers={}; allocated=0
    for filename in region_files:
        with open(filename,"rb") as handle: header=handle.read(4096)
        size=filename.stat().st_size
        if size<8192 or len(header)!=4096: raise ValueError("incomplete MCA header")
        sector_ranges=[]
        for slot in range(1024):
            packed=struct.unpack_from(">I",header,slot*4)[0]
            if not packed: continue
            allocated+=1; offset,count=packed>>8,packed&255
            if offset<2 or count==0 or (offset+count)*4096>size:
                raise ValueError("MCA chunk sector bounds invalid")
            sector_ranges.append((offset,offset+count))
        sector_ranges.sort()
        if any(b[0]<a[1] for a,b in zip(sector_ranges,sector_ranges[1:])):
            raise ValueError("overlapping MCA chunk sector ranges")
        headers[filename.name]=header
    first_x,first_z,last_x,last_z=bounds
    missing=[]; expected=0
    for z in range(math.floor(first_z/16),math.ceil(last_z/16)):
        for x in range(math.floor(first_x/16),math.ceil(last_x/16)):
            expected+=1; header=headers.get("r.%d.%d.mca"%(x//32,z//32)); slot=x%32+(z%32)*32
            if header is None or struct.unpack_from(">I",header,slot*4)[0]==0:
                missing.append([x,z])
    return dict(expectedCoreChunks=expected,allocatedChunkCount=allocated,missingCoreChunks=missing)

def verify_joined(manifest_path,manifest_sha256,gate_path,gate_sha256,world_root=None):
    p=Path(manifest_path).resolve(); row=dict(manifest=str(p),id="joined:"+p.parent.name,
        evidenceKind="global-block-run-world",eligible=False)
    root=Path(world_root).resolve() if world_root else p.parent
    row["worldRoot"]=str(root)
    try:
        if not manifest_sha256 or digest(p).lower()!=manifest_sha256.lower():
            raise ValueError("writer manifest SHA256 absent or mismatched")
        if not gate_path or not gate_sha256 or digest(gate_path).lower()!=gate_sha256.lower():
            raise ValueError("independent joined gate SHA256 absent or mismatched")
        if gate_sha256.lower()=="13b091b7e121c55fc00d0ef090493bd1fc9d03729128204b7c3ac767a28ee584":
            raise ValueError("original combined gate revoked for modern spawn/layout; historical block geometry only")
        m,g=read_json(p),read_json(gate_path)
        if m.get("schemaVersion")!=1 or m.get("kind")!="global-block-run-world":
            raise ValueError("unsupported joined writer schema")
        frame=dict(blocksPerMeter=1,crs="EPSG:3414",x="easting",z="60000-northing")
        if m.get("coordinateFrame")!=frame: raise ValueError("joined world uses the wrong global grid")
        bounds=m.get("bounds")
        if not isinstance(bounds,list) or len(bounds)!=4 or any(type(v) is not int for v in bounds) or bounds[0]>=bounds[2] or bounds[1]>=bounds[3]:
            raise ValueError("invalid joined core bounds")
        if m.get("verticalRange")!=[-64,320]: raise ValueError("unsupported joined vertical range")
        geometry=box(*bounds)
        if g.get("kind")!="independent-joined-strip-structural-gate" or g.get("status")!="PASS":
            raise ValueError("independent joined structural gate did not pass")
        if g.get("writerManifestSha256","").lower()!=manifest_sha256.lower() or g.get("bounds")!=bounds:
            raise ValueError("independent joined gate is bound to a different writer or core bounds")
        if g.get("chunkCount")!=m.get("chunkCount") or type(m.get("chunkCount")) is not int:
            raise ValueError("independent joined chunk count mismatch")
        if g.get("comparedCells")!=int(geometry.area)*384 or g.get("mismatchedCells")!=0:
            raise ValueError("independent oracle did not compare the entire core volume with zero mismatches")
        if type(g.get("seamComparedCells")) is not int or g["seamComparedCells"]<=0 or g.get("seamMismatchedCells")!=0:
            raise ValueError("independent seam comparison missing or failed")
        modern=g.get("metadataProofSha256") is not None
        if m.get("dataVersion")==4790 and not modern:
            raise ValueError("modern writer requires corrected typed metadata proof")
        if g.get("heightmapMismatches")!=0 or g.get("spawnClear") is not True:
            raise ValueError("independent heightmap or spawn gate failed")
        if modern:
            if m.get("dataVersion")!=4790 or m.get("minecraftTarget")!="26.1.2" or m.get("dataPacks")!=["vanilla"]:
                raise ValueError("modern writer version or vanilla datapacks not bound")
            if g.get("errors")!=[] or g.get("spawnSchema")!="modern Data.spawn.pos" or g.get("spawn")!=m.get("spawn"):
                raise ValueError("modern metadata gate errors or spawn schema mismatch")
            if m.get("regionDirectory")!="dimensions/minecraft/overworld/region" or g.get("regionDirectory")!=m["regionDirectory"]:
                raise ValueError("modern metadata gate region layout mismatch")
            required_hashes=("priorGeometryOracleSha256","metadataProofSha256")
        else:
            if g.get("fileHashErrors")!=[]: raise ValueError("independent file inventory gate failed")
            required_hashes=("oracleSha256","seamSha256","pipelineResultSha256","joinReceiptSha256")
        for key in required_hashes:
            value=g.get(key,"")
            if not isinstance(value,str) or len(value)!=64 or any(c not in "0123456789abcdefABCDEF" for c in value):
                raise ValueError("independent gate missing evidence hash: "+key)
        region_directory=m.get("regionDirectory","region")
        if region_directory not in ("region","dimensions/minecraft/overworld/region"): raise ValueError("unsupported declared overworld region directory")
        outputs=m.get("outputs")
        if not isinstance(outputs,list) or not outputs: raise ValueError("joined output file inventory absent")
        names=[r["path"] for r in outputs]
        if len(set(names))!=len(names) or "level.dat" not in names: raise ValueError("invalid joined output inventory")
        if modern:
            if "data/minecraft/world_gen_settings.dat" not in names:
                raise ValueError("modern world generation settings output is unbound")
            expected_regions={r["path"]:r["sha256"].lower() for r in outputs if r["path"].lower().endswith(".mca")}
            identities=g.get("regionIdentity",[])
            if len(identities)!=len(expected_regions) or {r["newPath"]:r["sha256"].lower() for r in identities}!=expected_regions:
                raise ValueError("corrected gate does not bind every current region identity")
        files=[verified_world_file(root,r) for r in outputs]
        regions=[f for f in files if f.suffix.lower()==".mca"]
        if not regions or any(f.parent!=(root/region_directory).resolve() for f in regions) or set(regions)!=set(f.resolve() for f in root.rglob("*.mca")):
            raise ValueError("joined region inventory differs from actual MCA files")
        chunks=global_core_chunks(root,bounds,regions)
        if chunks["missingCoreChunks"] or chunks["allocatedChunkCount"]!=chunks["expectedCoreChunks"] or chunks["allocatedChunkCount"]!=m["chunkCount"]:
            raise ValueError("global core chunk headers absent or padded/outside declared bounds")
        row.update(eligible=True,manifestSha256=digest(p),independentGateSha256=digest(gate_path),
            core=dict(boundsXZ=bounds,areaM2=geometry.area),coreChunks=chunks,regionCount=len(regions),regionDirectory=region_directory,
            independentStructuralGateAccepted=True,comparedCells=g["comparedCells"],mismatchedCells=0,
            seamComparedCells=g["seamComparedCells"],seamMismatchedCells=0,
            oracleSha256=g["priorGeometryOracleSha256"] if modern else g["oracleSha256"],
            metadataProofSha256=g.get("metadataProofSha256"),seamSha256=g.get("seamSha256"),
            seamProofBinding="pinned corrected gate inherits unchanged region and seam cell proof" if modern else "pinned independent seam report",
            evidenceScope="corrected modern metadata plus byte-identical region block proof" if modern else "independent structural proof",
            runtimeLoadAccepted=False,actualTerrainAccepted=False,fullWorldAccepted=False)
        return row,geometry
    except (OSError,ValueError,KeyError,TypeError) as error:
        row["error"]=str(error); return row,None

def expected_coverage(p,generated,boundary,boundary_sha256=None):
    d=read_json(p); tiles=d if isinstance(d,list) else d.get("tiles",d.get("expectedCores"))
    if isinstance(d,dict) and boundary_sha256 and d.get("maskSha256","").lower()!=boundary_sha256.lower(): raise ValueError("expected core manifest is not bound to the current boundary SHA256")
    if not isinstance(tiles,list): raise ValueError("expected manifest must be array or have tiles array")
    rows=[]; gs=[]
    for i,t in enumerate(tiles):
        g=rect(t["tile"] if isinstance(t.get("tile"),dict) else t); gs.append(g); target=g.intersection(boundary) if boundary is not None else g
        missing=target.difference(generated).area
        if missing>EPS: rows.append(dict(id=t.get("id",i),missingM2=missing))
    union,totals=geometry_summary(gs)
    return dict(manifestSha256=digest(p),expectedTileCount=len(tiles),missingTileCount=len(rows),missingTileSample=rows[:100],
        missingTileSampleTruncated=len(rows)>100,**totals,plannedExtentMissingFromGeneratedM2=union.difference(generated).area,
        boundaryMissingFromPlanM2=boundary.difference(union).area if boundary is not None else None,
        status="extent planning only; expected cores are not acquired or generated evidence")
def source_inventory(p,root):
    rows=[]
    for s in read_json(p).get("sources",[]):
        if not s.get("download"): continue
        row=dict(id=s.get("id"),acquiredFileVerified=False)
        try:
            f=Path(root)/s["filename"]; receipt=f.parent/(s["id"]+".receipt.json"); sha=s.get("sha256")
            if receipt.exists(): sha=sha or read_json(receipt).get("sha256")
            row["bytes"]=f.stat().st_size
            if sha:
                row["sha256"]=digest(f); row["acquiredFileVerified"]=row["sha256"].lower()==sha.lower()
                if not row["acquiredFileVerified"]: row["reason"]="SHA256 mismatch"
            else: row["reason"]="no declared SHA256 available"
        except (OSError,ValueError,KeyError) as e: row["reason"]=str(e)
        rows.append(row)
    return dict(manifestSha256=digest(p),files=rows,geometricCoverageM2=None,
        status="file acquisition only; file bounds do not prove source feature completeness")
def audit(a):
    errors=[]; paths=[]
    for p in a.tiles:
        p=Path(p); paths.extend(sorted(p.glob("*/tile-manifest.json")) if p.is_dir() else [p])
    rows=[]; gs=[]
    for p in paths:
        row,g=verify_tile(p); rows.append(row)
        if g is not None: gs.append(g)
    if getattr(a,"joined_manifest",None):
        row,g=verify_joined(a.joined_manifest,a.joined_manifest_sha256,a.joined_gate,a.joined_gate_sha256,getattr(a,"joined_world_root",None)); rows.append(row)
        if g is not None: gs.append(g)
    generated,totals=geometry_summary(gs)
    if not gs: errors.append("no verified generated cores")
    if any(not r["eligible"] for r in rows): errors.append("one or more tiles failed verification")
    if totals["overlapPairCount"]: errors.append("verified cores overlap or duplicate")
    ids=[r.get("id") for r in rows]
    if len(set(ids))!=len(ids): errors.append("duplicate tile IDs")
    boundary=None; measured=dict(status="MISSING_UNACCEPTED",coveragePercent=None)
    try:
        if not a.boundary: raise ValueError("no frozen national boundary; whole-Singapore coverage unaccepted")
        boundary=load_boundary(a.boundary,a.boundary_sha256)
        measured=dict(status="MEASURED",sha256=digest(a.boundary),kind="OSM administrative mask including water; not a coastline or surveyed land mask",**boundary_measurement(boundary,generated))
        measured.update(boundary_semantics(measured["sha256"],getattr(a,"boundary_audit",None),getattr(a,"boundary_audit_sha256",None)))
        if not measured["countryLandFilterAccepted"]: errors.append("boundary country land filter semantics remain unaccepted")
        if measured["missingM2"]>EPS: errors.append("frozen national boundary has missing generated core coverage")
    except (OSError,ValueError,KeyError,TypeError) as e: errors.append(str(e))
    planned=dict(status="MISSING",expectedTileCount=None)
    if a.expected:
        try: planned=expected_coverage(a.expected,generated,boundary,measured.get("sha256"))
        except (OSError,ValueError,KeyError,TypeError) as e: errors.append("expected coverage: "+str(e))
    acquired=dict(status="NOT_AUDITED_IN_THIS_RUN",geometricCoverageM2=None)
    if a.source_manifest:
        try: acquired=source_inventory(a.source_manifest,a.source_root)
        except (OSError,ValueError,KeyError,TypeError) as e: errors.append("source inventory: "+str(e))
    return dict(schemaVersion=1,auditedUtc=datetime.now(timezone.utc).isoformat(),grid=GRID,plannedExtent=planned,acquiredSource=acquired,
        generatedWorld=dict(verifiedCoreCount=len(gs),**totals,tiles=rows,coverageBasis="union of verified half-open tile cores only; exclude halos and padded MCA/chunk extents"),
        frozenBoundary=measured,coverageGateAccepted=not errors,fullWorldAccepted=False,releaseAccepted=False,assemblyAccepted=False,
        runtimeLoadAccepted=False,actualTerrainAccepted=False,
        errors=errors,warnings=["Generated coverage verifies source/output hashes, projection and core chunk headers; terrain, facade and every-feature fidelity remain unverified.",
        "Joined structural and seam evidence is scoped to the pinned candidate; nationwide assembly remains unaccepted." if getattr(a,"joined_manifest",None) else "Separate local tile worlds. Assembly and seam acceptance require independent measured evidence."])
def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--joined-world-root")
    p.add_argument("--joined-manifest"); p.add_argument("--joined-manifest-sha256")
    p.add_argument("--joined-gate"); p.add_argument("--joined-gate-sha256")
    p.add_argument("--tiles",action="append",default=[],help="tile manifest or parent of tile directories; repeatable")
    p.add_argument("--boundary-audit"); p.add_argument("--boundary-audit-sha256")
    p.add_argument("--boundary"); p.add_argument("--boundary-sha256"); p.add_argument("--expected",help="expected core array or object with tiles array")
    p.add_argument("--source-manifest"); p.add_argument("--source-root",default="."); p.add_argument("--out")
    a=p.parse_args(); result=audit(a); text=json.dumps(result,indent=2)+"\n"
    if a.out: Path(a.out).write_text(text,encoding="utf-8")
    print(text); return 0 if result["coverageGateAccepted"] else 1
if __name__=="__main__": sys.exit(main())
