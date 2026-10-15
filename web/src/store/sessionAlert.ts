import type { Session } from "../protocol/models";

/** A change in a session that the user wants to hear about while they look
 *  elsewhere, such as in a notification: the agent waits for them, finished
 *  its turn, or failed. As RAKit's SessionAlert. */
export type SessionAlert =
  /** It waits for an approval or an answer. */
  | { kind: "needsYou" }
  /** Its turn ended. */
  | { kind: "finished" }
  /** The agent didn't start, or exited with an error. */
  | { kind: "failed"; error?: string };

/** The alert for a session that changed from `old` to `next`, if any. An
 *  archived session has none. */
export function sessionAlert(old: Session, next: Session): SessionAlert | undefined {
  if (next.archived || old.status === next.status) return undefined;
  switch (next.status) {
    case "awaiting_approval":
      return { kind: "needsYou" };
    case "idle":
      return old.status === "running" ? { kind: "finished" } : undefined;
    case "error":
      return { kind: "failed", error: next.error };
  }
  return undefined;
}
