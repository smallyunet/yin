import { appendFileSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { createInterface } from "node:readline";

const outputPath = resolve(process.argv[2] ?? "runtime/tickets.jsonl");
const input = createInterface({ input: process.stdin, crlfDelay: Infinity });

function respond(id, result) {
  process.stdout.write(`${JSON.stringify({ jsonrpc: "2.0", id, result })}\n`);
}

input.on("line", (line) => {
  const message = JSON.parse(line);
  if (message.method === "notifications/initialized") return;
  if (message.method === "notifications/cancelled") return;
  if (message.method === "initialize") {
    respond(message.id, {
      protocolVersion: "2025-11-25",
      capabilities: { tools: {} },
      serverInfo: { name: "yin-ticket-demo", version: "1.0.0" },
    });
    return;
  }
  if (message.method === "tools/list") {
    respond(message.id, {
      tools: [
        {
          name: "create_ticket",
          description: "Create a local demonstration ticket",
          inputSchema: {
            type: "object",
            properties: {
              repository: { type: "string" },
              title: { type: "string" },
              body: { type: "string" },
            },
            required: ["repository", "title", "body"],
            additionalProperties: false,
          },
        },
      ],
    });
    return;
  }
  if (message.method === "tools/call") {
    if (message.params?.name !== "create_ticket") {
      process.stdout.write(`${JSON.stringify({
        jsonrpc: "2.0",
        id: message.id,
        error: { code: -32602, message: "unknown tool" },
      })}\n`);
      return;
    }
    mkdirSync(dirname(outputPath), { recursive: true });
    const ticket = {
      ticketId: `LOCAL-${Date.now()}`,
      repository: message.params.arguments.repository,
      title: message.params.arguments.title,
      body: message.params.arguments.body,
    };
    appendFileSync(outputPath, `${JSON.stringify(ticket)}\n`, "utf8");
    const structuredContent = {
      ticketId: ticket.ticketId,
      repository: ticket.repository,
    };
    respond(message.id, {
      content: [{ type: "text", text: JSON.stringify(structuredContent) }],
      structuredContent,
      isError: false,
    });
    return;
  }
  process.stdout.write(`${JSON.stringify({
    jsonrpc: "2.0",
    id: message.id,
    error: { code: -32601, message: "method not found" },
  })}\n`);
});
