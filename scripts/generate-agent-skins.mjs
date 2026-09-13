import {
	existsSync,
	mkdirSync,
	readFileSync,
	readdirSync,
	rmSync,
	writeFileSync,
} from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { deflateSync, inflateSync } from 'node:zlib';

// The vanilla humanoid UV layout is authored in 64 logical pixels. Brand agent
// textures use an 8x raster so the visible 8x8 face can carry a clean logo;
// ordinary provider skins remain native 64x64 textures.
const LOGICAL_SIZE = 64;
const DEFAULT_TEXTURE_SCALE = 1;
const BRAND_TEXTURE_SCALE = 8;
let TEXTURE_SCALE = DEFAULT_TEXTURE_SCALE;
let WIDTH = LOGICAL_SIZE * DEFAULT_TEXTURE_SCALE;
let HEIGHT = LOGICAL_SIZE * DEFAULT_TEXTURE_SCALE;
const CHANNELS = 4;
const EXPECTED_PROVIDER_COUNT = 4;
const EXPECTED_FAMILY_COUNT = 4;
const EXPECTED_VARIANT_COUNT = 4;
const PNG_SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const TEXTURE_PREFIX = 'arenaagents:textures/entity/';
const SCRIPT_DIRECTORY = path.dirname(fileURLToPath(import.meta.url));
const PROJECT_DIRECTORY = path.resolve(SCRIPT_DIRECTORY, '..');
const MANIFEST_PATH = path.join(
	PROJECT_DIRECTORY,
	'src',
	'main',
	'resources',
	'assets',
	'arenaagents',
	'identity',
	'agent_visual_manifest.json',
);
const OUTPUT_DIRECTORY = path.join(
	PROJECT_DIRECTORY,
	'src',
	'main',
	'resources',
	'assets',
	'arenaagents',
	'textures',
	'entity',
);

const PALETTES = Object.freeze({
	codex: Object.freeze({
		dark: Object.freeze([17, 24, 28, 255]),
		base: Object.freeze([31, 42, 47, 255]),
		mid: Object.freeze([72, 94, 99, 255]),
		light: Object.freeze([211, 229, 228, 255]),
		accent: Object.freeze([24, 202, 196, 255]),
	}),
	gemini: Object.freeze({
		dark: Object.freeze([27, 35, 53, 255]),
		base: Object.freeze([80, 99, 137, 255]),
		mid: Object.freeze([142, 166, 211, 255]),
		light: Object.freeze([235, 240, 249, 255]),
		accent: Object.freeze([91, 139, 235, 255]),
	}),
	kimi: Object.freeze({
		dark: Object.freeze([20, 18, 35, 255]),
		base: Object.freeze([45, 39, 70, 255]),
		mid: Object.freeze([105, 94, 151, 255]),
		light: Object.freeze([229, 225, 243, 255]),
		accent: Object.freeze([144, 116, 235, 255]),
	}),
	cursor: Object.freeze({
		dark: Object.freeze([18, 20, 20, 255]),
		base: Object.freeze([39, 43, 42, 255]),
		mid: Object.freeze([104, 111, 101, 255]),
		light: Object.freeze([226, 231, 213, 255]),
		accent: Object.freeze([205, 220, 69, 255]),
	}),
});

// These are deterministic, nearest-neighbour pixel interpretations of each official
// mark. Keep the body colour dominant so the identity reads clearly at Minecraft's
// normal camera distance. The existing provider keys remain the source of truth for
// filenames.
const BRAND_PALETTES = Object.freeze({
	openai: Object.freeze({
		base: Object.freeze([16, 163, 127, 255]),
		dark: Object.freeze([8, 75, 59, 255]),
		light: Object.freeze([232, 255, 247, 255]),
		accent: Object.freeze([255, 255, 255, 255]),
	}),
	claude: Object.freeze({
		base: Object.freeze([217, 119, 87, 255]),
		dark: Object.freeze([107, 47, 34, 255]),
		light: Object.freeze([255, 224, 209, 255]),
		accent: Object.freeze([246, 176, 132, 255]),
	}),
	deepseek: Object.freeze({
		base: Object.freeze([245, 248, 255, 255]),
		dark: Object.freeze([26, 55, 117, 255]),
		light: Object.freeze([255, 255, 255, 255]),
		accent: Object.freeze([87, 134, 254, 255]),
	}),
	gemini: Object.freeze({
		base: Object.freeze([138, 180, 248, 255]),
		dark: Object.freeze([66, 133, 244, 255]),
		light: Object.freeze([235, 242, 255, 255]),
		accent: Object.freeze([66, 133, 244, 255]),
	}),
	kimi: Object.freeze({
		base: Object.freeze([0, 0, 0, 255]),
		dark: Object.freeze([0, 0, 0, 255]),
		light: Object.freeze([2, 2, 2, 255]),
		accent: Object.freeze([1, 1, 1, 255]),
	}),
});

