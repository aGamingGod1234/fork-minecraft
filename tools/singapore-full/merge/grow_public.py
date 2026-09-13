"""Package a qualified preview without changing source ZIPs or existing saves.

package_public(candidate_zip, public_metadata, output_dir) creates a NEW directory
under package.OUTPUT_ROOT. Metadata keys are packageName, saveFolder, worldName,
sources, omissions, limitations, sourceComplete/routeComplete/fullFidelity=false,
and private gateEvidence {grow,runtime,plan}, each {path,sha256}. Optional
omissionEvidence {records,redactions} preserves public-safe source records in
SOURCE-OMISSIONS.json. Summaries then also carry the exact original scope;
outside_render permits both impact flags false, unresolved scope requires null.
A source has
filename,snapshotDate,sha256,url,licenseUrl. An omission has sourceId,reason,count,
affectsCore,affectsHalo. All facts must come from actual frozen records.

The original archive must contain one world folder and root README.txt only.
Existing README bytes and every world payload byte are retained. Gate paths and
raw receipts stay outside the ZIP. Failed writes retain only an INCOMPLETE file.
"""
from __future__ import annotations

from datetime import date
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
from urllib.parse import urlsplit
import zipfile

import package

ODBL = "https://opendatacommons.org/licenses/odbl/1-0/"
COPYRIGHT = "https://www.openstreetmap.org/copyright"
FLAGS = ("sourceComplete", "routeComplete", "fullFidelity")
REGION = re.compile(r"dimensions/minecraft/overworld/region/r\.-?\d+\.-?\d+\.mca")
PRIVATE = re.compile(r"(?i)((?<![a-z])[a-z]:[\\/]|\\\\|/(?:Users|home)/|(?:api[_ -]?key|bearer|password|token)\s*[:=]|[\w.+-]+@[\w.-]+\.[a-z]{2,})")


def _need(value, message):
    if not value:
        raise ValueError(message)


def _sha(data):
    return hashlib.sha256(data).hexdigest()


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False).encode("utf-8")


def _keys(value, names, label):
    _need(isinstance(value, dict) and set(value) == set(names), "Invalid " + label + " fields")


def _text(value, label):
    _need(isinstance(value, str) and 0 < len(value) <= 1000 and not PRIVATE.search(value)
          and not any(ord(c) < 32 for c in value), "Invalid public " + label)
    return value


def _hash(value):
    _need(isinstance(value, str) and re.fullmatch(r"[a-f0-9]{64}", value), "Invalid SHA256")
    return value


def _name(value):
    _need(isinstance(value, str) and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,99}", value)
          and value not in (".", "..") and not value.endswith("."), "Invalid public filename")
    _need(value.split(".")[0].upper() not in {"CON", "PRN", "AUX", "NUL", *["COM"+str(i) for i in range(1, 10)],
          *["LPT"+str(i) for i in range(1, 10)]}, "Reserved filename")
    return value


