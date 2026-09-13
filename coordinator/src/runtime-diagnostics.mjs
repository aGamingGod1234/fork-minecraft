/** Attach bounded runtime diagnostics to a coordinator lifecycle. */
export function wireRuntimeDiagnostics(coordinator, reporter) {
	if (coordinator === null || typeof coordinator !== 'object'
		|| typeof coordinator.on !== 'function' || typeof coordinator.off !== 'function') {
		throw new TypeError('runtime diagnostics coordinator must support on and off');
	}
	if (reporter === null || typeof reporter !== 'object'
		|| typeof reporter.report !== 'function' || typeof reporter.recovered !== 'function') {
		throw new TypeError('runtime diagnostics reporter must support report and recovered');
	}

	const invoke = (operation) => {
		try { Promise.resolve(operation()).catch(() => {}); }
		catch { /* diagnostics cannot interrupt coordinator work */ }
	};
	const onRuntimeError = (error) => invoke(() => reporter.report(error));
	const onReconciled = () => invoke(() => reporter.recovered());
	coordinator.on('runtimeError', onRuntimeError);
	coordinator.on('reconciled', onReconciled);

	let disposed = false;
	return () => {
		if (disposed) return;
		disposed = true;
		try { coordinator.off('runtimeError', onRuntimeError); } catch { /* cleanup is best effort */ }
		try { coordinator.off('reconciled', onReconciled); } catch { /* cleanup is best effort */ }
	};
}
