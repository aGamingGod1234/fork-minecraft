import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {validateStructure, captions} from './film.mjs';

// Metadata fixtures only. These never create footage or authorize a film render.
const template = JSON.parse(fs.readFileSync(new URL('../../media/edit/film-edit.json', import.meta.url)));
function acceptedMetadata() {
  const m = structuredClone(template), hash = 'a'.repeat(64);
  Object.assign(m.acceptance,{acceptedByMain:true,releaseCommit:'b'.repeat(40),packageSha256:hash,worldSha256:hash,cameraBindingSha256:hash,mode:'live',rewindMethod:'Verified bounded restore',actualCoverage:'Fictional court only',newDevelopmentCredit:'Fixture metadata test only',developmentEvidence:'Fixture receipt only'});
  m.credits=['Game notice example','Map attribution example','Development attribution example','Human narrator example'];
  for (const k of Object.keys(m.checks)) m.checks[k] = k === 'threeRestoresEvidence' ? 'Test receipt only' : true;
  for (const s of m.shots) {
    s.reviewed=true; s.evidence='Metadata fixture only';
    s.segments=[{kind:'clip',source:'media/source/test.mkv',sha256:hash,in:0,out:s.frames/30,frames:s.frames,captionSafeReviewed:true,audio:false}];
  }
  m.shots[21].segments = [1,2].map(() => ({kind:'card',textFile:'media/edit/cards/title.txt',frames:90,reviewed:true}));
  m.voice=[{source:'media/source/human.wav',sha256:hash,in:1,out:3,at:60,windowEnd:68,text:'Same start. Six rounds.',humanConfirmed:true,transcriptConfirmed:true}];
  return m;
}
test('incomplete template cannot become a final film', () => assert.throws(() => validateStructure(template), /Main has not accepted/));
test('complete timeline requires exactly 2700 frames and 23 shots', () => {
  const m=acceptedMetadata(); assert.deepEqual(validateStructure(m),{frames:2700,seconds:90,shots:23});
  m.shots[18].frames=299; assert.throws(() => validateStructure(m), /wrong timeline/);
});
test('an unapproved fixture reduction is rejected', () => {
  const m=acceptedMetadata(); m.acceptance.mode='fixture'; assert.throws(() => validateStructure(m), /Lucas scope/);
});
test('narration cannot enter the final two comparison seconds', () => {
  const m=acceptedMetadata(); Object.assign(m.voice[0],{at:67,windowEnd:70}); assert.throws(() => validateStructure(m), /narration-free/);
});
test('source references cannot escape the leased media paths', () => {
  const m=acceptedMetadata(); m.shots[0].segments[0].source='media/source/../../coordinator/private.mkv'; assert.throws(() => validateStructure(m), /Unleased media path/);
});
test('human and transcript confirmation are required', () => {
  const m=acceptedMetadata(); m.voice[0].humanConfirmed=false; assert.throws(() => validateStructure(m), /confirmed human/);
});
test('gameplay cannot be replaced by an editorial card', () => {
  const m=acceptedMetadata(); m.shots[0].segments=[{kind:'card',textFile:'media/edit/cards/title.txt',frames:60,reviewed:true}]; assert.throws(() => validateStructure(m), /Cards only/);
});
test('captions follow actual source take duration', () => assert.equal(captions([{at:60,in:1,out:3,text:'Same start. Six rounds.'}]),'1\n00:01:00,000 --> 00:01:02,000\nSame start. Six rounds.\n'));
