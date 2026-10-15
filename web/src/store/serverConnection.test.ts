// As apple/RAKit/Tests/RAKitTests/ConnectionTests.swift.
import { createHash } from "node:crypto";
import { createServer, type IncomingMessage } from "node:http";
import type { AddressInfo } from "node:net";
import type { Duplex } from "node:stream";
import { afterEach, describe, expect, it, vi } from "vitest";
import { RPCClient, RPCError } from "../protocol/rpc";
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

/** A WebSocket server on loopback that stands in for rad: it answers
 *  `server.info` and `session.prompt`, ignores `slow`, and records the calls.
 *  `quiet()` makes the sockets open so far ignore everything, as a socket
 *  that died while the computer slept does: nothing comes back, and nothing
 *  says it closed. As apple/RAKit/Tests/RAKitTests/TestServer.swift. */
class TestServer {
  /** Every call that arrived, answered or not. */
  calls: { connection: number; method: string; commandId?: string }[] = [];
  private http = createServer((_, res) => res.writeHead(404).end());
  private sockets: Duplex[] = [];
  private quietCount = 0;

  /** Starts listening; returns the base URL. */
  async start(): Promise<string> {
    this.http.on("upgrade", (req, socket) => this.accept(req, socket));
    await new Promise<void>((r) => this.http.listen(0, "127.0.0.1", r));
    return `http://127.0.0.1:${(this.http.address() as AddressInfo).port}`;
  }

  async stop() {
    for (const s of this.sockets) s.destroy();
    this.http.closeAllConnections();
    await new Promise((r) => this.http.close(r));
  }

  /** The sockets open now stop answering; later ones answer. */
  quiet() {
    this.quietCount = this.sockets.length;
  }

  get connectionCount(): number {
    return this.sockets.length;
  }

  private accept(req: IncomingMessage, socket: Duplex) {
    const n = this.sockets.length;
    this.sockets.push(socket);
    socket.on("error", () => {});
    const accept = createHash("sha1")
      .update(req.headers["sec-websocket-key"] + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
      .digest("base64");
    socket.write(
      "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
        `Sec-WebSocket-Accept: ${accept}\r\nSec-WebSocket-Protocol: rad.v1\r\n\r\n`,
    );
    let buffered = Buffer.alloc(0);
    socket.on("data", (chunk: Buffer) => {
      buffered = Buffer.concat([buffered, chunk]);
      for (let f = readFrame(buffered); f; f = readFrame(buffered)) {
        buffered = buffered.subarray(f.size);
        const quiet = n < this.quietCount;
        if (f.opcode === 1) this.answer(socket, n, f.payload.toString(), quiet);
        else if (f.opcode === 8 && !quiet) socket.end();
      }
    });
  }

  private answer(socket: Duplex, n: number, text: string, quiet: boolean) {
    const req = JSON.parse(text);
    this.calls.push({ connection: n, method: req.method, commandId: req.params?.commandId });
    if (quiet || req.method === "slow") return;
    let result: object = {};
    if (req.method === "server.info") result = { serverId: "srv", name: "Test", protocolVersion: 1, version: "0.1" };
    if (req.method === "session.prompt") {
      const at = "2026-01-01T00:00:00Z";
      result = { id: "i" + req.id, sessionId: req.params.sessionId, order: 1, kind: "user_message", status: "queued", createdAt: at, updatedAt: at };
    }
    socket.write(textFrame(JSON.stringify({ id: req.id, result })));
  }
}

/** The client frame at the start of `data`, unmasked, if it has all arrived. */
function readFrame(data: Buffer): { opcode: number; payload: Buffer; size: number } | undefined {
  if (data.length < 2) return undefined;
  let length = data[1] & 0x7f;
  let at = 2;
  if (length === 126) {
    if (data.length < 4) return undefined;
    length = data.readUInt16BE(2);
    at = 4;
  } else if (length === 127) {
    if (data.length < 10) return undefined;
    length = Number(data.readBigUInt64BE(2));
    at = 10;
  }
  const size = at + 4 + length;
  if (data.length < size) return undefined;
  const mask = data.subarray(at, at + 4);
  const payload = Buffer.from(data.subarray(at + 4, size));
  for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
  return { opcode: data[0] & 0x0f, payload, size };
}

/** A server's text frame, which isn't masked. */
function textFrame(text: string): Buffer {
  const payload = Buffer.from(text);
  const head = payload.length < 126 ? Buffer.from([0x81, payload.length]) : Buffer.from([0x81, 126, payload.length >> 8, payload.length & 0xff]);
  return Buffer.concat([head, payload]);
}

/** A connection to `server`, connected, with short timings. */
async function connect(server: TestServer): Promise<ServerConnection> {
  const c = new ServerConnection(saved(await server.start()), "tok", () => {});
  c.firstRetryDelay = 0.01;
  c.heartbeat = 0.05;
  c.pingTimeout = 0.3;
  c.start();
  await waitFor(() => c.state.kind === "connected");
  return c;
}

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

