"""Audit an OSM country mask without generating or changing world data.

Dependencies: osmium, shapely, pyproj. One sequential reader; no location cache.
Administrative water boundaries are not coastlines or proof of settled sovereignty.
"""
import argparse
import hashlib
import json
from pathlib import Path
import sys
import unittest

import osmium
from pyproj import Geod
from shapely.geometry import LineString, Point, Polygon, box, mapping, shape
from shapely.ops import polygonize, unary_union
from shapely.validation import explain_validity

RELATION = 536780
GEOD = Geod(ellps="WGS84")
TARGETS = ("singapore", "singapore island", "pulau ujong", "pulau ubin", "pulau tekong",
           "sentosa", "pulau sudong", "pulau pawai", "pulau senang",
           "pulau satumu", "pulau semakau", "pulau bukom", "pedra branca",
           "south ledge", "middle rocks", "pulau sekudu", "pulau hantu besar", "pulau hantu kecil")
# Named site relation 18694563 identifies these two otherwise unnamed coastlines.
KNOWN_WAY_WITNESSES = {1359256910: "middle rocks", 1359256911: "middle rocks"}


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().upper()


def country_feature(document):
    features = document.get("features", [document])
    matches = [f for f in features if f.get("id") == f"relation/{RELATION}"]
    if len(matches) != 1:
        raise ValueError("Expected exactly one Singapore relation/536780 feature")
    feature = matches[0]
    tags = feature["properties"]
    if tags.get("admin_level") != "2" or "SG" not in (
            tags.get("ISO3166-1"), tags.get("ISO3166-1:alpha2")):
        raise ValueError("Feature does not identify Singapore admin_level=2")
    return feature


def geometry_checks(geometry, rebuilt=None):
    errors = []
    if geometry.is_empty or geometry.geom_type not in ("Polygon", "MultiPolygon"):
        errors.append("Mask must be a non-empty Polygon or MultiPolygon")
        return errors
    if not geometry.is_valid:
        errors.append("Invalid mask: " + explain_validity(geometry))
    if geometry.equals(box(*geometry.bounds)):
        errors.append("A bounding rectangle cannot stand in for the Singapore boundary")
    if rebuilt is not None and not geometry.equals(rebuilt):
        errors.append("Mask differs from the complete geometry rebuilt from its OSM relation")
    return errors


def land_scope_findings(witnesses):
    findings = []
    for witness in witnesses:
        if witness["target"] == "middle rocks" and witness.get("mask_intersects_feature"):
            findings.append({"code": "MALAYSIAN_LAND_INSIDE_OSM_SG_MARITIME_MASK",
                "osm": witness["osm"],
                "detail": "Middle Rocks is Malaysian according to the cited MFA/ICJ account. "
                          "Exclude this land geometry before treating the mask as Singapore-only land."})
        if witness["target"] == "south ledge" and witness.get("mask_intersects_feature"):
            findings.append({"code": "SOUTH_LEDGE_MARITIME_DELIMITATION_UNRESOLVED",
                "osm": witness["osm"],
                "detail": "OSM membership is not proof of settled sovereignty; preserve separate provenance."})
    return findings


class BoundaryReader(osmium.SimpleHandler):
    def __init__(self):
        super().__init__()
        self.nodes, self.ways, self.relations = {}, {}, {}

    def node(self, obj):
        self.nodes[obj.id] = (obj.location.lon, obj.location.lat)

    def way(self, obj):
        self.ways[obj.id] = {"nodes": [n.ref for n in obj.nodes], "tags": dict(obj.tags)}

    def relation(self, obj):
        self.relations[obj.id] = {"version": obj.version, "tags": dict(obj.tags),
            "members": [{"type": m.type, "ref": m.ref, "role": m.role} for m in obj.members]}


def missing_references(reader):
    missing = []
    stores = {"n": reader.nodes, "w": reader.ways, "r": reader.relations}
    for rid, rel in reader.relations.items():
        for member in rel["members"]:
            if member["ref"] not in stores[member["type"]]:
                missing.append(f"relation/{rid}: missing {member['type']}/{member['ref']}")
    for wid, way in reader.ways.items():
        missing.extend(f"way/{wid}: missing node/{nid}" for nid in way["nodes"]
                       if nid not in reader.nodes)
    return missing


