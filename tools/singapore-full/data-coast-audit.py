"""Independent raw-node coastline closure and conservative land/sea audit.

No endpoint snapping, way reversal, polygon repair, or artificial closing edges.
Run --self-test for tiny synthetic topology fixtures (no source/world reads).
"""
import argparse
from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path
import unittest

from shapely.geometry import GeometryCollection, LineString, Point, Polygon, box, mapping, shape
from shapely.ops import unary_union
from shapely.affinity import affine_transform
from pyproj import Transformer
from shapely.validation import explain_validity


def osm_identity(value):
    """Normalize an actual positive OSM integer ID, never a missing placeholder."""
    if isinstance(value, bool):
        raise ValueError("OSM IDs must be positive integers")
    if isinstance(value, int) and value > 0:
        return str(value)
    if isinstance(value, str) and value.isdecimal() and int(value) > 0:
        return str(int(value))
    raise ValueError("OSM IDs must be positive integers")


def signed_area(coords):
    origin_x, origin_y = coords[0]
    return sum((a[0] - origin_x) * (b[1] - origin_y)
               - (b[0] - origin_x) * (a[1] - origin_y)
               for a, b in zip(coords, coords[1:])) / 2


def audit(document):
    """Return JSON report and EPSG:3414 feature collection; never mutate input."""
    if document.get("crs") != "EPSG:3414":
        raise ValueError("Explicit crs EPSG:3414 is required")
    space = document.get("coordinate_space", "east_north")
    if space not in ("east_north", "minecraft_xz"):
        raise ValueError("coordinate_space must be east_north or minecraft_xz")

    def en(pair):
        if len(pair) != 2 or not all(math.isfinite(float(x)) for x in pair):
            raise ValueError("Coordinates must be finite numeric pairs")
        x, y = map(float, pair)
        return (x, 60000 - y) if space == "minecraft_xz" else (x, y)

    bounds = document["bounds"]
    if len(bounds) != 4 or not all(math.isfinite(float(x)) for x in bounds):
        raise ValueError("bounds requires four finite numbers in input coordinate space")
    lo, hi = en(bounds[:2]), en(bounds[2:])
    if not (float(bounds[0]) < float(bounds[2]) and float(bounds[1]) < float(bounds[3])):
        raise ValueError("bounds must have positive width and height")
    domain = box(min(lo[0], hi[0]), min(lo[1], hi[1]),
                 max(lo[0], hi[0]), max(lo[1], hi[1]))
    raw = document["ways"]
    if not isinstance(raw, list):
        raise ValueError("ways must be a list")
    ids = [osm_identity(w["id"]) for w in raw]
    if len(ids) != len(set(ids)):
        raise ValueError("Duplicate raw way IDs: cannot provide unique accounting")

    records = [{"id": w["id"], "status": "unresolved", "reasons": []} for w in raw]
    node_coords, bad_nodes = {}, set()
    outgoing, incoming, incident = defaultdict(list), defaultdict(list), defaultdict(set)
    edges, raw_lines, opaque = [], {}, False

    def reject(indices, reason):
        for i in indices:
            if reason not in records[i]["reasons"]:
                records[i]["reasons"].append(reason)
            records[i]["status"] = "unresolved"

    for i, w in enumerate(raw):
        try:
            coords = [en(p) for p in w["coordinates"]]
            if len(coords) >= 2:
                raw_lines[i] = LineString(coords)
            nodes = [osm_identity(n) for n in w["node_ids"]]
            if len(nodes) < 2 or len(nodes) != len(coords):
                raise ValueError("node/coordinate length mismatch or fewer than two nodes")
            tags = w.get("tags", {})
            if not isinstance(tags, dict):
                raise ValueError("tags must be a mapping")
            if tags.get("natural", "coastline") != "coastline":
                raise ValueError("not tagged natural=coastline")
            for node, xy in zip(nodes, coords):
                if node in node_coords and node_coords[node] != xy:
                    bad_nodes.add(node)
                node_coords.setdefault(node, xy)
                incident[node].add(i)
            for a, b in zip(nodes, nodes[1:]):
                k = len(edges)
                edges.append((a, b, i))
                outgoing[a].append(k)
                incoming[b].append(k)
        except (KeyError, TypeError, ValueError, OverflowError) as error:
            reject([i], "invalid_raw_way: " + str(error))
            if i not in raw_lines:
                opaque = True

    # Weak components are formed at every raw node, including interior way nodes.
    unseen = set(incident)
    rings, components = [], []
    while unseen:
        seed = min(unseen)
        todo, nodes, members, component_edges = [seed], set(), set(), set()
        while todo:
            node = todo.pop()
            if node in nodes:
                continue
            nodes.add(node)
            unseen.discard(node)
            members.update(incident[node])
            for k in outgoing[node] + incoming[node]:
                component_edges.add(k)
                a, b, _ = edges[k]
                todo.append(b if a == node else a)
        ci = len(components)
        defect = [{"node_id": n, "in_degree": len(incoming[n]),
                   "out_degree": len(outgoing[n]),
                   "en": list(node_coords[n])}
                  for n in sorted(nodes)
                  if len(incoming[n]) != 1 or len(outgoing[n]) != 1]
        reasons = []
        if defect:
            reasons.append("directed_degree_not_one")
        if nodes & bad_nodes:
            reasons.append("raw_node_coordinate_conflict")
        if any(records[i]["reasons"] for i in members):
            reasons.append("invalid_member_way")
        component = {"id": ci, "way_ids": [raw[i]["id"] for i in sorted(members)],
                     "node_count": len(nodes), "edge_count": len(component_edges),
                     "defects": defect, "status": "unresolved"}
        components.append(component)
        for i in members:
            records[i]["component"] = ci
        if reasons:
            for reason in reasons:
                reject(members, reason)
            component["reasons"] = reasons
            continue
        current, visited, coords = seed, set(), [node_coords[seed]]
        while True:
            k = outgoing[current][0]
            if k in visited:
                break
            visited.add(k)
            current = edges[k][1]
            coords.append(node_coords[current])
        if current != seed or visited != component_edges:
            reject(members, "not_one_complete_directed_ring")
            continue
        line = LineString(coords)
        if len(set(coords[:-1])) < 3 or not line.is_ring or not line.is_simple:
            reject(members, "self_intersection_or_degenerate_ring")
            continue
        poly = Polygon(coords)
        area = signed_area(coords)
        if not poly.is_valid or poly.is_empty or not area:
            reject(members, "invalid_polygon: " + explain_validity(poly))
            continue
        ri = len(rings)
        component.update(status="complete_directed_ring", ring=ri)
        rings.append({"id": ri, "component": ci, "members": members,
                      "polygon": poly, "line": line, "signed_area_en": area,
                      "signed_area_xz": -area, "interior": "land" if area > 0 else "sea",
                      "boundary_contact": line.intersects(domain.boundary),
                      "valid": True, "parent": None})
        for i in members:
            records[i].update(status="complete_directed_ring", ring=ri)

    # Crossings/touches and inconsistent nesting cannot establish a trusted face.
    for i, a in enumerate(rings):
        containers = []
        for j, b in enumerate(rings):
            if i == j:
                continue
            if i < j and a["line"].intersects(b["line"]):
                for ring in (a, b):
                    ring["valid"] = False
                    reject(ring["members"], "rings_touch_or_cross")
            if b["polygon"].contains(a["polygon"]):
                containers.append(j)
        if containers:
            a["parent"] = min(containers, key=lambda j: rings[j]["polygon"].area)
    for a in rings:
        parent = a["parent"]
        if parent is not None and a["interior"] == rings[parent]["interior"]:
            for ring in (a, rings[parent]):
                ring["valid"] = False
                reject(ring["members"], "nested_orientation_does_not_alternate")
    # A invalid enclosing ring cannot certify its descendants' nesting.
    changed = True
    while changed:
        changed = False
        for a in rings:
            if a["valid"] and a["parent"] is not None and not rings[a["parent"]]["valid"]:
                a["valid"] = False
                reject(a["members"], "unresolved_enclosing_ring")
                changed = True

    unresolved_lines = [line if line.length else Point(line.coords[0])
                        for i, line in raw_lines.items()
                        if records[i]["status"] == "unresolved"]
    unresolved = unary_union(unresolved_lines) if unresolved_lines else GeometryCollection()
    trusted = [r for r in rings if r["valid"]]
    faces = []
    for ring in trusted:
        children = [r["polygon"] for r in trusted if r["parent"] == ring["id"]]
        face = ring["polygon"].difference(unary_union(children)).intersection(domain)
        if face.is_empty:
            continue
        contaminated = opaque or (not unresolved.is_empty and
                                  face.relate_pattern(unresolved, "T********"))
        faces.append((face, "unknown" if contaminated else ring["interior"],
                      "unresolved_source_in_face" if contaminated else "directed_ring",
                      ring["id"]))

    # Bounds are only clipping support. No edge of this rectangle is coastline.
    exterior = domain.difference(unary_union([r["polygon"] for r in trusted]))
    if not exterior.is_empty:
        faces.append((exterior, "unknown", "exterior_requires_independent_face_evidence", None))
    if not faces:
        faces.append((domain, "unknown", "no_trusted_rings", None))
    features = [{"type": "Feature", "properties":
                 {"class": kind, "basis": basis, "ring": ring_id},
                 "geometry": mapping(geometry)}
                for geometry, kind, basis, ring_id in faces]
    ring_reports = [{key: value for key, value in ring.items()
                     if key not in ("polygon", "line", "members")}
                    for ring in rings]
    for ring in rings:
        if not ring["valid"]:
            components[ring["component"]]["status"] = "unresolved"
    known = sum(g.area for g, kind, _, _ in faces if kind != "unknown")
    report = {"schema": "singapore-coast-closure-audit/v1",
              "source": document.get("source", {}), "crs": "EPSG:3414",
              "input_coordinate_space": space, "output_coordinate_space": "east_north",
              "bounds": list(domain.bounds), "raw_way_count": len(raw),
              "complete_ring_way_count": sum(r["status"] == "complete_directed_ring" for r in records),
              "unresolved_way_count": sum(r["status"] == "unresolved" for r in records),
              "all_raw_ways_accounted": len(records) == len(raw),
              "ways": records, "components": components, "rings": ring_reports,
              "known_area_m2": known, "unknown_area_m2": domain.area - known,
              "boundary_policy": "Sample centers on raw shoreline are unknown; use classify_point.",
              "artificial_closure_edges": 0,
              "fatal_missing_geometry": opaque}
    assert report["raw_way_count"] == report["complete_ring_way_count"] + report["unresolved_way_count"]
    mask = {"type": "FeatureCollection", "name": "independent_conservative_coast_mask",
            "crs": {"type": "name", "properties": {"name": "EPSG:3414"}},
            "coordinate_space": "east_north",
            "metadata": {"boundary_policy": report["boundary_policy"],
                         "not_geographic_rfc7946": True},
            "features": features}
    return report, mask


