import path from 'node:path';
const coordinator='/root/singapore_full_coordinator';

export function queueLease(policy,root,environment,now=new Date()){
  if(policy?.approvedBy!==coordinator||policy.heavyJobSlot!=='A'||policy.cpuThreads!==1||policy.memoryGiB!==4)throw Error('Unapproved queue resource policy');
  if(!/^[a-f0-9]{64}$/.test(environment.FORK_JOB_ID??'')||!/^\d+$/.test(environment.FORK_JOB_ATTEMPT??''))throw Error('Queue identity is missing');
  if(path.resolve(environment.FORK_OUTPUT_DIR??'')!==path.resolve(root))throw Error('Queue output identity mismatch');
  const suffix=path.join('jobs',environment.FORK_JOB_ID,'attempt-'+environment.FORK_JOB_ATTEMPT,'output');
  if(!path.resolve(root).endsWith(path.sep+suffix))throw Error('Queue attempt path mismatch');
  const expires=new Date(policy.expiresUtc);if(!Number.isFinite(+expires)||expires<=now)throw Error('Queue resource policy expired');
  return {schemaVersion:1,id:environment.FORK_JOB_ID+'-'+environment.FORK_JOB_ATTEMPT,machine:'Desktop',approvedBy:coordinator,heavyJobSlot:'A',outputRoot:path.resolve(root),startsUtc:now.toISOString(),expiresUtc:expires.toISOString(),cpuThreads:1,memoryGiB:4,reservedCores:2,reservedMemoryGiB:6,coordinatorPid:process.pid,queueJobId:environment.FORK_JOB_ID,queueAttempt:Number(environment.FORK_JOB_ATTEMPT)};
}

export function sourceQuarantine(manifest,features,policy,allowedIds=[]){
  const byId=new Map(features.map(f=>[String(f.id??f.properties?.featureid),f]));
  const permitted=new Set(allowedIds.map(String));
  const experimental=policy?.mode==='invalid-building-parts-report';
  if(policy&&(!experimental||policy.approvedBy!==coordinator||policy.maxFraction!==0.1))throw Error('Unapproved source quarantine policy');
  const exclusions=(manifest.exclusions??[]).map(item=>{
    const featureId=String(item.featureId??item.id??''),feature=byId.get(featureId);
    if(!feature)throw Error('Excluded feature has no original source geometry: '+featureId);
    const tags=feature.properties?.tags??feature.properties??{};
    const known=permitted.has(featureId.replace(/^way\//,''));
    const sourcePart=(tags['building:part']&&tags['building:part']!=='no')||feature.properties?.buildingRole==='part';
    if(!known&&(!experimental||!sourcePart||!/height|levels|roof/i.test(item.reason??'')))throw Error('Unexpected or systemic source exclusion: '+JSON.stringify(item));
    return {...item,featureId,originalTags:tags,policy:known?'explicit-feature-allowlist':'experimental-invalid-building-part'};
  });
  const relevantFeatureCount=(manifest.selectedFeatureCount??0)+exclusions.length;
  const fraction=relevantFeatureCount?exclusions.length/relevantFeatureCount:0;
  if(experimental&&fraction>policy.maxFraction)throw Error('Source quarantine exceeds ten percent of relevant building features');
  return {schemaVersion:1,mode:experimental?policy.mode:'explicit-feature-allowlist',excludedCount:exclusions.length,relevantFeatureCount,excludedFraction:fraction,exclusions,sourceGeometryComplete:manifest.completeSourceGeometryAccepted===true&&exclusions.length===0,productionAccepted:false};
}
