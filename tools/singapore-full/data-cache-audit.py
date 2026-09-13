"""Read-only, portable verification of the pinned geographic cache and notices."""
import argparse
import hashlib
import json
from pathlib import Path
import sys

def digest(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
    return value.hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--landcover-dir", type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[2]
    manifest = json.loads((repo / "docs/singapore-full/data-provenance.json").read_text(encoding="utf-8-sig"))
    roots = {"data": args.data_dir, "landcover": args.landcover_dir,
             "license": repo / "docs/singapore-full/data-licenses"}
    results = []
    for entry in manifest["files"]:
        root = roots[entry["root"]].resolve()
        path = (root / entry["path"]).resolve()
        if not path.is_relative_to(root):
            raise ValueError("Manifest path escapes its root")
        result = {"root": entry["root"], "path": entry["path"], "pass": False}
        try:
            result["bytes"] = path.stat().st_size
            result["sha256"] = digest(path)
            result["pass"] = result["bytes"] == entry["bytes"] and result["sha256"] == entry["sha256"]
        except OSError as error:
            result["error"] = type(error).__name__
        results.append(result)
    report = {"schemaVersion": 1, "files": results,
              "pass": all(row["pass"] for row in results),
              "worldcoverFullIslandCoverageVerified": False,
              "meaning": "Recorded byte identity only; not a coverage or legal-clearance certificate"}
    print(json.dumps(report, indent=2))
    return 0 if report["pass"] else 1

if __name__ == "__main__":
    sys.exit(main())
