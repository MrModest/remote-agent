// As apple/RAKit/Tests/RAKitTests/ConnectionTests.swift.
import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import { afterEach, describe, expect, it, vi } from "vitest";
import { RPCError } from "../protocol/rpc";
import { ServerConnection } from "./serverConnection";
import type { SavedServer } from "./serverStore";

function saved(url: string): SavedServer {
  return { id: "srv", name: "Test", urls: [url], deviceId: "dev1", addedAt: "2026-10-09T12:00:00Z" };
}

/** Counts the sockets opened, one per attempt. */
function countAttempts(): () => number {
  let n = 0;
  vi.stubGlobal(
    "WebSocket",
    class extends WebSocket {
      constructor(url: string | URL, protocols?: string | string[]) {
        n++;
        super(url, protocols);
      }
    },
  );
  return () => n;
}

/** The kinds of state the connection went through, without repeats. */
function states(c: ServerConnection): string[] {
  const seen = [c.state.kind];
  c.watch(() => {
    if (seen.at(-1) !== c.state.kind) seen.push(c.state.kind);
  });
  return seen;
}

/** Polls until `condition` holds, or fails after `timeout` ms. */
async function waitFor(condition: () => boolean, timeout = 10_000) {
  const deadline = Date.now() + timeout;
  while (!condition()) {
    if (Date.now() > deadline) throw new Error("timed out");
    await new Promise((r) => setTimeout(r, 5));
  }
}

const pause = (ms: number) => new Promise((r) => setTimeout(r, ms));

describe("ServerConnection", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("gives up after maxAttempts failures until started again", async () => {
    const attempts = countAttempts();
    // Nothing listens on port 1, so each attempt fails at once.
    const c = new ServerConnection(saved("http://127.0.0.1:1"), "tok", () => {});
    c.firstRetryDelay = 0.01;
    const seen = states(c);
    c.start();
    expect(c.state.kind).toBe("connecting");
    await waitFor(() => c.state.kind === "failed");
    // Connecting between attempts, failed only once it gave up.
    expect(seen).toEqual(["idle", "connecting", "failed"]);
    expect(attempts()).toBe(ServerConnection.maxAttempts);

    // Given up: no more attempts, so it stays failed.
    await pause(200);
    expect(c.state.kind).toBe("failed");
    expect(attempts()).toBe(ServerConnection.maxAttempts);

    // Opening the server, or coming back to the page, tries again.
    c.start();
    expect(c.state.kind).toBe("connecting");
    await waitFor(() => c.state.kind === "failed");
    expect(attempts()).toBe(2 * ServerConnection.maxAttempts);

    // So does Retry.
    c.retry();
    expect(c.state.kind).toBe("connecting");
    await waitFor(() => c.state.kind === "failed");
    expect(attempts()).toBe(3 * ServerConnection.maxAttempts);
    c.stop();
  });

  it("gives up at once when the device is no longer authorized", async () => {
    // A server that turns the token away: the upgrade and the HTTP check alike.
    const server = createServer((_, res) => {
      res.writeHead(401).end();
    });
    server.on("upgrade", (_, socket) => {
      socket.end("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
    });
    await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
    try {
      const attempts = countAttempts();
      const { port } = server.address() as AddressInfo;
      const c = new ServerConnection(saved(`http://127.0.0.1:${port}`), "tok", () => {});
      c.firstRetryDelay = 0.01;
      c.start();
      await waitFor(() => c.state.kind === "failed");
      expect(c.state).toEqual({ kind: "failed", message: RPCError.unauthorized().message });
      await pause(100);
      expect(attempts()).toBe(1);
      c.stop();
    } finally {
      server.closeAllConnections();
      await new Promise((r) => server.close(r));
    }
  });
});
