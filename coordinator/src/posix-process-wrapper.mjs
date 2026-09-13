import { spawn } from "node:child_process";
import { closeSync, openSync, unlinkSync, writeSync } from "node:fs";
import { setTimeout as delay } from "node:timers/promises";
import { processGroupIsRunning } from "./posix-process-group.mjs";

const GRACEFUL_STOP_MS = 2_000;
const [claimPath, launchId, gate, target, ...arguments_] = process.argv.slice(2);

if (process.platform === "win32") throw new Error("POSIX coordinator ownership is unavailable on Windows");
if (!claimPath || !launchId || !gate || !target) {
  throw new Error("POSIX coordinator wrapper requires a claim, launch ID, gate, and target");
}

let claim;
try {
  claim = openSync(claimPath, "wx", 0o600);
} catch (error) {
  throw new Error("POSIX coordinator ownership claim is already in use", { cause: error });
}

let child;
let released = false;
let stopping;

const removeClaim = () => {
  try {
    unlinkSync(claimPath);
    return true;
  } catch (error) {
    if (error?.code === "ENOENT") return true;
    process.stderr.write(`Could not remove POSIX coordinator claim: ${error.message}\n`);
    return false;
  }
};

const signalGroup = (signal) => {
  if (!Number.isInteger(child?.pid) || child.pid <= 0) return false;
  try {
    process.kill(-child.pid, signal);
    return true;
  } catch (error) {
    if (error?.code === "ESRCH") return false;
    throw error;
  }
};

const groupAlive = () => {
  return processGroupIsRunning(child.pid);
};

const waitForGroup = async (timeoutMs) => {
  const deadline = Date.now() + timeoutMs;
  while (groupAlive() && Date.now() < deadline) await delay(10);
  return !groupAlive();
};

const stop = (exitCode = 0) => {
  if (stopping) return stopping;
  stopping = (async () => {
    try {
      signalGroup("SIGTERM");
      if (!await waitForGroup(GRACEFUL_STOP_MS)) {
        signalGroup("SIGKILL");
        if (!await waitForGroup(GRACEFUL_STOP_MS)) {
          throw new Error("owned process group stayed alive after SIGKILL");
        }
      }
    } catch (error) {
      process.stderr.write(`Could not terminate POSIX coordinator process group: ${error.message}\n`);
      stopping = undefined;
      return;
    }
    if (!removeClaim()) {
      stopping = undefined;
      return;
    }
    process.exit(exitCode);
  })();
  return stopping;
};

for (const signal of ["SIGTERM", "SIGINT", "SIGHUP"]) {
  process.on(signal, () => { void stop(0); });
}
process.stdin.on("end", () => { void stop(0); });
process.stdin.on("error", () => { void stop(1); });

try {
  child = spawn(process.execPath, [gate, target, ...arguments_], {
    cwd: process.cwd(),
    detached: true,
    env: process.env,
    stdio: ["pipe", "inherit", "inherit"],
    windowsHide: true,
  });
  await new Promise((resolve, reject) => {
    const cleanup = () => { child.off("spawn", onSpawn); child.off("error", onError); };
    const onSpawn = () => { cleanup(); resolve(); };
    const onError = (error) => { cleanup(); reject(error); };
    child.once("spawn", onSpawn);
    child.once("error", onError);
    if (Number.isInteger(child.pid) && child.pid > 0) onSpawn();
  });
  writeSync(claim, `version=1\nlaunchId=${launchId}\nsessionPid=${process.pid}\nrootPid=${child.pid}\n`);
  closeSync(claim);
  claim = undefined;
} catch (error) {
  if (claim !== undefined) closeSync(claim);
  if (child) await stop(1);
  else removeClaim();
  throw error;
}

child.once("exit", () => { void stop(0); });
if (child.exitCode !== null || child.signalCode !== null) void stop(0);
process.stdin.on("data", (chunk) => {
  if (released || chunk.length !== 1 || chunk[0] !== 1) {
    void stop(1);
    return;
  }
  released = true;
  child.stdin.end(Buffer.from([1]));
});
process.stdin.resume();
