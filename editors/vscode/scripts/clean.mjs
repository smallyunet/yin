import { rmSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const extensionDirectory = resolve(dirname(fileURLToPath(import.meta.url)), "..");
for (const path of ["dist", "server", "LICENSE"]) {
  rmSync(resolve(extensionDirectory, path), { recursive: true, force: true });
}