// These are compressed RGBA rasterizations of the referenced brand marks rather
// than runtime downloads, so generation remains deterministic and the mod has
// no network dependency. Most marks use a 56px raster inside the 64px face UV
// square; KIMI uses the supplied complete 64px black-background face tile.
const BRAND_LOGO_RASTER_SIZE = 56;
const BRAND_LOGO_RASTERS = Object.freeze({
	openai: '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',
	claude: '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',
	deepseek: 'eNrtmluIVlUUx6d0Gkvxgo3jpTTUJB8yE1/SUbp4LwcUSwihfMiMwlJRR1AQA9NMTVNBVBQUX7QeqgdRRhRNDURBNE2LKVNLy/GCt7KZn2fBGjhu9vnOPufb3zcjnP+TOHutvf5n773Wf6/9lZRkyJAhQ4YMDw+A0cDrD0GcrYERQDWwAvgMGBljUwZcBGqbQfwtgFLj/x4DxgLbgFs8iHvA/Bif40LjXy4SjxeAucD3wK9AHfC/xlAPXAAOAzuBa0RjqsNci0PjzwC9UsTbBqiIGfMI8CZwFD+4LD4dYtts2N0BVgGdHLk9qTGfyzGmN7APv5C92sYhvu0R9lfj9iswBPhZx9dFjBmuvgqBLUDLmBiX57Cva1xHoB/wZZCvDgHHNCeFsd/ie0yQ6+5SWBwB+ubg91GM/WxgmZ77XFht+JXvcZPi4EZwRqoi+HWPif2e4xxDQj6f0FxVTNRH5VPNw/ngpOFvKU2DBmC6hV9fS+1MgrEhXz2C2vYvTYtPLBwnK/+kWGT4WUPTQ3hMs3CcqPXPBXIuqw37VgWsBWk4zrBwHOnA62vgRYttFc0P68KaVuuxDStVX3V2rKVSG6YBQwNt3Ec15yvB+n4c6JmtwKUicpR6PUxjXGv5+xVHrbY3ZLPTQY++BuxIUIOi9pPow78dxv4QkUdPOPI7H7L5MYE+7xpoyS+A/1LwGxz6XqPknpfCxy7HOG+HbM6nuIc8r984CTYBkwLt2F59VOh+TIKljvE1GPumVQqOjwIzQ/fBOPwm+ws4Czwe4nguAb8JjrGZe7syjztwlaN+nQO8pf9+OmQ/2EEzN9aRLo4xXTC1eZ73/AGBpvzLIb7Gte5q2H/lwO9wgngOGLY1HnoZ/WN6FGEMMmzLjZxgQ3WCWDZYNH03DxyHOZ7H08Azhp6qjaktTyWI4z2Lj1meelJzE/QtpNe2EfgjZuw3CWN4zuJDamKZB35S32o865rKFHH8lKaP5+i7m/ZMfGB3yhjmWXxJXm3nieM7HrjJWR6Ycv6KiPvteo/94R158luR5/yrI+rUaE/8OqhuSYPfXfqmDmt4PaLP1d/BXu5Q83PVFuCllHr8DU/feEqEfzmLz8bYHg/10pc06krLuDnF3JeWfP5txDz/5MrNljvoGdt41eLbHLntN9+mPHBsB5yKmE/62h/a3khEg1h0lWihBfKGZnk/2+3Ab7LLe0wKjt31TEdhj7y/WOx2RYyvMXsksn+B7xw41mr9KvfMsVdwB/0lpve8T9fnA+0J5dKbf8rbhjFHqeN9Ad0bMkdbjxw7AQc966sDmmMqgZ4Bv476dl3vaD/O8zqWao+tgcJBuH0qPWzlb6tTV7SHV1ZSAOi74ckCcJOaP9wyX7mub0/fZy8Hx5ZBPO96emeSnvlC+X1FM/zNh9TJV/W+djHhm5/Uhfd95okicJW3+vHaQ/tc85zU+0V6pt7Wt9MW2S+XMmTIkCFDc8B9qmEgAg==',
	gemini: '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',
	kimi: 'eJzt09ENAjEMA9BOwqSszTcsACptnThxHcmfp/Oz7sYY7yGax/P1NexeTPstG8z8yhv8Y7dfdwP777Xbj/XPLsqwcjt+VA+2/VcH1Pee7UfYM/so2zP9Fe1Z/qr2DH9le7S/uj3S38Ee5e9ij/B3sqP93exIf0c7yt/VjvB3tp/6u9tP/Ar2Xb+KfcevZF/1q9lX/Ir2HZf9WhtEH9vH9lffIPPYVra/4gasY7sRfsTz7CC6d94A1fn0uvij/6fK/uh/irFDVKcuG0R3QRzTn/mNMXbIfOft/tMNFPwnG6j4d3dQ869uoOhf2UDVz+xSyT/rlb07y+44juM4juM4u/kACYL5ag==',
});

const BRAND_TEXTURE_NAMES = Object.freeze(Object.keys(BRAND_PALETTES).flatMap((brand) =>
	Array.from({ length: EXPECTED_VARIANT_COUNT }, (_, variantIndex) => ({
		providerKey: `brand-${brand}`,
		providerIndex: -1,
		familyKey: brand,
		familyIndex: 0,
		variantIndex,
		textureName: `brand_${brand}_agent_${variantIndex}.png`,
		brandKey: brand,
	}))));

