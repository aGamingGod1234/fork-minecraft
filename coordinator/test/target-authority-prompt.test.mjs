import assert from 'node:assert/strict';
import test from 'node:test';

import { PLANNER_SYSTEM_PROMPT } from '../src/prompts.mjs';

test('planner prompt makes observed stable ids the only combat target authority', () => {
	assert.match(PLANNER_SYSTEM_PROMPT, /candidate\.stableId/);
	assert.match(PLANNER_SYSTEM_PROMPT, /targetId/);
	assert.match(PLANNER_SYSTEM_PROMPT, /never use nearest_hostile, nearest_player, nearest_living/);
});
