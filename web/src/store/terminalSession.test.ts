import { describe, expect, it, vi } from "vitest";
import { TerminalSession } from "./terminalSession";

/** Stands in for the browser's WebSocket. */
class FakeSocket {
  readyState = 1;
  binaryType = "blob";
  sent: (string | Uint8Array)[] = [];
  closed = false;
  onmessage: ((e: { data: unknown }) => void) | null = null;
  onclose: ((e: { code: number }) => void) | null = null;
  constructor(readonly query: string) {}
  send(d: string | Uint8Array) {
    this.sent.push(d);
  }
  close() {
    this.closed = true;
  }
  text(m: object) {
    this.onmessage?.({ data: JSON.stringify(m) });
  }
  bytes(s: string) {
    this.onmessage?.({ data: new TextEncoder().encode(s).buffer });
  }
  drop(code = 1006) {
    this.onclose?.({ code });
  }
}

function setup() {
  const sockets: FakeSocket[] = [];
  let connected = true;
  const host = {
    connected: () => connected,
    open: (q: string) => {
      const s = new FakeSocket(q);
      sockets.push(s);
      return s as unknown as WebSocket;
    },
  };
  const t = new TerminalSession(host, "s1");
  const out: string[] = [];
  let resets = 0;
  t.onOutput = (d) => out.push(new TextDecoder().decode(d));
  t.onReset = () => resets++;
  return { t, sockets, out, resets: () => resets, setConnected: (c: boolean) => (connected = c) };
}

describe("TerminalSession", () => {
  it("builds its query", () => {
    expect(TerminalSession.query("s1", 80, 24, true, undefined, 0)).toBe("session=s1&cols=80&rows=24&create=1");
    expect(TerminalSession.query("s1", 100, 30, false, "ab", 4096)).toBe("session=s1&cols=100&rows=30&id=ab&offset=4096");
  });

  it("clears the screen on a fresh attach and resumes after a drop", () => {
    vi.useFakeTimers();
    const { t, sockets, out, resets } = setup();
    t.start(80, 24, true);
    expect(sockets[0].query).toContain("create=1");
    sockets[0].text({ type: "attached", id: "sh", offset: 0, reset: true, cols: 80, rows: 24 });
    expect(resets()).toBe(1);
    expect(t.state.kind).toBe("open");
    sockets[0].bytes("hello");
    expect(out).toEqual(["hello"]);

    sockets[0].drop();
    expect(t.state.kind).toBe("reconnecting");
    vi.advanceTimersByTime(1000);
    // A reconnect resumes where the view left off, and never asks for a new shell.
    expect(sockets[1].query).toBe("session=s1&cols=80&rows=24&id=sh&offset=5");
    sockets[1].text({ type: "attached", id: "sh", offset: 5, cols: 80, rows: 24 });
    expect(resets()).toBe(1);
    expect(t.state.kind).toBe("open");
    vi.useRealTimers();
  });

  it("stops after the shell exits, an error or a revoked device", () => {
    vi.useFakeTimers();
    for (const end of [
      (s: FakeSocket) => s.text({ type: "exit", exitCode: 3 }),
      (s: FakeSocket) => s.text({ type: "error", code: "gone", message: "the shell has ended" }),
      (s: FakeSocket) => s.drop(4001),
    ]) {
      const { t, sockets } = setup();
      t.start(80, 24, true);
      sockets[0].text({ type: "attached", id: "sh", reset: true, cols: 80, rows: 24 });
      end(sockets[0]);
      vi.advanceTimersByTime(30_000);
      expect(sockets).toHaveLength(1);
      expect(t.live).toBe(false);
    }
    vi.useRealTimers();
  });

  it("chunks input and sends its size once it settles", () => {
    vi.useFakeTimers();
    const { t, sockets } = setup();
    t.start(80, 24, true);
    sockets[0].text({ type: "attached", id: "sh", reset: true, cols: 80, rows: 24 });
    t.send(new Uint8Array(40 << 10));
    expect(sockets[0].sent.map((d) => (d as Uint8Array).length)).toEqual([16 << 10, 16 << 10, 8 << 10]);
    t.resize(100, 30);
    t.resize(120, 40);
    vi.advanceTimersByTime(100);
    expect(sockets[0].sent.at(-1)).toBe(JSON.stringify({ type: "resize", cols: 120, rows: 40 }));
    vi.useRealTimers();
  });

  it("waits for the server connection", () => {
    const { t, sockets, setConnected } = setup();
    setConnected(false);
    t.start(80, 24, true);
    expect(sockets).toHaveLength(0);
    setConnected(true);
    t.connectionChanged(true);
    expect(sockets).toHaveLength(1);
  });
});
