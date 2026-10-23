// As apple/RAKit/Tests/RAKitTests/ServerTaskProgressTests.swift.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ServerTask, TaskKind, TaskStatus } from "../protocol/models";
import { RPCError } from "../protocol/rpc";
import { ServerTaskProgress, ServerTasks, type TaskSource } from "./serverTask";

/** A server whose task answers come from a script. */
class FakeTaskSource implements TaskSource {
  /** Each poll takes the next answer; the last one repeats. */
  answers: (ServerTask | RPCError)[] = [];
  serverVersion: string | undefined = "0.1.0";
  polls = 0;
  inputs: string[] = [];
  cancelled = false;
  ended: ServerTask[] = [];

  async task(): Promise<ServerTask> {
    this.polls++;
    const a = this.answers.length > 1 ? this.answers.shift()! : this.answers[0];
    if (a instanceof RPCError) throw a;
    return a;
  }

  async taskInput(_id: string, text: string) {
    this.inputs.push(text);
  }

  async cancelTask() {
    this.cancelled = true;
  }

  async taskEnded(t: ServerTask) {
    this.ended.push(t);
  }

  revived = 0;
  reviveConnection() {
    this.revived++;
  }
}

function task(kind: TaskKind, status: TaskStatus, more: Partial<ServerTask> = {}): ServerTask {
  return { id: "k1", kind, harness: kind === "update_server" ? undefined : "claude", status, output: "", startedAt: "2026-10-09T12:00:00Z", ...more };
}

const notFound = () => new RPCError("not_found", "no such task");

/** Lets the clock run, a poll at a time, until `cond` holds. */
async function until(cond: () => boolean, maxSeconds = 300) {
  for (let i = 0; i < maxSeconds * 4 && !cond(); i++) await vi.advanceTimersByTimeAsync(250);
  expect(cond()).toBe(true);
}

