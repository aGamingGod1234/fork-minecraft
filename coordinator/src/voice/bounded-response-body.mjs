export async function readBoundedResponseBody(response, maximumBytes, createLimitError, { onLimit = () => {} } = {}) {
	if (!Number.isSafeInteger(maximumBytes) || maximumBytes < 1) throw new TypeError('maximum response bytes must be positive');
	if (typeof createLimitError !== 'function' || typeof onLimit !== 'function') throw new TypeError('response body callbacks must be functions');
	const declaredLength = Number(response?.headers?.get?.('content-length'));
	if (Number.isFinite(declaredLength) && declaredLength > maximumBytes) return rejectLimit();
	const chunks = [];
	let total = 0;
	const append = (value) => {
		const chunk = Buffer.from(value);
		total += chunk.length;
		if (total > maximumBytes) return false;
		chunks.push(chunk);
		return true;
	};
	const reader = response?.body?.getReader?.();
	if (reader !== undefined) {
		try {
			while (true) {
				const { done, value } = await reader.read();
				if (done) break;
				if (!append(value)) {
					try { await reader.cancel(); } catch { /* the size error owns this boundary */ }
					return rejectLimit();
				}
			}
		} finally {
			try { reader.releaseLock(); } catch { /* response cleanup is best effort */ }
		}
		return Buffer.concat(chunks, total);
	}
	if (response?.body?.[Symbol.asyncIterator] !== undefined) {
		for await (const chunk of response.body) if (!append(chunk)) return rejectLimit();
		return Buffer.concat(chunks, total);
	}
	const buffered = Buffer.from(await response.arrayBuffer());
	if (buffered.length > maximumBytes) return rejectLimit();
	return buffered;

	function rejectLimit() {
		const error = createLimitError();
		try { onLimit(error); } catch { /* the bounded response error remains authoritative */ }
		throw error;
	}
}