const CUBOID_FACES = Object.freeze([
	[8, 0, 8, 8], [16, 0, 8, 8], [0, 8, 8, 8], [8, 8, 8, 8], [16, 8, 8, 8], [24, 8, 8, 8],
	[20, 16, 8, 4], [28, 16, 8, 4], [16, 20, 4, 12], [20, 20, 8, 12], [28, 20, 4, 12], [32, 20, 8, 12],
	[44, 16, 4, 4], [48, 16, 4, 4], [40, 20, 4, 12], [44, 20, 4, 12], [48, 20, 4, 12], [52, 20, 4, 12],
	[4, 16, 4, 4], [8, 16, 4, 4], [0, 20, 4, 12], [4, 20, 4, 12], [8, 20, 4, 12], [12, 20, 4, 12],
	[20, 48, 4, 4], [24, 48, 4, 4], [16, 52, 4, 12], [20, 52, 4, 12], [24, 52, 4, 12], [28, 52, 4, 12],
	[36, 48, 4, 4], [40, 48, 4, 4], [32, 52, 4, 12], [36, 52, 4, 12], [40, 52, 4, 12], [44, 52, 4, 12],
]);

function loadOutputs() {
	const manifest = JSON.parse(readFileSync(MANIFEST_PATH, 'utf8'));
	if (manifest.schemaVersion !== 1) throw new Error('Manifest schemaVersion must be 1');
	if (!Array.isArray(manifest.providers) || manifest.providers.length !== EXPECTED_PROVIDER_COUNT) {
		throw new Error(`Manifest must declare exactly ${EXPECTED_PROVIDER_COUNT} providers`);
	}

	const outputs = [];
	const providerKeys = new Set();
	const textureNames = new Set();
	for (const [providerIndex, provider] of manifest.providers.entries()) {
		if (typeof provider.key !== 'string' || !PALETTES[provider.key]) {
			throw new Error(`Manifest provider ${providerIndex} has no generator chassis`);
		}
		if (providerKeys.has(provider.key)) throw new Error(`Duplicate manifest provider ${provider.key}`);
		providerKeys.add(provider.key);
		if (!Array.isArray(provider.families) || provider.families.length !== EXPECTED_FAMILY_COUNT) {
			throw new Error(`Manifest provider ${provider.key} must declare exactly ${EXPECTED_FAMILY_COUNT} families`);
		}

		const familyKeys = new Set();
		for (const [familyIndex, family] of provider.families.entries()) {
			if (typeof family.key !== 'string' || familyKeys.has(family.key)) {
				throw new Error(`Manifest provider ${provider.key} has an invalid or duplicate family key`);
			}
			familyKeys.add(family.key);
			if (!Array.isArray(family.variants) || family.variants.length !== EXPECTED_VARIANT_COUNT) {
				throw new Error(`Manifest family ${provider.key}/${family.key} must declare exactly ${EXPECTED_VARIANT_COUNT} variants`);
			}
			for (const [variantIndex, variant] of family.variants.entries()) {
				const texturePath = variant.texturePath;
				if (typeof texturePath !== 'string' || !texturePath.startsWith(TEXTURE_PREFIX)) {
					throw new Error(`Manifest family ${provider.key}/${family.key} has an invalid texture path`);
				}
				const textureName = texturePath.slice(TEXTURE_PREFIX.length);
				if (path.basename(textureName) !== textureName || !textureName.endsWith('.png')) {
					throw new Error(`Manifest texture ${texturePath} must be a direct entity PNG`);
				}
				if (textureNames.has(textureName)) throw new Error(`Duplicate manifest texture ${texturePath}`);
				textureNames.add(textureName);
				outputs.push(Object.freeze({
					providerKey: provider.key,
					providerIndex,
					familyKey: family.key,
					familyIndex,
					variantIndex,
					textureName,
				}));
			}
		}
	}

	const expectedCount = EXPECTED_PROVIDER_COUNT * EXPECTED_FAMILY_COUNT * EXPECTED_VARIANT_COUNT;
	if (outputs.length !== expectedCount) {
		throw new Error(`Manifest output count drift: expected ${expectedCount}, found ${outputs.length}`);
	}
	if (!Array.isArray(manifest.brandSkins) || manifest.brandSkins.length !== BRAND_TEXTURE_NAMES.length / EXPECTED_VARIANT_COUNT) {
		throw new Error(`Manifest must declare exactly ${BRAND_TEXTURE_NAMES.length / EXPECTED_VARIANT_COUNT} brand skins`);
	}
	const declaredBrandTextures = new Set();
	for (const brand of manifest.brandSkins) {
		if (!BRAND_PALETTES[brand.key] || !Array.isArray(brand.variants)
				|| brand.variants.length !== EXPECTED_VARIANT_COUNT) {
			throw new Error(`Manifest brand skin ${brand.key} is missing its four variants`);
		}
		for (const variant of brand.variants) {
			const textureName = variant.texturePath?.slice(TEXTURE_PREFIX.length);
			if (!textureName || !textureName.startsWith(`brand_${brand.key}_agent_`)) {
				throw new Error(`Manifest brand skin ${brand.key} has an invalid texture path`);
			}
			declaredBrandTextures.add(textureName);
		}
	}
	if (declaredBrandTextures.size !== BRAND_TEXTURE_NAMES.length) {
		throw new Error('Manifest brand skin texture count drift');
	}
	return outputs.concat(BRAND_TEXTURE_NAMES);
}

