import type { ServerTask, TaskKind } from "../protocol/models";
import { RPCError } from "../protocol/rpc";
import { Observable } from "./observable";

/** Where a `ServerTaskProgress` finds its task: the connection. As RAKit's `TaskSource`. */
export interface TaskSource {
  task(id: string): Promise<ServerTask>;
  taskInput(id: string, text: string): Promise<void>;
  cancelTask(id: string): Promise<void>;
  /** The version of rad the connection last connected to. */
  readonly serverVersion: string | undefined;
  /** Called once an agent's update or sign-in ended. */
  taskEnded(task: ServerTask): Promise<void>;
  /** Connects again if the connection gave up, as it may while rad restarts. */
  reviveConnection(): void;
}

export type TaskPhase =
  | { kind: "running" }
  /** rad installed its update and restarts. */
  | { kind: "restarting" }
  | { kind: "done" }
  | { kind: "failed"; message: string }
  | { kind: "cancelled" };

/** Follows a task on the server until it ends, polling it each second. A
 *  dropped connection doesn't end it: it asks again until the server comes
 *  back. An update of rad then waits for rad to come back as the new
 *  version. As RAKit's `ServerTaskProgress`. */
export class ServerTaskProgress extends Observable {
  readonly id: string;
  task: ServerTask;
  phase: TaskPhase = { kind: "running" };
  /** Seconds between polls. */
  interval = 1;
  /** Seconds an update of rad may take to come back as the new version. */
  restartTimeout = 120;

  /** The version rad had when the task started. */
  private readonly startVersion: string | undefined;
  /** Bumped to stop the current poll loop. */
  private run = 0;
  private polling = false;
  private restartDeadline: number | undefined;

  constructor(
    task: ServerTask,
    private source: TaskSource,
  ) {
    super();
    this.id = task.id;
    this.task = task;
    this.startVersion = source.serverVersion;
  }

  get isActive(): boolean {
    return this.phase.kind === "running" || this.phase.kind === "restarting";
  }

  /** Polls until the task ends, unless it already has. */
  start() {
    if (this.polling || !this.isActive) return;
    this.polling = true;
    const run = ++this.run;
    void (async () => {
      if (this.task.status !== "running") await this.ended();
      await this.loop(run);
    })();
  }

  /** Stops polling, as when the view goes away; the task goes on on the server. */
  stop() {
    this.run++;
    this.polling = false;
  }

  /** Types a line into the task's command, such as a code to paste. */
  send(text: string): Promise<void> {
    return this.source.taskInput(this.id, text);
  }

  cancel(): Promise<void> {
    return this.source.cancelTask(this.id);
  }

  private async loop(run: number) {
    try {
      while (run === this.run) {
        switch (this.phase.kind) {
          case "running":
            await this.poll(run);
            break;
          case "restarting": {
            const v = this.task.version;
            this.source.reviveConnection();
            if (v && this.source.serverVersion === v) this.setPhase({ kind: "done" });
            else if (this.restartDeadline !== undefined && Date.now() >= this.restartDeadline) {
              this.setPhase({ kind: "failed", message: `rad hasn't come back as ${v ?? "the new version"}` });
            }
            break;
          }
          default:
            return;
        }
        if (run !== this.run || !this.isActive) return;
        await new Promise((r) => setTimeout(r, this.interval * 1000));
      }
    } finally {
      if (run === this.run) this.polling = false;
    }
  }

  private async poll(run: number) {
    try {
      const t = await this.source.task(this.id);
      if (run !== this.run) return;
      this.task = t;
      this.changed();
      if (t.status !== "running") await this.ended();
    } catch (e) {
      if (run !== this.run) return;
      if (e instanceof RPCError && (e.code === "not_found" || e.code === "method_not_found")) {
        // rad forgot it: it restarted, maybe as a release from before tasks.
        const v = this.source.serverVersion;
        if (this.task.kind === "update_server" && v && v !== this.startVersion) {
          this.task = { ...this.task, status: "succeeded", version: this.task.version ?? v };
          await this.ended();
        } else {
          this.setPhase({ kind: "failed", message: "rad restarted before the task finished" });
          if (isAgentTask(this.task.kind)) await this.source.taskEnded(this.task).catch(() => {});
        }
      }
      // Disconnected or slow: ask again.
    }
  }

  private async ended() {
    const t = this.task;
    switch (t.status) {
      case "succeeded":
        if (t.kind === "update_server") {
          this.restartDeadline = Date.now() + this.restartTimeout * 1000;
          this.setPhase({ kind: "restarting" });
        } else {
          this.setPhase({ kind: "done" });
        }
        break;
      case "cancelled":
        this.setPhase({ kind: "cancelled" });
        break;
      default:
        this.setPhase({ kind: "failed", message: t.error || "The task failed" });
    }
    if (isAgentTask(t.kind)) await this.source.taskEnded(t).catch(() => {});
  }

  private setPhase(p: TaskPhase) {
    this.phase = p;
    this.changed();
  }
}

function isAgentTask(kind: TaskKind): boolean {
  return kind === "update_agent" || kind === "login";
}

/** The tasks a server's settings follow while they're open, one progress
 *  each: those started there, and those that were running when it opened. */
export class ServerTasks extends Observable {
  private all = new Map<string, ServerTaskProgress>();
  private live = true;

  constructor(private source: TaskSource) {
    super();
  }

  /** The progress for a task, made and started the first time. Starting a
   *  task that already runs returns it, so it keeps its progress. */
  follow(task: ServerTask): ServerTaskProgress {
    let p = this.all.get(task.id);
    if (!p) {
      p = new ServerTaskProgress(task, this.source);
      p.watch(() => this.changed());
      this.all.set(task.id, p);
      if (this.live) p.start();
      this.changed();
    }
    return p;
  }

  get(id: string | undefined): ServerTaskProgress | undefined {
    return id === undefined ? undefined : this.all.get(id);
  }

  /** The latest task of this kind, for this agent, while it runs (or rad restarts). */
  active(kind: TaskKind, harness?: string): ServerTaskProgress | undefined {
    let found: ServerTaskProgress | undefined;
    for (const p of this.all.values()) {
      if (p.task.kind === kind && p.task.harness === harness && p.isActive) found = p;
    }
    return found;
  }

  /** Polls again after `stop`. */
  start() {
    this.live = true;
    for (const p of this.all.values()) p.start();
  }

  /** Stops polling them all: polling doesn't outlive the screen. */
  stop() {
    this.live = false;
    for (const p of this.all.values()) p.stop();
  }
}
