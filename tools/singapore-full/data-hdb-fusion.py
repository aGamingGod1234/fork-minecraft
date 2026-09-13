"""Produce optional HDB floor-derived estimates without changing OSM or worlds.
Only unique conservative address matches can be offered. Observations and estimates stay separate.
"""
import argparse,collections,hashlib,importlib.util,json,pathlib,time
def module(path,name):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
HERE=pathlib.Path(__file__).resolve().parent
audit=module(HERE/"data-height-audit.py","fork_height_audit")
def sha(path):
    with open(path,"rb") as f:return hashlib.file_digest(f,"sha256").hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument("--source",type=pathlib.Path,required=True);p.add_argument("--extract-report",type=pathlib.Path,required=True)
    p.add_argument("--hdb",type=pathlib.Path,required=True);p.add_argument("--hdb-descriptor",type=pathlib.Path,required=True);p.add_argument("--output-dir",type=pathlib.Path,required=True);p.add_argument("--metres-per-floor",type=float,default=2.8)
    a=p.parse_args();start=time.time();out=a.output_dir
    if out.exists():raise SystemExit("Use a new output directory; fusion results are immutable.")
    report=json.loads(a.extract_report.read_text(encoding="utf-8-sig"))
    entry=next(x for x in report["files"] if x["name"]==a.source.name)
    if not report.get("referenceComplete") or any(report["missingReferences"].values()) or sha(a.source)!=entry["sha256"]:raise SystemExit("OSM integrity/reference gate failed")
    desc=json.loads(a.hdb_descriptor.read_text(encoding="utf-8-sig"));hdb_sha=sha(a.hdb)
    expected=next(x for x in desc["captured_sources"] if x["filename"]==a.hdb.name)
    if hdb_sha!=expected["sha256"] or a.hdb.stat().st_size!=expected["bytes"]:raise SystemExit("HDB frozen-source mismatch")
    snapshot=json.loads(a.hdb.read_text(encoding="utf-8-sig"))
    rows=snapshot["result"]["records"]
    if not snapshot.get("success") or len(rows)!=snapshot["result"]["total"]:raise SystemExit("HDB incomplete row set")
    match=module(HERE/"data-hdb-matching.py","fork_hdb_matching")
    hdb=collections.defaultdict(list);bad_hdb=[];counts=collections.Counter()
    for row in rows:
        key=match.make_key(row.get("blk_no"),row.get("street"))
        if key["key"]:hdb[key["key"]].append(row)
        else:bad_hdb.append({"row":row,"key":key})
    candidates=[];osm_keys=collections.Counter();bad_address_examples=[]
    for o in audit.elements(a.source):
        tags=o.get("tags",{})
        if tags.get("building") in (None,"no") and tags.get("building:part") in (None,"no"):continue
        counts["osmBuildingOrPartObjects"]+=1
        if o["type"] not in ("way","relation"):counts["nonAreaBuildingObjects"]+=1;continue
        if not tags.get("addr:housenumber") or not tags.get("addr:street"):counts["missingExactAddressFields"]+=1;continue
        key=match.make_key(tags["addr:housenumber"],tags["addr:street"])
        counts["addressCandidates"]+=1
        if key["key"]:
            osm_keys[key["key"]]+=1;candidates.append(({"type":o["type"],"id":o["id"],"tags":tags},key))
        else:
            counts["rejectedAddressForms"]+=1
            if len(bad_address_examples)<25:bad_address_examples.append({"type":o["type"],"id":o["id"],"key":key})
    out.mkdir(parents=True);status=collections.Counter();reasons=collections.Counter();matched_keys=set();new_keys=set();overrides=0;examples=[]
    override_path=out/"hdb-height-overrides-v1.jsonl";decision_path=out/"hdb-address-decisions.jsonl"
    with override_path.open("x",encoding="utf-8") as offered,decision_path.open("x",encoding="utf-8") as decisions:
        for o,key in candidates:
            hrows=hdb.get(key["key"],[])
            if not hrows:counts["noHdbAddressMatch"]+=1;continue
            counts["hdbAddressMatchedObjects"]+=1;matched_keys.add(key["key"])
            row=dict(hrows[0]);row["_match_count"]=len(hrows)
            decision=match.enrichment_decision(o["tags"],row,osm_keys[key["key"]],metres_per_floor=a.metres_per_floor)
            status[decision["status"]]+=1;reasons[decision["reason"]]+=1
            record={"osmType":o["type"],"osmId":o["id"],"addressKey":key["key"],"decision":decision,"osmClassificationBefore":audit.classify(o["tags"])["classification"]}
            if len(examples)<30:examples.append(record)
            decisions.write(json.dumps(record,separators=(",",":"))+"\n")
            if decision.get("enrichment"):
                if record["osmClassificationBefore"]!="missing":raise RuntimeError("Attempted override of nonmissing OSM evidence")
                overrides+=1;new_keys.add(key["key"])
                offered.write(json.dumps({"schemaVersion":1,"osmType":o["type"],"osmId":o["id"],"optional":True,"appliedToSource":False,"appliedToWorld":False,"addressKey":key["key"],"osmRawAddress":{"housenumber":o["tags"].get("addr:housenumber"),"street":o["tags"].get("addr:street")},"hdbRawRecord":hrows[0],"inference":decision["enrichment"],"policyVersion":decision["policyVersion"],"source":{"osmExtractSha256":entry["sha256"],"hdbSnapshotSha256":hdb_sha,"hdbDataset":desc["dataset_id"],"hdbCatalogue":desc["source_catalogue_url"],"hdbLicence":desc["licence_url"],"osmLicence":"https://opendatacommons.org/licenses/odbl/1-0/","osmAttribution":"OpenStreetMap contributors","redistributionStatus":"REVIEW_REQUIRED: joined HDB and OSM terms remain separately applicable; no compatibility certification"}},separators=(",",":"))+"\n")
    result={"schemaVersion":1,"sourceSha256":entry["sha256"],"hdbSnapshotSha256":hdb_sha,"hdbDescriptorSha256":sha(a.hdb_descriptor),"maskSha256":report["maskSha256"],"hdbRows":len(rows),"normalizedHdbKeys":len(hdb),"duplicateNormalizedHdbKeys":sum(len(v)>1 for v in hdb.values()),"invalidHdbAddresses":bad_hdb,"counts":dict(counts),"decisionCounts":dict(status),"decisionReasonCounts":dict(reasons),"matchedHdbAddressKeys":len(matched_keys),"newOptionalHeightOverrides":overrides,"newUniqueHdbAddressKeys":len(new_keys),"existingSourceModified":False,"worldModified":False,"redistributionStatus":"REVIEW_REQUIRED","licenceNote":"HDB records retain Singapore Open Data Licence terms; OSM source IDs and geographic database retain ODbL obligations. No blanket ODbL relicensing or downstream sublicense compatibility is asserted.","badAddressExamples":bad_address_examples,"examples":examples,"elapsedSeconds":time.time()-start,"policy":{"kind":"optional exact-address HDB maximum-floor proxy","metresPerFloor":a.metres_per_floor,"metresEvidence":"Explicitly chosen estimate, not verified per-building physical or roof height","matching":"conservative token normalization; no fuzzy/geographic nearest matching","multiplicity":"Repeated OSM key or HDB key is ambiguous; no silent fusion","preservation":"Existing valid OSM height or levels retained; invalid evidence requires review","applicability":"New estimates restricted to residential, non-multistorey-carpark outlines. Source counts include parts, not deduplicated physical buildings.","consumerContract":"Prefer the separate max-floor observation and a common renderer policy; never represent estimated metres as measured roof height."},"files":[{"name":f.name,"bytes":f.stat().st_size,"sha256":sha(f)} for f in (override_path,decision_path)],"implementation":{"fusionSha256":sha(pathlib.Path(__file__)),"matcherSha256":sha(HERE/"data-hdb-matching.py"),"heightParserSha256":sha(HERE/"data-height-audit.py")}}
    (out/"summary.json").write_text(json.dumps(result,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({k:result[k] for k in ("hdbRows","counts","decisionCounts","decisionReasonCounts","matchedHdbAddressKeys","newOptionalHeightOverrides","newUniqueHdbAddressKeys","elapsedSeconds","files")}))
if __name__=="__main__":main()
