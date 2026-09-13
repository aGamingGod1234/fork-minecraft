"""Optional bounded-memory wrapper around the frozen complete-building renderer.

Each complete source feature remains intact. Small half-open subcores bound the
in-memory run list. A stripe is spooled to private temporary files, then merged
in global z/x/y order, so output records and the semantic digest match one whole
render. The original renderer's geometry, precedence and failure policy remain
unchanged.
"""
from __future__ import annotations

import argparse
import hashlib
import heapq
import json
import math
import os
from pathlib import Path
import tempfile
import uuid

from renderer import render_features
from source_validation import validate_document


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"))


def _run_key(run):
    return (run["z"], run["x"], run["yMin"], run["featureId"], run["block"])


def _remember(mapping, identity, record):
    previous = mapping.get(identity)
    if previous is not None and previous != record:
        raise ValueError(f"Subcore provenance changed for source feature {identity}")
    mapping[identity] = record


def _merge_stripe(paths, output, semantic_digest, output_digest, emitted):
    """At most one decoded run from each stripe file is held in the heap."""
    handles = []
    heap = []
    try:
        for index, path in enumerate(paths):
            handle = path.open("r", encoding="utf-8")
            handles.append(handle)
            line = handle.readline()
            if line:
                run = json.loads(line)
                heapq.heappush(heap, (_run_key(run), index, run))
        while heap:
            _, index, run = heapq.heappop(heap)
            canonical = _canonical(run).encode("utf-8")
            if emitted:
                semantic_digest.update(b"\n")
            semantic_digest.update(canonical)
            # Match the original CLI file representation as well as semantics.
            line = (json.dumps(run, sort_keys=True) + "\n").encode("utf-8")
            output.write(line)
            output_digest.update(line)
            emitted += 1
            following = handles[index].readline()
            if following:
                next_run = json.loads(following)
                heapq.heappush(heap, (_run_key(next_run), index, next_run))
    finally:
        for handle in handles:
            handle.close()
    return emitted


