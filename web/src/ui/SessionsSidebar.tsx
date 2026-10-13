import { Archive, ChevronRight, Ellipsis, Hand, Monitor, Pencil, Plus, RefreshCw, Settings, SquarePen, Trash2, WifiOff } from "lucide-react";
import { useEffect, useState } from "react";
import type { Session } from "../protocol/models";
import { useObserved, useObservedAll } from "../store/observable";
import type { ServerConnection } from "../store/serverConnection";
import type { SavedServer } from "../store/serverStore";
import { storage } from "../store/storage";
import { ConnectionDot, connectionLabel } from "./components";
import { ConnectionContext, useServerStore } from "./context";
import { Menu, Spinner } from "./primitives";
import { navigate, type Route } from "./router";
import { ArchiveConfirm, SessionRow } from "./SessionRow";

export interface ServerActions {
  pair: () => void;
  settings: () => void;
  newSession: (c: ServerConnection) => void;
  rename: (s: SavedServer) => void;
  remove: (s: SavedServer) => void;
}

/** Tries to connect again, and, as pulling to refresh in the apps, looks for newly installed agents. */
export function reconnect(c: ServerConnection) {
  c.retry();
  void c.refreshHarnesses(true);
}

/** The sidebar in a wide window, as on iPad and the Mac: every paired
 *  server, each with its sessions. A click on a server's name shows or hides
 *  them, or, once the connection has given up, tries again. */
export function SessionsSidebar({ route, actions }: { route: Route; actions: ServerActions }) {
  const store = useServerStore();
  useObserved(store);
  const [collapsed, setCollapsed] = useCollapsed(store.servers);
  return (
    <div className="server-home">
      <header className="bar">
        <h1 className="bar-title">Sessions</h1>
        <button className="icon-button" onClick={actions.settings} title="Settings" aria-label="Settings">
          <Settings size={18} />
        </button>
        <NewSessionButton onNew={actions.newSession} />
      </header>
      <div className="list-scroll">
        {store.servers.map((s) => (
          <ServerSection
            key={s.id}
            connection={store.connection(s)}
            route={route}
            expanded={!collapsed.has(s.id)}
            setExpanded={(shown) => setCollapsed(s.id, !shown)}
            actions={actions}
          />
        ))}
      </div>
      <div className="sidebar-foot">
        <button className="sidebar-foot-button" onClick={actions.pair}>
          <Plus size={16} /> Pair a server
        </button>
      </div>
    </div>
  );
}

const collapsedKey = "collapsedServers";

/** The servers whose sessions are hidden, by id, kept across visits. */
function useCollapsed(servers: SavedServer[]) {
  const [ids, setIds] = useState(() => new Set((storage.get(collapsedKey) ?? "").split(" ").filter(Boolean)));
  const set = (id: string, collapsed: boolean) => {
    // Forgets servers that were removed.
    const next = new Set([...ids].filter((x) => x !== id && servers.some((s) => s.id === x)));
    if (collapsed) next.add(id);
    storage.set(collapsedKey, [...next].join(" "));
    setIds(next);
  };
  return [ids, set] as const;
}

/** Starts a session on the server connected, or, with several, on the one
 *  picked from a menu. */
function NewSessionButton({ onNew }: { onNew: (c: ServerConnection) => void }) {
  const store = useServerStore();
  const connections = store.servers.map((s) => store.connection(s));
  useObservedAll(connections);
  const connected = connections.filter((c) => c.state.kind === "connected");
  if (connected.length > 1) {
    return <Menu title="New session" label={<SquarePen size={19} />} entries={connected.map((c) => ({ label: `On ${c.server.name}`, onSelect: () => onNew(c) }))} />;
  }
  const only = connected[0];
  return (
    <button
      className="icon-button"
      onClick={() => only && onNew(only)}
      disabled={!only}
      title={only ? `New session on ${only.server.name}` : "New session"}
      aria-label="New session"
    >
      <SquarePen size={19} />
    </button>
  );
}

/** One server in the sidebar: its header, how its connection is doing, and
 *  its sessions, those waiting for the user first. */
