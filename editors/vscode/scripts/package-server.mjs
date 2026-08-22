import { copyFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const extensionDirectory = resolve(scriptDirectory, "..");
const repositoryRoot = resolve(extensionDirectory, "../..");
copyFileSync(join(repositoryRoot, "LICENSE"), join(extensionDirectory, "LICENSE"));