function createPixels() {
	return Buffer.alloc(WIDTH * HEIGHT * CHANNELS);
}

function setTexturePixel(pixels, x, y, color) {
	if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT) return;
	const offset = ((y * WIDTH) + x) * CHANNELS;
	for (let channel = 0; channel < CHANNELS; channel += 1) pixels[offset + channel] = color[channel];
}

function setPixel(pixels, x, y, color) {
	const scaledX = x * TEXTURE_SCALE;
	const scaledY = y * TEXTURE_SCALE;
	for (let pixelY = scaledY; pixelY < scaledY + TEXTURE_SCALE; pixelY += 1) {
		for (let pixelX = scaledX; pixelX < scaledX + TEXTURE_SCALE; pixelX += 1) {
			if (pixelX < 0 || pixelX >= WIDTH || pixelY < 0 || pixelY >= HEIGHT) continue;
			setTexturePixel(pixels, pixelX, pixelY, color);
		}
	}
}

function fillRect(pixels, x, y, width, height, color) {
	for (let row = y; row < y + height; row += 1) {
		for (let column = x; column < x + width; column += 1) setPixel(pixels, column, row, color);
	}
}

function drawLine(pixels, startX, startY, endX, endY, color, thickness = 1) {
	const steps = Math.max(Math.abs(endX - startX), Math.abs(endY - startY));
	for (let step = 0; step <= steps; step += 1) {
		const x = Math.round(startX + ((endX - startX) * step) / steps);
		const y = Math.round(startY + ((endY - startY) * step) / steps);
		fillRect(pixels, x, y, thickness, thickness, color);
	}
}

function drawBase(pixels, palette) {
	for (const [x, y, width, height] of CUBOID_FACES) fillRect(pixels, x, y, width, height, palette.base);
	for (const [x, y, width, height] of [
		[0, 8, 8, 8], [16, 8, 8, 8],
		[16, 20, 4, 12], [28, 20, 4, 12],
		[40, 20, 4, 12], [48, 20, 4, 12],
		[0, 20, 4, 12], [8, 20, 4, 12],
		[16, 52, 4, 12], [24, 52, 4, 12],
		[32, 52, 4, 12], [40, 52, 4, 12],
	]) fillRect(pixels, x, y, width, height, palette.dark);
	fillRect(pixels, 8, 8, 8, 2, palette.mid);
	fillRect(pixels, 20, 20, 8, 2, palette.mid);
	fillRect(pixels, 32, 20, 8, 2, palette.mid);
}

function drawCodexChassis(pixels, palette) {
	// Squared helmet and split visor.
	fillRect(pixels, 8, 8, 8, 2, palette.light);
	fillRect(pixels, 8, 10, 2, 5, palette.mid);
	fillRect(pixels, 14, 10, 2, 5, palette.mid);
	fillRect(pixels, 9, 11, 2, 2, palette.accent);
	fillRect(pixels, 13, 11, 2, 2, palette.accent);
	fillRect(pixels, 11, 11, 2, 2, palette.dark);
	fillRect(pixels, 25, 9, 6, 2, palette.light);
	fillRect(pixels, 27, 11, 2, 4, palette.mid);

	// Open-knot torso construction on front and back.
	for (const originX of [20, 32]) {
		fillRect(pixels, originX, 20, 3, 2, palette.light);
		fillRect(pixels, originX + 5, 20, 3, 2, palette.light);
		fillRect(pixels, originX, 22, 2, 4, palette.light);
		fillRect(pixels, originX + 6, 22, 2, 4, palette.light);
		fillRect(pixels, originX + 1, 26, 3, 2, palette.light);
		fillRect(pixels, originX + 4, 26, 3, 2, palette.accent);
		fillRect(pixels, originX + 3, 24, 2, 2, palette.dark);
		fillRect(pixels, originX + 2, 28, 4, 2, palette.accent);
	}
	// Broad symmetric shoulder frame on both arms.
	fillRect(pixels, 44, 20, 4, 4, palette.light);
	fillRect(pixels, 36, 52, 4, 4, palette.light);
	fillRect(pixels, 52, 20, 4, 3, palette.mid);
	fillRect(pixels, 44, 52, 4, 3, palette.mid);
	fillRect(pixels, 44, 24, 1, 6, palette.accent);
	fillRect(pixels, 39, 56, 1, 6, palette.accent);
}

function drawFourPointMark(pixels, originX, originY, color) {
	fillRect(pixels, originX + 2, originY, 2, 6, color);
	fillRect(pixels, originX, originY + 2, 6, 2, color);
	fillRect(pixels, originX + 1, originY + 1, 4, 4, color);
}

