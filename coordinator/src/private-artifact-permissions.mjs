import { chmod, lstat, mkdir, open } from 'node:fs/promises';
import { execFile as execFileCallback } from 'node:child_process';
import { promisify } from 'node:util';
import path from 'node:path';

const execFile = promisify(execFileCallback);
const PRIVATE_DIRECTORY_MODE = 0o700;
const PRIVATE_FILE_MODE = 0o600;

/** Creates or repairs an owner-only diagnostic artifact without exposing its contents. */
export async function preparePrivateArtifact(filePath, dependencies = {}) {
	const target = path.resolve(filePath);
	const directory = path.dirname(target);
	const mkdirFn = dependencies.mkdir ?? mkdir;
	const lstatFn = dependencies.lstat ?? lstat;
	const openFn = dependencies.open ?? open;
	const chmodFn = dependencies.chmod ?? chmod;
	const platform = dependencies.platform ?? process.platform;
	const secureWindowsPath = dependencies.secureWindowsPath ?? ((artifactPath, isDirectory) => applyWindowsOwnerOnlyDacl(
		artifactPath,
		isDirectory,
		{ currentWindowsSid: dependencies.currentWindowsSid, runWindowsAcl: dependencies.runWindowsAcl },
	));

	await mkdirFn(directory, { recursive: true, mode: PRIVATE_DIRECTORY_MODE });
	await assertDirectory(directory, lstatFn);
	if (platform === 'win32') await secureWindowsPath(directory, true);
	else {
		await chmodFn(directory, PRIVATE_DIRECTORY_MODE);
		await assertPosixOwnerOnly(directory, PRIVATE_DIRECTORY_MODE, lstatFn);
	}

	await assertAbsentOrRegularFile(target, lstatFn);
	const handle = await openFn(target, 'a', PRIVATE_FILE_MODE);
	await handle.close();
	await assertRegularFile(target, lstatFn);
	if (platform === 'win32') await secureWindowsPath(target, false);
	else {
		await chmodFn(target, PRIVATE_FILE_MODE);
		await assertPosixOwnerOnly(target, PRIVATE_FILE_MODE, lstatFn);
	}
}

async function assertDirectory(directory, lstatFn) {
	const metadata = await lstatFn(directory);
	if (metadata.isSymbolicLink() || !metadata.isDirectory()) throw new Error('Private artifact directory is unsafe');
}

async function assertRegularFile(target, lstatFn) {
	const metadata = await lstatFn(target);
	if (metadata.isSymbolicLink() || !metadata.isFile()) throw new Error('Private artifact file is unsafe');
}

async function assertAbsentOrRegularFile(target, lstatFn) {
	try {
		await assertRegularFile(target, lstatFn);
	} catch (error) {
		if (error?.code === 'ENOENT') return;
		throw error;
	}
}

async function assertPosixOwnerOnly(target, expectedMode, lstatFn) {
	const { mode } = await lstatFn(target);
	if (typeof mode !== 'number' || (mode & 0o777) !== expectedMode) {
		throw new Error('Unable to enforce private artifact permissions');
	}
}

/** Replaces the complete DACL and verifies no broad or inherited ACE survives the repair. */
export async function applyWindowsOwnerOnlyDacl(target, directory, dependencies = {}) {
	const getCurrentWindowsSid = dependencies.currentWindowsSid ?? currentWindowsSid;
	const runWindowsAcl = dependencies.runWindowsAcl ?? runWindowsAclRepair;
	const sid = await getCurrentWindowsSid();
	try {
		const output = await runWindowsAcl({ target, directory, sid, script: windowsAclRepairScript() });
		if (!isOwnerOnlyWindowsAcl(parseWindowsAclReport(output), sid, directory)) {
			throw new Error('Windows ACL verification failed');
		}
	} catch {
		throw new Error('Unable to enforce private artifact permissions');
	}
}

/** Returns whether a read-back ACL contains exactly one explicit full-control owner ACE. */
export function isOwnerOnlyWindowsAcl(report, sid, directory) {
	if (report === null || typeof report !== 'object' || report.protected !== true || !Array.isArray(report.entries)) return false;
	if (report.entries.length !== 1) return false;
	const [entry] = report.entries;
	return entry !== null && typeof entry === 'object'
		&& entry.sid === sid
		&& entry.type === 'Allow'
		&& entry.inherited === false
		&& entry.rights === 0x1f01ff
		&& entry.inheritance === (directory ? 3 : 0);
}

function parseWindowsAclReport(output) {
	try {
		const parsed = JSON.parse(String(output).trim());
		return {
			protected: parsed?.protected === true,
			entries: Array.isArray(parsed?.entries) ? parsed.entries : parsed?.entries === undefined ? [] : [parsed.entries],
		};
	} catch {
		throw new Error('Windows ACL verification failed');
	}
}

async function runWindowsAclRepair({ target, directory, sid, script }) {
	const encoded = Buffer.from(script, 'utf16le').toString('base64');
	const { stdout } = await execFile('powershell.exe', [
		'-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', encoded,
		target, sid, directory ? 'directory' : 'file',
	], { windowsHide: true, maxBuffer: 32 * 1024 });
	return stdout;
}

function windowsAclRepairScript() {
	return [
		'param([string]$Target,[string]$Sid,[string]$Kind)',
		'$ErrorActionPreference = "Stop"',
		'$section = [System.Security.AccessControl.AccessControlSections]::Access',
		'$inheritance = if ($Kind -eq "directory") { "OICI" } else { "" }',
		'$security = Get-Acl -LiteralPath $Target',
		'$security.SetSecurityDescriptorSddlForm("D:P(A;$inheritance;FA;;;$Sid)", $section)',
		'Set-Acl -LiteralPath $Target -AclObject $security',
		'$verified = Get-Acl -LiteralPath $Target',
		'$rules = @($verified.GetAccessRules($true, $true, [System.Security.Principal.SecurityIdentifier]) | ForEach-Object {',
		'  [pscustomobject]@{ sid = $_.IdentityReference.Value; type = $_.AccessControlType.ToString(); rights = [int]$_.FileSystemRights; inherited = $_.IsInherited; inheritance = [int]$_.InheritanceFlags }',
		'})',
		'[pscustomobject]@{ protected = $verified.AreAccessRulesProtected; entries = $rules } | ConvertTo-Json -Compress -Depth 3',
	].join('\n');
}

async function currentWindowsSid() {
	try {
		const { stdout } = await execFile('whoami', ['/user', '/fo', 'csv', '/nh'], { windowsHide: true });
		const sid = String(stdout).match(/S-\d-\d+(?:-\d+)+/i)?.[0];
		if (sid !== undefined) return sid;
	} catch {
		// Windows ACL enforcement fails closed when the current SID is unavailable.
	}
	throw new Error('Unable to identify the current Windows user');
}
