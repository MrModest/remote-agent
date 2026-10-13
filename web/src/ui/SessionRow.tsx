import { Archive, GitBranch } from "lucide-react";
import type { Session } from "../protocol/models";
import type { ServerConnection } from "../store/serverConnection";
import { HarnessBadge, RelativeTime, StatusIcon } from "./components";
import { useConnection } from "./context";
import { Confirm } from "./primitives";

/** A session in a list: its status, title, agent, project and branch. */
export function SessionRow({ session, selected, onOpen, onArchive }: { session: Session; selected: boolean; onOpen: () => void; onArchive?: () => void }) {
  const connection = useConnection();
  const project = connection.projects.get(session.projectId);
  const harness = connection.harness(session.harness);
  return (
    <div className={"session-row" + (selected ? " selected" : "")}>
      <button className="session-row-main" onClick={onOpen} aria-current={selected ? "page" : undefined}>
        <StatusIcon status={session.status} background={!!session.backgroundTasks?.length} />
        <span className="session-row-text">
          <span className="session-title">{session.title}</span>
          <span className="session-meta">
            <HarnessBadge id={session.harness} name={harness?.name} />
            {project && <span className="nowrap">{project.name}</span>}
            {session.workspace.kind === "worktree" && session.workspace.branch && (
              <span className="branch">
                <GitBranch size={11} /> <span className="branch-name">{session.workspace.branch}</span>
              </span>
            )}
            <span className="spacer" />
            <RelativeTime date={session.updatedAt} />
          </span>
        </span>
      </button>
      {onArchive && (
        <button className="session-row-action icon-button" onClick={onArchive} title="Archive…" aria-label="Archive">
          <Archive size={15} />
        </button>
      )}
    </div>
  );
}

/** Asks before archiving a session, and whether to delete its worktree. */
export function ArchiveConfirm({ connection, session, onClose }: { connection: ServerConnection; session: Session; onClose: () => void }) {
  const worktree = session.workspace.kind === "worktree";
  return (
    <Confirm
      title="Archive session?"
      message={worktree ? `The branch ${session.workspace.branch ?? ""} is kept either way.` : "The agent process is stopped."}
      actions={[
        { label: "Archive", onSelect: () => connection.archive(session.id, false).catch(() => {}) },
        ...(worktree ? [{ label: "Archive and delete worktree", destructive: true, onSelect: () => connection.archive(session.id, true).catch(() => {}) }] : []),
      ]}
      onClose={onClose}
    />
  );
}