function drawGeminiChassis(pixels, palette) {
	// Four-point face mark on a light upper body.
	fillRect(pixels, 8, 8, 8, 8, palette.mid);
	drawFourPointMark(pixels, 9, 9, palette.light);
	fillRect(pixels, 11, 11, 2, 2, palette.accent);
	fillRect(pixels, 24, 8, 8, 8, palette.light);
	drawFourPointMark(pixels, 25, 9, palette.mid);

	// Mirrored quadrant torso construction.
	fillRect(pixels, 20, 20, 4, 6, palette.light);
	fillRect(pixels, 24, 20, 4, 6, palette.mid);
	fillRect(pixels, 20, 26, 4, 6, palette.mid);
	fillRect(pixels, 24, 26, 4, 6, palette.light);
	fillRect(pixels, 32, 20, 4, 6, palette.mid);
	fillRect(pixels, 36, 20, 4, 6, palette.light);
	fillRect(pixels, 32, 26, 4, 6, palette.light);
	fillRect(pixels, 36, 26, 4, 6, palette.mid);
	fillRect(pixels, 23, 23, 2, 2, palette.accent);
	fillRect(pixels, 35, 23, 2, 2, palette.accent);

	// Bright diagonal shoulder structure.
	drawLine(pixels, 44, 20, 46, 24, palette.light, 2);
	drawLine(pixels, 38, 52, 36, 56, palette.light, 2);
	drawLine(pixels, 52, 20, 54, 24, palette.accent, 2);
	drawLine(pixels, 46, 52, 44, 56, palette.accent, 2);
}

function drawKimiChassis(pixels, palette) {
	// Hooded edge and crescent face asymmetry.
	fillRect(pixels, 8, 8, 3, 8, palette.light);
	fillRect(pixels, 11, 8, 5, 8, palette.dark);
	fillRect(pixels, 10, 9, 4, 6, palette.accent);
	fillRect(pixels, 12, 9, 3, 5, palette.dark);
	fillRect(pixels, 9, 10, 2, 4, palette.light);
	fillRect(pixels, 24, 8, 5, 8, palette.dark);
	fillRect(pixels, 29, 8, 3, 8, palette.light);
	fillRect(pixels, 25, 10, 4, 4, palette.accent);

	// Crescent/arc torso and high-contrast side panel.
	fillRect(pixels, 20, 20, 3, 12, palette.light);
	fillRect(pixels, 23, 20, 5, 12, palette.dark);
	fillRect(pixels, 22, 21, 5, 9, palette.accent);
	fillRect(pixels, 24, 21, 4, 7, palette.dark);
	fillRect(pixels, 21, 23, 2, 5, palette.light);
	fillRect(pixels, 32, 20, 5, 12, palette.dark);
	fillRect(pixels, 37, 20, 3, 12, palette.light);
	fillRect(pixels, 33, 22, 4, 7, palette.accent);
	fillRect(pixels, 35, 22, 3, 5, palette.dark);
	fillRect(pixels, 44, 20, 2, 12, palette.light);
	fillRect(pixels, 38, 52, 2, 12, palette.light);
	fillRect(pixels, 52, 20, 2, 12, palette.dark);
	fillRect(pixels, 44, 52, 2, 12, palette.dark);
}

function drawCursorChassis(pixels, palette) {
	// Angled helmet split; intentionally unrelated to Codex's squared visor.
	fillRect(pixels, 8, 8, 8, 8, palette.dark);
	for (let row = 0; row < 8; row += 1) {
		const split = Math.min(7, row + 1);
		fillRect(pixels, 8, 8 + row, split, 1, palette.mid);
		fillRect(pixels, 8 + split, 8 + row, 1, 1, palette.accent);
	}
	fillRect(pixels, 9, 9, 2, 2, palette.light);
	fillRect(pixels, 24, 8, 8, 8, palette.dark);
	drawLine(pixels, 24, 9, 30, 15, palette.accent, 1);
	fillRect(pixels, 24, 13, 3, 3, palette.mid);

	// Arrow-cursor torso cut on front and back.
	for (const originX of [20, 32]) {
		fillRect(pixels, originX, 20, 8, 12, palette.mid);
		fillRect(pixels, originX + 1, 21, 2, 8, palette.light);
		fillRect(pixels, originX + 3, 23, 2, 2, palette.light);
		fillRect(pixels, originX + 5, 25, 2, 2, palette.light);
		fillRect(pixels, originX + 3, 27, 2, 4, palette.light);
		fillRect(pixels, originX + 5, 29, 2, 2, palette.accent);
	}
	// Diagonal forearm bands.
	drawLine(pixels, 44, 24, 46, 28, palette.light, 2);
	drawLine(pixels, 38, 56, 36, 60, palette.light, 2);
	drawLine(pixels, 52, 25, 54, 29, palette.accent, 2);
	drawLine(pixels, 46, 57, 44, 61, palette.accent, 2);
}

function drawProviderChassis(pixels, providerKey, palette) {
	if (providerKey === 'codex') return drawCodexChassis(pixels, palette);
	if (providerKey === 'gemini') return drawGeminiChassis(pixels, palette);
	if (providerKey === 'kimi') return drawKimiChassis(pixels, palette);
	if (providerKey === 'cursor') return drawCursorChassis(pixels, palette);
	throw new Error(`No chassis renderer for provider ${providerKey}`);
}