describe("ServerTaskProgress", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("polls each second until the task ends", async () => {
    const source = new FakeTaskSource();
    source.answers = [
      task("login", "running", { output: "Paste code" }),
      RPCError.disconnected(), // a dropped connection doesn't end it
      task("login", "running", { output: "Paste code > abc", input: true }),
      task("login", "succeeded", { output: "Signed in" }),
    ];
    const p = new ServerTaskProgress(task("login", "running"), source);
    p.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(source.polls).toBe(1);
    expect(p.task.output).toBe("Paste code");
    await vi.advanceTimersByTimeAsync(999);
    expect(source.polls).toBe(1);
    await vi.advanceTimersByTimeAsync(1);
    expect(source.polls).toBe(2);
    await until(() => p.phase.kind === "done");
    expect(p.task.output).toBe("Signed in");
    expect(source.polls).toBe(4);
    // The agents are looked at again, for their versions and sign-in states.
    expect(source.ended.map((t) => t.status)).toEqual(["succeeded"]);
    // Ended: no more polls.
    await vi.advanceTimersByTimeAsync(5000);
    expect(source.polls).toBe(4);

    await p.send("abc");
    expect(source.inputs).toEqual(["abc"]);
  });

  it("fails with the task's error, and is cancelled", async () => {
    const source = new FakeTaskSource();
    source.answers = [task("update_agent", "failed", { error: "codex: exit status 1" })];
    const p = new ServerTaskProgress(task("update_agent", "running"), source);
    p.start();
    await until(() => !p.isActive);
    expect(p.phase).toEqual({ kind: "failed", message: "codex: exit status 1" });

    source.answers = [task("login", "cancelled")];
    const c = new ServerTaskProgress(task("login", "running"), source);
    c.start();
    await c.cancel();
    await until(() => !c.isActive);
    expect(c.phase.kind).toBe("cancelled");
    expect(source.cancelled).toBe(true);

    // A status this client doesn't know has ended too.
    source.answers = [task("login", "exploded")];
    const u = new ServerTaskProgress(task("login", "running"), source);
    u.start();
    await until(() => !u.isActive);
    expect(u.phase).toEqual({ kind: "failed", message: "The task failed" });
  });

  it("waits for rad to come back as the new version after updating it", async () => {
    const source = new FakeTaskSource();
    source.answers = [task("update_server", "succeeded", { version: "0.2.0" })];
    const p = new ServerTaskProgress(task("update_server", "running"), source);
    p.start();
    await until(() => p.phase.kind === "restarting");
    await vi.advanceTimersByTimeAsync(30_000);
    expect(p.phase.kind).toBe("restarting"); // still the old rad
    const polls = source.polls;
    source.serverVersion = "0.2.0";
    await until(() => p.phase.kind === "done");
    expect(source.polls).toBe(polls); // the task ended: only the version is watched
    expect(source.ended).toEqual([]); // rad's own update doesn't refresh the agents
    expect(source.revived).toBeGreaterThan(0); // in case the connection gave up meanwhile
  });

  it("takes an update to a rad without tasks as done", async () => {
    // A release from before tasks doesn't know task.get.
    const source = new FakeTaskSource();
    source.answers = [RPCError.disconnected(), new RPCError("method_not_found", "unknown method task.get")];
    const p = new ServerTaskProgress(task("update_server", "running"), source);
    p.start();
    source.serverVersion = "0.11.0";
    await until(() => p.phase.kind === "done");
    expect(p.task.version).toBe("0.11.0");
  });

  it("fails when rad doesn't come back as the new version", async () => {
    const source = new FakeTaskSource();
    source.answers = [task("update_server", "succeeded", { version: "0.2.0" })];
    const p = new ServerTaskProgress(task("update_server", "running"), source);
    p.start();
    await until(() => p.phase.kind === "restarting");
    await vi.advanceTimersByTimeAsync(119_000);
    expect(p.phase.kind).toBe("restarting");
    await until(() => !p.isActive);
    expect(p.phase).toEqual({ kind: "failed", message: "rad hasn't come back as 0.2.0" });
  });

  it("takes a task rad forgot as done when rad came back as a new version", async () => {
    // rad restarted as a new version before a poll saw the update end.
    const source = new FakeTaskSource();
    source.answers = [RPCError.disconnected(), notFound()];
    const p = new ServerTaskProgress(task("update_server", "running"), source);
    p.start();
    source.serverVersion = "0.2.0";
    await until(() => p.phase.kind === "done");
    expect(p.task.status).toBe("succeeded");
    expect(p.task.version).toBe("0.2.0");

    // Any other task that rad forgot failed.
    const other = new FakeTaskSource();
    other.answers = [notFound()];
    const q = new ServerTaskProgress(task("login", "running"), other);
    q.start();
    await until(() => !q.isActive);
    expect(q.phase).toEqual({ kind: "failed", message: "rad restarted before the task finished" });

    // So did an update of rad that came back as the same version.
    const same = new FakeTaskSource();
    same.answers = [notFound()];
    const r = new ServerTaskProgress(task("update_server", "running"), same);
    r.start();
    await until(() => !r.isActive);
    expect(r.phase).toEqual({ kind: "failed", message: "rad restarted before the task finished" });
  });

  it("resolves a task that had ended when it started", async () => {
    const source = new FakeTaskSource();
    const p = new ServerTaskProgress(task("update_agent", "succeeded", { version: "2.1.310" }), source);
    p.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(p.phase.kind).toBe("done");
    expect(source.polls).toBe(0);
  });

  it("stops polling", async () => {
    const source = new FakeTaskSource();
    source.answers = [task("login", "running")];
    const p = new ServerTaskProgress(task("login", "running"), source);
    p.start();
    await until(() => source.polls >= 2);
    p.stop();
    const polls = source.polls;
    await vi.advanceTimersByTimeAsync(10_000);
    expect(source.polls).toBe(polls);
    expect(p.phase.kind).toBe("running");

    // And goes on when started again.
    p.start();
    await vi.advanceTimersByTimeAsync(0);
    expect(source.polls).toBe(polls + 1);
    p.stop();
  });
});

describe("ServerTasks", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it("keeps one progress per task, and stops them all", async () => {
    const source = new FakeTaskSource();
    source.answers = [task("login", "running")];
    const tasks = new ServerTasks(source);
    const p = tasks.follow(task("login", "running"));
    // Starting a task that runs returns it, and its progress.
    expect(tasks.follow(task("login", "running"))).toBe(p);
    expect(tasks.active("login", "claude")).toBe(p);
    expect(tasks.active("update_agent", "claude")).toBeUndefined();
    expect(tasks.active("login", "codex")).toBeUndefined();
    await vi.advanceTimersByTimeAsync(2000);
    expect(source.polls).toBe(3);

    tasks.stop();
    await vi.advanceTimersByTimeAsync(5000);
    expect(source.polls).toBe(3);

    // One followed while stopped waits for start.
    const update = tasks.follow({ ...task("update_server", "running"), id: "k2" });
    await vi.advanceTimersByTimeAsync(5000);
    expect(source.polls).toBe(3);
    expect(tasks.active("update_server")).toBe(update);

    source.answers = [task("login", "succeeded")];
    tasks.start();
    await until(() => p.phase.kind === "done");
    expect(tasks.active("login", "claude")).toBeUndefined();
    tasks.stop();
  });
});