def _metadata(metadata):
    expected = {"packageName", "saveFolder", "worldName", "sources", "omissions", "limitations", "gateEvidence", *FLAGS}
    _need(isinstance(metadata, dict) and set(metadata) in (expected, expected | {"omissionEvidence"}), "Invalid metadata fields")
    _name(metadata["packageName"])
    _name(metadata["saveFolder"])
    _text(metadata["worldName"], "world name")
    _need(all(metadata[k] is False for k in FLAGS), "Preview completeness flags must remain false")
    _need(isinstance(metadata["sources"], list) and metadata["sources"], "Frozen public sources required")
    for source in metadata["sources"]:
        _keys(source, ("filename", "snapshotDate", "sha256", "url", "licenseUrl"), "source")
        _name(source["filename"])
        _hash(source["sha256"])
        _need(isinstance(source["snapshotDate"], str) and date.fromisoformat(source["snapshotDate"]).isoformat() == source["snapshotDate"], "Invalid snapshot date")
        parsed = urlsplit(_text(source["url"], "source URL"))
        _need(parsed.scheme == "https" and parsed.netloc in ("download.geofabrik.de", "www.openstreetmap.org", "api.openstreetmap.org")
              and not parsed.query and not parsed.fragment and not parsed.username and "%" not in parsed.path
              and ".." not in parsed.path.split("/"), "Source URL must be a plain official public URL")
        _need(source["licenseUrl"] == ODBL, "OSM source must declare the official ODbL licence")
    _need(any(urlsplit(s["url"]).netloc == "download.geofabrik.de" for s in metadata["sources"]), "Geofabrik snapshot provenance required")
    _need(isinstance(metadata["omissions"], list), "Exact omission list required, including explicit empty list")
    for omitted in metadata["omissions"]:
        fields = {"sourceId", "reason", "count", "affectsCore", "affectsHalo"}
        _need(isinstance(omitted, dict) and set(omitted) in (fields, fields | {"scope"}), "Invalid omission fields")
        _text(omitted["sourceId"], "source ID")
        _text(omitted["reason"], "omission reason")
        _need(type(omitted["count"]) is int and omitted["count"] >= 0, "Omission count must be an exact nonnegative integer")
        flags = (omitted["affectsCore"], omitted["affectsHalo"])
        if "scope" in omitted:
            expected_flags = {"core": (True, None), "halo_only": (False, True), "outside_render": (False, False),
                              "lateral-impact-unresolved": (None, None)}
            _need(omitted["scope"] in expected_flags and all(a is b for a, b in zip(flags, expected_flags[omitted["scope"]])),
                  "Omission impact flags contradict source scope")
        else:
            _need(all(type(v) is bool for v in flags) and any(flags), "Omission needs explicit core/halo scope")
    if "omissionEvidence" in metadata:
        evidence = metadata["omissionEvidence"]
        _keys(evidence, ("records", "redactions"), "omission evidence")
        _need(isinstance(evidence["records"], list) and isinstance(evidence["redactions"], list), "Invalid omission evidence lists")
        _need(len(evidence["records"]) == len(metadata["omissions"]), "Omission evidence count mismatch")
        ids = set()
        for original, summary in zip(evidence["records"], metadata["omissions"]):
            _need(isinstance(original, dict) and all(k in original for k in ("featureId", "sourceSha256", "reason", "scope", "classification")),
                  "Incomplete frozen omission record")
            _hash(original["sourceSha256"])
            _need(original["featureId"] not in ids and original["featureId"] == summary["sourceId"]
                  and original["reason"] == summary["reason"] and original["scope"] == summary.get("scope")
                  and summary["count"] == 1, "Omission summary differs from original source record")
            ids.add(original["featureId"])
        for redaction in evidence["redactions"]:
            _keys(redaction, ("featureId", "field", "reason"), "public redaction")
            _need(redaction["featureId"] in ids, "Redaction refers to unknown omission")
        def public_values(value):
            if isinstance(value, str):
                _text(value, "omission record")
            elif isinstance(value, dict):
                for key, child in value.items():
                    _text(key, "omission field")
                    _need(key.lower() not in {"email", "password", "token", "username", "account", "api_key"}, "Private/contact field in public omission")
                    public_values(child)
            elif isinstance(value, list):
                for child in value:
                    public_values(child)
            else:
                _need(value is None or type(value) in (int, float, bool), "Invalid omission JSON value")
        public_values(evidence)
    _keys(metadata["limitations"], ("terrain", "heights", "facades", "coverage"), "limitations")
    for key, value in metadata["limitations"].items():
        _text(value, key)
    return {k: v for k, v in metadata.items() if k != "gateEvidence"}


def _gates(metadata):
    evidence = metadata["gateEvidence"]
    _keys(evidence, ("grow", "runtime", "plan"), "gate evidence")
    documents = {}
    for name, reference in evidence.items():
        _keys(reference, ("path", "sha256"), "gate reference")
        path = package._resolve(reference["path"])
        raw = path.read_bytes()
        _need(_sha(raw) == _hash(reference["sha256"]), "Gate file hash mismatch: " + name)
        documents[name] = json.loads(raw)
    grow, runtime, plan = (documents[n] for n in ("grow", "runtime", "plan"))
    _need(grow.get("kind") == "exact-core-grown-world" and grow.get("assemblyAccepted") is True
          and grow.get("verification", {}).get("status") == "PASS", "Assembly gate is not accepted")
    _need(grow.get("sourceSubsetPreviewAccepted") is True and all(grow.get(k) is False for k in FLAGS), "Qualified subset-preview gate required")
    _need(runtime.get("schema") == "fork.grown-runtime-receipt.v1" and runtime.get("runtimeLoadAccepted") is True
          and runtime.get("candidateUnchanged") is True and runtime.get("minecraft_version") == "26.1.2", "Runtime gate is not accepted")
    _need(plan.get("schema") == "fork.grown-runtime-plan.v1", "Unknown runtime plan schema")
    world = package._resolve(grow["world"])
    _need(world == package._resolve(runtime["candidate_path"]) == package._resolve(plan["candidate_path"]), "Gates refer to different worlds")
    planned = dict(plan)
    claimed = planned.pop("plan_sha256", None)
    _need(claimed == _sha(_canonical(planned)) == runtime.get("plan_sha256"), "Runtime plan binding mismatch")
    manifest = grow["verification"]["hash_manifest"]
    _need(manifest == plan["candidate_before"], "Runtime and assembly payload manifests differ")
    files = manifest["files"]
    _need(manifest.get("sha256") == _sha(_canonical(files)), "Invalid payload manifest digest")
    _need(isinstance(files, dict) and "level.dat" in files and "data/minecraft/world_gen_settings.dat" in files
          and any(REGION.fullmatch(p) for p in files), "Missing modern world payload")
    for path, record in files.items():
        _need(path in ("level.dat", "data/minecraft/world_gen_settings.dat") or REGION.fullmatch(path), "Unexpected world payload path")
        _keys(record, ("bytes", "sha256"), "payload record")
        _hash(record["sha256"])
        _need(type(record["bytes"]) is int and record["bytes"] > 0, "Invalid payload size")
    _need(type(runtime.get("selected_chunk_count")) is int and runtime["selected_chunk_count"] > 0
          and type(runtime.get("block_sentinel_count")) is int and runtime["block_sentinel_count"] > 0, "Runtime scope missing")
    _need(type(grow.get("expectedChunks")) is int and grow["expectedChunks"] > 0
          and isinstance(grow.get("extent"), list) and len(grow["extent"]) == 4
          and all(type(v) is int for v in grow["extent"]), "Assembly extent missing")
    return grow, runtime, files