function drawFamilyMotif(pixels, familyIndex, palette) {
	if (familyIndex === 0) {
		// Central upper-body spine with open crown.
		fillRect(pixels, 22, 36, 4, 2, palette.light);
		fillRect(pixels, 23, 38, 2, 6, palette.accent);
		fillRect(pixels, 34, 36, 4, 2, palette.light);
		fillRect(pixels, 35, 38, 2, 6, palette.accent);
		return;
	}
	if (familyIndex === 1) {
		// Wide stepped chevrons.
		fillRect(pixels, 20, 36, 2, 3, palette.light);
		fillRect(pixels, 26, 36, 2, 3, palette.light);
		fillRect(pixels, 22, 38, 2, 3, palette.accent);
		fillRect(pixels, 24, 40, 2, 3, palette.accent);
		fillRect(pixels, 32, 36, 2, 3, palette.light);
		fillRect(pixels, 38, 36, 2, 3, palette.light);
		fillRect(pixels, 34, 38, 2, 3, palette.accent);
		fillRect(pixels, 36, 40, 2, 3, palette.accent);
		return;
	}
	if (familyIndex === 2) {
		// Two broad value rails joined across the chest.
		fillRect(pixels, 20, 36, 3, 8, palette.mid);
		fillRect(pixels, 25, 36, 3, 8, palette.light);
		fillRect(pixels, 22, 39, 4, 2, palette.accent);
		fillRect(pixels, 32, 36, 3, 8, palette.light);
		fillRect(pixels, 37, 36, 3, 8, palette.mid);
		fillRect(pixels, 34, 39, 4, 2, palette.accent);
		return;
	}
	if (familyIndex === 3) {
		// Split gate around a dark center window.
		fillRect(pixels, 20, 36, 2, 9, palette.accent);
		fillRect(pixels, 26, 36, 2, 9, palette.light);
		fillRect(pixels, 22, 36, 4, 2, palette.light);
		fillRect(pixels, 22, 39, 4, 5, palette.dark);
		fillRect(pixels, 32, 36, 2, 9, palette.light);
		fillRect(pixels, 38, 36, 2, 9, palette.accent);
		fillRect(pixels, 34, 36, 4, 2, palette.light);
		fillRect(pixels, 34, 39, 4, 5, palette.dark);
		return;
	}
	throw new Error(`No family motif renderer for index ${familyIndex}`);
}

function drawIndividualSignature(pixels, variantIndex, palette) {
	if (variantIndex === 0) {
		// Left shoulder cap, single back rail, right boot cuff.
		fillRect(pixels, 36, 52, 4, 3, palette.accent);
		fillRect(pixels, 33, 27, 2, 5, palette.light);
		fillRect(pixels, 4, 28, 4, 4, palette.accent);
		return;
	}
	if (variantIndex === 1) {
		// Right shoulder cap, broad back bar, left boot cuff.
		fillRect(pixels, 44, 20, 4, 3, palette.accent);
		fillRect(pixels, 32, 28, 8, 2, palette.light);
		fillRect(pixels, 20, 60, 4, 4, palette.accent);
		return;
	}
	if (variantIndex === 2) {
		// Opposed shoulder slashes, centered back column, split greaves.
		drawLine(pixels, 44, 20, 46, 23, palette.accent, 2);
		drawLine(pixels, 38, 52, 36, 55, palette.accent, 2);
		fillRect(pixels, 35, 27, 2, 5, palette.light);
		fillRect(pixels, 4, 29, 2, 3, palette.accent);
		fillRect(pixels, 22, 61, 2, 3, palette.accent);
		return;
	}
	if (variantIndex === 3) {
		// Twin shoulder blocks, back corner pair, double boot bands.
		fillRect(pixels, 44, 20, 2, 4, palette.accent);
		fillRect(pixels, 38, 52, 2, 4, palette.accent);
		fillRect(pixels, 32, 28, 3, 4, palette.light);
		fillRect(pixels, 37, 28, 3, 4, palette.light);
		fillRect(pixels, 4, 29, 4, 2, palette.accent);
		fillRect(pixels, 20, 61, 4, 2, palette.accent);
		return;
	}
	throw new Error(`No individual signature renderer for index ${variantIndex}`);
}

function visualBrandFor(output) {
	if (output.brandKey) return output.brandKey;
	if (output.providerKey === 'codex') return 'openai';
	if (output.providerKey === 'gemini') return output.familyKey === 'claude' ? 'claude' : 'gemini';
	if (output.providerKey === 'kimi') return 'kimi';
	// Cursor is intentionally left on its established art. DeepSeek is available
	// as a standalone brand skin until a runtime provider key is added for it.
	return 'cursor';
}

function drawBrandBase(pixels, palette, variantIndex, familyIndex = 0) {
	for (const [x, y, width, height] of CUBOID_FACES) fillRect(pixels, x, y, width, height, palette.base);
	// A small amount of edge shading keeps the flat brand colour readable on a
	// humanoid model without introducing the old provider-specific chassis art.
	for (const [x, y, width, height] of [
		[0, 8, 8, 8], [16, 8, 8, 8], [16, 20, 4, 12], [28, 20, 4, 12],
		[40, 20, 4, 12], [48, 20, 4, 12], [0, 20, 4, 12], [8, 20, 4, 12],
		[16, 52, 4, 12], [24, 52, 4, 12], [32, 52, 4, 12], [40, 52, 4, 12],
	]) fillRect(pixels, x, y, width, height, palette.dark);
	// Variant marks are deliberately tiny and stay below the face logo.
	const accentX = variantIndex % 2 === 0 ? 20 : 32;
	const accentY = 30 + Math.floor(variantIndex / 2);
	fillRect(pixels, accentX, accentY, 8, 1, palette.accent);
	// Family accents retain deterministic uniqueness for legacy model families
	// while the brand mark remains identical across every family.
	if (familyIndex > 0) fillRect(pixels, 20 + (familyIndex * 2), 42, 2, 1, palette.light);
}

