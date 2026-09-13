"""Versioned, dependency-free Overpass spatial index. All bounds are EPSG:3414 E/N."""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import sqlite3
import sys
import time
from contextlib import closing

SCHEMA = "fork-spatial-index-v1"
SELECTABLE_RELATIONS = {"multipolygon", "building"}
CLIPPING_CONTRACT = "Full original geometry retained; renderer must clip output cells against accepted country mask and tile core."


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def svy21(lon, lat):
    # WGS84 Transverse Mercator, EPSG:3414; same global grid as fidelity validator.
    a, f = 6378137, 1 / 298.257223563
    e2 = f * (2 - f)
    e4, e6, ep2 = e2 * e2, e2 ** 3, e2 / (1 - e2)
    p, p0, l0 = map(math.radians, (lat, 1 + 22 / 60, 103 + 50 / 60))
    def meridian(x):
        return a * ((1-e2/4-3*e4/64-5*e6/256)*x-(3*e2/8+3*e4/32+45*e6/1024)*math.sin(2*x)+(15*e4/256+45*e6/1024)*math.sin(4*x)-35*e6/3072*math.sin(6*x))
    n = a / math.sqrt(1 - e2 * math.sin(p) ** 2)
    t, c, A = math.tan(p) ** 2, ep2 * math.cos(p) ** 2, math.cos(p) * (math.radians(lon) - l0)
    return (28001.642+n*(A+(1-t+c)*A**3/6+(5-18*t+t*t+72*c-58*ep2)*A**5/120),
            38744.572+meridian(p)-meridian(p0)+n*math.tan(p)*(A*A/2+(5-t+9*c+4*c*c)*A**4/24+(61-58*t+t*t+600*c-330*ep2)*A**6/720))


def elements_stream(path, chunk_size=1024 * 1024):
    """Read one element at a time, including elements split across any chunk boundary."""
    decoder = json.JSONDecoder()
    with open(path, encoding="utf-8-sig") as f:
        buffer = ""
        while True:
            block = f.read(chunk_size)
            if not block:
                raise ValueError("Overpass elements array missing")
            buffer += block
            import re
            match = re.search(r'"elements"\s*:\s*\[', buffer)
            if match:
                buffer = buffer[match.end():]
                break
            if len(buffer) > 4 * 1024 * 1024:
                raise ValueError("Overpass header exceeds 4 MiB")
        eof, position = False, 0
        while True:
            while position < len(buffer) and buffer[position] in " \r\n\t,":
                position += 1
            if position < len(buffer) and buffer[position] == "]":
                return
            if position < len(buffer):
                try:
                    value, end = decoder.raw_decode(buffer,position)
                except json.JSONDecodeError:
                    if eof:
                        raise ValueError("Truncated or malformed Overpass element")
                else:
                    if not isinstance(value, dict):
                        raise ValueError("Overpass element must be an object")
                    yield value
                    position = end
                    continue
            if eof:
                raise ValueError("Unterminated Overpass elements array")
            block = f.read(chunk_size)
            eof = not block
            buffer = buffer[position:] + block
            position = 0


def feature_class(tags):
    if tags.get("building:part") not in (None, "no"):
        return "building_part"
    if tags.get("building") not in (None, "no"):
        return "building"
    if tags.get("type") == "building":
        return "building_group"
    for key in ("highway", "railway", "waterway", "natural", "landuse", "amenity"):
        if key in tags:
            return key
    return "other"


def open_index(path):
    db = sqlite3.connect(Path(path).resolve().as_uri() + "?mode=ro", uri=True)
    db.row_factory = sqlite3.Row
    meta = dict(db.execute("SELECT key,value FROM metadata"))
    if meta.get("schema") != SCHEMA or meta.get("complete") != "true":
        db.close()
        raise ValueError("Incomplete or incompatible spatial index")
    return db, meta


