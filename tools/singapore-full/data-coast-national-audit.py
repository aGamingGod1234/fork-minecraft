"""Bounded, indexed audit of a Singapore coastal mask clipped to project scope.

Reads only the named source/mask bundle and a Changi comparison mask.
The source PBF is never opened. Use --self-test for small synthetic regressions.
"""
import argparse
from collections import Counter
import ctypes
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import sys
import threading
import time
import unittest
from unittest.mock import patch

from shapely import intersection as precision_intersection
from shapely.affinity import affine_transform
from shapely.errors import GEOSException
from shapely.geometry import Point, Polygon, LineString, box, mapping, shape
from shapely.ops import unary_union
from shapely.prepared import prep
from shapely.strtree import STRtree

# Load the owned sibling without creating a cache file beside the source.
_ring_path = Path(__file__).with_name("data-coast-audit.py")
_spec = importlib.util.spec_from_file_location("singapore_coast_ring_audit", _ring_path)
a = importlib.util.module_from_spec(_spec)
exec(compile(_ring_path.read_bytes(), str(_ring_path), "exec"), a.__dict__)

BUNDLE_NAMES = ("coast-source.json", "coast-mask-en.geojson",
                "coast-mask-xz.geojson", "coast-receipt.json")
MAX_INPUT_BYTES = 32 * 1024 * 1024


def _peak_working_set_bytes():
    if os.name != "nt":
        return None
    from ctypes import wintypes
    class Counters(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD)] + [
            (name, ctypes.c_size_t) for name in (
                "PeakWorkingSetSize", "WorkingSetSize", "QuotaPeakPagedPoolUsage",
                "QuotaPagedPoolUsage", "QuotaPeakNonPagedPoolUsage",
                "QuotaNonPagedPoolUsage", "PagefileUsage", "PeakPagefileUsage")]
    kernel = ctypes.WinDLL("kernel32")
    kernel.GetCurrentProcess.restype = wintypes.HANDLE
    counters = Counters()
    counters.cb = ctypes.sizeof(counters)
    psapi = ctypes.WinDLL("psapi")
    psapi.GetProcessMemoryInfo.argtypes = [
        wintypes.HANDLE, ctypes.POINTER(Counters), wintypes.DWORD]
    if not psapi.GetProcessMemoryInfo(kernel.GetCurrentProcess(),
                                     ctypes.byref(counters), ctypes.sizeof(counters)):
        return None
    return counters.PeakWorkingSetSize


def _pin_one_cpu():
    if os.name == "nt":
        from ctypes import wintypes
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.GetCurrentProcess.restype = wintypes.HANDLE
        handle = kernel.GetCurrentProcess()
        kernel.GetProcessAffinityMask.argtypes = [
            wintypes.HANDLE, ctypes.POINTER(ctypes.c_size_t),
            ctypes.POINTER(ctypes.c_size_t)]
        kernel.SetProcessAffinityMask.argtypes = [wintypes.HANDLE, ctypes.c_size_t]
        allowed, system = ctypes.c_size_t(), ctypes.c_size_t()
        if not kernel.GetProcessAffinityMask(handle, ctypes.byref(allowed), ctypes.byref(system)):
            raise OSError("Cannot read process CPU affinity")
        if not kernel.SetProcessAffinityMask(handle, allowed.value & -allowed.value):
            raise OSError("Cannot constrain process to one CPU")
    elif hasattr(os, "sched_getaffinity"):
        os.sched_setaffinity(0, {min(os.sched_getaffinity(0))})
    else:
        raise OSError("This platform cannot enforce the one-CPU audit limit")


