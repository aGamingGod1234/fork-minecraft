import { normalizeForkPacket } from './protocol.mjs';

/** Reuses the approved ProviderService transport. Each role gets a new branch/epoch/request namespace. */
export class ForkRunner {
  constructor(service, send, { budgetMs, report=event=>console.info('[FORK runtime] '+JSON.stringify(event)) }={}) {
    if (budgetMs !== undefined && (!Number.isSafeInteger(budgetMs) || budgetMs < 1)) throw new TypeError('Invalid FORK budget limit');
    this.service=service;this.send=send;this.budgetMs=budgetMs;this.active=null;this.receipts=new Map();this.report=report;
  }
  cancel() { const a=this.active;this.active=null;if(a) {a.controller.abort();for(const id of a.ids) void this.service.removeAgent(id).catch(()=>{});} }
  receipt(value) { if(value?.ticket) this.receipts.set(JSON.stringify(value.ticket),structuredClone(value)); }
  async request(raw) {
    const p=normalizeForkPacket('fork_request',raw);
    if(this.active) throw new Error('FORK attempt already pending');
    // The server owns the budget. An injected local cap may shorten it, never extend it.
    const budgetMs=Math.min(p.budgetMs,this.budgetMs??p.budgetMs);
    const a={controller:new AbortController(),ids:[],ticket:p.ticket};this.active=a;
    const started=performance.now(),roles={};
    const report=(stage,details={})=>{try{this.report({stage,ticket:p.ticket,elapsedMs:Math.round(performance.now()-started),budgetMs,...details});}catch{}};
    const progress=(role,phase)=>{roles[role]=phase;report('role-stage',{role,phase});};
    report('attempt-start');
    let timer;
    const expired=new Promise((_,reject)=>{timer=setTimeout(()=>{a.controller.abort();reject(new Error(`Whole three-role attempt exceeded ${budgetMs/1000} seconds`));},budgetMs);});
    const current=()=>this.active===a&&!a.controller.signal.aborted;
    try {
      const intents=await Promise.race([expired,Promise.all(['MEDIC','ENGINEER','COURIER'].map(async role=>{
        const id=`fork-${p.ticket.branch}-${p.ticket.epoch}-${p.ticket.requestId}-${role}`;
        a.ids.push(id);
        progress(role,'creating');
        const agent=await this.service.createAgent({agentId:id,provider:'codex',model:'gpt-5.6-luna',reasoningEffort:'xhigh',serviceTier:'priority'}, {controlProtocol:'fork',recoverySummary:null});
        if(!current()) { void this.service.removeAgent(id).catch(()=>{});throw new Error('Cancelled before role turn'); }
        progress(role,'created');
        await agent.setGoalRevision(p.ticket.baseRevision);
        if(!current()) throw new Error('Cancelled before role turn');
        const actions=role==='MEDIC'?['request_spare','wait']:role==='ENGINEER'?['repair','wait']:['deliver_spare','wait'];
        // No notebook, archive, recovery summary or physical tool context is included.
        const input=JSON.stringify({schema:'fork-1',mode:'LIVE',ticket:p.ticket,role,allowedActions:actions,state:p.state,
          rules:'Six rounds. Batteries clinic only. Deliver B1 once. Three powered workshop repairs activate grid NEXT round. Medic request is nonbinding. Reply one proposed action. Server alone commits complete batches.'});
        progress(role,'deciding');
        const proposal=await agent.decide(input,{goalRevision:p.ticket.baseRevision,signal:a.controller.signal,
          systemPrompt:'You propose exactly one legal FORK role action. Never call tools. Do not infer prior branch knowledge.',
          outputSchema:{type:'object',properties:{action:{type:'string',enum:actions}},required:['action'],additionalProperties:false},
          parseOutput:text=>{const v=JSON.parse(text);if(Object.keys(v).join()!=='action'||!actions.includes(v.action))throw new Error('Malformed FORK role proposal');return v;}});
        if(!current()) throw new Error('Cancelled role completion');
        roles[role]='complete';
        const settings=agent.executionSettings;
        report('role-proposal',{role,proposal:proposal.action,requested:settings?.requested??{provider:'codex',model:'gpt-5.6-luna',reasoningEffort:'xhigh',serviceTier:'priority'},effective:settings?.effective??null,evidence:settings?.evidence??null});
        return {role,actionId:`${p.ticket.requestId}-${role}`,action:proposal.action};
      }))]);
      if(current()) { await this.send('fork_batch',{ticket:p.ticket,intents});report('batch-sent'); }
    } catch(error) { report(this.active===a?'attempt-error':'attempt-cancelled',{error:String(error.message).slice(0,256),roles:{...roles}});if(this.active===a) await this.send('fork_batch',{ticket:p.ticket,error:String(error.message).slice(0,256)}); }
    finally { clearTimeout(timer);if(this.active===a)this.active=null;a.controller.abort();for(const id of a.ids) void this.service.removeAgent(id).catch(()=>{}); }
  }
}