function ServerSection({
  connection,
  route,
  expanded,
  setExpanded,
  actions,
}: {
  connection: ServerConnection;
  route: Route;
  expanded: boolean;
  setExpanded: (shown: boolean) => void;
  actions: ServerActions;
}) {
  useObserved(connection);
  const [showArchived, setShowArchived] = useState(false);
  const [archiveTarget, setArchiveTarget] = useState<Session>();
  const id = connection.server.id;
  const selected = route.serverID === id ? route.sessionID : undefined;

  // Every server connects; one that was given up on waits for a click.
  useEffect(() => {
    if (connection.state.kind === "idle") connection.start();
  }, [connection]);

  // Landing on an archived session, such as one from a link, shows its row.
  const selectedArchived = selected !== undefined && connection.sessions.get(selected)?.archived === true;
  useEffect(() => {
    if (selectedArchived) setShowArchived(true);
  }, [selectedArchived]);

  const active = connection.activeSessions;
  const sessions = [...active.filter((s) => s.status === "awaiting_approval"), ...active.filter((s) => s.status !== "awaiting_approval")];
  const archived = connection.archivedSessions;
  const row = (s: Session) => (
    <li key={s.id}>
      <SessionRow
        session={s}
        selected={s.id === selected}
        onOpen={() => navigate({ serverID: id, sessionID: s.id })}
        onArchive={s.archived ? undefined : () => setArchiveTarget(s)}
      />
    </li>
  );

  return (
    <ConnectionContext.Provider value={connection}>
      <section className="server-section" aria-label={connection.server.name}>
        <ServerHeader connection={connection} expanded={expanded} setExpanded={setExpanded} actions={actions} />
        {expanded && (
          <>
            <ConnectionStatus connection={connection} onNew={() => actions.newSession(connection)} />
            <ul className="session-list">{sessions.map(row)}</ul>
            {archived.length > 0 && (
              <button className="sidebar-status" onClick={() => setShowArchived(!showArchived)} aria-expanded={showArchived}>
                <span className="status-icon">
                  <Archive size={15} className="c-overlay" />
                </span>
                <span className="c-subtext">Archived</span>
                <span className="c-overlay">{archived.length}</span>
                <ChevronRight size={14} className={"chevron c-overlay" + (showArchived ? " open" : "")} />
              </button>
            )}
            {archived.length > 0 && showArchived && <ul className="session-list">{archived.map(row)}</ul>}
          </>
        )}
      </section>
      {archiveTarget && <ArchiveConfirm connection={connection} session={archiveTarget} onClose={() => setArchiveTarget(undefined)} />}
    </ConnectionContext.Provider>
  );
}

/** A server's header: its name and connection, and while its sessions are
 *  hidden, how many there are and whether one is waiting for the user. A
 *  click shows or hides them, or tries again once the connection has given
 *  up. On hover, a + starts a session on the server and ⋯ has its other
 *  actions. */
function ServerHeader({
  connection,
  expanded,
  setExpanded,
  actions,
}: {
  connection: ServerConnection;
  expanded: boolean;
  setExpanded: (shown: boolean) => void;
  actions: ServerActions;
}) {
  const server = connection.server;
  const state = connection.state;
  const offline = state.kind === "failed";
  const connected = state.kind === "connected";
  const active = connection.activeSessions;
  const waiting = active.some((s) => s.status === "awaiting_approval");

  const activate = () => {
    if (offline) {
      connection.retry();
      setExpanded(true);
    } else {
      setExpanded(!expanded);
    }
  };
  const label = [server.name, connectionLabel(state)];
  if (!expanded) label.push(`${active.length} sessions`);
  if (!expanded && waiting) label.push("needs you");

  return (
    <div className="server-header">
      <button
        className="server-header-main"
        onClick={activate}
        aria-expanded={expanded}
        aria-label={label.join(", ")}
        title={offline ? `Try to connect to ${server.name} again` : connectionLabel(state)}
      >
        <span className="status-icon">
          <Monitor size={15} className="c-overlay" />
        </span>
        <span className="server-header-name">{server.name}</span>
        <ConnectionDot state={state} />
        {!expanded && active.length > 0 && <span className="c-overlay">{active.length}</span>}
        {!expanded && waiting && <Hand size={13} strokeWidth={2.25} className="c-yellow" />}
      </button>
      {connected && (
        <button
          className="icon-button server-header-action"
          onClick={() => actions.newSession(connection)}
          title={`New session on ${server.name}`}
          aria-label={`New session on ${server.name}`}
        >
          <Plus size={16} />
        </button>
      )}
      <Menu
        className="icon-button server-header-action"
        title={`${server.name} actions`}
        label={<Ellipsis size={16} />}
        entries={[
          { label: `New session on ${server.name}`, icon: SquarePen, disabled: !connected, onSelect: () => actions.newSession(connection) },
          { label: "Reconnect", icon: RefreshCw, onSelect: () => reconnect(connection) },
          { label: "Rename…", icon: Pencil, onSelect: () => actions.rename(server) },
          "divider",
          { label: `Remove ${server.name}…`, icon: Trash2, destructive: true, onSelect: () => actions.remove(server) },
        ]}
      />
    </div>
  );
}

/** While the server isn't connected, why; once it has given up, a way to
 *  try again. Connected without sessions, a way to start one. */
function ConnectionStatus({ connection, onNew }: { connection: ServerConnection; onNew: () => void }) {
  const state = connection.state;
  switch (state.kind) {
    case "failed":
      return (
        <button className="sidebar-status" onClick={() => connection.retry()}>
          <span className="status-icon">
            <WifiOff size={15} className="c-red" />
          </span>
          <span className="sidebar-status-text">
            <span className="c-subtext clamp3">{state.message}</span>
            <span className="c-accent">Try again</span>
          </span>
        </button>
      );
    case "connecting":
      return (
        <div className="sidebar-status">
          <span className="status-icon">
            <Spinner size={15} />
          </span>
          <span className="c-subtext">{connection.indexSynced ? "Reconnecting…" : "Connecting…"}</span>
        </div>
      );
    case "connected":
      if (!connection.indexSynced || connection.sessions.size > 0) return null;
      return (
        <button className="sidebar-status" onClick={onNew}>
          <span className="status-icon">
            <SquarePen size={15} className="c-overlay" />
          </span>
          <span className="c-subtext">Start a session</span>
        </button>
      );
    default:
      return null;
  }
}