def audit_documents(doc, en, xz, receipt, changi, files, *, verify_named_islands=True):
    """Return (report, ring mask) without modifying input documents or files.

    STRtree limits segment comparisons to spatial candidates. The only precision
    fallback is for independent-ring area intersections. Original source segments,
    directed probes, source identities and EN/XZ geometry retain their coordinates.
    """
    started = time.monotonic()
    if doc.get("coordinate_space","east_north") != "east_north":
        raise ValueError("National source coordinates must be east_north")
    for document,space in ((en,"east_north"),(xz,"minecraft_xz"),(changi,"east_north")):
        if document.get("coordinate_space","east_north") != space:
            raise ValueError("Mask coordinate space is incorrect")
        if space == "east_north":
            crs=document.get("crs")
            if isinstance(crs,dict):
                crs=crs.get("properties",{}).get("name")
            if crs != "EPSG:3414":
                raise ValueError("EN masks must explicitly identify EPSG:3414")
        for feature in document["features"]:
            if feature["properties"].get("class") not in ("land","sea","unknown"):
                raise ValueError("Mask classes must be land, sea or unknown")
            geometry=shape(feature["geometry"])
            if geometry.geom_type not in ("Polygon","MultiPolygon") or geometry.area <= 0 or not geometry.is_valid:
                raise ValueError("Mask features must be valid positive-area polygons; remove zero-area clipping remnants")
    report,ringmask=a.audit(doc)
    errors=[]; notices=[]
    def check(ok,message):
     if not ok: errors.append(message)
    area_tol=1e-4
    precision_fallbacks=[]
    def overlay(first,second):
     try: return first.intersection(second)
     except GEOSException as error:
      precision_fallbacks.append({"operation":"intersection","grid_size_m":1e-6,"reason":str(error)})
      return precision_intersection(first,second,grid_size=1e-6)
    domain=box(*doc["bounds"])
    features=en["features"]; geoms=[shape(f["geometry"]) for f in features]
    prepared=[prep(g) for g in geoms]; tree=STRtree(geoms)
    def locate(point):
     found=[int(i) for i in tree.query(point) if prepared[int(i)].contains(point)]
     return features[found[0]]["properties"]["class"] if len(found)==1 else "unknown"
    labels=("land","sea","unknown")
    check(all(f["properties"].get("class") in labels for f in features),"invalid_class")
    check(all(g.is_valid for g in geoms),"invalid_candidate_geometry")
    byclass={k:unary_union([g for f,g in zip(features,geoms) if f["properties"]["class"]==k]) for k in labels}
    union=unary_union(geoms)
    gap=domain.difference(union).area
    outside=union.difference(domain).area
    overlap=max(0,sum(g.area for g in geoms)-union.area)
    check(gap<area_tol and outside<area_tol and overlap<area_tol,"candidate_partition_failure")
    areas={k:g.area for k,g in byclass.items()}
    for k in ("land","sea"):
     check(abs(areas[k]-receipt["areaSquareMetres"][k])<area_tol,"receipt_class_area_mismatch")
    check(abs(areas["unknown"]-receipt["outsideScopeAreaSquareMetres"])<area_tol,"unknown_area_does_not_match_outside_scope_receipt")
    independent={k:unary_union([shape(f["geometry"]) for f in ringmask["features"] if f["properties"]["class"]==k]) for k in labels}
    contradictions={k:overlay(independent[k],byclass["sea" if k=="land" else "land"]).area for k in ("land","sea")}
    check(all(v<area_tol for v in contradictions.values()),"independent_ring_known_class_contradiction")
    ring_unknown_gap={k:overlay(independent[k],byclass["unknown"]).area for k in ("land","sea")}
    endpoint_defects=[d for component in report["components"] for d in component["defects"]
                      if domain.contains(Point(d["en"]))]
    check(not endpoint_defects,"raw_open_endpoint_inside_domain")
    segments=[]
    for w in doc["ways"]:
     for i,(first,last) in enumerate(zip(w["coordinates"],w["coordinates"][1:])):
      line=LineString([first,last]); clipped=line.intersection(domain)
      if clipped.is_empty or not clipped.length: continue
      segments.append({"way":w["id"],"nodes":w["node_ids"][i:i+2],"a":first,"b":last,"clip":clipped,"line":line,"index":i})
    check(bool(segments),"no_source_shoreline_evidence")
    shore_geoms=[s["clip"] for s in segments]; shore_tree=STRtree(shore_geoms)
    check(len(segments)==receipt["sourceSegments"],"source_segment_count_mismatch")
    crossings=[]; probes=Counter(); badprobes=[]; probe_witnesses=[]
    for i,s in enumerate(segments):
     line=s["clip"]
     for j0 in shore_tree.query(line,predicate="intersects"):
      j=int(j0)
      if j>=i: continue
      other=segments[j]; hit=line.intersection(other["clip"])
      if hit.length>0 or (domain.contains(hit) and not set(s["nodes"]).intersection(other["nodes"])):
       crossings.append({"ways":[s["way"],other["way"]],"nodes":[s["nodes"],other["nodes"]],"geometry":mapping(hit)})
     mid=line.interpolate(.5,normalized=True)
     near=[mid.distance(shore_geoms[int(j)]) for j in shore_tree.query(box(mid.x-2,mid.y-2,mid.x+2,mid.y+2)) if int(j)!=i]
     clearance=min(min(near,default=1000),mid.distance(domain.boundary),line.length)
     dx,dy=s["b"][0]-s["a"][0],s["b"][1]-s["a"][1]; length=math.hypot(dx,dy)
     for base in (.01,.1,.5):
      offset=min(base,clearance/4)
      if offset<=1e-8:
       probes["ambiguous_clearance"]+=2
       continue
      for sign,expected in ((1,"land"),(-1,"sea")):
       pt=Point(mid.x-sign*dy/length*offset,mid.y+sign*dx/length*offset)
       actual=locate(pt)
       probes["total"]+=1
       if actual=="unknown": probes["outside_scope_or_unknown"]+=1
       elif actual==expected: probes["known_agree"]+=1
       else:
        probes["known_contradiction"]+=1
        badprobes.append({"way":s["way"],"nodes":s["nodes"],"expected":expected,"actual":actual,"pointEN":[pt.x,pt.y]})
       if len(probe_witnesses)<12 and actual==expected:
        probe_witnesses.append({"way":s["way"],"nodes":s["nodes"],"expected":expected,"pointEN":[pt.x,pt.y]})
    check(probes["known_agree"]>0,"no_known_directed_evidence_inside_scope")
    check(not badprobes,"directed_source_probe_contradiction")
    check(not crossings,"raw_source_segment_intersection_conflict")
    check(not probes["ambiguous_clearance"],"ambiguous_source_probe_clearance")
    witness_counts=Counter(); witness_failures=[]
    for f,g in zip(features,geoms):
     for w in f["properties"].get("witnesses",[]):
      mid=Point(w["shoreMidpointEN"]); point=Point(w["pointEN"]); expected=w["class"]
      support=[]
      for j0 in shore_tree.query(box(mid.x-1e-7,mid.y-1e-7,mid.x+1e-7,mid.y+1e-7)):
       s=segments[int(j0)]
       if s["line"].distance(mid)>1e-7: continue
       dx,dy=s["b"][0]-s["a"][0],s["b"][1]-s["a"][1]
       cross=dx*(point.y-mid.y)-dy*(point.x-mid.x)
       support.append("land" if cross>0 else "sea" if cross<0 else "unknown")
      actual=locate(point)
      witness_counts["total"]+=1
      if support and set(support)=={expected}:
       witness_counts["source_direction_verified"]+=1
       if actual=="unknown": witness_counts["outside_scope_after_clipping"]+=1
       elif actual==expected and g.contains(point): witness_counts["retained_face_verified"]+=1
       else: witness_failures.append({"face":f.get("id"),"actual":actual,"expected":expected,"pointEN":w["pointEN"]})
      else: witness_failures.append({"face":f.get("id"),"reason":"missing_or_wrong_directed_source_support"})
    check(not witness_failures,"saved_face_witness_contradiction")
    xzbyid={f["id"]:f for f in xz["features"]}
    check(len(xzbyid)==len(xz["features"]) and len({f["id"] for f in features})==len(features),"duplicate_feature_ID")
    check(set(xzbyid)=={f["id"] for f in features},"XZ_feature_inventory_mismatch")
    xz_diffs=[]
    for f,g in zip(features,geoms):
     other=xzbyid[f["id"]]; actual=shape(other["geometry"]); expected=affine_transform(g,[1,0,0,-1,0,60000])
     diff=expected.symmetric_difference(actual).area; dist=expected.hausdorff_distance(actual)
     check(diff<area_tol and dist<1e-7 and actual.is_valid and other["properties"]["class"]==f["properties"]["class"],"XZ_geometry_or_class_mismatch")
     xz_diffs.append((diff,dist))
    changi_known=unary_union([shape(f["geometry"]) for f in changi["features"]
                             if f["properties"]["class"] in ("land","sea")])
    changi_known_overlap=changi_known.intersection(unary_union([byclass["land"],byclass["sea"]])).area
    check(changi_known_overlap>area_tol,"no_changi_known_overlap_evidence")
    changi_mismatches=[]
    for f in changi["features"]:
     kind=f["properties"]["class"]
     if kind not in ("land","sea"): continue
     opposite="sea" if kind=="land" else "land"
     difference=shape(f["geometry"]).intersection(byclass[opposite])
     if difference.area>area_tol:
      changi_mismatches.append({"changi_class":kind,"national_class":opposite,"area_m2":difference.area,"bounds":list(difference.bounds)})
    check(not changi_mismatches,"changi_known_overlap_disagreement")
    islands=[]
    for identity,label in ((469394519,"Pedra Branca"),(1359256910,"Middle Rocks"),(1359256911,"Middle Rocks")):
     raw=next((w for w in doc["ways"] if w["id"]==identity),None)
     if not raw:
      check(not verify_named_islands,"required_island_missing_from_source_subset")
      islands.append({"name":label,"way_id":identity,"status":"not_in_supplied_subset"})
      continue
     closed=raw["node_ids"][0]==raw["node_ids"][-1]
     if not closed:
      check(not verify_named_islands,"required_island_not_single_closed_raw_way")
      islands.append({"name":label,"way_id":identity,"status":"not_single_closed_raw_way"})
      continue
     trusted=next((r["status"]=="complete_directed_ring" for r in report["ways"] if r["id"]==identity),False)
     if len(raw["coordinates"])<4 or not trusted:
      check(False,"required_island_geometry_not_trusted")
      islands.append({"name":label,"way_id":identity,"status":"untrusted_raw_geometry"})
      continue
     geom=Polygon(raw["coordinates"])
     if geom.is_empty or not geom.is_valid or geom.area<=area_tol:
      check(False,"required_island_geometry_not_trusted")
      islands.append({"name":label,"way_id":identity,"status":"degenerate_raw_geometry"})
      continue
     cover={k:geom.intersection(byclass[k]).area for k in labels}
     islands.append({"name":label,"way_id":identity,"raw_area_m2":geom.area,"class_overlap_m2":cover,"status":"geometry_checked"})
     if label=="Pedra Branca": check(cover["land"]>=geom.area-area_tol,"Pedra_Branca_not_retained_as_land")
     else: check(cover["unknown"]>=geom.area-area_tol and cover["land"]<area_tol and cover["sea"]<area_tol,"Middle_Rocks_not_unknown_outside_scope")
    receipt_hashes=[]
    check({f["name"] for f in receipt["files"]}==set(BUNDLE_NAMES)-{"coast-receipt.json"},"receipt_input_file_inventory_mismatch")
    for f in receipt["files"]:
     actual=files[f["name"]]; verified=len(actual)==f["bytes"] and hashlib.sha256(actual).hexdigest()==f["sha256"]
     receipt_hashes.append({"name":f["name"],"verified":verified})
     check(verified,"receipt_file_digest_mismatch")
    check(doc["source"]==receipt["source"],"source_provenance_receipt_mismatch")
    report["national_face_audit"]={"status":"PASS" if not errors else "FAIL","errors":errors,
     "source_json_sha256":hashlib.sha256(files["coast-source.json"]).hexdigest(),
     "raw_component_count":len(report["components"]),"raw_endpoint_defects_inside_domain":endpoint_defects,"closed_ring_count":len([r for r in report["rings"] if r["valid"]]),
     "open_or_unresolved_component_count":len([c for c in report["components"] if c["status"]=="unresolved"]),
     "serialized_features":len(features),"pre_scope_faces_from_receipt":receipt["faces"],
     "class_areas_m2":areas,"domain_coverage_gap_m2":gap,"outside_domain_m2":outside,"overlap_m2":overlap,
     "known_ring_contradiction_m2":contradictions,"physical_ring_area_clipped_to_unknown_m2":ring_unknown_gap,
     "directed_probes":dict(probes),"failed_probes":badprobes,"sample_source_witnesses":probe_witnesses,
     "source_segment_intersections":crossings,"stored_witnesses":dict(witness_counts),"stored_witness_failures":witness_failures,
     "max_XZ_symmetric_difference_m2":max(t[0] for t in xz_diffs),"max_XZ_hausdorff_m":max(t[1] for t in xz_diffs),
     "changi_known_overlap_area_m2":changi_known_overlap,"changi_known_overlap_disagreements":changi_mismatches,"island_scope_checks":islands,"verified_receipt_files":receipt_hashes,
     "completeness_qualification":f"All {len(doc['ways'])} supplied coastline ways are independently accounted for and checked. Regional PBF totals and reference completeness remain producer-receipt assertions; no PBF rescan or independent omission verification was performed.",
     "scope_qualification":"Land and sea describe physical OSM coast orientation inside the accepted project scope. Unknown outside that scope does not mean sea. The accepted scope itself was not independently reconstructed from its source geometry in this audit. Inclusion/exclusion tests do not establish sovereignty.",
     "ring_qualification":"Ring-only known land or sea clipped to unknown can be intentional project-scope exclusion, including foreign coastline. It is reported separately from opposite known-class contradictions.",
     "raster_qualification":"Vector island retention does not prove retention after integer-block sampling or later rasterization.",
     "resource_usage":{"wall_seconds":time.monotonic()-started,"peak_working_set_bytes":_peak_working_set_bytes()},
     "area_comparison_tolerance_m2":area_tol,"overlay_precision_fallbacks":precision_fallbacks}
    report["input_sha256"]=hashlib.sha256(files["coast-source.json"]).hexdigest()
    return report,ringmask


