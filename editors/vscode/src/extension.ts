import * as vscode from "vscode";
import {
  LanguageClient,
  LanguageClientOptions,
  ServerOptions,
} from "vscode-languageclient/node";

let client: LanguageClient | undefined;

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const yinPath = vscode.workspace.getConfiguration("yin").get<string>("path", "yin");
  const serverOptions: ServerOptions = {
    command: yinPath,
    args: ["--lsp"],
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