  it("drops a socket that stops answering", async () => {
    const server = new TestServer();
    const c = await connect(server);
    try {
      // While it answers, the pings keep it.
      await pause(300);
      expect(server.connectionCount).toBe(1);

      // Gone quiet, as after a sleep: the ping goes unanswered, so it
      // reconnects rather than passing for connected.
      server.quiet();
      await waitFor(() => server.connectionCount === 2);
      await waitFor(() => c.state.kind === "connected");
    } finally {
      c.stop();
      await server.stop();
    }
  });

  it("checks the socket at once when the page comes back", async () => {
    const server = new TestServer();
    const c = await connect(server);
    try {
      // No heartbeat for a minute, so only the check finds the dead socket.
      c.heartbeat = 60;
      server.quiet();
      c.check();
      await waitFor(() => server.connectionCount === 2);
      await waitFor(() => c.state.kind === "connected");
    } finally {
      c.stop();
      await server.stop();
    }
  });

  it("delivers a prompt sent on a dead socket after reconnecting", async () => {
    const server = new TestServer();
    const c = await connect(server);
    try {
      const store = c.sessionStore("s1");
      server.quiet();
      await store.send("hello");

      // Sent again on the new socket, with the same commandId.
      const prompts = server.calls.filter((m) => m.method === "session.prompt");
      expect(prompts.map((p) => p.connection)).toEqual([0, 1]);
      expect(new Set(prompts.map((p) => p.commandId)).size).toBe(1);
      // Shown until the transcript has it.
      expect([...store.outbox.values()].map((o) => o.text)).toEqual(["hello"]);
    } finally {
      c.stop();
      await server.stop();
    }
  });

  it("fails a prompt once the connection gives up", async () => {
    const server = new TestServer();
    const c = await connect(server);
    try {
      const store = c.sessionStore("s1");
      await server.stop();
      await expect(store.send("hello")).rejects.toBeInstanceOf(RPCError);
      expect(c.state.kind).toBe("failed");
      expect(store.outbox.size).toBe(0);
    } finally {
      c.stop();
    }
  });
});

describe("RPCClient", () => {
  it("pings when a call goes unanswered", async () => {
    const server = new TestServer();
    const url = await server.start();
    const client = new RPCClient("tok");
    client.pingTimeout = 0.3;
    let closed = 0;
    client.onMessage = (m) => m.type === "closed" && closed++;
    try {
      await client.connect([url]);
      // The server answers the ping, so it's only slow: the call times out.
      await expect(client.call("slow", undefined, 0.05)).rejects.toMatchObject({ code: "timeout" });
      expect(client.isConnected).toBe(true);

      // It doesn't: the socket is dropped, and the call fails as disconnected.
      server.quiet();
      await expect(client.call("slow", undefined, 0.05)).rejects.toMatchObject({ code: "disconnected" });
      expect(client.isConnected).toBe(false);
      expect(closed).toBe(1);
    } finally {
      client.disconnect();
      await server.stop();
    }
  });
});
