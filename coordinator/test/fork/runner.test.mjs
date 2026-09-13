import assert from 'node:assert/strict';
import { test } from 'node:test';
import { ForkRunner } from '../../src/fork/runner.mjs';
import { normalizeForkPacket } from '../../src/fork/protocol.mjs';
import { createProtocolV2Envelope } from '../../src/protocol-v2.mjs';
function request(branch='B') { return {ticket:{branch,epoch:2,round:2,baseRevision:1,requestId:'r2'},state:{mode:'LIVE',branch,epoch:2,round:1,revision:1},budgetMs:20000}; }
test('three roles use isolated request contexts and produce one complete batch',async()=>{
  const prompts=[],created=[],sent=[];
  const service={async createAgent(p,o){created.push([p,o]);return {async setGoalRevision(){},async decide(input){prompts.push(input);return {action:'wait'};}};},async removeAgent(){}};
  const runner=new ForkRunner(service,(type,p)=>sent.push([type,p]));await runner.request(request());
  assert.equal(created.length,3);assert.equal(sent.length,1);assert.equal(sent[0][1].intents.length,3);
  for(const [profile,options] of created){assert.match(profile.agentId,/fork-B-2-r2-/);assert.equal(options.controlProtocol,'fork');assert.equal(options.recoverySummary,null);}
  for(const input of prompts){assert.equal(JSON.parse(input).ticket.branch,'B');assert.ok(!input.includes('A-only-canary'));}
  normalizeForkPacket('fork_batch',sent[0][1]);
});
test('whole attempt timeout sends only error and late results never send a batch',async()=>{
  const sent=[];let resolve;const delayed=new Promise(r=>resolve=r);
  const service={async createAgent(){await delayed;return {async setGoalRevision(){},async decide(){return {action:'wait'};}};},async removeAgent(){}};
  const runner=new ForkRunner(service,(t,p)=>sent.push(p),{budgetMs:5});await runner.request(request());resolve();await new Promise(r=>setTimeout(r,10));
  assert.equal(sent.length,1);assert.ok(sent[0].error);assert.equal(sent[0].intents,undefined);
});
test('cancel detaches attempt; incomplete batch rejected',async()=>{
  const sent=[];let resolve;const delayed=new Promise(r=>resolve=r);
  const service={async createAgent(){await delayed;return {async setGoalRevision(){},async decide(){return {action:'wait'};}};},async removeAgent(){}};
  const runner=new ForkRunner(service,(t,p)=>sent.push(p));const task=runner.request(request());runner.cancel();resolve();await task;
  assert.equal(sent.length,0);assert.throws(()=>normalizeForkPacket('fork_batch',{ticket:request().ticket,intents:[]}));
});
test('existing bridge codec admits the exact whole-batch FORK protocol',()=>{
  const payload=request();
  const envelope=createProtocolV2Envelope({serverInstanceId:'test-server',agentId:'server',type:'fork_request',messageId:'request-1',payload});
  assert.equal(envelope.payload.ticket.round,2);
  const batch={ticket:payload.ticket,intents:['MEDIC','ENGINEER','COURIER'].map(role=>({role,actionId:`r2-${role}`,action:'wait'}))};
  const reply=createProtocolV2Envelope({serverInstanceId:'test-server',agentId:'server',type:'fork_batch',messageId:'batch-1',payload:batch});
  assert.equal(reply.payload.intents.length,3);
  assert.throws(()=>normalizeForkPacket('fork_request',{...payload,state:{...payload.state,mode:'RECORDED'}}));
});
test('A canary never enters B requests after rewind or runner restart',async()=>{
  const prompts=[],ids=[];
  const service={async createAgent(p){ids.push(p.agentId);return {async setGoalRevision(){},async decide(input){prompts.push(JSON.parse(input));return {action:'wait'};}};},async removeAgent(){}};
  const runner=new ForkRunner(service,()=>{});
  const a=request('A');a.state.diagnostic='A-only-canary';await runner.request(a);
  runner.receipt({ticket:a.ticket,state:a.state});runner.cancel();
  await runner.request(request('B'));
  const restarted=new ForkRunner(service,()=>{});const b=request('B-restart');b.ticket.epoch=3;b.state.epoch=3;await restarted.request(b);
  const later=prompts.filter(p=>p.ticket.branch!=='A');assert.equal(later.length,6);
  for(const p of later) assert.ok(!JSON.stringify(p).includes('A-only-canary'));
  assert.equal(new Set(ids).size,9);
});