def audit_bundle(bundle_directory, changi_bundle_directory):
    """Read only the five explicitly named JSON inputs, never their source PBF."""
    directory = Path(bundle_directory)
    paths = {name: directory / name for name in BUNDLE_NAMES}
    changi_path = Path(changi_bundle_directory) / "coast-mask-en.geojson"
    if sum(path.stat().st_size for path in [*paths.values(), changi_path]) > MAX_INPUT_BYTES:
        raise ValueError("Input bundle exceeds the bounded 32 MiB audit limit")
    files = {name: path.read_bytes() for name, path in paths.items()}
    doc, en, xz, receipt = [json.loads(files[name]) for name in BUNDLE_NAMES]
    changi = json.loads(changi_path.read_bytes())
    return audit_documents(doc, en, xz, receipt, changi, files)


def _validate_outputs(report_path, mask_path, bundle, changi_bundle):
    outputs = [Path(report_path).resolve(), Path(mask_path).resolve()]
    protected = {(Path(bundle) / name).resolve() for name in BUNDLE_NAMES}
    protected.add((Path(changi_bundle) / "coast-mask-en.geojson").resolve())
    if outputs[0] == outputs[1] or any(path in protected for path in outputs):
        raise ValueError("Audit outputs must be distinct from each other and all input files")
    if any(path.exists() for path in outputs):
        raise ValueError("Audit outputs already exist; choose new report and mask paths")
    return outputs