def build(source, index, expected_sha256=None):
    source, index = Path(source).resolve(), Path(index).resolve()
    if source == index:
        raise ValueError("Source and index must differ")
    started = time.monotonic()
    source_hash = sha256(source)
    if expected_sha256 and source_hash != expected_sha256.lower():
        raise ValueError("Source SHA256 does not match accepted source")
    if index.exists():
        db, meta = open_index(index)
        db.close()
        if meta["sourceSha256"] == source_hash:
            return {"cacheHit": True, "sourceSha256": source_hash, "index": str(index)}
        raise ValueError("Index contains another source; use a new versioned path")
    index.parent.mkdir(parents=True, exist_ok=True)
    temp = index.with_name(index.name + ".building")
    if temp.exists():
        raise ValueError("Index build already exists; inspect its owner before recovery")
    db = sqlite3.connect(temp)
    db.executescript("""
        PRAGMA journal_mode=OFF; PRAGMA synchronous=OFF; PRAGMA temp_store=FILE;
        PRAGMA cache_size=-65536;
        CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL);
        CREATE TABLE elements(kind TEXT NOT NULL,id INTEGER NOT NULL,payload TEXT NOT NULL,
          min_e REAL,max_e REAL,min_n REAL,max_n REAL,selectable INTEGER NOT NULL,
          class TEXT NOT NULL,UNIQUE(kind,id));
        CREATE TABLE refs(owner_kind TEXT,owner_id INTEGER,member_kind TEXT,member_id INTEGER,role TEXT);
        CREATE VIRTUAL TABLE spatial USING rtree(element_rowid,min_e,max_e,min_n,max_n);
    """)
    count = 0
    rows, refs = [], []
    try:
        for e in elements_stream(source):
            kind, identifier = e["type"], e["id"]
            if kind not in ("node", "way", "relation") or not isinstance(identifier, int):
                raise ValueError("Unsupported element type/id")
            tags = e.get("tags", {})
            bounds = (None,) * 4
            if kind == "node":
                east, north = svy21(e["lon"], e["lat"])
                if not all(map(math.isfinite, (east, north))):
                    raise ValueError("Non-finite source coordinate")
                bounds = (east, east, north, north)
            if kind == "way":
                refs.extend((kind, identifier, "node", n, "") for n in e.get("nodes", []))
            if kind == "relation":
                refs.extend((kind, identifier, m["type"], m["ref"], m.get("role", "")) for m in e.get("members", []))
            selectable = kind == "way" or (kind == "node" and bool(tags)) or (kind == "relation" and tags.get("type") in SELECTABLE_RELATIONS)
            rows.append((kind, identifier, canonical(e), *bounds, int(selectable), feature_class(tags)))
            count += 1
            if len(rows) >= 5000 or len(refs) >= 50000:
                db.executemany("INSERT INTO elements VALUES (?,?,?,?,?,?,?,?,?)", rows)
                db.executemany("INSERT INTO refs VALUES (?,?,?,?,?)", refs)
                rows.clear(); refs.clear()
        db.executemany("INSERT INTO elements VALUES (?,?,?,?,?,?,?,?,?)", rows)
        db.executemany("INSERT INTO refs VALUES (?,?,?,?,?)", refs)
        db.executescript("CREATE INDEX refs_owner ON refs(owner_kind,owner_id); CREATE INDEX refs_member ON refs(member_kind,member_id); CREATE INDEX element_class ON elements(class);")
        missing = db.execute("SELECT r.* FROM refs r LEFT JOIN elements e ON e.kind=r.member_kind AND e.id=r.member_id WHERE e.id IS NULL LIMIT 12").fetchall()
        if missing:
            raise ValueError("Missing source references: " + repr(missing))
        db.execute("""UPDATE elements AS w SET (min_e,max_e,min_n,max_n)=(
          SELECT min(n.min_e),max(n.max_e),min(n.min_n),max(n.max_n)
          FROM refs r JOIN elements n ON n.kind=r.member_kind AND n.id=r.member_id
          WHERE r.owner_kind='way' AND r.owner_id=w.id) WHERE w.kind='way'""")
        # Relations may refer to other relations. Monotonic bounds reach a fixed point.
        relation_count = db.execute("SELECT count(*) FROM elements WHERE kind='relation'").fetchone()[0]
        for _ in range(relation_count + 1):
            changed = db.execute("""UPDATE elements AS p SET (min_e,max_e,min_n,max_n)=(
              SELECT min(c.min_e),max(c.max_e),min(c.min_n),max(c.max_n)
              FROM refs r JOIN elements c ON c.kind=r.member_kind AND c.id=r.member_id
              WHERE r.owner_kind='relation' AND r.owner_id=p.id)
              WHERE p.kind='relation' AND (min_e,max_e,min_n,max_n) IS NOT (
              SELECT min(c.min_e),max(c.max_e),min(c.min_n),max(c.max_n)
              FROM refs r JOIN elements c ON c.kind=r.member_kind AND c.id=r.member_id
              WHERE r.owner_kind='relation' AND r.owner_id=p.id)""").rowcount
            if not changed:
                break
        else:
            raise ValueError("Relation geometry bounds did not converge")
        db.execute("INSERT INTO spatial SELECT rowid,min_e,max_e,min_n,max_n FROM elements WHERE selectable=1 AND min_e IS NOT NULL")
        metadata = {"schema": SCHEMA, "sourceSha256": source_hash, "complete": "true", "elementCount": str(count), "projection": "EPSG:3414", "sourceBytes": str(source.stat().st_size)}
        db.executemany("INSERT INTO metadata VALUES (?,?)", metadata.items())
        db.commit()
        if sha256(source) != source_hash:
            raise ValueError("Source changed while indexing")
        db.close()
        os.replace(temp, index)
        return {"cacheHit": False, **metadata, "index": str(index), "seconds": round(time.monotonic() - started, 3)}
    except BaseException:
        db.close()
        # The diagnostic .building file is retained; never publish an incomplete index.
        raise


