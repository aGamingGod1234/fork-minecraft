"""Compare each national core against the audited coast raster and consumed runs."""
import importlib.util, json, sys
from pathlib import Path
from prepare_core import BASE,FULL,ROOT,read,pin,write,checked

HELPER=BASE/'fork-minecraft-worktrees/full-singapore-water-gates/tools/singapore-full/validate-national-water-source.py'
loader=importlib.util.spec_from_file_location('national_water',HELPER)
n=importlib.util.module_from_spec(loader);loader.loader.exec_module(n);w=n.water

def coast(core_id):
    root=ROOT/'cores'/core_id;d=read(root/'prepared.json');s=d['source'];c=d['core'];bounds=c['coreBounds']
    job=checked(d['jobSpecification']);result=checked(d['pipelineResult']);tile=next(t for t in result['tiles'] if t['id']==core_id)
    tile_root=Path(d['pipelineResult']['path']).parent/core_id
    historical=read(BASE/'fork-minecraft-worktrees/full-singapore-water-gates/.work/water-ring14-validation-1/minipc-ring-29696-29440-v1/water-oracle.json')
    chain=historical['evidence']['geometryChain'];chain={**chain,'coast_mask':job['coast']['mask']}
    values={k:checked(v) for k,v in chain.items()}
    qualifications=n.validate_geometry_chain(chain,values['prior_audit'],values['normalization'],values['geometry_receipt'])
    country=job['roads']['countryMask'];foreign=job['roads']['foreignExclusions']
    assert country['sha256']==n.COUNTRY and foreign['sha256']==n.FOREIGN
    mask=values['coast_mask'];expected,labels,allowed,counts=w.classify_core(mask,checked(country),checked(foreign),bounds)
    runs=d['coastRuns'];comparison=w.check_runs(runs['path'],runs['sha256'],tile['renderBounds'],bounds,expected,labels,allowed)
    assert comparison['consumedBytes']==runs['bytes']
    assert not comparison['errorCounts'] and comparison['mismatches']==0 and counts['unknownCoreCells']==0, 'Coast source mismatch'
    ids=n.feature_identity_check(runs['path'],runs['sha256'],mask)
    # Count only source IDs actually emitted inside this owned core.
    core_ids=set();core_runs=0
    with Path(runs['path']).open() as stream:
        for line in stream:
            if not line.strip():continue
            run=json.loads(line)
            if bounds[0]<=run['x']<bounds[2] and bounds[1]<=run['z']<bounds[3]:core_ids.add(run['featureId']);core_runs+=1
    ids=sorted(core_ids)
    proof={'schemaVersion':1,'kind':'independent-national-coastline-water-source-oracle','status':'PASS','synthetic':False,
           'component':'water','coreId':core_id,'coreBounds':bounds,'writerManifestSha256':c['writerManifest']['sha256'],
           'sourceSha256':job['coast']['mask']['sha256'],'runsSha256':runs['sha256'],'countryMaskSha256':n.COUNTRY,
           'foreignExclusionsSha256':n.FOREIGN,'comparedBlocks':(bounds[2]-bounds[0])*(bounds[3]-bounds[1]),
           'mismatches':0,'errors':[],'classification':counts,'comparison':comparison,'emittedSourceFeatureIds':ids,
           'evidence':{'geometryChain':chain,'pipelineResult':d['pipelineResult'],'writerManifest':c['writerManifest'],
                       'componentGate':c['structuralGate'],'validator':pin(__file__),'comparisonHelper':pin(HELPER)},
           'nationalGeometryQualifications':qualifications,'runtimeAccepted':False,'terrainAccepted':False,'fullWorldAccepted':False}
    oracle=write(root/'water-oracle.json',proof)
    coverage={'schemaVersion':1,'kind':'independent-coastline-source-coverage','status':'PASS' if ids else 'NO_FEATURES',
              'synthetic':False,'component':'water','coreBounds':bounds,'sourceSha256':proof['sourceSha256'],'featureCount':len(ids),
              'sourceCoverageComplete':True,'blockedDiagnostics':0,'unmappedSourceCount':0,'expectedCoastWaterCells':counts['expectedWaterCoreCells'],
              'oracle':oracle,'countryMaskSha256':n.COUNTRY,'foreignExclusionsSha256':n.FOREIGN,'fullWorldAccepted':False,
              'scope':'Audited coastline mask only; excludes inland water and surveyed water elevations'}
    coverage_ref=write(root/'source-coverage.json',coverage)
    clip=pin(tile_root/'coast-mask-manifest.json');raw=pin(tile_root/'coast.raw.runs.jsonl');report=pin(tile_root/'coast-manifest.json')
    evidence={'schemaVersion':1,'component':'water','status':coverage['status'],'synthetic':False,'coreBounds':bounds,
              'writerManifestSha256':c['writerManifest']['sha256'],'sourceSha256':proof['sourceSha256'],'featureCount':len(ids),
              'emittedFeatureCount':len(ids),'emittedFeatureIds':ids,'sourceCoverageComplete':True,'blockedDiagnostics':0,'unmappedSourceCount':0,
              'componentRuns':core_runs,'componentVoxels':counts['expectedWaterCoreCells'],'reportPath':oracle['path'],'reportSha256':oracle['sha256'],
              'runsPath':runs['path'],'runsSha256':runs['sha256'],'sourceReportPath':report['path'],'sourceReportSha256':report['sha256'],
              'structuralGatePath':s['structural_gate_path'],'structuralGateSha256':pin(s['structural_gate_path'])['sha256'],
              'structuralKind':'national-pipeline-structural-result','sourceCoverageEvidence':coverage_ref,
              'maskEvidence':{'maskManifestPath':clip['path'],'maskManifestSha256':clip['sha256'],'rawRunsPath':raw['path'],'rawRunsSha256':raw['sha256'],
                              'countryMaskPath':country['path'],'countryMaskSha256':n.COUNTRY,'foreignExclusionsPath':foreign['path'],'foreignExclusionsSha256':n.FOREIGN},
              'actualTerrainAccepted':False,'waterHeightAccepted':False,'fullWorldAccepted':False,'runtimeAccepted':False}
    write(root/'coast-evidence.json',evidence)
    print(json.dumps({'core':core_id,'coastStatus':coverage['status'],'cellsCompared':proof['comparedBlocks'],'mismatches':0}),flush=True)

if __name__=='__main__':coast(sys.argv[1])