def rebuild_boundary(path):
    features = []
    factory = osmium.geom.GeoJSONFactory()

    class Areas(osmium.SimpleHandler):
        def area(self, obj):
            if not obj.from_way() and obj.orig_id() == RELATION:
                features.append(shape(json.loads(factory.create_multipolygon(obj))))

    Areas().apply_file(str(path), locations=True)
    if len(features) != 1:
        raise ValueError(f"Expected one rebuilt country area, found {len(features)}")
    return features[0]


def target_name(tags):
    if tags.get("place") not in ("island", "islet") and tags.get("natural") not in (
            "coastline", "rock", "reef"):
        return None
    names = {part.strip().lower() for key in ("name", "name:en", "alt_name")
             for part in tags.get(key, "").split(";")}
    return next((target for target in TARGETS if target in names), None)


def source_witnesses(source, geometry, components, boundary_reader):
    """Find actual source OSM named land features using two bounded sequential passes."""
    found, selected_ways, wanted, coordinates = [], [], set(), {}
    selected_relations, relation_ways = [], {}
    source_country = None

    def record(kind, oid, tags, geom, target):
        witness = geom.representative_point()
        hits = [i + 1 for i, component in enumerate(components) if component.covers(witness)]
        found.append({"target": target, "osm": f"{kind}/{oid}",
            "url": f"https://www.openstreetmap.org/{kind}/{oid}",
            "name": tags.get("name"), "place": tags.get("place"),
            "natural": tags.get("natural"), "witness_lon_lat": list(witness.coords)[0],
            "component_ids": hits, "witness_covered": bool(hits),
            "entire_feature_covered": bool(geometry.covers(geom)),
            "mask_intersects_feature": bool(geometry.intersects(geom)),
            "aliases_matched": [alias for alias in TARGETS if alias in
                {part.strip().lower() for key in ("name", "name:en", "alt_name")
                 for part in tags.get(key, "").split(";")}],
            "exclusion_or_remote_reference_geometry": mapping(geom) if target in
                ("middle rocks", "pedra branca", "south ledge") else None,
            "geometry_kind": geom.geom_type})

    class FindNames(osmium.SimpleHandler):
        def node(self, obj):
            if not obj.tags:
                return
            tags = dict(obj.tags)
            target = target_name(tags)
            if target:
                record("node", obj.id, tags, Point(obj.location.lon, obj.location.lat), target)

        def way(self, obj):
            if not obj.tags:
                return
            tags = dict(obj.tags)
            target = target_name(tags) or KNOWN_WAY_WITNESSES.get(obj.id)
            if target:
                ids = [n.ref for n in obj.nodes]
                selected_ways.append((obj.id, tags, ids, target))
                wanted.update(ids)

        def relation(self, obj):
            nonlocal source_country
            if obj.id == RELATION:
                source_country = {"version": obj.version, "tags": dict(obj.tags),
                    "members": [{"type": m.type, "ref": m.ref, "role": m.role} for m in obj.members]}
            tags = dict(obj.tags)
            target = target_name(tags)
            if target:
                selected_relations.append((obj.id, tags,
                    [(m.type, m.ref, m.role) for m in obj.members], target))

    # libosmium filters before Python callbacks: the regional source contains
    # millions of unrelated road/building nodes, which need no Python objects.
    finder = FindNames()
    for obj in osmium.FileProcessor(str(source)).with_filter(osmium.filter.KeyFilter(
            "place", "natural", "ISO3166-1", "ISO3166-1:alpha2")):
        if isinstance(obj, osmium.osm.Node):
            finder.node(obj)
        elif isinstance(obj, osmium.osm.Way):
            finder.way(obj)
        elif isinstance(obj, osmium.osm.Relation):
            finder.relation(obj)

    relation_way_ids = {ref for _, _, members, _ in selected_relations
                        for kind, ref, role in members if kind == "w" and role in ("", "outer", "inner")}
    if relation_way_ids:
        for obj in osmium.FileProcessor(str(source), osmium.osm.WAY).with_filter(
                osmium.filter.IdFilter(relation_way_ids)):
            ids = [n.ref for n in obj.nodes]
            relation_ways[obj.id] = ids
            wanted.update(ids)

    class FindCoordinates(osmium.SimpleHandler):
        def node(self, obj):
            if obj.id in wanted:
                coordinates[obj.id] = (obj.location.lon, obj.location.lat)

    if wanted:
        coordinate_finder = FindCoordinates()
        for obj in osmium.FileProcessor(str(source), osmium.osm.NODE).with_filter(
                osmium.filter.IdFilter(wanted)):
            coordinate_finder.node(obj)
    for wid, tags, ids, target in selected_ways:
        if any(nid not in coordinates for nid in ids):
            found.append({"osm": f"way/{wid}", "target": target,
                          "error": "source way has missing node coordinates"})
            continue
        points = [coordinates[nid] for nid in ids]
        geom = Polygon(points) if len(points) >= 4 and ids[0] == ids[-1] else LineString(points)
        record("way", wid, tags, geom, target)
    for rid, tags, members, target in selected_relations:
        lines = {"outer": [], "inner": []}
        incomplete = False
        for kind, ref, role in members:
            if role not in ("", "outer", "inner"):
                continue
            ids = relation_ways.get(ref) if kind == "w" else None
            if not ids or any(nid not in coordinates for nid in ids):
                incomplete = True
                break
            lines["inner" if role == "inner" else "outer"].append(
                LineString([coordinates[nid] for nid in ids]))
        outer = list(polygonize(lines["outer"])) if not incomplete else []
        if not outer:
            found.append({"osm": f"relation/{rid}", "target": target,
                          "error": "island relation has unresolved or unclosed geometry"})
            continue
        geom = unary_union(outer)
        if lines["inner"]:
            geom = geom.difference(unary_union(list(polygonize(lines["inner"]))))
        record("relation", rid, tags, geom, target)
    return sorted(found, key=lambda item: (item["target"], item["osm"])), (
        source_country == boundary_reader.relations.get(RELATION))


