import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { Server } from 'node:net';
import childProcess from 'node:child_process';
import { syncBuiltinESMExports } from 'node:module';
import test from 'node:test';
import { createDynamicCoordinator, createForkVoiceRuntime, startCoordinatorControl } from '../../src/dynamic-main.mjs';
import { normalizeForkPacket } from '../../src/fork/protocol.mjs';

test('FORK voice lifecycle is explicitly disabled, has no endpoint, and remains safe after repeated cleanup', async () => {
  const voice=createForkVoiceRuntime();
  assert.equal(voice.enabled,false);
  assert.equal(voice.endpoint,null);
  assert.deepEqual(voice.statusSnapshots(),[]);
  assert.equal(voice.start(),undefined);
  assert.equal(await voice.removeAgent('FORK_MEDIC'),false);
  await voice.close();await voice.close();voice.start();
  assert.equal(await voice.removeAgent('FORK_COURIER'),false);
  assert.deepEqual(voice.statusSnapshots(),[]);
  assert.equal(voice.endpoint,null); // Consumers have no voice URL to call or listener to use.
});

test('default control startup and shutdown do not require a voice service', async () => {
  const coordinator=new EventEmitter();let starts=0;
  coordinator.start=async()=>{starts++;};
  await startCoordinatorControl(coordinator);
  coordinator.emit('shutdown');
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(starts,1);
  await assert.rejects(startCoordinatorControl({async start(){throw new Error('bridge failed');}}),/bridge failed/);
});

test('real coordinator starts and routes a three-role LIVE request while no voice worker, process or endpoint starts', async t => {
  const listens=t.mock.method(Server.prototype,'listen',()=>{throw new Error('Unexpected optional HTTP listener');});
  const spawns=t.mock.method(childProcess,'spawn',()=>{throw new Error('Unexpected speech/provider process');});
  syncBuiltinESMExports();
  t.after(()=>{t.mock.restoreAll();syncBuiltinESMExports();});
  class Bridge extends EventEmitter {
    ready=false;sent=[];
    start(){this.ready=true;}
    stop(){this.ready=false;}
    async send(type,agentId,payload){this.sent.push({type,agentId,payload});}
  }
  const bridge=new Bridge(),prompts=[],profiles=[];
  let providerStops=0,voiceStarts=0;
  const service={
    catalog:{stale:false,async refresh(){return {models:[],refreshedAtEpochMs:0};},assertSupported(){}},
    async stop(){providerStops++;},
    async removeAgent(){},
    async createAgent(profile,options){
      profiles.push({profile,options});
      return {async setGoalRevision(){},async decide(input){prompts.push(JSON.parse(input));return {action:'wait'};}};
    },
  };
  const planner={
    async requestPlan(){throw new Error('FORK must use its own Live adapter');},
    async interrupt(){},
    beginReconcile(){return {complete:Promise.resolve({providers:{valid:[],invalid:[],catalog:{models:[],refreshedAtEpochMs:0}}})};},
  };
  const voice={...createForkVoiceRuntime(),start(){voiceStarts++;throw new Error('Disabled voice startup was invoked');}};
  const coordinator=createDynamicCoordinator({
    bridge:{port:25570,secret:'fork-unit-test-only-secret-32chars'},
    codex:{controlProtocol:'native_tools',launchProfile:{agentId:'coordinator',model:'gpt-5.6-luna',reasoningEffort:'xhigh',serviceTier:'priority'}},
  },{env:{},bridge,planner,providerService:service,memoryDirectory:null,setStatusInterval:()=>1,clearStatusInterval(){},runtimeHooks:{onRemoved:id=>voice.removeAgent(id)}});
  const errors=[];coordinator.on('runtimeError',error=>errors.push(error));
  t.after(async()=>{await coordinator.stop();await voice.close();});
  await startCoordinatorControl(coordinator,voice);
  assert.equal(bridge.ready,true);
  bridge.emit('ready',{serverInstanceId:'fork-voice-disabled-test',registry:[],connectionEpoch:1});
  await new Promise(resolve=>setImmediate(resolve));
  const request={ticket:{branch:'LIVE-test',epoch:1,round:1,baseRevision:0,requestId:'request-1'},state:{mode:'LIVE',branch:'LIVE-test',epoch:1,round:0,revision:0},budgetMs:55000};
  bridge.emit('fork_request',{agentId:'server',connectionEpoch:1,payload:request});
  for(let i=0;i<20&&!bridge.sent.some(m=>m.type==='fork_batch');i++)await new Promise(resolve=>setImmediate(resolve));
  const batch=bridge.sent.find(m=>m.type==='fork_batch');
  assert.ok(batch,'FORK adapter must emit the complete proposal batch');
  normalizeForkPacket('fork_batch',batch.payload);
  assert.equal(batch.payload.intents.length,3);
  assert.equal(profiles.length,3);
  assert.ok(profiles.every(p=>p.options.controlProtocol==='fork'&&p.profile.model==='gpt-5.6-luna'&&p.profile.reasoningEffort==='xhigh'));
  assert.ok(prompts.every(p=>p.mode==='LIVE'&&p.state.mode==='LIVE'));
  assert.equal(voiceStarts,0);assert.equal(listens.mock.callCount(),0);assert.equal(spawns.mock.callCount(),0);
  assert.deepEqual(errors,[]);
  for(const mode of ['FIXTURE','RECORDED'])assert.throws(()=>normalizeForkPacket('fork_request',{...request,state:{...request.state,mode}}));
  coordinator.emit('shutdown');await coordinator.stop();await voice.close();
  assert.equal(bridge.ready,false);assert.equal(providerStops,1);
});
