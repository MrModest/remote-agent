import RAKit
import SwiftUI

/// The iPad and Mac layout: every server's sessions in the sidebar, the
/// chosen session beside them, or in the Columns layout, a list of sessions
/// between the two. In a narrow iPad window it collapses into a stack. The
/// app has this one window, which opens again on the session that was open
/// last. It shows the sheets and confirmations the sidebars ask for.
struct ServerSplitView: View {
    @Environment(ServerStore.self) private var store
    @Binding var pendingLink: PairingLink?
    @AppStorage(SidebarLayout.key) private var layout = SidebarLayout.inbox
    /// The open session, as "<server id> <session id>", or "" for none.
    @AppStorage("openSession") private var openSession = ""
    /// The Columns layout's collection, as `SessionCollection.stored`.
    @AppStorage("sessionCollection") private var openCollection = ""
    @State private var showAdd = false
    @State private var newSession: NewSessionRequest?
    @State private var archiving: SessionRequest?
    @State private var deleting: SessionRequest?
    @State private var removing: SavedServer?
    #if os(macOS)
    @Environment(Notifier.self) private var notifier
    #endif

    /// The open session, while its server is still paired.
    private var route: SessionRoute? {
        let ids = openSession.split(separator: " ")
        guard ids.count == 2, store.servers.contains(where: { $0.id == ids[0] }) else { return nil }
        return SessionRoute(serverID: String(ids[0]), sessionID: String(ids[1]))
    }

    private var selection: Binding<SessionRoute?> {
        Binding { route } set: { openSession = $0.map { "\($0.serverID) \($0.sessionID)" } ?? "" }
    }

    private var collection: Binding<SessionCollection?> {
        Binding { SessionCollection(stored: openCollection) ?? .all } set: { openCollection = $0?.stored ?? "" }
    }

    private func connection(_ route: SessionRoute) -> ServerConnection? {
        store.servers.first { $0.id == route.serverID }.map { store.connection(for: $0) }
    }

    /// The open session is no longer on the server. Only the synced index can
    /// tell: before it, every session looks missing.
    private var openSessionGone: Bool {
        guard let route, let connection = connection(route) else { return false }
        return connection.indexSynced && connection.sessions[route.sessionID] == nil
    }

    /// The Columns layout's project is no longer on the server, as when it was
    /// forgotten, here or on another device. Only the synced index can tell.
    private var collectionProjectGone: Bool {
        guard case .project(let serverID, let projectID) = collection.wrappedValue,
              let server = store.servers.first(where: { $0.id == serverID }) else { return false }
        let c = store.connection(for: server)
        return c.indexSynced && c.projects[projectID] == nil
    }

    /// Where a new session starts from the menu bar, as with the New Session
    /// button: in the Columns layout's project, else on the open session's
    /// server, else on the first one connected.
    private var newSessionTarget: NewSessionRequest? {
        store.newSessionTarget(in: layout == .columns ? collection.wrappedValue : nil, beside: route)
    }

    var body: some View {
        split
            .environment(\.sessionActions, SessionActions(
                startSession: { newSession = $0 },
                archive: { archiving = $0 },
                delete: { deleting = $0 },
                remove: { removing = $0 }))
            .sheet(item: $newSession) { request in
                NewSessionView(connection: request.connection, projectID: request.projectID) { session in
                    selection.wrappedValue = SessionRoute(serverID: request.connection.server.id, sessionID: session.id)
                }
                .appStyle()
            }
            .sheet(isPresented: $showAdd) {
                AddServerView(initialLink: pendingLink) { _ in }
                    .appStyle()
            }
            // Every server connects; one that was given up on waits for a click.
            .task(id: store.servers.map(\.id)) {
                for c in store.connections where c.state == .idle { c.start() }
            }
            .focusedSceneValue(\.newSession, newSessionTarget.map { target in
                Binding { newSession != nil } set: { newSession = $0 ? target : nil }
            })
            .focusedSceneValue(\.pairServer, $showAdd)
            .onChange(of: pendingLink) { _, link in
                if link != nil { showAdd = true }
            }
            .onChange(of: showAdd) { _, shown in
                if !shown { pendingLink = nil }
            }
            .onChange(of: openSessionGone, initial: true) { _, gone in
                // Landing on a session that is gone shows nothing rather than an error.
                if gone { openSession = "" }
            }
            .onChange(of: revealKey, initial: true) { _, _ in reveal() }
            .onChange(of: collectionProjectGone, initial: true) { _, gone in
                if gone { collection.wrappedValue = .all }
            }
            #if os(macOS)
            .onChange(of: route, initial: true) { _, route in notifier.shown = route }
            .onChange(of: notifier.opened, initial: true) { _, route in
                // A click on a notification, which may have launched the app.
                guard let route else { return }
                selection.wrappedValue = route
                notifier.opened = nil
            }
            #endif
    }