def audit(mask_path, boundary_path, source_path=None):
    feature = country_feature(json.loads(Path(mask_path).read_text(encoding="utf-8-sig")))
    geometry = shape(feature["geometry"])
    reader = BoundaryReader()
    reader.apply_file(str(boundary_path))
    missing = missing_references(reader)
    rebuilt = rebuild_boundary(boundary_path)
    errors = geometry_checks(geometry, rebuilt)
    if missing:
        errors.append("Boundary PBF has unresolved references")
    relation = reader.relations.get(RELATION)
    if relation is None:
        raise ValueError("Boundary PBF lacks Singapore relation/536780")
    components = sorted(list(geometry.geoms) if geometry.geom_type == "MultiPolygon"
                        else [geometry], key=lambda p: p.bounds)
    component_rows = []
    for i, component in enumerate(components):
        refs = []
        for member in relation["members"]:
            if member["type"] == "w" and member["role"] in ("outer", "inner"):
                way = reader.ways.get(member["ref"])
                if way and all(nid in reader.nodes for nid in way["nodes"]):
                    line = LineString([reader.nodes[nid] for nid in way["nodes"]])
                    if component.boundary.covers(line):
                        refs.append(member["ref"])
        area, perimeter = GEOD.geometry_area_perimeter(component)
        component_rows.append({"component_id": i + 1, "bbox_lon_lat": list(component.bounds),
            "administrative_area_km2_not_land_area": abs(area) / 1e6,
            "perimeter_m": perimeter, "outer_vertex_count": len(component.exterior.coords),
            "interior_rings": len(component.interiors), "outer_way_ids": refs})
    witnesses, source_relation_equal = source_witnesses(
        source_path, geometry, components, reader) if source_path else ([], None)
    if source_path and not source_relation_equal:
        errors.append("Boundary relation version, tags or members differ from source PBF")
    matched = {name for witness in witnesses
               for name in [witness["target"], *witness.get("aliases_matched", [])]}
    scope_findings = land_scope_findings(witnesses)
    envelope_area = abs(GEOD.geometry_area_perimeter(box(*geometry.bounds))[0])
    actual_area = sum(abs(GEOD.geometry_area_perimeter(p)[0]) for p in components)
    return {"schema": 1,
        "status": "FAIL" if errors else "REVIEW_REQUIRED" if scope_findings else "PASS",
        "boundary_integrity": "FAIL" if errors else "PASS",
        "land_scope": "REVIEW_REQUIRED" if scope_findings else "SAMPLE_CHECKS_ONLY" if source_path else "NOT_CHECKED",
        "country_land_filter_accepted": False,
        "land_scope_findings": scope_findings,
        "scope": "OSM source boundary consistency, not survey-complete land or legal sovereignty validation",
        "country_relation": f"https://www.openstreetmap.org/relation/{RELATION}",
        "relation_version": relation["version"], "mask_sha256": sha256(mask_path),
        "boundary_pbf_sha256": sha256(boundary_path),
        "source_pbf_sha256": sha256(source_path) if source_path else None,
        "valid": geometry.is_valid, "validity_detail": explain_validity(geometry),
        "geometry_kind": geometry.geom_type, "component_count": len(components),
        "components": component_rows, "relation_members": relation["members"],
        "pbf_object_counts": {"nodes": len(reader.nodes), "ways": len(reader.ways),
                              "relations": len(reader.relations)},
        "missing_references": missing, "rebuilt_geometry_topologically_equal": geometry.equals(rebuilt),
        "source_country_relation_equal": source_relation_equal,
        "bounding_rectangle_is_mask": geometry.equals(box(*geometry.bounds)),
        "administrative_area_km2_not_land_area": actual_area / 1e6,
        "bounding_rectangle_area_km2": envelope_area / 1e6,
        "bounding_rectangle_overstates_area_factor": envelope_area / actual_area,
        "source_land_witnesses": witnesses,
        "unmatched_witness_names": sorted(set(TARGETS) - matched) if source_path else list(TARGETS),
        "warnings": ["Do not fill administrative water polygons as land; use coastline and elevation data.",
            "Three administrative components do not mean three islands.",
            "OSM remote maritime boundaries are not evidence of settled sovereignty.",
            "Named source witnesses are sample checks, not proof that every coastline or island is complete."],
        "errors": errors}


