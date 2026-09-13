import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";
import { processGroupIsRunning } from "../src/posix-process-group.mjs";

const coordinatorRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const wrapper = path.join(coordinatorRoot, "src", "posix-process-wrapper.mjs");
const gate = path.join(coordinatorRoot, "src", "job-gate.mjs");

test("zombie-only POSIX groups are stopped when PID 1 does not reap adopted grandchildren", () => {
  const groupId = 41_273;
  const signal = (pid, signalNumber) => {
    assert.equal(pid, -groupId);
    assert.equal(signalNumber, 0);
  };

  assert.equal(processGroupIsRunning(groupId, {
    signal,
    readProcessTable: () => `  ${groupId} Z\n  ${groupId} Z+\n`,
  }), false, "zombies do not keep cleanup waiting");
  assert.equal(processGroupIsRunning(groupId, {
    signal,
    readProcessTable: () => `  ${groupId} Z\n  ${groupId} S\n`,
  }), true, "one running member keeps the group owned");
  assert.equal(processGroupIsRunning(groupId, {
    signal,
    readProcessTable: () => undefined,
  }), true, "an unreadable process table fails closed");
});

test("POSIX wrapper owns a child spawned in the former snapshot gap", {
  skip: process.platform === "win32" ? "POSIX process groups are unavailable on Windows" : false,
}, async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "arena-posix-wrapper-"));
  const claim = path.join(directory, "claim.properties");
  const providerPidFile = path.join(directory, "provider.pid");
  const target = path.join(directory, "target.mjs");
  let owner;
  let unrelated;
  let providerPid;
  try {
    await writeFile(target, `
      import { spawn } from "node:child_process";
      import { existsSync } from "node:fs";
      import { setTimeout as delay } from "node:timers/promises";
      const providerScript = 'process.on("SIGTERM", () => {}); require("node:fs").writeFileSync(process.argv[1], String(process.pid)); setInterval(() => {}, 60_000);';
      const provider = spawn(process.execPath, ["-e", providerScript, process.argv[2]], { stdio: "ignore" });
      while (!existsSync(process.argv[2])) await delay(1);
      provider.unref();
    `);
    unrelated = spawn(process.execPath, ["-e", "setInterval(() => {}, 60_000)"], { stdio: "ignore" });
    owner = spawn(process.execPath, [wrapper, claim, "00000000-0000-0000-0000-000000000970", gate, target, providerPidFile], {
      cwd: directory,
      stdio: ["pipe", "ignore", "pipe"],
    });
    await waitFor(() => descriptorReady(claim), "wrapper ownership descriptor");
    assert.equal(existsSync(providerPidFile), false, "coordinator target stays gated until ownership is durable");
    owner.stdin.write(Buffer.from([1]));
    await waitFor(() => existsSync(providerPidFile), "late provider PID");
    providerPid = Number.parseInt(await readFile(providerPidFile, "utf8"), 10);
    assert.equal(processAlive(providerPid), true, "late provider starts before its root exits");
    const exit = await Promise.race([
      new Promise((resolve) => owner.once("exit", (code) => resolve(code))),
      new Promise((_, reject) => setTimeout(() => reject(new Error("wrapper did not finish cleanup")), 5_000)),
    ]);
    assert.equal(exit, 0);
    await waitFor(() => !processAlive(providerPid), "late provider group cleanup");
    assert.equal(processAlive(unrelated.pid), true, "group cleanup leaves an unrelated process alive");
    assert.equal(existsSync(claim), false, "wrapper removes its claim after group cleanup");
  } finally {
    owner?.stdin?.destroy();
    if (owner?.pid && processAlive(owner.pid)) owner.kill("SIGKILL");
    if (providerPid && processAlive(providerPid)) process.kill(providerPid, "SIGKILL");
    if (unrelated?.pid && processAlive(unrelated.pid)) unrelated.kill("SIGKILL");
    await rm(directory, { recursive: true, force: true });
  }
});

test("POSIX wrapper rejects a duplicate ownership claim before release", {
  skip: process.platform === "win32" ? "POSIX process groups are unavailable on Windows" : false,
}, async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "arena-posix-duplicate-"));
  const claim = path.join(directory, "claim.properties");
  const target = path.join(directory, "target.mjs");
  let first;
  let second;
  try {
    await writeFile(target, "await new Promise(() => {});\n");
    const command = [wrapper, claim, "00000000-0000-0000-0000-000000000971", gate, target];
    first = spawn(process.execPath, command, { cwd: directory, stdio: ["pipe", "ignore", "pipe"] });
    await waitFor(() => descriptorReady(claim), "first ownership descriptor");
    second = spawn(process.execPath, command, { cwd: directory, stdio: ["pipe", "ignore", "pipe"] });
    const secondExit = await new Promise((resolve) => second.once("exit", (code) => resolve(code)));
    assert.notEqual(secondExit, 0, "duplicate wrapper exits before coordinator release");
    assert.equal(first.exitCode, null, "original ownership remains active");
  } finally {
    first?.stdin?.destroy();
    second?.stdin?.destroy();
    if (first?.pid && processAlive(first.pid)) first.kill("SIGTERM");
    if (second?.pid && processAlive(second.pid)) second.kill("SIGKILL");
    await Promise.allSettled([
      first && first.exitCode === null ? new Promise((resolve) => first.once("exit", resolve)) : Promise.resolve(),
      second && second.exitCode === null ? new Promise((resolve) => second.once("exit", resolve)) : Promise.resolve(),
    ]);
    await rm(directory, { recursive: true, force: true });
  }
});

function descriptorReady(claim) {
  if (!existsSync(claim)) return false;
  try {
    const text = readFileSync(claim, "utf8");
    return text.includes("version=1") && text.includes("rootPid=");
  } catch {
    return false;
  }
}

function processAlive(pid) {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    if (error?.code === "ESRCH") return false;
    throw error;
  }
}

async function waitFor(condition, label) {
  const deadline = Date.now() + 5_000;
  while (!condition() && Date.now() < deadline) await new Promise((resolve) => setTimeout(resolve, 10));
  assert.equal(condition(), true, `${label} timed out`);
}