function blendLogoPixel(pixels, x, y, colour, alpha) {
	if (x < 0 || x >= WIDTH || y < 0 || y >= HEIGHT) return;
	if (alpha === 0) return;
	if (alpha === 255) {
		setTexturePixel(pixels, x, y, colour);
		return;
	}
	const offset = ((y * WIDTH) + x) * CHANNELS;
	const inverse = 255 - alpha;
	const blended = [0, 0, 0, 255];
	for (let channel = 0; channel < 3; channel += 1) {
		blended[channel] = Math.round(((pixels[offset + channel] * inverse) + (colour[channel] * alpha)) / 255);
	}
	setTexturePixel(pixels, x, y, blended);
}

function logoColour(brand, source) {
	if (brand === 'gemini') return source;
	if (brand === 'deepseek') return [87, 134, 254, 255];
	if (brand === 'kimi') return source;
	return [255, 255, 255, 255];
}

function drawLogoRaster(pixels, brand, originX, originY) {
	const raster = inflateSync(Buffer.from(BRAND_LOGO_RASTERS[brand], 'base64'));
	const rasterSize = brand === 'kimi' ? LOGICAL_SIZE : BRAND_LOGO_RASTER_SIZE;
	const expectedLength = rasterSize * rasterSize * CHANNELS;
	if (raster.length !== expectedLength) throw new Error(`Invalid ${brand} logo raster length`);
	const faceSize = 8 * TEXTURE_SCALE;
	const offsetX = originX * TEXTURE_SCALE + Math.floor((faceSize - rasterSize) / 2);
	const offsetY = originY * TEXTURE_SCALE + Math.floor((faceSize - rasterSize) / 2);
	for (let row = 0; row < rasterSize; row += 1) {
		for (let column = 0; column < rasterSize; column += 1) {
			const sourceOffset = ((row * rasterSize) + column) * CHANNELS;
			const alpha = raster[sourceOffset + 3];
			blendLogoPixel(pixels, offsetX + column, offsetY + row, logoColour(brand, [
				raster[sourceOffset], raster[sourceOffset + 1], raster[sourceOffset + 2], 255,
			]), alpha);
		}
	}
}

function drawBrandLogo(pixels, brand) {
	// Keep the source logo on the front face only. Torso decals make the mark read
	// like a repeated texture rather than an agent identity.
	drawLogoRaster(pixels, brand, 8, 8);
}

function renderBrandSkin(output) {
	const brand = visualBrandFor(output);
	const palette = BRAND_PALETTES[brand] ?? PALETTES[output.providerKey];
	if (!palette) throw new Error(`No skin palette for ${brand}`);
	const pixels = createPixels();
	drawBrandBase(pixels, palette, output.variantIndex, output.familyIndex);
	drawBrandLogo(pixels, brand);
	return pixels;
}

function renderSkin(output) {
	const isBrand = output.brandKey || output.providerKey !== 'cursor';
	const previousScale = TEXTURE_SCALE;
	const previousWidth = WIDTH;
	const previousHeight = HEIGHT;
	TEXTURE_SCALE = isBrand ? BRAND_TEXTURE_SCALE : DEFAULT_TEXTURE_SCALE;
	WIDTH = LOGICAL_SIZE * TEXTURE_SCALE;
	HEIGHT = LOGICAL_SIZE * TEXTURE_SCALE;
	try {
		if (isBrand) return {
			pixels: renderBrandSkin(output),
			width: WIDTH,
			height: HEIGHT,
		};
		const pixels = createPixels();
		const palette = PALETTES[output.providerKey];
		drawBase(pixels, palette);
		drawProviderChassis(pixels, output.providerKey, palette);
		drawFamilyMotif(pixels, output.familyIndex, palette);
		drawIndividualSignature(pixels, output.variantIndex, palette);
		return { pixels, width: WIDTH, height: HEIGHT };
	} finally {
		TEXTURE_SCALE = previousScale;
		WIDTH = previousWidth;
		HEIGHT = previousHeight;
	}
}

function encodePng(pixels, width, height) {
	const scanlines = Buffer.alloc((width * CHANNELS + 1) * height);
	for (let y = 0; y < height; y += 1) {
		const rowStart = y * (width * CHANNELS + 1);
		scanlines[rowStart] = 0;
		pixels.copy(scanlines, rowStart + 1, y * width * CHANNELS, (y + 1) * width * CHANNELS);
	}
	const header = Buffer.alloc(13);
	header.writeUInt32BE(width, 0);
	header.writeUInt32BE(height, 4);
	header[8] = 8;
	header[9] = 6;
	return Buffer.concat([
		PNG_SIGNATURE,
		chunk('IHDR', header),
		chunk('IDAT', deflateSync(scanlines, { level: 9 })),
		chunk('IEND', Buffer.alloc(0)),
	]);
}

