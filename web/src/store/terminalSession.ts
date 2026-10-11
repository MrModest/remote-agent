import type { TerminalMessage } from "../protocol/models";
import { Observable } from "./observable";

/** Mirrors apple/RAKit/Sources/RAKit/Store/TerminalSession.swift. */
export type TerminalState =
  | { kind: "connecting" }
  | { kind: "open" }
  | { kind: "reconnecting" }
  /** The shell exited, with its exit code. */
  | { kind: "exited"; code: number }
  /** The shell ended while this view was away. */
  | { kind: "gone" }
  | { kind: "failed"; message: string };

/** What a TerminalSession needs from the server connection. */
export interface TerminalHost {
  connected(): boolean;
  /** Opens `GET /v1/terminal?<query>`; throws while disconnected. */
  open(query: string): WebSocket;
}

/** Close code rad uses for a revoked device. */
const revokedCode = 4001;
/** Input goes in frames of at most this many bytes. */
const chunk = 16 << 10;

/** A session's shell on the server, as one view shows it: the socket to it,
 *  and the view's place in its output, so a dropped connection resumes
 *  rather than redraws. The shell outlives the view; `detach()` only closes
 *  the socket. See "Terminal" in protocol/PROTOCOL.md. */
export class TerminalSession extends Observable {
  state: TerminalState = { kind: "connecting" };
  /** This view closed the shell, so its exit is no surprise. */
  closedHere = false;
  /** Output to show, in order. */
  onOutput: (data: Uint8Array) => void = () => {};
  /** Clear the screen: the output that follows starts afresh. */
  onReset: () => void = () => {};

  private ws: WebSocket | null = null;
  private shellID: string | undefined;
  private offset = 0;
  private size: { cols: number; rows: number } | undefined;
  private sentSize: { cols: number; rows: number } | undefined;
  private create = false;
  private delay = 1;
  private detached = false;
  private retryTimer: ReturnType<typeof setTimeout> | undefined;
  private resizeTimer: ReturnType<typeof setTimeout> | undefined;

  constructor(
    private host: TerminalHost,
    readonly sessionID: string,
  ) {
    super();
  }

  get live(): boolean {
    const k = this.state.kind;
    return k === "open" || k === "connecting" || k === "reconnecting";
  }

  /** Connects once the view knows its size. `create` starts a shell if the
   *  session has none; it's for the user's own action, never a reconnect. */
  start(cols: number, rows: number, create: boolean) {
    this.size = { cols, rows };
    this.create = create;
    this.connect();
  }

  /** Starts a new shell after the last one ended. */
  restart() {
    this.closedHere = false;
    this.shellID = undefined;
    this.offset = 0;
    this.create = true;
    this.onReset();
    this.connect();
  }

  /** Closes the socket. The shell keeps running. */
  detach() {
    this.detached = true;
    clearTimeout(this.retryTimer);
    clearTimeout(this.resizeTimer);
    this.drop();
  }

  /** Ends the shell, for every client. */
  closeShell() {
    this.closedHere = true;
    this.changed();
    this.sendControl({ type: "close" });
  }

  send(data: Uint8Array<ArrayBuffer>) {
    if (this.state.kind !== "open" || !this.ws) return;
    for (let i = 0; i < data.length; i += chunk) this.ws.send(data.subarray(i, i + chunk));
  }

  /** The view's size changed. Sent after it settles: panels resize the view
   *  while they're dragged. */
  resize(cols: number, rows: number) {
    this.size = { cols, rows };
    clearTimeout(this.resizeTimer);
    this.resizeTimer = setTimeout(() => this.sendSize(), 100);
  }

  /** Follows the server connection: the terminal's socket goes with it, and
   *  comes back when it does. */
  connectionChanged(connected: boolean) {
    if (connected) {
      if (!this.ws && this.live && this.size) this.connect();
    } else if (this.ws) {
      this.drop();
      if (this.live) this.setState({ kind: "reconnecting" });
    }
  }

  static query(sessionID: string, cols: number, rows: number, create: boolean, shellID: string | undefined, offset: number): string {
    const q = new URLSearchParams({ session: sessionID, cols: String(cols), rows: String(rows) });
    if (create) q.set("create", "1");
    if (shellID) {
      q.set("id", shellID);
      q.set("offset", String(offset));
    }
    return q.toString();
  }

  private connect() {
    clearTimeout(this.retryTimer);
    this.drop();
    if (this.detached || !this.size) return;
    if (this.shellID) this.setState({ kind: "reconnecting" });
    else if (this.state.kind !== "reconnecting") this.setState({ kind: "connecting" });
    if (!this.host.connected()) return; // connectionChanged resumes
    let ws: WebSocket;
    try {
      ws = this.host.open(TerminalSession.query(this.sessionID, this.size.cols, this.size.rows, this.create && !this.shellID, this.shellID, this.offset));
    } catch {
      this.dropped();
      return;
    }
    ws.binaryType = "arraybuffer";
    this.ws = ws;
    ws.onmessage = (e) => {
      if (this.ws !== ws) return;
      if (typeof e.data === "string") {
        let m: TerminalMessage;
        try {
          m = JSON.parse(e.data);
        } catch {
          return;
        }
        if (!this.handle(m)) this.drop();
      } else {
        const data = new Uint8Array(e.data as ArrayBuffer);
        this.offset += data.length;
        this.onOutput(data);
      }
    };
    ws.onclose = (e) => {
      if (this.ws !== ws) return;
      if (e.code === revokedCode) {
        this.drop();
        this.setState({ kind: "failed", message: "This device is no longer authorized; pair again." });
        return;
      }
      this.dropped();
    };
  }

  /** Returns false when the socket is done. */
  private handle(m: TerminalMessage): boolean {
    switch (m.type) {
      case "attached":
        if (m.reset || m.id !== this.shellID) this.onReset();
        this.shellID = m.id;
        this.offset = m.offset ?? 0;
        this.sentSize = { cols: m.cols ?? 0, rows: m.rows ?? 0 };
        this.delay = 1;
        this.setState({ kind: "open" });
        this.sendSize();
        return true;
      case "reset":
        this.onReset();
        this.offset = m.offset ?? 0;
        return true;
      case "exit":
        this.shellID = undefined;
        this.setState({ kind: "exited", code: m.exitCode ?? 0 });
        return false;
      case "error":
        this.shellID = undefined;
        this.setState(m.code === "gone" ? { kind: "gone" } : { kind: "failed", message: m.message ?? "The terminal isn't available" });
        return false;
    }
    return true;
  }

  /** The socket dropped: try again, resuming, while the server connection is up. */
  private dropped() {
    this.drop();
    if (this.detached || !this.live) return;
    this.setState({ kind: "reconnecting" });
    const delay = this.delay;
    this.retryTimer = setTimeout(() => {
      this.delay = Math.min(delay * 2, 10);
      this.connect();
    }, delay * 1000);
  }

  private drop() {
    const ws = this.ws;
    if (!ws) return;
    this.ws = null;
    ws.onmessage = ws.onclose = null;
    try {
      ws.close(1000);
    } catch {
      // already closing
    }
  }

  private sendSize() {
    const s = this.size;
    if (this.state.kind !== "open" || !s || (this.sentSize?.cols === s.cols && this.sentSize?.rows === s.rows)) return;
    this.sentSize = s;
    this.sendControl({ type: "resize", cols: s.cols, rows: s.rows });
  }

  private sendControl(m: TerminalMessage) {
    if (this.ws?.readyState === 1) this.ws.send(JSON.stringify(m)); // OPEN
  }

  private setState(s: TerminalState) {
    this.state = s;
    this.changed();
  }
}