def _docs(metadata, grow, runtime, files):
    lines = [metadata["worldName"], "", "QUALIFIED SOURCE-SUBSET PREVIEW", "Map data (c) OpenStreetMap contributors.",
             "Underlying OpenStreetMap data is licensed under ODbL 1.0.", COPYRIGHT, ODBL, "", "Frozen sources:"]
    for s in metadata["sources"]:
        lines.extend([s["filename"] + " | snapshot " + s["snapshotDate"], "SHA256 " + s["sha256"], s["url"], "Licence: " + s["licenseUrl"]])
    lines += ["", "Exact declared omissions (counts are per supplied source record):"]
    for o in metadata["omissions"]:
        flag = lambda value: "unknown" if value is None else str(value).lower()
        lines.append(f'{o["sourceId"]}: {o["reason"]}; count={o["count"]}; affectsCore={flag(o["affectsCore"])}; affectsHalo={flag(o["affectsHalo"])}'
                     + ("; scope=" + o["scope"] if "scope" in o else ""))
    if not metadata["omissions"]:
        lines.append("No omissions declared in the supplied frozen list; this is not a completeness claim.")
    lines += ["", f'Owned chunks: {grow["expectedChunks"]}; bounding extent X/Z [minX,minZ,maxX,maxZ): {grow["extent"]}.',
              "The bounding extent can contain unowned gaps. Do not infer validated coverage outside owned chunks.",
              "Coordinate frame: EPSG:3414, X=easting, Z=60000-northing; one block per horizontal metre, with voxel rounding."]
    lines += [key.capitalize() + ": " + value for key, value in metadata["limitations"].items()]
    lines += ["sourceComplete=false; routeComplete=false; fullFidelity=false.", "",
              f'Minecraft Java Edition 26.1.2 runtime: {runtime["selected_chunk_count"]} selected chunks and {runtime["block_sentinel_count"]} block sentinels.',
              "This is a representative headless load check, not runtime testing of every chunk. Client visual and AI behaviour acceptance are not claimed.",
              "The map-data licence does not provide Minecraft software or account rights.", "", "Unchanged world payload SHA256:"]
    lines += [record["sha256"] + "  " + path for path, record in sorted(files.items())]
    evidence = metadata.get("omissionEvidence")
    if evidence is not None:
        lines += ["", f'SOURCE-OMISSIONS.json retains {len(evidence["records"])} individual source records, with {len(evidence["redactions"])} explicitly listed contact/privacy field redactions.',
                  "Scope flags describe source-feature relevance, not an omitted voxel count. Unknown impact remains unknown."]
    sources = "\n".join(lines) + "\n"
    install = (f'{metadata["worldName"]}\n\nMinecraft Java Edition 26.1.2 saved world.\n'
               '1. Close Minecraft or leave the current world. Extract the ZIP to a temporary folder.\n'
               f'2. Copy the complete {metadata["saveFolder"]} folder into the saves directory of your chosen Minecraft instance.\n'
               '   level.dat must be directly inside that folder, beside data and dimensions.\n'
               f'3. If {metadata["saveFolder"]} already exists, choose a NEW unique folder name. Never merge or overwrite.\n'
               '4. Open Singleplayer and select the imported world.\n\n'
               'Never replace CBD capture saves, SingaporeDistrict, Market Street, or any current world.\n'
               'Do not copy individual region files into an existing save. Custom instances may use a different saves directory.\n'
               'The original README is preserved unchanged; use this file for this package\'s exact installation folder.\n'
               'This package contains a world, not Minecraft software, account access, or an AI-agent gameplay mod.\n'
               'See WORLD-SOURCES.txt for frozen provenance, omissions, estimated fields, coverage and test scope.\n')
    documents = {"WORLD-SOURCES.txt": sources.encode("utf-8"), "INSTALL-WORLD.txt": install.encode("utf-8")}
    if evidence is not None:
        documents["SOURCE-OMISSIONS.json"] = _canonical({"schema": "fork.public-source-omissions.v1", **evidence,
                                                       **{key: False for key in FLAGS}}) + b"\n"
    return documents


