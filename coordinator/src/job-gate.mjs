import { pathToFileURL } from "node:url";

const [target, ...arguments_] = process.argv.slice(2);
if (!target) throw new Error("Coordinator job gate requires a target module");

await new Promise((resolve, reject) => {
  process.stdin.once("data", (chunk) => {
    if (chunk.length !== 1 || chunk[0] !== 1) {
      reject(new Error("Coordinator job gate received an invalid release token"));
      return;
    }
    resolve();
  });
  process.stdin.once("end", () => reject(new Error("Coordinator job gate closed before release")));
  process.stdin.resume();
});

process.stdin.pause();
process.argv = [process.argv[0], target, ...arguments_];
await import(pathToFileURL(target).href);
