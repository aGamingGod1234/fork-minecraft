import { execFile as nodeExecFile } from 'node:child_process';

export const DEFAULT_CHILD_STOP_TIMEOUT_MS = 2_000;

export async function terminateChildProcess(child, {
	timeoutMs = DEFAULT_CHILD_STOP_TIMEOUT_MS,
	platform = process.platform,
	execFile = nodeExecFile,
} = {}) {
	if (child === null || child === undefined) return;
	if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0) throw new TypeError('child process stop timeout must be a positive safe integer');
	if (platform === 'win32' && Number.isSafeInteger(child.pid) && child.pid > 0) {
		await terminateWindowsProcessTree(child, timeoutMs, execFile);
		return;
	}
	if (hasExited(child)) return;

	sendSignal(child, 'SIGTERM');
	if (hasExited(child) || await waitForExit(child, timeoutMs)) return;

	sendSignal(child, 'SIGKILL');
}

async function terminateWindowsProcessTree(child, timeoutMs, execute) {
	const deadline = Date.now() + timeoutMs;
	const graceful = await runTaskkill(execute, child.pid, false, deadline);
	if (hasExited(child)) return;
	if (graceful.completed && await waitForExit(child, Math.max(1, Math.floor(remainingMs(deadline) / 2)))) return;

	const forced = graceful.completed && remainingMs(deadline) > 0
		? await runTaskkill(execute, child.pid, true, deadline)
		: { completed: false, succeeded: false };
	if (forced.completed && forced.succeeded) return;
	if (hasExited(child) || await waitForExit(child, remainingMs(deadline))) return;

	try {
		sendSignal(child, 'SIGKILL');
	} catch {
		// Preserve the deadline error below; the direct signal is only a final fallback.
	}
	throw new Error(`Could not terminate provider process tree ${child.pid} before the ${timeoutMs}ms deadline`);
}

function runTaskkill(execute, pid, force, deadline) {
	const args = ['/PID', String(pid), '/T'];
	if (force) args.push('/F');
	return new Promise((resolve) => {
		let settled = false;
		let helper;
		const finish = (result) => {
			if (settled) return;
			settled = true;
			clearTimeout(timer);
			resolve(result);
		};
		const timer = setTimeout(
			() => {
				try { helper?.kill(); } catch { /* the outer deadline remains authoritative */ }
				finish({ completed: false, succeeded: false });
			},
			remainingMs(deadline),
		);
		try {
			helper = execute('taskkill.exe', args, { windowsHide: true }, (error) => finish({
				completed: true,
				succeeded: error === null || error === undefined,
			}));
		} catch {
			finish({ completed: true, succeeded: false });
		}
	});
}

function remainingMs(deadline) {
	return Math.max(0, deadline - Date.now());
}

function hasExited(child) {
	return child.exitCode !== null || child.signalCode !== null;
}

function sendSignal(child, signal) {
	try {
		child.kill(signal);
	} catch (error) {
		if (!hasExited(child)) throw new Error(`Could not send ${signal} to provider process: ${error.message}`, { cause: error });
	}
}

function waitForExit(child, timeoutMs) {
	if (hasExited(child)) return Promise.resolve(true);
	return new Promise((resolve) => {
		let timer;
		const onExit = () => finish(true);
		const finish = (exited) => {
			clearTimeout(timer);
			child.off('exit', onExit);
			resolve(exited);
		};
		child.once('exit', onExit);
		timer = setTimeout(() => finish(hasExited(child)), timeoutMs);
	});
}