def _record_stream(stream, target=None):
    digest, size = hashlib.sha256(), 0
    for data in iter(lambda: stream.read(1024 * 1024), b""):
        digest.update(data)
        size += len(data)
        if target is not None:
            target.write(data)
    return {"sha256": digest.hexdigest(), "bytes": size}


def package_public(candidate_zip, public_metadata, output_dir):
    """Return a private proof receipt only after reopened CRC and byte hashes pass."""
    public = _metadata(public_metadata)
    grow, runtime, files = _gates(public_metadata)
    source = package._resolve(candidate_zip)
    output = package._resolve(output_dir)
    _need(package.OUTPUT_ROOT.resolve() in output.parents and not output.exists(), "Output must be a new directory under merged")
    _need(package._resolve(grow["world"]) not in output.parents, "Output cannot be inside the accepted world")
    before = package._file_record(source)
    docs = _docs(public, grow, runtime, files)
    with zipfile.ZipFile(source) as original:
        members = original.infolist()
        names = [m.filename for m in members]
        _need(len({n.casefold() for n in names}) == len(names), "Duplicate or case-colliding ZIP members")
        for entry in members:
            path = PurePosixPath(entry.filename)
            _need(not entry.is_dir() and not path.is_absolute() and all(p not in ("", ".", "..") for p in entry.filename.split("/"))
                  and "\\" not in entry.filename and ":" not in entry.filename
                  and not stat.S_ISLNK(entry.external_attr >> 16), "Unsafe ZIP member")
        _need("README.txt" in names, "Original root README.txt required")
        roots = {n.split("/", 1)[0] for n in names if n != "README.txt"}
        _need(len(roots) == 1, "Candidate must have one world folder")
        old_root = roots.pop()
        _name(old_root)
        _need(set(names) == {"README.txt", *[old_root + "/" + p for p in files]}, "Archive payload set differs from accepted manifest")
        readme = original.read("README.txt")
        _need(not PRIVATE.search(readme.decode("utf-8")), "Original README contains private data")
        for path, expected in files.items():
            with original.open(old_root + "/" + path) as stream:
                _need(_record_stream(stream) == expected, "Candidate payload hash mismatch")
        # Nothing is created until both gates and every source archive byte pass.
        output.mkdir()
        pending = output / (public["packageName"] + ".INCOMPLETE")
        final = output / (public["packageName"] + ".zip")
        expected_out = {public["saveFolder"] + "/" + p: r for p, r in files.items()}
        extras = {"README.txt": readme, **docs}
        expected_out.update({n: {"sha256": _sha(b), "bytes": len(b)} for n, b in extras.items()})
        with zipfile.ZipFile(pending, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
            for path, expected in sorted(files.items()):
                with original.open(old_root + "/" + path) as src, archive.open(public["saveFolder"] + "/" + path, "w", force_zip64=True) as dst:
                    _need(_record_stream(src, dst) == expected, "Payload changed during packaging")
            for name, data in extras.items():
                archive.writestr(name, data)
    _need(package._file_record(source) == before, "Candidate ZIP changed during packaging")
    with zipfile.ZipFile(pending) as archive:
        _need(archive.testzip() is None and set(archive.namelist()) == set(expected_out), "Reopened ZIP CRC or entry set failed")
        for name, expected in expected_out.items():
            with archive.open(name) as stream:
                _need(_record_stream(stream) == expected, "Reopened ZIP bytes differ")
    # Directory was exclusively created above; no previous published ZIP is replaced.
    _need(not final.exists(), "Immutable output ZIP already exists")
    os.rename(pending, final)
    receipt = {"schema": "fork.public-preview-package.v1", "status": "PASS", "zip": {"path": final.name, **package._file_record(final)},
               "candidateZip": before, "payloadFiles": files, "entryCount": len(expected_out), "crcAccepted": True,
               "documentFiles": {n: expected_out[n] for n in extras},
               "readmeSha256": _sha(readme), "publicMetadataSha256": _sha(_canonical(public)),
               "gateEvidence": public_metadata["gateEvidence"], "sourceComplete": False, "routeComplete": False, "fullFidelity": False}
    with (output / "private-package-receipt.json").open("x", encoding="utf-8") as stream:
        json.dump(receipt, stream, indent=2, sort_keys=True)
        stream.write("\n")
    return receipt
