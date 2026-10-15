// As SessionAlertTests and IndexChangeTests in apple/RAKit/Tests/RAKitTests/StoreTests.swift.
import { describe, expect, it } from "vitest";
import type { Event, Session } from "../protocol/models";
import type { ServerMessage } from "../protocol/rpc";
import { sessionAlert } from "./sessionAlert";
import { ServerConnection } from "./serverConnection";

function session(id: string, status: string, archived = false, error?: string, seq = 1): Session {
  return {
    id,
    projectId: "p",
    harness: "fake",
    status,
    title: id,
    archived,
    error,
    workspace: { kind: "root", path: "/tmp/p" },
    createdAt: "2026-10-09T12:00:00Z",
    updatedAt: `2026-10-09T12:00:${String(seq % 60).padStart(2, "0")}Z`,
  };
}

function event(seq: number, id: string, status: string, error?: string): Event {
  return { stream: "index", seq, type: "session.upserted", session: session(id, status, false, error, seq) };
}

describe("sessionAlert", () => {
  const alert = (from: string, to: string, archived = false) =>
    sessionAlert(session("s", from), session("s", to, archived, to === "error" ? "boom" : undefined, 2));

  it("follows status changes", () => {
    expect(alert("idle", "running")).toBeUndefined();
    expect(alert("running", "awaiting_approval")).toEqual({ kind: "needsYou" });
    expect(alert("idle", "awaiting_approval")).toEqual({ kind: "needsYou" });
    expect(alert("awaiting_approval", "running")).toBeUndefined();
    expect(alert("running", "idle")).toEqual({ kind: "finished" });
    // Answered elsewhere and done, or interrupted: nothing to tell.
    expect(alert("awaiting_approval", "idle")).toBeUndefined();
    expect(alert("running", "error")).toEqual({ kind: "failed", error: "boom" });
    expect(alert("idle", "error")).toEqual({ kind: "failed", error: "boom" });
    expect(alert("running", "running")).toBeUndefined();
    expect(alert("running", "idle", true)).toBeUndefined();
    expect(alert("running", "stopped")).toBeUndefined();
  });
});

describe("ServerConnection.onSessionChange", () => {
  function connection(): [(m: ServerMessage) => void, string[]] {
    const c = new ServerConnection({ id: "srv", name: "Test", urls: ["http://127.0.0.1:1"], deviceId: "d", addedAt: "" }, "", () => {});
    const seen: string[] = [];
    c.onSessionChange = (old, next) => {
      // Reported once the batch is applied.
      expect(c.sessions.get(next.id)).toBe(next);
      const a = sessionAlert(old, next);
      if (a) seen.push(`${next.id} ${a.kind}${a.kind === "failed" ? " " + a.error : ""}`);
    };
    const handle = (m: ServerMessage) => (c as unknown as { handle(m: ServerMessage): void }).handle(m);
    return [handle, seen];
  }

  it("is not called for the first sync", () => {
    const [handle, seen] = connection();
    handle({ type: "events", stream: "index", events: [event(3, "a", "running")] });
    handle({ type: "synchronized", stream: "index", seq: 3, reset: true });
    expect(seen).toEqual([]);
    handle({ type: "events", stream: "index", events: [event(4, "a", "awaiting_approval"), event(5, "b", "running")] });
    expect(seen).toEqual(["a needsYou"]);
  });

  it("covers what changed while away", () => {
    const [handle, seen] = connection();
    handle({ type: "events", stream: "index", events: [event(2, "a", "running"), event(3, "b", "running")] });
    handle({ type: "synchronized", stream: "index", seq: 3, reset: true });
    // Back after a sleep: the replay holds what changed meanwhile.
    handle({ type: "closed" });
    handle({ type: "events", stream: "index", events: [event(7, "a", "idle")] });
    handle({ type: "synchronized", stream: "index", seq: 7, reset: false });
    expect(seen).toEqual(["a finished"]);
    // The server lost its place: a reset, compared with what was cached.
    handle({ type: "closed" });
    handle({ type: "events", stream: "index", events: [event(1, "a", "idle"), event(2, "b", "error", "boom")] });
    handle({ type: "synchronized", stream: "index", seq: 2, reset: true });
    expect(seen).toEqual(["a finished", "b failed boom"]);
  });

  it("is called once for a repeated replay", () => {
    const [handle, seen] = connection();
    handle({ type: "events", stream: "index", events: [event(2, "a", "running")] });
    handle({ type: "synchronized", stream: "index", seq: 2, reset: true });
    handle({ type: "closed" });
    const replay = event(5, "a", "awaiting_approval");
    handle({ type: "events", stream: "index", events: [replay, replay] });
    handle({ type: "synchronized", stream: "index", seq: 5, reset: false });
    expect(seen).toEqual(["a needsYou"]);
  });
});
