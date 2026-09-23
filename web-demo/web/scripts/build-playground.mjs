import { spawn } from "node:child_process";
import { cp, rm } from "node:fs/promises";
import { basename } from "node:path";
import { fileURLToPath } from "node:url";

const source = fileURLToPath(new URL("../../../playground-core/", import.meta.url));
const destination = fileURLToPath(new URL("../.playground-core/", import.meta.url));

// Build the dependency in the demo's generated directory. The supplied checkout stays untouched.
await rm(destination, { recursive: true, force: true });
await cp(source, destination, {
  recursive: true,
  filter: (path) => !["node_modules", "dist", ".git"].includes(basename(path))
});
for (const args of [["ci"], ["run", "build"]]) {
  await new Promise((resolve, reject) => {
    const child = spawn("npm", args, { cwd: destination, stdio: "inherit" });
    child.on("error", reject);
    child.on("exit", (code, signal) => {
      if (code === 0) resolve();
      else reject(new Error(`Playground build failed (${signal ?? code}).`));
    });
  });
}
