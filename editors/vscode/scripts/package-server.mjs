import { copyFileSync, mkdirSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const extensionDirectory = resolve(scriptDirectory, "..");
const repositoryRoot = resolve(extensionDirectory, "../..");
const manifest = JSON.parse(readFileSync(join(extensionDirectory, "package.json"), "utf8"));
const wrapper = process.platform === "win32" ? "mvnw.cmd" : "mvnw";
const build = spawnSync(join(repositoryRoot, wrapper), ["--batch-mode", "-DskipTests", "clean", "package"], {
  cwd: repositoryRoot,
  env: process.env,
  stdio: "inherit",
});

if (build.status !== 0) {
  process.exit(build.status ?? 1);
}

const source = join(repositoryRoot, "target", `yin-${manifest.version}.jar`);
const serverDirectory = join(extensionDirectory, "server");
mkdirSync(serverDirectory, { recursive: true });
copyFileSync(source, join(serverDirectory, "yin.jar"));
copyFileSync(join(repositoryRoot, "LICENSE"), join(extensionDirectory, "LICENSE"));