def classify_point(document, mask, easting, northing):
    """Classify an EN sample center; exact raw shoreline contacts are unknown."""
    if not all(math.isfinite(float(v)) for v in (easting, northing)):
        return "unknown"
    point = Point(easting, northing)
    space = document.get("coordinate_space", "east_north")
    for way in document["ways"]:
        try:
            coords = way.get("coordinates", [])
            if len(coords) < 2:
                continue
            coords = [(float(x), float(y)) for x, y in coords]
            if not all(math.isfinite(v) for pair in coords for v in pair):
                return "unknown"
            if space == "minecraft_xz":
                coords = [(x, 60000 - z) for x, z in coords]
            if LineString(coords).covers(point):
                return "unknown"
        except (TypeError, ValueError, OverflowError):
            return "unknown"
    found = {f["properties"]["class"] for f in mask["features"] if shape(f["geometry"]).covers(point)}
    return next(iter(found)) if len(found) == 1 else "unknown"


def compare_mask(independent, candidate):
    """Candidate may resolve unknowns; a positive-area opposite class is a failure."""
    crs = candidate.get("crs")
    if isinstance(crs, dict):
        crs = crs.get("properties", {}).get("name")
    if crs != "EPSG:3414" or candidate.get("coordinate_space", "east_north") != "east_north":
        raise ValueError("Comparison mask must explicitly identify EPSG:3414 east/north")
    supplied_geometries = []
    for supplied in candidate["features"]:
        label = supplied["properties"].get("class")
        geometry = shape(supplied["geometry"])
        if label not in ("land", "sea", "unknown"):
            raise ValueError("Comparison feature class must be land, sea or unknown")
        if geometry.geom_type not in ("Polygon", "MultiPolygon") or not geometry.is_valid:
            raise ValueError("Comparison features must be valid polygon geometries")
        supplied_geometries.append((label, geometry))
    conflicts = []
    for expected in independent["features"]:
        label = expected["properties"]["class"]
        if label == "unknown":
            continue
        opposite = "sea" if label == "land" else "land"
        a = shape(expected["geometry"])
        for supplied_label, supplied_geometry in supplied_geometries:
            if supplied_label != opposite:
                continue
            overlap = a.intersection(supplied_geometry)
            if overlap.area > 0:
                conflicts.append({"expected": label, "actual": opposite,
                                  "area_m2": overlap.area, "bounds": list(overlap.bounds)})
    return conflicts