def export_exclusions(mask_path, result, destination):
    """Write derived artifacts; never alter the original OSM administrative mask."""
    destination.mkdir(parents=True, exist_ok=True)
    source = country_feature(json.loads(mask_path.read_text(encoding="utf-8-sig")))
    original = shape(source["geometry"])
    references = [w for w in result["source_land_witnesses"] if w["target"] == "middle rocks"]
    if {w["osm"] for w in references} != {f"way/{wid}" for wid in KNOWN_WAY_WITNESSES}:
        raise ValueError("Both exact Middle Rocks source coastlines are required for exclusions")
    citation = "https://geneva-un.mfa.gov.sg/mission-updates/press-20170701-01-jul-2017/"
    features = [{"type": "Feature", "id": w["osm"], "properties": {
        "name": "Middle Rocks", "source_osm_url": w["url"], "country": "MY",
        "source_pbf_sha256": result["source_pbf_sha256"], "official_citation": citation,
        "reason": "Malaysian land inside OSM Singapore maritime administrative geometry",
        "sampling_rule": "Exclusion covers(point) takes precedence, including its boundary."},
        "geometry": w["exclusion_or_remote_reference_geometry"]} for w in references]
    exclusions = unary_union([shape(f["geometry"]) for f in features])
    derived = original.difference(exclusions)
    if not derived.is_valid:
        raise ValueError("Derived mask is invalid: " + explain_validity(derived))
    remaining_foreign_interior = derived.intersection(exclusions).area
    if remaining_foreign_interior > 1e-18:
        raise ValueError("Derived mask still contains Middle Rocks land interior")
    remote_checks = []
    for witness in result["source_land_witnesses"]:
        if witness["target"] in ("pedra branca", "south ledge"):
            retained = derived.covers(shape(witness["exclusion_or_remote_reference_geometry"]))
            remote_checks.append({"osm": witness["osm"], "target": witness["target"], "retained": retained})
            if not retained:
                raise ValueError("Named exclusion accidentally removed a separate remote reference")
    exclusion_path = destination / "foreign-land-exclusions.geojson"
    derived_path = destination / "singapore-admin-mask-minus-middle-rocks.geojson"
    exclusion_path.write_text(json.dumps({"type": "FeatureCollection", "features": features},
                                       indent=2) + "\n", encoding="utf-8")
    derived_path.write_text(json.dumps({"type": "FeatureCollection", "features": [{
        "type": "Feature", "id": "fork/singapore-admin-mask-minus-middle-rocks/v1",
        "properties": {"version": 1, "name": "OSM Singapore administrative selection minus Middle Rocks land",
            "source_relation": result["country_relation"], "source_relation_version": result["relation_version"],
            "source_mask_sha256": result["mask_sha256"], "excluded_osm_ids": [w["osm"] for w in references],
            "exclusion_geojson_sha256": sha256(exclusion_path), "official_citation": citation,
            "scope": "Derived administrative selection geometry, NOT a complete Singapore land mask",
            "south_ledge_status": "Unresolved maritime delimitation; retained as unverified OSM provenance",
            "sampling_rule": "Exclude when foreign-land-exclusions.covers(point), then apply this mask."},
        "geometry": mapping(derived)}]}, indent=2) + "\n", encoding="utf-8")
    receipt = {"version": 1, "original_mask_sha256": sha256(mask_path),
        "original_mask_unchanged": sha256(mask_path) == result["mask_sha256"],
        "exclusion_geojson_sha256": sha256(exclusion_path), "derived_geojson_sha256": sha256(derived_path),
        "negative_control": "PASS", "remaining_middle_rocks_interior_area_degrees2": remaining_foreign_interior,
        "exclusion_count": len(features), "derived_valid": derived.is_valid,
        "remote_reference_checks": remote_checks,
        "land_scope": "REVIEW_REQUIRED_UNTIL_RENDER_ADAPTER_APPLIES_EXCLUSIONS",
        "country_land_filter_accepted": False,
        "south_ledge_sovereignty_verified": False,
        "coordinate_reference": "EPSG:4326 longitude, latitude",
        "sampling_rule": "Exclusion covers(point) takes precedence, including the shared boundary."}
    receipt_path = destination / "foreign-land-exclusions.receipt.json"
    receipt_path.write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    return receipt