def touching(db, bounds, classes=None):
    e0, n0, e1, n1 = bounds
    sql = """SELECT e.* FROM spatial s JOIN elements e ON e.rowid=s.element_rowid
      WHERE s.max_e>=? AND s.min_e<=? AND s.max_n>=? AND s.min_n<=?
      AND e.max_e>=? AND e.min_e<=? AND e.max_n>=? AND e.min_n<=?"""
    params = [e0,e1,n0,n1,e0,e1,n0,n1]
    if classes:
        sql += " AND e.class IN (" + ",".join("?" for _ in classes) + ")"
        params.extend(classes)
    return db.execute(sql, params).fetchall()


def point_in_ring(point, ring):
    x, y = point
    inside = False
    for (x0,y0),(x1,y1) in zip(ring, ring[1:]):
        cross = (x-x0)*(y1-y0)-(y-y0)*(x1-x0)
        if abs(cross) < 1e-7 and min(x0,x1)-1e-8 <= x <= max(x0,x1)+1e-8 and min(y0,y1)-1e-8 <= y <= max(y0,y1)+1e-8:
            return True
        if (y0 > y) != (y1 > y) and x < (x1-x0)*(y-y0)/(y1-y0)+x0:
            inside = not inside
    return inside


def rings_for(db, feature):
    e = json.loads(feature["payload"])
    if e["type"] == "way":
        paths = [("outer", e.get("nodes", []))]
    else:
        paths = []
        for m in e.get("members", []):
            if m["type"] == "way" and m.get("role", "") in ("", "outer", "inner", "outline"):
                row = db.execute("SELECT payload FROM elements WHERE kind='way' AND id=?", (m["ref"],)).fetchone()
                paths.append(("inner" if m.get("role") == "inner" else "outer", json.loads(row[0])["nodes"]))
    result = {"outer": [], "inner": []}
    for role in result:
        pending = [list(p) for r,p in paths if r == role and p]
        while pending:
            chain = pending.pop()
            while chain[0] != chain[-1]:
                found = False
                for i, p in enumerate(pending):
                    if chain[-1] == p[0]:
                        chain += p[1:]; found = True
                    elif chain[-1] == p[-1]:
                        chain += p[-2::-1]; found = True
                    elif chain[0] == p[-1]:
                        chain = p[:-1] + chain; found = True
                    elif chain[0] == p[0]:
                        chain = p[:0:-1] + chain; found = True
                    if found:
                        pending.pop(i)
                        break
                if not found:
                    return None
            if len(chain) >= 4:
                result[role].append([tuple(db.execute("SELECT min_e,min_n FROM elements WHERE kind='node' AND id=?", (n,)).fetchone()) for n in chain])
    return result if result["outer"] else None


