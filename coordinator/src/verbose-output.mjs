export const MAX_VERBOSE_CALLBACK_MESSAGE_LENGTH = 256;

export function reportVisibleOutput(callback, value) {
	if (typeof callback !== 'function' || typeof value !== 'string' || value.length === 0) return;
	for (let offset = 0; offset < value.length; offset += MAX_VERBOSE_CALLBACK_MESSAGE_LENGTH) {
		const message = value.slice(offset, offset + MAX_VERBOSE_CALLBACK_MESSAGE_LENGTH);
		try { Promise.resolve(callback('output', message)).catch(() => {}); }
		catch { /* visible-output reporting cannot affect provider work */ }
	}
}
