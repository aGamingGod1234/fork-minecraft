const BASE_COMPLETION_CONTRACT = Object.freeze({
	goalRevision: 1,
	predicates: Object.freeze([{ type: 'inventory_min', itemId: 'minecraft:wooden_pickaxe', count: 1 }]),
});

export function factualCompletionContract(goalRevision = 1) {
	if (!Number.isSafeInteger(goalRevision) || goalRevision < 0) throw new TypeError('goalRevision must be a nonnegative safe integer');
	return { goalRevision, predicates: [{ ...BASE_COMPLETION_CONTRACT.predicates[0] }] };
}

export function replaceDecision({ summary = 'Wait safely.', source = 'program.onUnhandledAttention("continue_and_notify"); await player.wait(25);', goalRevision = 1 } = {}) {
	return {
		summary,
		directive: 'replace',
		source,
	};
}

export function replaceDecisionJson(options) {
	return JSON.stringify(replaceDecision(options));
}

export function finishDecision({ summary = 'Done' } = {}) {
	return {
		summary,
		directive: 'finish',
		source: null,
	};
}

export function finishDecisionJson(options) {
	return JSON.stringify(finishDecision(options));
}
