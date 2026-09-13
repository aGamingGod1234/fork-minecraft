export const FACT_DOMAIN = Object.freeze({
	player: 1 << 0,
	worldItems: 1 << 1,
	worldEntities: 1 << 2,
	worldBlocks: 1 << 3,
	inventoryItems: 1 << 4,
	inventoryTagCounts: 1 << 5,
	worldState: 1 << 6,
	menu: 1 << 7,
	inventoryState: 1 << 8,
});

export const ALL_FACT_DOMAINS = Object.values(FACT_DOMAIN).reduce((mask, domain) => mask | domain, 0);
