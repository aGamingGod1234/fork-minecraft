export function resampleS16leMono(input, sourceRate = 44_100, targetRate = 48_000, maxSeconds = 20) {
	if (!Buffer.isBuffer(input)) throw new TypeError('PCM input must be a Buffer');
	if (input.length % 2 !== 0) throw new TypeError('PCM input must contain complete signed 16-bit samples');
	if (!Number.isSafeInteger(sourceRate) || sourceRate <= 0) throw new TypeError('sourceRate must be positive');
	if (!Number.isSafeInteger(targetRate) || targetRate <= 0) throw new TypeError('targetRate must be positive');
	if (!Number.isFinite(maxSeconds) || maxSeconds <= 0) throw new TypeError('maxSeconds must be positive');
	const sourceSamples = Math.min(input.length / 2, Math.floor(sourceRate * maxSeconds));
	if (sourceSamples === 0) return Buffer.alloc(0);
	const targetSamples = Math.min(
		Math.floor(targetRate * maxSeconds),
		Math.max(1, Math.floor(sourceSamples * targetRate / sourceRate)),
	);
	const output = Buffer.allocUnsafe(targetSamples * 2);
	for (let index = 0; index < targetSamples; index++) {
		const sourcePosition = index * sourceRate / targetRate;
		const leftIndex = Math.min(sourceSamples - 1, Math.floor(sourcePosition));
		const rightIndex = Math.min(sourceSamples - 1, leftIndex + 1);
		const fraction = sourcePosition - leftIndex;
		const left = input.readInt16LE(leftIndex * 2);
		const right = input.readInt16LE(rightIndex * 2);
		const sample = Math.max(-32_768, Math.min(32_767, Math.round(left + (right - left) * fraction)));
		output.writeInt16LE(sample, index * 2);
	}
	return output;
}
