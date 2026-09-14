"""Public provenance for the unchanged, runtime-tested national preview payload."""
import copy, json
from collections import Counter
from pathlib import Path
import prepare_core as p
import grow_public as g

SNAPSHOT=p.FULL/'merged/grow-national-preview-v1'
def main():
    plan=p.read(p.ROOT/'delivery-v2/plan.json')
    manifest=p.read(SNAPSHOT/'grow-manifest.json')
    runtime=p.FULL/'runtime-check/national-preview-v1'
    old=p.read(p.FULL/'merged/grow-cbd-east-ring-v1/public-metadata-final.json')
    regional=copy.deepcopy(old['sources'][0])
    assert p.pin(p.FULL/'data'/regional['filename'])['sha256']==regional['sha256']
    sources=[regional];omissions=[];records=[];redactions=[];indices=Counter()
    for s in plan['sources']:
        d=p.read(p.ROOT/'cores'/s['id']/'prepared.json')
        source=d['core']['source'];p.checked(source)
        sources.append({'filename':Path(source['path']).name,'snapshotDate':regional['snapshotDate'],
            'sha256':source['sha256'],'url':regional['url'],'licenseUrl':g.ODBL,'coreBounds':s['core_bounds']})
        audit=p.checked(d['audit'])
        for original in audit['omissions']:
            row=copy.deepcopy(original);sha=row['sourceSha256'];index=indices[sha];indices[sha]+=1
            flags={'core':(True,None),'halo_only':(False,True),'outside_render':(False,False),
                   'lateral-impact-unresolved':(None,None)}[row['scope']]
            omissions.append({'sourceId':row['featureId'],'reason':row['reason'],'count':1,
                'scope':row['scope'],'affectsCore':flags[0],'affectsHalo':flags[1],
                'sourceSha256':sha,'occurrenceIndex':index})
            def clean(value,prefix=''):
                if isinstance(value,dict):
                    for key in list(value):
                        child=value[key];field=prefix+'.'+key if prefix else key
                        sensitive=(key.lower() in {'email','password','token','username','account','api_key'}
                                   or key.lower().startswith('contact:') or key.lower() in {'phone','fax'})
                        invalid=isinstance(child,str) and (g.PRIVATE.search(child) or not child or any(ord(c)<32 for c in child))
                        if sensitive or invalid:
                            del value[key]
                            redactions.append({'featureId':row['featureId'],'field':field,
                                'reason':'Contact/private field or empty/control-character metadata omitted from public copy',
                                'sourceSha256':sha,'occurrenceIndex':index})
                        else:clean(child,field)
                elif isinstance(value,list):
                    for i,child in enumerate(value):clean(child,prefix+'.'+str(i))
            clean(row);records.append(row)
    metadata={'packageName':'FORK-Singapore-National-Preview-v1-public','saveFolder':'FORK-Singapore-National-Preview-v1',
        'worldName':plan['world_name'],'sources':sources,'omissions':omissions,
        'omissionEvidence':{'records':records,'redactions':redactions},
        'sourceComplete':False,'routeComplete':False,'fullFidelity':False,
        'limitations':{
            'terrain':'Ground is flat provisional Y0. Coastal and inland water levels are provisional. Surveyed terrain, tides and bathymetry are not represented.',
            'heights':'Mapped building footprints are used where available. Heights mix mapped values with declared estimates/defaults. Unsupported source geometry remains omitted; metre-scale placement is not surveyed height fidelity.',
            'facades':'Facades, windows, roofs, colours and materials are generic Minecraft interpretations, not photo-matched reconstructions.',
            'coverage':f"This preview contains {len(plan['sources'])} adjacent 1024-metre-square source cores, {manifest['expectedChunks']*256/1e6:.6f} square kilometres. Extent X/Z {manifest['extent']}. The rectangle is fully owned. Roads with unresolved elevation, unsupported types and planned roads retain explicit omissions. SOURCE-OMISSIONS.json preserves all {len(records)} road/water source diagnostic occurrences. Building estimates and renderer omissions remain declared; source, route and visual fidelity are incomplete. This is a world preview without the gameplay mod or AI agents."},
        'gateEvidence':{name:{k:v for k,v in p.pin(path).items() if k!='bytes'} for name,path in {
            'grow':SNAPSHOT/'grow-manifest.json','runtime':runtime/'runtime-receipt.json','plan':runtime/'runtime-plan.json'}.items()}}
    g._metadata(metadata)
    p.write(SNAPSHOT/'public-metadata.json',metadata)
    result=g.package_public(SNAPSHOT/'FORK-Singapore-National-Preview-v1.zip',metadata,SNAPSHOT/'public-v1')
    print(json.dumps({'status':result['status'],'zip':result['zip'],'areaKm2':manifest['expectedChunks']*256/1e6}),flush=True)

if __name__=='__main__':main()