function chunk(type, data) {
	const typeBuffer = Buffer.from(type, 'ascii');
	const length = Buffer.alloc(4);
	length.writeUInt32BE(data.length, 0);
	const checksum = Buffer.alloc(4);
	checksum.writeUInt32BE(crc32(Buffer.concat([typeBuffer, data])), 0);
	return Buffer.concat([length, typeBuffer, data, checksum]);
}

function crc32(buffer) {
	let crc = 0xffffffff;
	for (const byte of buffer) {
		crc ^= byte;
		for (let bit = 0; bit < 8; bit += 1) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
	}
	return (crc ^ 0xffffffff) >>> 0;
}

function validateIhdr(buffer, expectedWidth, expectedHeight) {
	if (buffer.length < 33 || !buffer.subarray(0, 8).equals(PNG_SIGNATURE)) return 'not a PNG';
	if (buffer.readUInt32BE(8) !== 13 || buffer.toString('ascii', 12, 16) !== 'IHDR') return 'has no leading IHDR';
	const width = buffer.readUInt32BE(16);
	const height = buffer.readUInt32BE(20);
	const bitDepth = buffer[24];
	const colorType = buffer[25];
	const compression = buffer[26];
	const filter = buffer[27];
	const interlace = buffer[28];
	if (width !== expectedWidth || height !== expectedHeight || bitDepth !== 8 || colorType !== 6
			|| compression !== 0 || filter !== 0 || interlace !== 0) {
		return `has IHDR ${width}x${height}, bitDepth=${bitDepth}, colorType=${colorType}, compression=${compression}, filter=${filter}, interlace=${interlace}`;
	}
	return null;
}

function checkOutputs(outputs) {
	const errors = [];
	const expectedNames = new Set(outputs.map((output) => output.textureName));
	const actualNames = existsSync(OUTPUT_DIRECTORY)
		? readdirSync(OUTPUT_DIRECTORY).filter((name) => name.endsWith('.png')).sort()
		: [];
	if (actualNames.length !== outputs.length) {
		errors.push(`output count drift: manifest=${outputs.length}, directory=${actualNames.length}`);
	}
	for (const actualName of actualNames) {
		if (!expectedNames.has(actualName)) errors.push(`obsolete output: ${actualName}`);
	}

	const providerBuffers = new Map();
	for (const output of outputs) {
		const outputPath = path.join(OUTPUT_DIRECTORY, output.textureName);
		if (!existsSync(outputPath)) {
			errors.push(`missing output: ${output.textureName}`);
			continue;
		}
		const actual = readFileSync(outputPath);
		const expectedScale = output.brandKey || output.providerKey !== 'cursor'
			? BRAND_TEXTURE_SCALE : DEFAULT_TEXTURE_SCALE;
		const ihdrError = validateIhdr(actual, LOGICAL_SIZE * expectedScale, LOGICAL_SIZE * expectedScale);
		if (ihdrError) errors.push(`${output.textureName} ${ihdrError}`);
		const rendered = renderSkin(output);
		const expected = encodePng(rendered.pixels, rendered.width, rendered.height);
		if (!actual.equals(expected)) errors.push(`non-deterministic or stale output: ${output.textureName}`);
		const previous = providerBuffers.get(output.providerKey) ?? [];
		for (const candidate of previous) {
			if (actual.equals(candidate.buffer)) {
				errors.push(`duplicate ${output.providerKey} texture bytes: ${candidate.name} and ${output.textureName}`);
			}
		}
		previous.push({ name: output.textureName, buffer: actual });
		providerBuffers.set(output.providerKey, previous);
	}

	if (errors.length > 0) {
		for (const error of errors) process.stderr.write(`ERROR: ${error}\n`);
		throw new Error(`Agent skin check failed with ${errors.length} error(s)`);
	}
	process.stdout.write(`Validated ${outputs.length} manifest-driven agent textures (${BRAND_TEXTURE_SCALE}x branded, ${DEFAULT_TEXTURE_SCALE}x ordinary).\n`);
}

function writeOutputs(outputs) {
	mkdirSync(OUTPUT_DIRECTORY, { recursive: true });
	const expectedNames = new Set(outputs.map((output) => output.textureName));
	for (const output of outputs) {
		const rendered = renderSkin(output);
		writeFileSync(path.join(OUTPUT_DIRECTORY, output.textureName),
			encodePng(rendered.pixels, rendered.width, rendered.height));
	}
	for (const actualName of readdirSync(OUTPUT_DIRECTORY)) {
		if (actualName.endsWith('.png') && !expectedNames.has(actualName)) {
			rmSync(path.join(OUTPUT_DIRECTORY, actualName));
		}
	}
	process.stdout.write(`Generated ${outputs.length} manifest-driven agent textures.\n`);
}

const outputs = loadOutputs();
if (process.argv.slice(2).includes('--check')) {
	checkOutputs(outputs);
} else {
	writeOutputs(outputs);
}