class AuditTests(unittest.TestCase):
    def test_rejects_rectangle(self):
        self.assertTrue(geometry_checks(box(103, 1, 104, 2)))

    def test_rejects_invalid_polygon(self):
        self.assertTrue(geometry_checks(Polygon([(0, 0), (1, 1), (1, 0), (0, 1), (0, 0)])))

    def test_detects_dropped_remote_component(self):
        first = Polygon([(0, 0), (2, 0), (1, 2), (0, 0)])
        remote = Polygon([(4, 0), (6, 0), (5, 2), (4, 0)])
        complete = unary_union([first, remote])
        self.assertEqual(geometry_checks(complete, complete), [])
        self.assertTrue(geometry_checks(first, complete))

    def test_missing_way_and_node_refs(self):
        reader = BoundaryReader()
        reader.ways[3] = {"nodes": [8]}
        reader.relations[2] = {"members": [{"type": "w", "ref": 9, "role": "outer"}]}
        self.assertEqual(len(missing_references(reader)), 2)

    def test_rejects_wrong_country(self):
        with self.assertRaises(ValueError):
            country_feature({"type": "FeatureCollection", "features": []})

    def test_avoids_similarly_named_foreign_island(self):
        self.assertIsNone(target_name({"place": "island", "name": "Pulau Ujong Buloh"}))
        self.assertEqual(target_name({"place": "island", "name:en": "Pulau Ujong"}), "pulau ujong")

    def test_negative_control_prevents_country_land_claim(self):
        included = {"target": "middle rocks", "osm": "way/1359256910", "mask_intersects_feature": True}
        excluded = dict(included, mask_intersects_feature=False)
        self.assertEqual(len(land_scope_findings([included])), 1)
        self.assertEqual(land_scope_findings([excluded]), [])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mask", type=Path)
    parser.add_argument("--boundary-pbf", type=Path)
    parser.add_argument("--source-pbf", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    parser.add_argument("--export-exclusions", type=Path,
                        help="Export explicit Middle Rocks exclusions and a separately named derived mask")
    args = parser.parse_args()
    if args.self_test:
        result = unittest.TextTestRunner().run(unittest.defaultTestLoader.loadTestsFromTestCase(AuditTests))
        return 0 if result.wasSuccessful() else 1
    if not all((args.mask, args.boundary_pbf, args.output)):
        parser.error("--mask, --boundary-pbf and --output are required")
    result = audit(args.mask, args.boundary_pbf, args.source_pbf)
    if args.export_exclusions:
        result["derived_exclusion_receipt"] = export_exclusions(args.mask, result, args.export_exclusions)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("status", "boundary_integrity", "land_scope",
        "component_count", "mask_sha256", "missing_references", "rebuilt_geometry_topologically_equal",
        "land_scope_findings", "errors")}))
    return 0 if result["status"] == "PASS" else 2 if result["status"] == "REVIEW_REQUIRED" else 1


if __name__ == "__main__":
    sys.exit(main())
