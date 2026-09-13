export const FORK_PROVIDER_BUDGET_MS = 55_000;
const ROLES = ['MEDIC', 'ENGINEER', 'COURIER'];
function exact(value, keys) {
  if (!value || Array.isArray(value) || typeof value !== 'object' || Object.keys(value).sort().join('|') !== [...keys].sort().join('|')) throw new Error('Malformed FORK object');
}
export function forkTicket(t) {
  exact(t, ['branch', 'epoch', 'round', 'baseRevision', 'requestId']);
  for (const key of ['branch', 'requestId']) if (typeof t[key] !== 'string' || !/^[a-zA-Z0-9._-]{1,128}$/.test(t[key])) throw new Error('Invalid FORK identity');
  for (const key of ['epoch', 'round', 'baseRevision']) if (!Number.isSafeInteger(t[key]) || t[key] < 0) throw new Error('Invalid FORK revision');
  if (t.round < 1 || t.round > 6) throw new Error('Invalid FORK round');
  return structuredClone(t);
}
export function normalizeForkPacket(type, p) {
  if (JSON.stringify(p).length > 65536) throw new Error('FORK packet too large');
  if (type === 'fork_request') {
    exact(p, ['ticket', 'state', 'budgetMs']); forkTicket(p.ticket);
    if (![20_000, FORK_PROVIDER_BUDGET_MS].includes(p.budgetMs) || p.state?.mode !== 'LIVE' || p.state.branch !== p.ticket.branch || p.state.epoch !== p.ticket.epoch || p.state.revision !== p.ticket.baseRevision || p.state.round + 1 !== p.ticket.round) throw new Error('Invalid FORK observation');
  } else if (type === 'fork_cancel') { exact(p, ['epoch']); if (!Number.isSafeInteger(p.epoch) || p.epoch < 0) throw new Error('Invalid cancel epoch'); }
  else if (type === 'fork_batch') {
    if ('error' in p) { exact(p, ['ticket', 'error']); forkTicket(p.ticket); if(typeof p.error !== 'string' || p.error.length>256) throw new Error('Invalid error'); }
    else {
      exact(p, ['ticket', 'intents']); forkTicket(p.ticket);
      if(!Array.isArray(p.intents) || p.intents.length!==3) throw new Error('Incomplete FORK batch');
      const roles=new Set(), ids=new Set();
      for(const i of p.intents) { exact(i,['role','actionId','action']);
        if(!ROLES.includes(i.role)||roles.has(i.role)||typeof i.actionId!=='string'||i.actionId.length>128||ids.has(i.actionId)||typeof i.action!=='string'||i.action.length>64||!i.action) throw new Error('Invalid FORK intent');
        roles.add(i.role);ids.add(i.actionId);
      }
    }
  } else if(type !== 'fork_receipt') throw new Error('Unknown FORK packet');
  return structuredClone(p);
}