class NationalAuditTests(unittest.TestCase):
    def fixture(self):
        doc,en,xz,receipt,original,manifest,files = a.CoastAuditTests().face_bundle_fixture()
        doc["coordinate_space"] = "east_north"
        receipt["outsideScopeAreaSquareMetres"] = 0
        receipt["faces"] = len(en["features"])
        files = {name: json.dumps(value).encode() for name,value in (
            ("coast-source.json",doc),("coast-mask-en.geojson",en),
            ("coast-mask-xz.geojson",xz))}
        receipt["files"] = [{"name":name,"bytes":len(value),
                             "sha256":hashlib.sha256(value).hexdigest()}
                            for name,value in files.items()]
        files["coast-receipt.json"] = json.dumps(receipt).encode()
        changi = json.loads(json.dumps(en))
        return [doc,en,xz,receipt,changi,files]

    def test_indexed_audit_preserves_known_faces(self):
        report,_ = audit_documents(*self.fixture(),verify_named_islands=False)
        result=report["national_face_audit"]
        self.assertEqual(result["status"],"PASS")
        self.assertEqual(result["directed_probes"]["known_agree"],24)
        self.assertEqual(result["known_ring_contradiction_m2"],{"land":0,"sea":0})
        self.assertEqual(result["max_XZ_symmetric_difference_m2"],0)

    def test_wrong_changi_class_and_duplicate_xz_are_detected(self):
        args=self.fixture()
        args[4]["features"][0]["properties"]["class"]="sea"
        report,_=audit_documents(*args,verify_named_islands=False)
        self.assertIn("changi_known_overlap_disagreement",report["national_face_audit"]["errors"])
        args=self.fixture()
        args[2]["features"].insert(0,{"id":"0","properties":{"class":"sea"},
                                     "geometry":mapping(box(0,0,1,1))})
        report,_=audit_documents(*args,verify_named_islands=False)
        self.assertIn("duplicate_feature_ID",report["national_face_audit"]["errors"])

    def test_named_island_requirement_is_not_silent(self):
        report,_=audit_documents(*self.fixture())
        self.assertIn("required_island_missing_from_source_subset",
                      report["national_face_audit"]["errors"])

    def test_precision_fallback_leaves_original_coordinates_unchanged(self):
        args=self.fixture()
        before=json.dumps(args[:3],sort_keys=True)
        original_intersection=Polygon.intersection
        calls=[0]
        def fail_once(geometry,other,*extra,**kwargs):
            calls[0]+=1
            if calls[0]==1:
                raise GEOSException("synthetic precision assertion")
            return original_intersection(geometry,other,*extra,**kwargs)
        ring_result=a.audit(args[0])
        with patch.object(a,"audit",return_value=ring_result), patch.object(Polygon,"intersection",fail_once):
            report,_=audit_documents(*args,verify_named_islands=False)
        self.assertEqual(json.dumps(args[:3],sort_keys=True),before)
        self.assertEqual(len(report["national_face_audit"]["overlay_precision_fallbacks"]),1)

    def test_zero_area_clipping_remnants_are_rejected(self):
        args=self.fixture()
        args[1]["features"].append({"id":"line","properties":{"class":"land"},
                                    "geometry":mapping(LineString([(0,0),(1,1)]))})
        with self.assertRaisesRegex(ValueError,"positive-area polygons"):
            audit_documents(*args,verify_named_islands=False)

    def test_mirrored_source_and_empty_changi_cannot_pass(self):
        args=self.fixture()
        args[0]["coordinate_space"]="minecraft_xz"
        with self.assertRaisesRegex(ValueError,"source coordinates"):
            audit_documents(*args,verify_named_islands=False)
        args=self.fixture()
        args[4]["features"]=[]
        report,_=audit_documents(*args,verify_named_islands=False)
        self.assertIn("no_changi_known_overlap_evidence",report["national_face_audit"]["errors"])

    def test_degenerate_named_island_is_not_evidence(self):
        args=self.fixture()
        args[0]["ways"].append(a.way(469394519,[20,21,22,20],
                                     [(45002,41002)]*4))
        report,_=audit_documents(*args,verify_named_islands=False)
        self.assertIn("required_island_geometry_not_trusted",
                      report["national_face_audit"]["errors"])

    def test_source_and_report_output_paths_cannot_collide(self):
        with self.assertRaises(ValueError):
            _validate_outputs("bundle/coast-source.json","output/mask.json","bundle","changi")
        with self.assertRaises(ValueError):
            _validate_outputs("output/report.json","output/report.json","bundle","changi")


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle",type=Path)
    parser.add_argument("--changi-bundle",type=Path)
    parser.add_argument("--report",type=Path)
    parser.add_argument("--mask",type=Path)
    parser.add_argument("--max-seconds",type=int,default=90)
    parser.add_argument("--self-test",action="store_true")
    args=parser.parse_args()
    if args.self_test:
        result=unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(NationalAuditTests))
        return 0 if result.wasSuccessful() else 1
    if not all((args.bundle,args.changi_bundle,args.report,args.mask)):
        parser.error("--bundle, --changi-bundle, --report and --mask are required")
    if not 1 <= args.max_seconds <= 90:
        parser.error("--max-seconds must be between 1 and 90")
    outputs=_validate_outputs(args.report,args.mask,args.bundle,args.changi_bundle)
    _pin_one_cpu()
    def timeout():
        print("National audit exceeded its wall-time limit; no result is certified.",
              file=sys.stderr,flush=True)
        os._exit(124)
    watchdog=threading.Timer(args.max_seconds,timeout)
    watchdog.daemon=True
    watchdog.start()
    try:
        report,ringmask=audit_bundle(args.bundle,args.changi_bundle)
        report["national_face_audit"]["resource_usage"]["cpu_affinity_logical_processors"]=1
        for path,payload in zip(outputs,(report,ringmask)):
            path.parent.mkdir(parents=True,exist_ok=True)
            with path.open("x",encoding="utf-8") as stream:
                json.dump(payload,stream,indent=2)
                stream.write("\n")
        result=report["national_face_audit"]
        print(json.dumps({"status":result["status"],"errors":result["errors"],
                          "closed_rings":result["closed_ring_count"],
                          "unresolved_ways":report["unresolved_way_count"],
                          "directed_probes":result["directed_probes"],
                          "resource_usage":result["resource_usage"]}))
        return 0 if result["status"]=="PASS" else 1
    finally:
        watchdog.cancel()


if __name__=="__main__":
    raise SystemExit(main())
