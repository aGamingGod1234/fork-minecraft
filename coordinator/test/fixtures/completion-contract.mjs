/** Legacy fixture helper retained while older tests migrate to server-owned GoalSpec. */
export function completionContract(goalRevision = 1, predicate = { type: 'position_within', x: 0, y: 64, z: 0, radius: 16 }) {
	return { goalRevision, predicates: [structuredClone(predicate)] };
}

export function withCompletionContract(decision, goalRevision = 1) {
	return decision;
}
