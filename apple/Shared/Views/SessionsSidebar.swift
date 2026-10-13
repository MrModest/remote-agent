import RAKit
import SwiftUI

/// The iPad and Mac sidebar: every paired server, each with its sessions.
/// A click on a server's name shows or hides them, or, once the connection
/// has given up, tries again.
struct SessionsSidebar: View {
    @Environment(ServerStore.self) private var store
    @Binding var selection: SessionRoute?
    @Binding var showAdd: Bool
    @State private var newSession: NewSessionRequest?
    @State private var archiving: ArchiveRequest?
    @State private var removing: SavedServer?
    /// The servers shown with their archived sessions.
    @State private var showArchived: Set<String> = []
    /// The servers whose sessions are hidden, by id, separated by spaces.
    @AppStorage("collapsedServers") private var collapsedIDs = ""

    var body: some View {
        List(selection: $selection) {
            ForEach(store.servers) { server in
                ServerSection(
                    connection: store.connection(for: server),
                    selection: selection,
                    expanded: expanded(server.id),
                    showArchived: archivedShown(server.id),
                    newSession: { newSession = NewSessionRequest(connection: $0) },
                    archive: { archiving = $0 },
                    remove: { removing = $0 }
                )
            }
        }
        #if os(macOS)
        .listStyle(.sidebar)
        #else
        .listStyle(.plain)
        #endif
        .listBackground(Palette.mantle)
        .navigationTitle("Sessions")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                newSessionButton
            }
            #if os(iOS)
            // On the Mac these are at the foot of the sidebar and in the app menu.
            ToolbarItem(placement: .secondaryAction) {
                Button("Pair a server", systemImage: "plus") { showAdd = true }
            }
            ToolbarItem(placement: .secondaryAction) {
                SettingsButton()
            }
            #endif
        }
        #if os(macOS)
        .safeAreaInset(edge: .bottom, spacing: 0) {
            Button { showAdd = true } label: {
                Label("Pair a server", systemImage: "plus")
                    .scaledFont(.body)
                    .foregroundStyle(Palette.subtext)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }
        #endif
        .focusedSceneValue(\.newSession, defaultServer.map { c in
            Binding { newSession != nil } set: { newSession = $0 ? NewSessionRequest(connection: c) : nil }
        })
        .sheet(item: $newSession) { request in
            NewSessionView(connection: request.connection) { session in
                selection = SessionRoute(serverID: request.connection.server.id, sessionID: session.id)
            }
            .appStyle()
        }
        .archiveSessionDialog($archiving)
        .removeServerDialog($removing)
        .refreshable {
            for c in connections {
                if c.state == .connected { await c.refreshHarnesses(force: true) } else { c.retry() }
            }
        }
        // Every server connects; one that was given up on waits for a click.
        .task(id: store.servers.map(\.id)) {
            for c in connections where c.state == .idle { c.start() }
        }
        .onChange(of: selectionArchived, initial: true) { _, archived in
            // Landing on an archived session, such as the one open last, shows its row.
            if archived, let id = selection?.serverID { showArchived.insert(id) }
        }
    }

    private var connections: [ServerConnection] {
        store.servers.map { store.connection(for: $0) }
    }

    /// The open session is archived, which only the synced index can tell.
    private var selectionArchived: Bool {
        guard let route = selection else { return false }
        let c = connections.first { $0.server.id == route.serverID }
        return c?.sessions[route.sessionID]?.archived == true
    }

    /// Where a new session starts: on the open session's server, else on
    /// the first one connected.
    private var defaultServer: ServerConnection? {
        let connected = connections.filter { $0.state == .connected }
        return connected.first { $0.server.id == selection?.serverID } ?? connected.first
    }

    @ViewBuilder private var newSessionButton: some View {
        let connected = connections.filter { $0.state == .connected }
        if connected.count > 1, let first = defaultServer {
            Menu {
                ForEach(connected, id: \.server.id) { c in
                    Button("On \(c.server.name)") { newSession = NewSessionRequest(connection: c) }
                }
            } label: {
                Label("New session", systemImage: "square.and.pencil")
            } primaryAction: {
                newSession = NewSessionRequest(connection: first)
            }
            .help("New session on \(first.server.name)")
            #if os(iOS)
            .keyboardShortcut("n") // On the Mac, File › New Session has it.
            #endif
        } else {
            Button("New session", systemImage: "square.and.pencil") {
                if let first = defaultServer { newSession = NewSessionRequest(connection: first) }
            }
            #if os(iOS)
            .keyboardShortcut("n")
            #endif
            .disabled(defaultServer == nil)
        }
    }

    private func expanded(_ id: String) -> Binding<Bool> {
        Binding {
            !collapsedIDs.split(separator: " ").contains(Substring(id))
        } set: { shown in
            var ids = collapsedIDs.split(separator: " ").map(String.init).filter { $0 != id }
            if !shown { ids.append(id) }
            // Forgets servers that were removed.
            collapsedIDs = ids.filter { id in store.servers.contains { $0.id == id } }.joined(separator: " ")
        }
    }

    private func archivedShown(_ id: String) -> Binding<Bool> {
        Binding {
            showArchived.contains(id)
        } set: { shown in
            if shown { showArchived.insert(id) } else { showArchived.remove(id) }
        }
    }
}

