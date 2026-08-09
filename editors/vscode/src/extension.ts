import * as path from "node:path";
import * as vscode from "vscode";
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
} from "vscode-languageclient/node";

let client: LanguageClient | undefined;

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const javaPath = vscode.workspace.getConfiguration("yin").get<string>("java.path", "java");
  const serverJar = path.join(context.extensionPath, "server", "yin.jar");
  const serverOptions: ServerOptions = {
    command: javaPath,
    args: ["-jar", serverJar, "--lsp"],
  };
  const clientOptions: LanguageClientOptions = {
    documentSelector: [
      { scheme: "file", language: "yin" },
      { scheme: "untitled", language: "yin" },
    ],
    outputChannelName: "Yin Language Server",
  };

  client = new LanguageClient(
    "yinLanguageServer",
    "Yin Language Server",
    serverOptions,
    clientOptions,
  );
  context.subscriptions.push(client);
  await client.start();
}

export async function deactivate(): Promise<void> {
  if (client) {
    await client.stop();
    client = undefined;
  }
}
