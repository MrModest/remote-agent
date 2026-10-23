import { describe, expect, it } from "vitest";
import type { Session } from "../protocol/models";
import { isBusy, mostPressing, needsYou, parseCollection, shortAge, storedCollection, type SessionCollection } from "./sidebarLayout";

const session = (patch: Partial<Session>): Session => ({
  id: "s",
  projectId: "p",
  harness: "fake",
  workspace: { kind: "root", path: "/tmp/p" },
  status: "idle",
  title: "t",
  archived: false,
  createdAt: "2026-10-15T10:00:00Z",
  updatedAt: "2026-10-15T10:00:00Z",
  ...patch,
});

describe("sidebar layouts", () => {
  it("stores and reads collections", () => {
    const all: SessionCollection[] = [
      { kind: "needsYou" },
      { kind: "running" },
      { kind: "all" },
      { kind: "archived" },
      { kind: "project", serverID: "srv", projectID: "prj" },
    ];
    for (const c of all) expect(parseCollection(storedCollection(c))).toEqual(c);
    expect(parseCollection(null)).toBeUndefined();
    expect(parseCollection("project srv")).toBeUndefined();
    expect(parseCollection("bogus")).toBeUndefined();
  });

  it("groups sessions by what they need", () => {
    expect(needsYou(session({ status: "awaiting_approval" }))).toBe(true);
    expect(needsYou(session({ status: "error" }))).toBe(true);
    expect(needsYou(session({ status: "running" }))).toBe(false);
    expect(isBusy(session({ status: "running" }))).toBe(true);
    expect(isBusy(session({ backgroundTasks: [{ id: "b", kind: "command", description: "watch" }] }))).toBe(true);
    expect(isBusy(session({}))).toBe(false);
    expect(isBusy(session({ status: "stopped", backgroundTasks: [{ id: "b", kind: "command", description: "watch" }] }))).toBe(false);
  });

  it("shows a project's most pressing session", () => {
    const background = session({ id: "background", backgroundTasks: [{ id: "b", kind: "command", description: "watch" }] });
    const running = session({ id: "running", status: "running" });
    const failed = session({ id: "failed", status: "error" });
    const waiting = session({ id: "waiting", status: "awaiting_approval" });
    const idle = [session({ id: "idle" }), session({ id: "stopped", status: "stopped" })];
    expect(mostPressing([])).toBeUndefined();
    expect(mostPressing(idle)).toBeUndefined();
    expect(mostPressing([...idle, background])?.id).toBe("background");
    expect(mostPressing([background, running, ...idle])?.id).toBe("running");
    expect(mostPressing([running, failed])?.id).toBe("failed");
    expect(mostPressing([failed, waiting, running])?.id).toBe("waiting");
  });

  it("gives short ages", () => {
    const now = Date.parse("2026-10-15T12:00:00Z");
    expect(shortAge(now - 20_000, now)).toBe("now");
    expect(shortAge(now - 4 * 60_000, now)).toBe("4m");
    expect(shortAge(now - 3 * 3600_000, now)).toBe("3h");
    expect(shortAge(now - 2 * 86_400_000, now)).toBe("2d");
    expect(shortAge(now - 30 * 86_400_000, now)).not.toMatch(/d$/);
  });
});
