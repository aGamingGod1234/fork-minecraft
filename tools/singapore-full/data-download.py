"""Fetch pinned sources into an external cache; retain checksums and response metadata."""
import argparse, datetime, hashlib, json, pathlib, urllib.request
p=argparse.ArgumentParser(); p.add_argument("--manifest",required=True);p.add_argument("--cache",required=True);p.add_argument("--id",action="append")
a=p.parse_args(); config=json.load(open(a.manifest,encoding="utf-8-sig")); cache=pathlib.Path(a.cache).resolve();cache.mkdir(parents=True,exist_ok=True)
for s in config["sources"]:
    if not s.get("download") or a.id and s["id"] not in a.id: continue
    file=cache/s["filename"]; tmp=cache/(s["filename"]+".part")
    if file.parent!=cache:raise ValueError("Source filename must be a basename")
    headers={}
    if not file.exists():
        req=urllib.request.Request(s["url"],headers={"User-Agent":"FORK-Singapore-data/1.0 (public geodata acquisition)"})
        with urllib.request.urlopen(req,timeout=60) as r, tmp.open("xb") as o:
            headers=dict(r.headers.items())
            while block:=r.read(1024*1024):o.write(block)
        candidate=tmp
    else:candidate=file
    digest=hashlib.file_digest(open(candidate,"rb"),"sha256").hexdigest()
    md5=hashlib.file_digest(open(candidate,"rb"),"md5").hexdigest()
    if s.get("sha256") and digest.lower()!=s["sha256"].lower():raise ValueError("SHA256 mismatch: "+s["id"])
    if s.get("md5") and md5.lower()!=s["md5"].lower():raise ValueError("Publisher MD5 mismatch: "+s["id"])
    if s.get("bytes") and candidate.stat().st_size!=s["bytes"]:raise ValueError("Length mismatch: "+s["id"])
    if candidate==tmp:tmp.rename(file)
    receipt={"id":s["id"],"url":s["url"],"filename":s["filename"],"retrievedAtUtc":datetime.datetime.now(datetime.timezone.utc).isoformat(),"bytes":file.stat().st_size,"sha256":digest,"md5":md5,"headers":headers}
    (cache/(s["id"]+".receipt.json")).write_text(json.dumps(receipt,indent=2),encoding="utf-8")
    print(json.dumps(receipt))