private struct NewSessionRequest: Identifiable {
    let id = UUID()
    let connection: ServerConnection
}

/// One server in the sidebar: its header, how its connection is doing, and
/// its sessions, those waiting for the user first.
private struct ServerSection: View {
    let connection: ServerConnection
    let selection: SessionRoute?
    @Binding var expanded: Bool
    @Binding var showArchived: Bool
    let newSession: (ServerConnection) -> Void
    let archive: (ArchiveRequest) -> Void
    let remove: (SavedServer) -> Void

    var body: some View {
        Section(isExpanded: $expanded) {
            status
            let active = connection.activeSessions
            ForEach(active.filter { $0.status == .awaitingApproval } + active.filter { $0.status != .awaitingApproval }) {
                row($0)
            }
            let archived = connection.archivedSessions
            if !archived.isEmpty {
                Button {
                    withAnimation(.snappy(duration: 0.2)) { showArchived.toggle() }
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "archivebox")
                            .foregroundStyle(Palette.overlay)
                            .frame(width: 18)
                        Text("Archived")
                        Text("\(archived.count)")
                            .foregroundStyle(Palette.overlay)
                            .monospacedDigit()
                        Spacer(minLength: 0)
                        Image(systemName: "chevron.right")
                            .scaledFont(.caption)
                            .foregroundStyle(Palette.overlay)
                            .rotationEffect(.degrees(showArchived ? 90 : 0))
                    }
                    .foregroundStyle(Palette.subtext)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .scaledFont(.body)
                .accessibilityValue(showArchived ? "Shown" : "Hidden")
                .paletteRows(Palette.mantle)
                if showArchived {
                    ForEach(archived) { row($0) }
                }
            }
        } header: {
            ServerHeader(connection: connection, expanded: $expanded,
                         newSession: { newSession(connection) }, remove: { remove(connection.server) })
        }
    }

    /// While it isn't connected, why; and once it has given up, a way to try again.
    @ViewBuilder private var status: some View {
        switch connection.state {
        case .failed(let msg):
            Button { connection.retry() } label: {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "wifi.exclamationmark")
                        .foregroundStyle(Palette.red)
                        .frame(width: 18)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(msg)
                            .foregroundStyle(Palette.subtext)
                            .lineLimit(3)
                        Text("Try again")
                            .foregroundStyle(Palette.accent)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .scaledFont(.body)
            .paletteRows(Palette.mantle)
        case .connecting:
            HStack(spacing: 10) {
                ProgressView()
                    .controlSize(.small)
                    .frame(width: 18)
                Text(connection.indexSynced ? "Reconnecting…" : "Connecting…")
                    .foregroundStyle(Palette.subtext)
            }
            .scaledFont(.body)
            .paletteRows(Palette.mantle)
        case .connected where connection.indexSynced && connection.sessions.isEmpty:
            Button { newSession(connection) } label: {
                HStack(spacing: 10) {
                    Image(systemName: "square.and.pencil")
                        .foregroundStyle(Palette.overlay)
                        .frame(width: 18)
                    Text("Start a session")
                        .foregroundStyle(Palette.subtext)
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .scaledFont(.body)
            .paletteRows(Palette.mantle)
        default:
            EmptyView()
        }
    }

    private func row(_ s: Session) -> some View {
        let route = SessionRoute(serverID: connection.server.id, sessionID: s.id)
        return NavigationLink(value: route) {
            SessionRow(session: s, project: connection.projects[s.projectId], harness: connection.harness(s.harness))
                // The selection drawn below is a surface, not the system's
                // accent, so the row keeps the palette's text colours on it.
                .environment(\.backgroundProminence, .standard)
        }
        #if os(iOS)
        .listRowInsets(Metrics.rowInsets)
        #endif
        .listRowBackground(rowBackground(selected: selection == route))
        .swipeActions {
            if !s.archived {
                Button("Archive", systemImage: "archivebox") { archive(ArchiveRequest(session: s, connection: connection)) }
                    .tint(Palette.blue)
            }
        }
        .contextMenu {
            if !s.archived {
                Button("Archive…", systemImage: "archivebox") { archive(ArchiveRequest(session: s, connection: connection)) }
            }
        }
    }

    /// The sidebar's colour, which iOS would otherwise cover with the system's.
    /// It also covers the selection, which the sidebar draws itself in a
    /// surface colour: the Mac's would be the system accent.
    private func rowBackground(selected: Bool) -> some View {
        Palette.mantle.overlay {
            if selected {
                RoundedRectangle(cornerRadius: Metrics.Corner.selection)
                    .fill(Palette.surface0)
                    .padding(.horizontal, 8)
            }
        }
    }
}

/// A server's section header: its name and connection, and while its sessions
/// are hidden, how many there are and whether one is waiting for the user.
/// A click shows or hides them, as the Mac sidebar's chevron does, or tries
/// again once the connection has given up. A + starts a session on the server
/// (on the Mac, on hover). Its other actions are in a ⋯ menu on iPad, and in
/// the context menu on the Mac.
private struct ServerHeader: View {
    let connection: ServerConnection
    @Binding var expanded: Bool
    let newSession: () -> Void
    let remove: () -> Void
    @State private var hovering = false

    private var name: String { connection.server.name }
    private var offline: Bool {
        if case .failed = connection.state { true } else { false }
    }
    private var connected: Bool { connection.state == .connected }

    var body: some View {
        let active = connection.activeSessions
        let waiting = active.contains { $0.status == .awaitingApproval }
        HStack(spacing: 6) {
            Button(action: activate) {
                HStack(spacing: 6) {
                    Image(systemName: "desktopcomputer")
                        .foregroundStyle(Palette.overlay)
                        .frame(width: 18)
                    Text(name)
                        .foregroundStyle(Palette.text)
                        .lineLimit(1)
                    ConnectionDot(state: connection.state)
                        .scaleEffect(0.8)
                    if !expanded && !active.isEmpty {
                        Text("\(active.count)")
                            .foregroundStyle(Palette.overlay)
                            .monospacedDigit()
                    }
                    if !expanded && waiting {
                        Image(systemName: "hand.raised.fill")
                            .scaledFont(.caption)
                            .foregroundStyle(Palette.yellow)
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help(offline ? "Try to connect to \(name) again" : ConnectionDot(state: connection.state).label)
            .accessibilityLabel(name)
            .accessibilityValue(accessibilityValue(count: active.count, waiting: waiting))
            .accessibilityHint(offline ? "Tries to connect again" : "")

            if connected {
                Button("New session on \(name)", systemImage: "plus", action: newSession)
                    .labelStyle(.iconOnly)
                    .buttonStyle(.plain)
                    .foregroundStyle(Palette.subtext)
                    .help("New session on \(name)")
                    #if os(macOS)
                    .opacity(hovering ? 1 : 0)
                    #endif
            }
            #if os(iOS)
            // A long press goes to the button, so touch gets the actions here.
            Menu { actions } label: {
                Label("\(name) actions", systemImage: "ellipsis.circle")
                    .labelStyle(.iconOnly)
            }
            .foregroundStyle(Palette.subtext)
            #endif
        }
        .scaledFont(.body, weight: .semibold)
        .onHover { hovering = $0 }
        #if os(macOS)
        .contextMenu { actions }
        #endif
        // The header is one element to VoiceOver, which can't hover.
        .accessibilityActions {
            if connected {
                Button("New session on \(name)", action: newSession)
            }
            Button("Reconnect") { connection.retry() }
        }
    }

    @ViewBuilder private var actions: some View {
        Button("New Session on \(name)", systemImage: "plus", action: newSession)
            .disabled(!connected)
        Button("Reconnect", systemImage: "arrow.clockwise") { connection.retry() }
        Divider()
        Button("Remove \(name)…", systemImage: "trash", role: .destructive, action: remove)
    }

    private func accessibilityValue(count: Int, waiting: Bool) -> String {
        var parts = [ConnectionDot(state: connection.state).label]
        parts.append(expanded ? "Expanded" : "Collapsed, \(count) sessions")
        if !expanded && waiting { parts.append("Needs you") }
        return parts.joined(separator: ", ")
    }

    /// Shows or hides the sessions; once the connection has given up, tries again.
    private func activate() {
        if offline {
            connection.retry()
            withAnimation(.snappy(duration: 0.2)) { expanded = true }
        } else {
            withAnimation(.snappy(duration: 0.2)) { expanded.toggle() }
        }
    }
}