def contains_feature(db, parent, child, ring_cache):
    if not (parent["min_e"] <= child["min_e"] and parent["max_e"] >= child["max_e"] and parent["min_n"] <= child["min_n"] and parent["max_n"] >= child["max_n"]):
        return False
    key = (parent["kind"], parent["id"])
    if key not in ring_cache:
        ring_cache[key] = rings_for(db, parent)
    rings = ring_cache[key]
    if not rings:
        return False
    children = rings_for(db, child)
    if not children:
        return False
    points = [p for ring in children["outer"] for p in ring]
    if not points or not all(any(point_in_ring(p,r) for r in rings["outer"]) and not any(point_in_ring(p,r) for r in rings["inner"]) for p in points):
        return False
    def orientation(a,b,c):
        return (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
    def crosses(a,b,c,d):
        return orientation(a,b,c)*orientation(a,b,d) < -1e-12 and orientation(c,d,a)*orientation(c,d,b) < -1e-12
    # Vertex-only containment is insufficient for concave outlines or courtyard holes.
    for child_ring in children["outer"]:
        for a,b in zip(child_ring,child_ring[1:]):
            for parent_ring in rings["outer"] + rings["inner"]:
                if any(crosses(a,b,c,d) for c,d in zip(parent_ring,parent_ring[1:])):
                    return False
        if any(point_in_ring(hole[0],child_ring) for hole in rings["inner"]):
            return False
    return True


def export(index, bounds, output):
    if len(bounds) != 4 or not all(map(math.isfinite, bounds)) or bounds[0] >= bounds[2] or bounds[1] >= bounds[3]:
        raise ValueError("Bounds require minE minN maxE maxN")
    db, meta = open_index(index)
    with closing(db):
        return _export(db,meta,bounds,output)


def validate_cached_manifest(cached, identity, identity_hash, output):
    """A matching file hash alone does not validate the closure/identity receipt."""
    required = set(identity) | {"exportIdentitySha256", "subsetSha256", "closureSha256", "referenceComplete", "counts", "sourceClasses", "cacheHit", "countryMaskApplied", "clippingContract"}
    if not isinstance(cached, dict) or not required.issubset(cached):
        raise ValueError("Cached export manifest is missing required contract fields")
    if canonical({key:cached[key] for key in identity}) != canonical(identity) or cached["exportIdentitySha256"] != identity_hash:
        raise ValueError("Cached export manifest identity does not match this source/query")
    digest = cached["subsetSha256"]
    if not isinstance(digest,str) or len(digest) != 64 or any(c not in "0123456789abcdef" for c in digest):
        raise ValueError("Cached export manifest has an invalid subset SHA256")
    if cached["referenceComplete"] is not True or cached["closureSha256"] != digest:
        raise ValueError("Cached export manifest does not bind a complete closure to its subset")
    if cached["countryMaskApplied"] is not False or cached["clippingContract"] != CLIPPING_CONTRACT or type(cached["cacheHit"]) is not bool:
        raise ValueError("Cached export manifest clipping/cache contract is invalid")
    for key in ("counts", "sourceClasses"):
        value = cached[key]
        if not isinstance(value,dict) or any(not isinstance(k,str) or type(n) is not int or n < 0 for k,n in value.items()):
            raise ValueError("Cached export manifest counts are invalid")
    if not set(cached["counts"]).issubset({"node","way","relation"}):
        raise ValueError("Cached export manifest contains unsupported element counts")
    if sha256(output) != digest:
        raise ValueError("Export path has a different or modified version; choose a new path")
    # Validate metadata against the small cached subset, never the national source.
    counts, classes, present, references = {}, {}, set(), set()
    for e in elements_stream(output):
        kind, identifier = e.get("type"), e.get("id")
        key = (kind,identifier)
        if kind not in ("node","way","relation") or type(identifier) is not int or key in present:
            raise ValueError("Cached export contains an invalid or duplicate element")
        present.add(key)
        counts[kind] = counts.get(kind,0) + 1
        if kind != "node" or bool(e.get("tags")):
            cls = feature_class(e.get("tags",{}))
            classes[cls] = classes.get(cls,0) + 1
        if kind == "way":
            references.update(("node",n) for n in e.get("nodes",[]))
        elif kind == "relation":
            references.update((m["type"],m["ref"]) for m in e.get("members",[]))
    if counts != cached["counts"] or classes != cached["sourceClasses"] or not references.issubset(present):
        raise ValueError("Cached export counts or reference closure do not match its manifest")


def _export(db,meta,bounds,output):
    output = Path(output).resolve()
    sidecar = output.with_suffix(output.suffix + ".manifest.json")
    identity = {"schema": SCHEMA, "sourceSha256": meta["sourceSha256"], "boundsEPSG3414": list(map(float,bounds)), "closurePolicy": "full-way+geometric-relation+building-containment-v1"}
    identity_hash = hashlib.sha256(canonical(identity).encode()).hexdigest()
    if output.exists() and sidecar.exists():
        cached = json.loads(sidecar.read_text(encoding="utf-8"))
        validate_cached_manifest(cached,identity,identity_hash,output)
        db.close()
        return {**cached, "cacheHit": True}
    if output.exists() or sidecar.exists():
        raise ValueError("Incomplete existing export; inspect it before recovery")
    selected = {}
    pending = []
    def add(row):
        key = (row["kind"], row["id"])
        if key not in selected:
            selected[key] = row
            pending.append(key)
    for row in touching(db,bounds):
        add(row)
    ring_cache = {}
    processed_groups = set()
    while pending:
        kind, identifier = pending.pop()
        row = selected[kind, identifier]
        # Preserve complete original references, then ascend only geometric groups.
        for child in db.execute("SELECT e.* FROM refs r JOIN elements e ON e.kind=r.member_kind AND e.id=r.member_id WHERE r.owner_kind=? AND r.owner_id=?", (kind, identifier)):
            add(child)
        if kind != "node":
            for parent in db.execute("SELECT e.* FROM refs r JOIN elements e ON e.kind=r.owner_kind AND e.id=r.owner_id WHERE r.member_kind=? AND r.member_id=? AND e.kind='relation' AND e.selectable=1", (kind, identifier)):
                add(parent)
        if row["class"] in ("building", "building_part") and (kind,identifier) not in processed_groups:
            processed_groups.add((kind,identifier))
            box = (row["min_e"], row["min_n"], row["max_e"], row["max_n"])
            if row["class"] == "building_part":
                for parent in touching(db,box,["building"]):
                    if contains_feature(db,parent,row,ring_cache):
                        add(parent)
            else:
                for child in touching(db,box,["building_part"]):
                    if contains_feature(db,row,child,ring_cache):
                        add(child)
    output.parent.mkdir(parents=True, exist_ok=True)
    temp = output.with_name(output.name + ".writing")
    if temp.exists():
        raise ValueError("Another exporter may own this output")
    h = hashlib.sha256()
    counts, classes = {}, {}
    with open(temp,"xb") as f:
        def emit(text):
            b = text.encode("utf-8"); f.write(b); h.update(b)
        emit('{"version":0.6,"generator":"FORK indexed source closure","elements":[')
        ordered = sorted(selected, key=lambda k: ({"node":0,"way":1,"relation":2}[k[0]],k[1]))
        for i,key in enumerate(ordered):
            if i:
                emit(",")
            row = selected[key]
            emit(row["payload"])
            counts[key[0]] = counts.get(key[0],0) + 1
            if key[0] != "node" or row["selectable"]:
                classes[row["class"]] = classes.get(row["class"],0) + 1
        emit("]}\n")
    subset_hash = h.hexdigest()
    result = {**identity, "exportIdentitySha256": identity_hash, "subsetSha256": subset_hash, "closureSha256": subset_hash, "referenceComplete": True, "counts": counts, "sourceClasses": classes, "cacheHit": False, "countryMaskApplied": False, "clippingContract": CLIPPING_CONTRACT}
    os.replace(temp, output)
    sidecar.write_text(canonical(result) + "\n", encoding="utf-8")
    db.close()
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    b = commands.add_parser("build")
    b.add_argument("--source", required=True); b.add_argument("--index", required=True)
    b.add_argument("--expected-sha256")
    x = commands.add_parser("export")
    x.add_argument("--index", required=True); x.add_argument("--bounds", nargs=4, type=float, required=True)
    x.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.command == "build":
        result = build(args.source,args.index,args.expected_sha256)
    else:
        result = export(args.index,args.bounds,args.output)
    print(canonical(result))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, sqlite3.Error, KeyError) as error:
        print(canonical({"error":str(error)}),file=sys.stderr)
        sys.exit(1)