    /// The confirmations go on the column their rows are in, which an iPad
    /// points its popover at.
    @ViewBuilder private var split: some View {
        switch layout {
        case .columns:
            NavigationSplitView {
                CollectionsSidebar(collection: collection, showAdd: $showAdd)
                    .removeServerDialog($removing)
                    #if os(macOS)
                    .navigationSplitViewColumnWidth(min: 180, ideal: 210, max: 300)
                    #else
                    .navigationSplitViewColumnWidth(min: 260, ideal: 300, max: 360)
                    #endif
            } content: {
                SessionListColumn(collection: collection.wrappedValue ?? .all, selection: selection)
                    .archiveSessionDialog($archiving)
                    .deleteSessionDialog($deleting)
                    #if os(macOS)
                    .navigationSplitViewColumnWidth(min: 260, ideal: 320, max: 460)
                    #else
                    .navigationSplitViewColumnWidth(min: 300, ideal: 360, max: 460)
                    #endif
            } detail: {
                detail
            }
        case .inbox, .projects:
            NavigationSplitView {
                SessionsSidebar(layout: layout, selection: selection, showAdd: $showAdd)
                    .archiveSessionDialog($archiving)
                    .deleteSessionDialog($deleting)
                    .removeServerDialog($removing)
                    #if os(macOS)
                    .navigationSplitViewColumnWidth(min: 240, ideal: 300, max: 420)
                    #else
                    // As wide as an iPhone, which the rows and toolbar were laid out for.
                    .navigationSplitViewColumnWidth(min: 320, ideal: 390, max: 480)
                    #endif
            } detail: {
                detail
            }
        }
    }

    private var detail: some View {
        Group {
            if let route, let connection = connection(route), !openSessionGone {
                // A new identity per session, so the attachments and the subscription don't carry over.
                SessionView(connection: connection, store: connection.sessionStore(route.sessionID))
                    .id(route)
            } else if store.servers.isEmpty {
                ContentUnavailableView {
                    Label("No servers", systemImage: "desktopcomputer")
                } description: {
                    #if os(macOS)
                    Text("Run `rad serve` on your computer, then `rad pair`, and paste the link it prints.")
                    #else
                    Text("Run `rad serve` on your computer, then `rad pair` and scan the QR code.")
                    #endif
                } actions: {
                    Button { showAdd = true } label: {
                        Text("Pair a server").foregroundStyle(Palette.onAccent)
                    }
                    .buttonStyle(.borderedProminent)
                }
            } else {
                ContentUnavailableView("No session selected", systemImage: "bubble.left.and.text.bubble.right",
                                       description: Text("Choose a session in the sidebar, or start a new one."))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.base)
        .environment(\.inlineSessionTitle, layout == .columns)
    }

    /// The open session and whether it is archived, once the index has it.
    private var revealKey: String? {
        guard layout == .columns, let route, let s = connection(route)?.sessions[route.sessionID] else { return nil }
        return "\(route.serverID) \(route.sessionID) \(s.archived)"
    }

    /// In three columns, a session opened from elsewhere, such as the one open
    /// last, a notification or a new session, shows in the list: when the
    /// collection doesn't have it, All Sessions or Archived does.
    private func reveal() {
        guard layout == .columns, let route, let s = connection(route)?.sessions[route.sessionID] else { return }
        let current = collection.wrappedValue ?? .all
        if !current.sessions(in: store).contains(where: { $0.id == route }) {
            collection.wrappedValue = s.archived ? .archived : .all
        }
    }
}