def verify_faces(doc, en, xz, receipt, originalbytes, manifest, input_files):
    """Independently gate a bounded benchmark bundle against its original export.

    Exact set differences prove boundary support. Tiny line buffers can become
    numerically unstable at projected coordinate magnitudes and are not used.
    Segment probes use three offsets with clearance from all other source edges.
    This gate requires resolved, fully supported faces at all source probes.
    """
    report, ringmask = audit(doc)
    domain = box(*doc["bounds"])
    features = [(f, shape(f["geometry"])) for f in en["features"]]
    geometries = [g for _,g in features]
    boundary = unary_union([g.boundary for g in geometries])
    union = unary_union(geometries)
    errors = []
    def check(condition, message):
        if not condition: errors.append(message)
    def locate(pt):
        matches = [f["properties"]["class"] for f,g in features if g.contains(pt)]
        return matches[0] if len(matches)==1 else "unknown"
    tol = 1e-7
    check(all(g.is_valid for g in geometries), "invalid_EN_geometry")
    check(not compare_mask(ringmask, en), "known_ring_face_classification_contradiction")
    check(union.symmetric_difference(domain).area < tol, "domain_coverage_or_outside_geometry")
    overlap = sum(geometries[i].intersection(geometries[j]).area for i in range(len(geometries)) for j in range(i))
    check(overlap < tol, "positive_area_face_overlap")
    areas = {kind: sum(g.area for f,g in features if f["properties"]["class"] == kind) for kind in ("land","sea","unknown")}
    segments = []
    for w in doc["ways"]:
        for index,(a,b) in enumerate(zip(w["coordinates"],w["coordinates"][1:])):
            line = LineString([a,b])
            clipped = line.intersection(domain)
            if clipped.is_empty or clipped.length == 0: continue
            check(clipped.geom_type == "LineString", "unexpected_clipped_segment_geometry")
            segments.append({"way_id":w["id"], "node_ids":w["node_ids"][index:index+2], "line":line,
                             "clip":clipped, "a":a,"b":b,"index":index})
    check(bool(segments), "no_source_shoreline_evidence")
    source_intersections=[]
    for i,a in enumerate(segments):
        for b in segments[:i]:
            overlap_geometry=a["clip"].intersection(b["clip"])
            if overlap_geometry.is_empty:
                continue
            if overlap_geometry.length > 0 or (
                    domain.contains(overlap_geometry)
                    and not set(a["node_ids"]).intersection(b["node_ids"])):
                source_intersections.append({"way_ids":[a["way_id"],b["way_id"]],
                    "node_ids":[a["node_ids"],b["node_ids"]],
                    "geometry":mapping(overlap_geometry)})
    check(not source_intersections, "raw_source_segment_intersection_conflict")
    shore = unary_union([s["clip"] for s in segments])
    unrepresented = shore.difference(boundary).length
    unsupported = boundary.difference(unary_union([shore,domain.boundary])).length
    check(unrepresented < tol, "source_shoreline_missing_from_face_boundaries")
    check(unsupported < tol, "internal_face_boundary_not_supported_by_source_shoreline")
    probes, failed = [], []
    for index,s in enumerate(segments):
        a,b = s["a"],s["b"]
        dx,dy=b[0]-a[0],b[1]-a[1]
        length=math.hypot(dx,dy)
        mid=s["clip"].interpolate(.5,normalized=True)
        nearby=min((mid.distance(other["clip"]) for j,other in enumerate(segments) if j!=index),default=1000)
        clearance=min(nearby, mid.distance(domain.boundary), s["clip"].length)
        for base in (.01,.1,.5):
            offset=min(base,clearance/4)
            if offset <= 1e-8:
                failed.append({"reason":"ambiguous_probe_clearance","way_id":s["way_id"],"nodes":s["node_ids"]})
                continue
            for sign,expected in ((1,"land"),(-1,"sea")):
                point=Point(mid.x-sign*dy/length*offset,mid.y+sign*dx/length*offset)
                actual=locate(point)
                detail={"way_id":s["way_id"],"node_ids":s["node_ids"],"segment":s["index"],
                        "pointEN":[point.x,point.y],"midpointEN":[mid.x,mid.y],
                        "expected":expected,"actual":actual,"offset_m":offset}
                probes.append(detail)
                if actual != expected: failed.append(detail)
    check(not failed, "directed_source_segment_probe_contradiction")
    witnesses=[]
    for f,g in features:
        for witness in f["properties"].get("witnesses",[]):
            midpoint=Point(witness["shoreMidpointEN"])
            point=Point(witness["pointEN"])
            supporting=[s for s in segments if s["line"].distance(midpoint)<tol
                        and s["line"].project(midpoint)>0
                        and s["line"].project(midpoint)<s["line"].length]
            sides=[]
            for s in supporting:
                a,b=s["a"],s["b"]
                cross=(b[0]-a[0])*(point.y-midpoint.y)-(b[1]-a[1])*(point.x-midpoint.x)
                sides.append("land" if cross>0 else "sea" if cross<0 else "unknown")
            expected=witness["class"]
            okay=bool(supporting) and set(sides)=={expected} and g.contains(point) and locate(point)==expected and f["properties"]["class"]==expected
            witnesses.append({"face":f["id"],"pointEN":witness["pointEN"],"expected":expected,"source_way_ids":[s["way_id"] for s in supporting],"passed":okay})
            check(okay,"stored_face_witness_contradiction")
    xzbyid={f["id"]:f for f in xz["features"]}
    check(len(xzbyid)==len(xz["features"]), "duplicate_XZ_feature_ID")
    check(len({f["id"] for f,_ in features})==len(features), "duplicate_EN_feature_ID")
    xzchecks=[]
    check(xz.get("coordinate_space")=="minecraft_xz","XZ_coordinate_space_missing")
    check(set(xzbyid)=={f["id"] for f,_ in features},"XZ_feature_ID_mismatch")
    for f,g in features:
        other=xzbyid.get(f["id"])
        if other is None: continue
        transformed=affine_transform(g,[1,0,0,-1,0,60000])
        actual=shape(other["geometry"])
        diff=transformed.symmetric_difference(actual).area
        distance=transformed.hausdorff_distance(actual)
        check(diff<tol and distance<tol and actual.is_valid and other["properties"]["class"]==f["properties"]["class"],"XZ_transformation_or_class_mismatch")
        xzchecks.append({"face":f["id"],"symmetric_difference_m2":diff,"hausdorff_distance_m":distance})
    original=json.loads(originalbytes)
    originalnodes={e["id"]:e for e in original["elements"] if e["type"]=="node"}
    originalways=[e for e in original["elements"] if e["type"]=="way" and e.get("tags",{}).get("natural")=="coastline"]
    transformer=Transformer.from_crs("EPSG:4326","EPSG:3414",always_xy=True)
    supplied={w["id"]:w for w in doc["ways"]}
    sourcechecks=[]
    intersecting=[]
    missing=[]
    maxerror=0
    for w in originalways:
        absent=[n for n in w["nodes"] if n not in originalnodes]
        missing.extend(absent)
        if absent:
            errors.append("original_coastline_missing_node_references")
            continue
        coords=[transformer.transform(originalnodes[n]["lon"],originalnodes[n]["lat"]) for n in w["nodes"]]
        relevant=LineString(coords).intersects(domain)
        if relevant: intersecting.append(w["id"])
        provided=supplied.get(w["id"])
        if provided is not None:
            check(provided["node_ids"]==w["nodes"],"original_raw_node_order_mismatch")
            check(provided["tags"]==w.get("tags",{}),"original_raw_tags_mismatch")
            check(len(provided["coordinates"])==len(coords),"original_coordinate_count_mismatch")
            distance=max((math.hypot(a[0]-b[0],a[1]-b[1]) for a,b in zip(coords,provided["coordinates"])),default=0)
            maxerror=max(maxerror,distance)
            check(distance<tol,"original_projected_coordinate_mismatch")
        sourcechecks.append({"way_id":w["id"],"intersects_domain":relevant,"supplied":provided is not None,"raw_nodes":len(w["nodes"])})
    check(set(intersecting)==set(supplied),"original_intersecting_coastline_omitted_or_extra")
    originalsha=hashlib.sha256(originalbytes).hexdigest()
    check(originalsha==doc["source"]["sha256"]==manifest["subsetSha256"]==receipt["source"]["sha256"],"original_source_hash_mismatch")
    check(manifest["referenceComplete"] and doc["source"]["referenceComplete"],"source_reference_completeness_not_declared")
    check(manifest["boundsEPSG3414"]==doc["bounds"]==receipt["boundsEN"],"extraction_bounds_mismatch")
    files=[]
    check({f["name"] for f in receipt["files"]} == set(input_files), "receipt_file_inventory_mismatch")
    for f in receipt["files"]:
        if f["name"] not in input_files:
            check(False, "receipt_file_missing")
            continue
        body=input_files[f["name"]]
        okay=hashlib.sha256(body).hexdigest()==f["sha256"] and len(body)==f["bytes"]
        files.append({"name":f["name"],"verified":okay})
        check(okay,"receipt_file_hash_or_length_mismatch")
    defects=[d for c in report["components"] for d in c["defects"]]
    check(not any(domain.contains(Point(d["en"])) for d in defects),"raw_graph_open_endpoint_inside_domain")
    check(len(segments)==receipt["sourceSegments"],"receipt_clipped_source_segment_count_mismatch")
    for kind,value in areas.items():
        check(abs(value-receipt["areaSquareMetres"].get(kind,0))<tol,"receipt_class_area_mismatch")
    report["face_mask_audit"]={"status":"PASS" if not errors else "FAIL","errors":errors,
        "domain_area_m2":domain.area,"class_areas_m2":areas,"coverage_difference_m2":union.symmetric_difference(domain).area,
        "pairwise_overlap_m2":overlap,"unrepresented_shoreline_length_m":unrepresented,
        "unsupported_internal_boundary_length_m":unsupported,"clipped_source_segments":len(segments),
        "source_segment_intersection_conflicts":source_intersections,
        "directed_probe_count":len(probes),"failed_probes":failed,"directed_probes":probes,
        "stored_witnesses":witnesses,"xz_transform":xzchecks,
        "original_source_sha256":originalsha,"original_raw_coastline_ways":sourcechecks,
        "original_missing_node_refs":missing,"max_projected_coordinate_error_m":maxerror,
        "intersecting_original_coastline_way_ids":intersecting,"verified_receipt_files":files,
        "completeness_scope":"Every coastline way intersecting the declared domain in the original supplied 3.9MB export is retained with exact raw node order, tags and projected coordinates. Does not prove completeness beyond that original export.",
        "ring_scope":"Raw ring closure is reported separately. Open chains with endpoints outside the domain can support clipped face classification; ring-only unknown is not a face-mask contradiction.",
        "numeric_tolerance_m":tol}

    return report, ringmask