def render_stream(features, tile_box, output_path, manifest_path, *, ground_y,
                  ground_source_class, subcore_size=128,
                  invalid_feature_policy="error", min_y=-64, max_y=319):
    """Compile sequential bounded subcores; return the aggregate manifest.

    Targets are published only after every subcore succeeds. Compilation
    failures leave existing targets intact; only owned temporary files are
    removed. Source JSON features and compact per-feature evidence
    remain resident; there is never a whole-world run list or encoded string.
    """
    if len(tile_box) != 4 or any(isinstance(v, bool) or not isinstance(v, int)
                                for v in tile_box):
        raise ValueError("tile_box must contain four integer global coordinates")
    x0, z0, x1, z1 = tile_box
    if x0 >= x1 or z0 >= z1:
        raise ValueError("tile_box must have positive area")
    if isinstance(subcore_size, bool) or not isinstance(subcore_size, int) or not 1 <= subcore_size <= 256:
        raise ValueError("subcore_size must be an integer from 1 through 256")
    output_path, manifest_path = Path(output_path).resolve(), Path(manifest_path).resolve()
    if output_path == manifest_path:
        raise ValueError("output and manifest paths must differ")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    source_features = list(features)
    by_feature, evidence, exclusions, warnings = {}, {}, {}, set()
    overlap_samples = []
    overlap_voxels = subcores = stripes = peak_subcore_runs = 0
    semantic_digest, output_digest = hashlib.sha256(), hashlib.sha256()
    emitted, expected_records = 0, 0
    token = uuid.uuid4().hex
    partial_output = output_path.parent / ("." + output_path.name + "." + token + ".partial")
    partial_manifest = manifest_path.parent / ("." + manifest_path.name + "." + token + ".partial")
    try:
        with partial_output.open("wb") as output:
            for stripe_z in range(z0, z1, subcore_size):
                stripes += 1
                # The temporary directory is created under the verified output
                # parent. Its recursive cleanup cannot target another workspace.
                with tempfile.TemporaryDirectory(prefix="fork-building-stripe-", dir=output_path.parent) as directory:
                    temporary = Path(directory).resolve()
                    if temporary.parent != output_path.parent:
                        raise ValueError("Temporary stripe path escaped output directory")
                    paths = []
                    for core_x in range(x0, x1, subcore_size):
                        core = (core_x, stripe_z, min(x1, core_x+subcore_size), min(z1, stripe_z+subcore_size))
                        runs, receipt = render_features(
                            source_features, core, ground_y=ground_y,
                            ground_source_class=ground_source_class, min_y=min_y, max_y=max_y,
                            invalid_feature_policy=invalid_feature_policy)
                        subcores += 1
                        peak_subcore_runs = max(peak_subcore_runs, len(runs))
                        expected_records += len(runs)
                        overlap_voxels += receipt["resolvedOverlappingVoxels"]
                        for record in receipt["features"]:
                            _remember(by_feature, record["featureId"], record)
                        for record in receipt["evidence"]:
                            _remember(evidence, record["featureId"], record)
                        for record in receipt["exclusions"]:
                            exclusions[(record["featureId"], record["reason"])] = record
                        warnings.update(receipt["warnings"])
                        overlap_samples.extend(receipt["overlapSamples"])
                        # Only the first ten globally ordered samples can enter
                        # the original manifest; keep that bounded at all times.
                        overlap_samples.sort(key=lambda r:(r["z"],r["x"],r["yMin"],r["yMax"],r["featureIds"]))
                        overlap_samples = overlap_samples[:10]
                        path = temporary / (str(core_x) + ".jsonl")
                        with path.open("w", encoding="utf-8", newline="\n") as spool:
                            for run in runs:
                                spool.write(_canonical(run) + "\n")
                        paths.append(path)
                        # Release the complete subcore before computing another.
                        del runs, receipt
                    emitted = _merge_stripe(paths, output, semantic_digest, output_digest, emitted)
            output.flush()
            os.fsync(output.fileno())
        if emitted != expected_records:
            raise ValueError("Stream merge lost or duplicated a subcore record")
        # A full active-parts bbox can span gaps in which no source feature
        # selected that particular subcore. Recompute the original whole-core
        # candidate accounting from unique complete group bounds, not shard sums.
        candidate_columns = sum(
            max(0,min(x1,math.ceil(r["bounds"][2]))-max(x0,math.floor(r["bounds"][0]))) *
            max(0,min(z1,math.ceil(r["bounds"][3]))-max(z0,math.floor(r["bounds"][1])))
            for r in by_feature.values())
        manifest = {
            "schema":"fork-building-runs-v1", "coordinateSystem":"EPSG:3414",
            "axisMapping":"x=E,z=60000-N", "horizontalBlocksPerMetre":1,
            "tileCore":list(tile_box), "groundY":ground_y,
            "groundSourceClass":ground_source_class,
            "verticalRangeInclusive":[min_y,max_y], "requiresCleanTerrainLayer":True,
            "candidateColumns":candidate_columns, "runCount":emitted,
            "inputFeatureCount":len(source_features), "selectedFeatureCount":len(evidence),
            "runsSha256":semantic_digest.hexdigest(), "outputSha256":output_digest.hexdigest(),
            "features":[by_feature[k] for k in sorted(by_feature)],
            "warnings":sorted(warnings), "exclusions":[exclusions[k] for k in sorted(exclusions)],
            "resolvedOverlappingVoxels":overlap_voxels, "overlapSamples":overlap_samples,
            "overlapPolicy":"parts, explicit height, smaller complete footprint, stable source id",
            "completeSourceGeometryAccepted":not bool(exclusions or overlap_voxels),
            "evidence":[evidence[k] for k in sorted(evidence)],
            "streaming":{"subcoreSize":subcore_size,"subcoreCount":subcores,
                         "stripeCount":stripes,"maximumSubcoreRunCount":peak_subcore_runs,
                         "outputOrdering":"global z,x,yMin,featureId,block",
                         "wholeRunListRetained":False},
        }
        partial_manifest.write_text(json.dumps(manifest,indent=2)+"\n",encoding="utf-8")
        os.replace(partial_output,output_path)
        os.replace(partial_manifest,manifest_path)
        return manifest
    finally:
        for path in (partial_output,partial_manifest):
            if path.exists():
                # Exact private partial files only; never recursive user cleanup.
                path.unlink()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input",required=True,type=Path)
    parser.add_argument("--tile",required=True,type=int,nargs=4)
    parser.add_argument("--ground-y",required=True,type=float)
    parser.add_argument("--ground-source-class",required=True)
    parser.add_argument("--output",required=True,type=Path)
    parser.add_argument("--manifest",required=True,type=Path)
    parser.add_argument("--subcore-size",type=int,default=128)
    parser.add_argument("--invalid-feature-policy",choices=("error","report"),default="error")
    args=parser.parse_args()
    with args.input.open("r",encoding="utf-8-sig") as handle:
        source=json.load(handle)
    features=validate_document(source)
    result=render_stream(features,tuple(args.tile),args.output,args.manifest,
                         ground_y=args.ground_y,ground_source_class=args.ground_source_class,
                         subcore_size=args.subcore_size,invalid_feature_policy=args.invalid_feature_policy)
    print(json.dumps({"runCount":result["runCount"],"runsSha256":result["runsSha256"],
                      "manifest":str(args.manifest),"streaming":result["streaming"]}))


if __name__ == "__main__":
    main()
