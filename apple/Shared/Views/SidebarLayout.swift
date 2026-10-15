import RAKit
import SwiftUI

/// How the iPad and the Mac lay out sessions: chosen in View › Sidebar on the
/// Mac, and in Settings.
enum SidebarLayout: String, CaseIterable, Identifiable {
    /// Every server's sessions by state: what needs you, what runs, the rest.
    case inbox
    /// Each server's projects, folding open to their sessions.
    case projects
    /// Mail's three columns: collections, their sessions, the transcript.
    case columns

    static let key = "sidebarLayout"

    var id: Self { self }

    var title: String {
        switch self {
        case .inbox: "Inbox"
        case .projects: "Projects"
        case .columns: "Columns"
        }
    }

    var summary: String {
        switch self {
        case .inbox: "Sessions from every server, those that need you first."
        case .projects: "Each server's projects, with their sessions under them."
        case .columns: "Needs You, Running and each project in the sidebar, their sessions beside it."
        }
    }
}

/// What a sidebar's rows and menus ask of the split view, which shows the
/// sheets and confirmations for every layout.
struct SessionActions {
    var newSession: (ServerConnection) -> Void = { _ in }
    var archive: (ArchiveRequest) -> Void = { _ in }
    var remove: (SavedServer) -> Void = { _ in }
}

extension EnvironmentValues {
    @Entry var sessionActions = SessionActions()
    /// The session's title heads its transcript: in three columns the Mac's
    /// toolbar title is the session list's, as in Mail.
    @Entry var inlineSessionTitle = false
}

/// A session and the server it is on, for the lists that mix servers.
struct ServerSession: Identifiable {
    let connection: ServerConnection
    let session: Session
    let id: SessionRoute

    @MainActor init(connection: ServerConnection, session: Session) {
        self.connection = connection
        self.session = session
        id = SessionRoute(serverID: connection.server.id, sessionID: session.id)
    }

    @MainActor var project: Project? { connection.projects[session.projectId] }
}

extension Session {
    /// Waiting for an answer, or failed: what a person has to look at.
    var needsYou: Bool { status == .awaitingApproval || status == .error }
    /// Working, or done but with tasks left running in the background.
    var isBusy: Bool { status == .running || (status == .idle && backgroundTasks?.isEmpty == false) }
}

extension ServerStore {
    var connections: [ServerConnection] { servers.map { connection(for: $0) } }

    /// Every server's sessions, the latest first.
    func sessions(archived: Bool) -> [ServerSession] {
        connections
            .flatMap { c in (archived ? c.archivedSessions : c.activeSessions).map { ServerSession(connection: c, session: $0) } }
            .sorted { $0.session.updatedAt > $1.session.updatedAt }
    }

    /// Tries again where a connection gave up, and asks the others for their agents.
    func refreshConnections() async {
        for c in connections {
            if c.state == .connected { await c.refreshHarnesses(force: true) } else { c.retry() }
        }
    }
}

/// What the sidebar of the three-column layout shows the sessions of.
enum SessionCollection: Hashable {
    case needsYou, running, all, archived
    case project(serverID: String, projectID: String)

    /// As stored: "needsYou", or "project <server id> <project id>".
    init?(stored: String) {
        let parts = stored.split(separator: " ").map(String.init)
        switch parts.first {
        case "needsYou": self = .needsYou
        case "running": self = .running
        case "all": self = .all
        case "archived": self = .archived
        case "project" where parts.count == 3: self = .project(serverID: parts[1], projectID: parts[2])
        default: return nil
        }
    }

    var stored: String {
        switch self {
        case .needsYou: "needsYou"
        case .running: "running"
        case .all: "all"
        case .archived: "archived"
        case .project(let server, let project): "project \(server) \(project)"
        }
    }

    @MainActor func sessions(in store: ServerStore) -> [ServerSession] {
        switch self {
        case .needsYou: store.sessions(archived: false).filter(\.session.needsYou)
        case .running: store.sessions(archived: false).filter(\.session.isBusy)
        case .all:
            // What needs you first, as in the other layouts.
            store.sessions(archived: false).filter(\.session.needsYou) + store.sessions(archived: false).filter { !$0.session.needsYou }
        case .archived: store.sessions(archived: true)
        case .project(let server, let project):
            store.sessions(archived: false).filter { $0.connection.server.id == server && $0.session.projectId == project }
        }
    }

    @MainActor func title(in store: ServerStore) -> String {
        switch self {
        case .needsYou: "Needs You"
        case .running: "Running"
        case .all: "All Sessions"
        case .archived: "Archived"
        case .project(let server, let project):
            store.servers.first { $0.id == server }.flatMap { store.connection(for: $0).projects[project]?.name } ?? "Project"
        }
    }
}