def verify_face_bundle(directory, original_path):
    """Read only the named small bundle/export; callers choose report destinations."""
    directory, original_path = Path(directory), Path(original_path)
    names = ("coast-source.json", "coast-mask-en.geojson", "coast-mask-xz.geojson")
    input_files = {name: (directory / name).read_bytes() for name in names}
    doc, en, xz = [json.loads(input_files[name]) for name in names]
    receipt = json.loads((directory / "coast-receipt.json").read_bytes())
    originalbytes = original_path.read_bytes()
    manifest = json.loads(Path(str(original_path) + ".manifest.json").read_bytes())
    return verify_faces(doc, en, xz, receipt, originalbytes, manifest, input_files)


def fixture(ways, space="east_north"):
    return {"crs": "EPSG:3414", "coordinate_space": space,
            "bounds": [-1, -1, 11, 11] if space == "east_north" else [-1, 59989, 11, 60001],
            "ways": ways}


def way(i, nodes, coords):
    return {"id": i, "node_ids": nodes, "coordinates": coords,
            "tags": {"natural": "coastline"}}


class CoastAuditTests(unittest.TestCase):
    def square(self):
        return way(1, [1, 2, 3, 4, 1], [(0, 0), (10, 0), (10, 10), (0, 10), (0, 0)])

    def test_directed_ring_and_mirrored_orientation(self):
        d = fixture([self.square()])
        r, m = audit(d)
        self.assertEqual(r["complete_ring_way_count"], 1)
        self.assertGreater(r["rings"][0]["signed_area_en"], 0)
        self.assertLess(r["rings"][0]["signed_area_xz"], 0)
        self.assertEqual(classify_point(d, m, 5.5, 5.5), "land")
        self.assertEqual(classify_point(d, m, 10.5, 5.5), "unknown")
        self.assertEqual(classify_point(d, m, 0, 5), "unknown")
        w = self.square()
        w["coordinates"] = [(x, 60000 - n) for x, n in w["coordinates"]]
        mirrored, _ = audit(fixture([w], "minecraft_xz"))
        self.assertEqual(mirrored["rings"][0]["signed_area_en"], 100)

    def test_open_coordinates_do_not_close_by_distance(self):
        w = self.square()
        w["node_ids"][-1] = 5  # Identical coordinates are still distinct raw nodes.
        r, _ = audit(fixture([w]))
        self.assertEqual(r["unresolved_way_count"], 1)
        self.assertEqual(r["artificial_closure_edges"], 0)
        self.assertEqual(r["known_area_m2"], 0)

    def test_split_ring_and_reversed_member(self):
        a = way(1, [1, 2, 3], [(0, 0), (10, 0), (10, 10)])
        b = way(2, [3, 4, 1], [(10, 10), (0, 10), (0, 0)])
        self.assertEqual(audit(fixture([a, b]))[0]["complete_ring_way_count"], 2)
        b["node_ids"].reverse()
        b["coordinates"].reverse()
        self.assertEqual(audit(fixture([a, b]))[0]["unresolved_way_count"], 2)

    def test_interior_node_junction(self):
        branch = way(2, [2, 5], [(10, 0), (11, 0)])
        self.assertEqual(audit(fixture([self.square(), branch]))[0]["unresolved_way_count"], 2)

    def test_self_intersection(self):
        w = way(1, [1, 2, 3, 4, 1], [(0, 0), (10, 10), (10, 0), (0, 10), (0, 0)])
        r, _ = audit(fixture([w]))
        self.assertIn("self_intersection_or_degenerate_ring", r["ways"][0]["reasons"])

    def test_nested_sea_and_island(self):
        hole = way(2, [5, 6, 7, 8, 5], [(2, 2), (2, 8), (8, 8), (8, 2), (2, 2)])
        inner = way(3, [9, 10, 11, 12, 9], [(4, 4), (6, 4), (6, 6), (4, 6), (4, 4)])
        d = fixture([self.square(), hole, inner])
        r, m = audit(d)
        self.assertEqual(r["complete_ring_way_count"], 3)
        self.assertEqual(classify_point(d, m, 1.5, 1.5), "land")
        self.assertEqual(classify_point(d, m, 3.5, 3.5), "sea")
        self.assertEqual(classify_point(d, m, 5.5, 5.5), "land")
        hole["node_ids"].reverse()
        hole["coordinates"].reverse()
        self.assertEqual(audit(fixture([self.square(), hole]))[0]["unresolved_way_count"], 2)

    def test_boundary_contact_and_unresolved_interior(self):
        d = fixture([self.square()])
        d["bounds"] = [0, 0, 9, 9]
        self.assertTrue(audit(d)[0]["rings"][0]["boundary_contact"])
        d = fixture([self.square(), way(2, [20, 21], [(2, 2), (3, 3)])])
        r, m = audit(d)
        self.assertEqual(classify_point(d, m, 5.5, 5.5), "unknown")
        self.assertEqual(r["known_area_m2"], 0)

    def test_crossing_rings_and_missing_geometry(self):
        b = way(2, [11, 12, 13, 14, 11], [(5, 5), (15, 5), (15, 15), (5, 15), (5, 5)])
        self.assertEqual(audit(fixture([self.square(), b]))[0]["unresolved_way_count"], 2)
        broken = {"id": 2, "node_ids": [20, 21], "coordinates": []}
        r, _ = audit(fixture([self.square(), broken]))
        self.assertTrue(r["fatal_missing_geometry"])
        self.assertEqual(r["known_area_m2"], 0)

    def test_duplicate_ids_rejected(self):
        with self.assertRaises(ValueError):
            audit(fixture([self.square(), self.square()]))

    def test_mask_partition_and_contradiction(self):
        _, m = audit(fixture([self.square()]))
        geoms = [shape(f["geometry"]) for f in m["features"]]
        self.assertAlmostEqual(sum(g.area for g in geoms), 144)
        self.assertAlmostEqual(unary_union(geoms).area, 144)
        wrong = {"crs": "EPSG:3414", "features": [{"properties": {"class": "sea"},
                              "geometry": mapping(box(1, 1, 2, 2))}]}
        self.assertEqual(compare_mask(m, wrong)[0]["area_m2"], 1)

    def test_missing_node_identity_and_malformed_tags(self):
        w = self.square()
        w["node_ids"][0] = w["node_ids"][-1] = None
        self.assertEqual(audit(fixture([w]))[0]["unresolved_way_count"], 1)
        w = self.square()
        w["tags"] = None
        self.assertEqual(audit(fixture([w]))[0]["unresolved_way_count"], 1)

    def test_degenerate_unresolved_geometry_stays_unknown(self):
        broken = way(2, [20, 21], [(2, 2), (2, 2)])
        d = fixture([self.square(), broken])
        r, m = audit(d)
        self.assertEqual(r["known_area_m2"], 0)
        self.assertEqual(classify_point(d, m, 5.5, 5.5), "unknown")

    def test_malformed_point_classification_is_unknown(self):
        for coords in (None, [[1, 2], [None, 3]], [[1, 2], [float("nan"), 3]]):
            broken = {"id": 2, "node_ids": [20, 21], "coordinates": coords}
            d = fixture([self.square(), broken])
            _, m = audit(d)
            self.assertEqual(classify_point(d, m, 5.5, 5.5), "unknown")

    def test_comparison_rejects_unidentified_coordinates_and_classes(self):
        _, m = audit(fixture([self.square()]))
        for candidate in (
                {"features": []},
                {"crs": "EPSG:4326", "features": []},
                {"crs": "EPSG:3414", "coordinate_space": "minecraft_xz", "features": []},
                {"crs": "EPSG:3414", "features": [
                    {"properties": {"class": "water"}, "geometry": mapping(box(1, 1, 2, 2))}]}):
            with self.assertRaises(ValueError):
                compare_mask(m, candidate)


    def face_bundle_fixture(self):
        # Start from geographic nodes, then independently retain their EN projection.
        to_geo = Transformer.from_crs("EPSG:3414", "EPSG:4326", always_xy=True)
        to_en = Transformer.from_crs("EPSG:4326", "EPSG:3414", always_xy=True)
        coords = [(45000,41000),(45010,41000),(45010,41010),(45000,41010)]
        elements=[]
        projected=[]
        for index, xy in enumerate(coords,1):
            lon,lat=to_geo.transform(*xy)
            elements.append({"type":"node","id":index,"lon":lon,"lat":lat})
            projected.append(to_en.transform(lon,lat))
        projected.append(projected[0])
        raw=way(1,[1,2,3,4,1],projected)
        elements.append({"type":"way","id":1,"nodes":raw["node_ids"],"tags":raw["tags"]})
        originalbytes=json.dumps({"elements":elements}).encode()
        source={"sha256":hashlib.sha256(originalbytes).hexdigest(),"referenceComplete":True}
        doc={"crs":"EPSG:3414","bounds":[44999,40999,45011,41011],"ways":[raw],"source":source}
        domain=box(*doc["bounds"])
        land=Polygon(projected)
        sea=domain.difference(land)
        features=[]
        for i,(kind,g,point) in enumerate((("land",land,[45005,41000.1]),("sea",sea,[45005,40999.9]))):
            midpoint=[(projected[0][0]+projected[1][0])/2,(projected[0][1]+projected[1][1])/2]
            features.append({"id":str(i),"type":"Feature","properties":{"class":kind,
                "witnesses":[{"class":kind,"pointEN":point,"shoreMidpointEN":midpoint}]},
                "geometry":mapping(g)})
        en={"type":"FeatureCollection","crs":"EPSG:3414","features":features}
        xz={"coordinate_space":"minecraft_xz","features":[
            {**f,"geometry":mapping(affine_transform(shape(f["geometry"]),[1,0,0,-1,0,60000]))}
            for f in features]}
        files={name:json.dumps(value).encode() for name,value in (
            ("coast-source.json",doc),("coast-mask-en.geojson",en),("coast-mask-xz.geojson",xz))}
        receipt={"files":[{"name":name,"bytes":len(value),"sha256":hashlib.sha256(value).hexdigest()}
                          for name,value in files.items()],
                 "source":source,"boundsEN":doc["bounds"],"sourceSegments":4,
                 "areaSquareMetres":{"land":land.area,"sea":sea.area}}
        manifest={"subsetSha256":source["sha256"],"referenceComplete":True,"boundsEPSG3414":doc["bounds"]}
        return doc,en,xz,receipt,originalbytes,manifest,files

    def test_face_gate_exact_support_and_witnesses(self):
        report,_=verify_faces(*self.face_bundle_fixture())
        self.assertEqual(report["face_mask_audit"]["status"],"PASS")
        self.assertEqual(report["face_mask_audit"]["directed_probe_count"],24)
        self.assertEqual(report["face_mask_audit"]["unsupported_internal_boundary_length_m"],0)
        self.assertTrue(all(w["passed"] for w in report["face_mask_audit"]["stored_witnesses"]))

    def test_face_gate_detects_reversed_classes_and_bad_transform(self):
        args=list(self.face_bundle_fixture())
        args[1]["features"][0]["properties"]["class"]="sea"
        report,_=verify_faces(*args)
        self.assertIn("directed_source_segment_probe_contradiction",report["face_mask_audit"]["errors"])
        args=list(self.face_bundle_fixture())
        args[2]["features"][0]["geometry"]=mapping(box(0,0,1,1))
        report,_=verify_faces(*args)
        self.assertIn("XZ_transformation_or_class_mismatch",report["face_mask_audit"]["errors"])

    def test_face_gate_detects_original_way_omission(self):
        args=list(self.face_bundle_fixture())
        original=json.loads(args[4])
        original["elements"].append({"type":"way","id":2,"nodes":[1,3],"tags":{"natural":"coastline"}})
        args[4]=json.dumps(original).encode()
        report,_=verify_faces(*args)
        self.assertIn("original_intersecting_coastline_omitted_or_extra",report["face_mask_audit"]["errors"])
    def test_face_gate_rejects_duplicate_xz_features(self):
        args=list(self.face_bundle_fixture())
        duplicate={"id":"0","properties":{"class":"sea"},"geometry":mapping(box(0,0,1,1))}
        args[2]["features"].insert(0,duplicate)
        report,_=verify_faces(*args)
        self.assertIn("duplicate_XZ_feature_ID",report["face_mask_audit"]["errors"])

    def test_face_gate_rejects_land_without_source_evidence(self):
        args=list(self.face_bundle_fixture())
        args[0]["ways"]=[]
        args[1]["features"]=[{"id":"0","type":"Feature","properties":{"class":"land"},
                              "geometry":mapping(box(*args[0]["bounds"]))}]
        args[2]["features"]=[{**args[1]["features"][0],"geometry":mapping(
            affine_transform(box(*args[0]["bounds"]),[1,0,0,-1,0,60000]))}]
        args[4]=json.dumps({"elements":[]}).encode()
        report,_=verify_faces(*args)
        self.assertIn("no_source_shoreline_evidence",report["face_mask_audit"]["errors"])


    def test_face_gate_rejects_unshared_raw_crossing(self):
        args=list(self.face_bundle_fixture())
        args[0]["ways"].append(way(2,[10,11],[(44998,41005),(45012,41005)]))
        report,_=verify_faces(*args)
        self.assertIn("raw_source_segment_intersection_conflict",report["face_mask_audit"]["errors"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", nargs="?", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--mask", type=Path)
    parser.add_argument("--compare-mask", type=Path)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--verify-faces", type=Path, metavar="BUNDLE_DIRECTORY")
    parser.add_argument("--original-export", type=Path)
    args = parser.parse_args()
    if args.self_test:
        result = unittest.TextTestRunner(verbosity=2).run(
            unittest.defaultTestLoader.loadTestsFromTestCase(CoastAuditTests))
        return 0 if result.wasSuccessful() else 1
    if args.verify_faces:
        if not args.original_export or not args.report or not args.mask:
            parser.error("--verify-faces requires --original-export, --report and --mask")
        report, mask = verify_face_bundle(args.verify_faces, args.original_export)
        for path, payload in ((args.report, report), (args.mask, mask)):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        result = report["face_mask_audit"]
        print(json.dumps({"status": result["status"], "errors": result["errors"],
                          "directed_probes": result["directed_probe_count"],
                          "areas_m2": result["class_areas_m2"]}))
        return 0 if result["status"] == "PASS" else 1
    if not args.source or not args.report or not args.mask:
        parser.error("source, --report and --mask are required outside --self-test")
    source_bytes = args.source.read_bytes()
    report, mask = audit(json.loads(source_bytes))
    report["input_sha256"] = hashlib.sha256(source_bytes).hexdigest()
    if args.compare_mask:
        candidate = json.loads(args.compare_mask.read_text(encoding="utf-8-sig"))
        report["mask_contradictions"] = compare_mask(mask, candidate)
    for path, payload in ((args.report, report), (args.mask, mask)):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in (
        "raw_way_count", "complete_ring_way_count", "unresolved_way_count",
        "known_area_m2", "unknown_area_m2", "artificial_closure_edges")}))
    return 1 if report.get("mask_contradictions") else 0


if __name__ == "__main__":
    raise SystemExit(main())
