import RAKit
import SwiftUI

/// A server's sessions. On iPhone it is pushed and pushes sessions; in the
/// iPad and Mac sidebar, selection says which session the detail column shows.
struct ServerHomeView: View {
    let connection: ServerConnection
    var selection: Binding<SessionRoute?>?
    @Environment(\.dismiss) private var dismiss
    @State private var removing: SavedServer?
    /// The New session sheet. It carries the project the session starts in
    /// when it is opened from the project's header.
    @State private var newSessionSheet: NewSessionSheet?
    @State private var openSession: String?
    @State private var showArchived = false
    @State private var collapsedProjects: Set<String> = []
    @State private var archiveTarget: Session?
    #if os(macOS)
    @AppStorage(SessionGrouping.key) private var grouping = SessionGrouping.status
    #else
    private let grouping = SessionGrouping.status
    #endif

    var body: some View {
        Group {
            if let selection {
                List(selection: selection) { sections }
            } else {
                List { sections }
            }
        }
        #if os(macOS)
        .listStyle(.sidebar)
        #else
        .listStyle(.plain)
        #endif
        .listBackground(listColor)
        .overlay {
            if connection.indexSynced && connection.sessions.isEmpty {
                ContentUnavailableView {
                    Label("No sessions yet", systemImage: "bubble.left.and.text.bubble.right")
                } description: {
                    Text("Start an agent in one of your projects.")
                } actions: {
                    Button { newSessionSheet = NewSessionSheet() } label: {
                        Text("New session").foregroundStyle(Palette.onAccent)
                    }
                    .buttonStyle(.borderedProminent)
                }
            } else if !connection.indexSynced && connection.state == .connecting {
                ProgressView("Connecting…")
            }
        }
        .navigationTitle(connection.server.name)
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button("New session", systemImage: "square.and.pencil") { newSessionSheet = NewSessionSheet() }
                    #if os(iOS)
                    .keyboardShortcut("n") // On the Mac, File › New Session has it.
                    #endif
                    .disabled(connection.state != .connected)
            }
            #if os(macOS)
            // Also under View › Group Sessions By.
            ToolbarItem(placement: .primaryAction) {
                Menu("Group sessions", systemImage: "list.bullet.indent") {
                    SessionGrouping.Picker(grouping: $grouping)
                        .pickerStyle(.inline)
                }
                .menuIndicator(.hidden)
            }
            #endif
            // On the Mac these are in the server menu at the foot of the
            // sidebar, and Settings is in the app menu.
            #if os(iOS)
            ToolbarItem(placement: .secondaryAction) {
                Button("Reconnect", systemImage: "arrow.clockwise") { connection.retry() }
            }
            ToolbarItem(placement: .secondaryAction) {
                SettingsButton()
            }
            ToolbarItem(placement: .secondaryAction) {
                Button("Remove server", systemImage: "trash", role: .destructive) { removing = connection.server }
            }
            #endif
        }
        .focusedSceneValue(\.newSession, connection.state == .connected ? showNew : nil)
        // In the sidebar there is nothing to go back to; the split view picks another server.
        .removeServerDialog($removing) { if selection == nil { dismiss() } }
        .sheet(item: $newSessionSheet) { sheet in
            NewSessionView(connection: connection, projectID: sheet.projectID) { session in
                if let selection {
                    selection.wrappedValue = SessionRoute(serverID: connection.server.id, sessionID: session.id)
                } else {
                    openSession = session.id
                }
            }
            .appStyle()
        }
        .confirmationDialog("Archive session?", isPresented: .init(get: { archiveTarget != nil }, set: { if !$0 { archiveTarget = nil } }),
                            presenting: archiveTarget) { s in
            Button("Archive") { archive(s, removeWorktree: false) }
            if s.workspace.kind == .worktree {
                Button("Archive and delete worktree", role: .destructive) { archive(s, removeWorktree: true) }
            }
        } message: { s in
            Text(s.workspace.kind == .worktree ? "The branch \(s.workspace.branch ?? "") is kept either way." : "The agent process is stopped.")
        }
        .refreshable {
            if connection.state != .connected { connection.retry() }
            await connection.refreshHarnesses(force: true)
        }
        .navigationDestination(item: $openSession) { id in
            SessionView(connection: connection, store: connection.sessionStore(id))
        }
        .task { connection.start() }
    }

    /// For File › New Session, which starts in the project used last.
    private var showNew: Binding<Bool> {
        Binding { newSessionSheet != nil } set: { newSessionSheet = $0 ? NewSessionSheet() : nil }
    }

    /// A sidebar on iPad and the Mac, the main view on iPhone.
    private var listColor: Color { selection == nil ? Palette.base : Palette.mantle }

    @ViewBuilder private var sections: some View {
        switch connection.state {
        case .failed(let msg):
            Section {
                Label(msg, systemImage: "wifi.exclamationmark")
                    .foregroundStyle(Palette.red)
                Button("Retry now") { connection.retry() }
            }
            .scaledFont(.body)
            .paletteRows(listColor)
        case .connecting where connection.indexSynced:
            Label("Reconnecting…", systemImage: "arrow.triangle.2.circlepath")
                .scaledFont(.body)
                .foregroundStyle(.secondary)
                .paletteRows(listColor)
        default:
            EmptyView()
        }

        let active = connection.activeSessions
        switch grouping {
        case .status:
            let attention = active.filter { $0.status == .awaitingApproval }
            if !attention.isEmpty {
                Section {
                    ForEach(attention) { row($0) }
                } header: {
                    Text("Needs you").listHeaderStyle()
                }
            }
            Section {
                ForEach(active.filter { $0.status != .awaitingApproval }) { row($0) }
            } header: {
                Text("Sessions").listHeaderStyle()
            }
        case .project:
            // The project is the header, so the rows leave it out.
            ForEach(projectGroups(active), id: \.id) { group in
                let expanded = expanded(group.id)
                Section(isExpanded: expanded) {
                    ForEach(group.sessions) { row($0, showsProject: false) }
                } header: {
                    GroupHeader(name: group.name, symbol: "folder", help: connection.projects[group.id]?.path,
                                count: group.sessions.count, expanded: expanded,
                                newSession: newSessionAction(in: group.id))
                }
            }
        case .flat:
            Section {
                ForEach(active) { row($0) }
            }
        }
        let archived = connection.archivedSessions
        if !archived.isEmpty {
            #if os(macOS)
            // Sidebar sections show their own disclosure control.
            Section(isExpanded: $showArchived) {
                ForEach(archived) { row($0) }
            } header: {
                if grouping == .project {
                    // Headed like the projects above it.
                    GroupHeader(name: "Archived", symbol: "archivebox", count: archived.count, expanded: $showArchived)
                } else {
                    Text("Archived \(Text("\(archived.count)").foregroundStyle(.tertiary))")
                        .listHeaderStyle()
                }
            }
            #else
            Section(isExpanded: $showArchived) {
                ForEach(archived) { row($0) }
            } header: {
                // Plain lists have no disclosure control of their own.
                Button {
                    withAnimation { showArchived.toggle() }
                } label: {
                    HStack {
                        Text("Archived")
                        Text("\(archived.count)").foregroundStyle(.tertiary)
                        Spacer()
                        Image(systemName: "chevron.right")
                            .rotationEffect(.degrees(showArchived ? 90 : 0))
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
            #endif
        }
    }

    /// Sessions by project, the projects by name. Each keeps the sessions'
    /// order, newest first.
    private func projectGroups(_ sessions: [Session]) -> [(id: String, name: String, sessions: [Session])] {
        Dictionary(grouping: sessions, by: \.projectId)
            .map { (id: $0.key, name: connection.projects[$0.key]?.name ?? $0.key, sessions: $0.value) }
            .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    /// Starts a session in the project, while the server is connected and knows it.
    private func newSessionAction(in projectID: String) -> (() -> Void)? {
        guard connection.state == .connected, connection.projects[projectID] != nil else { return nil }
        return { newSessionSheet = NewSessionSheet(projectID: projectID) }
    }

    private func expanded(_ projectID: String) -> Binding<Bool> {
        Binding {
            !collapsedProjects.contains(projectID)
        } set: { shown in
            if shown { collapsedProjects.remove(projectID) } else { collapsedProjects.insert(projectID) }
        }
    }

    private func row(_ s: Session, showsProject: Bool = true) -> some View {
        let route = SessionRoute(serverID: connection.server.id, sessionID: s.id)
        return NavigationLink(value: route) {
            SessionRow(session: s, project: showsProject ? connection.projects[s.projectId] : nil,
                       harness: connection.harness(s.harness))
                // The selection drawn below is a surface, not the system's
                // accent, so the row keeps the palette's text colours on it.
                .environment(\.backgroundProminence, .standard)
        }
        #if os(iOS)
        .listRowInsets(Metrics.rowInsets)
        #endif
        .listRowBackground(rowBackground(selected: selection?.wrappedValue == route))
        .swipeActions {
            if !s.archived {
                Button("Archive", systemImage: "archivebox") { archiveTarget = s }
                    .tint(Palette.blue)
            }
        }
        .contextMenu {
            if !s.archived {
                Button("Archive…", systemImage: "archivebox") { archiveTarget = s }
            }
        }
    }

    /// The list's colour, which iOS would otherwise cover with the system's.
    /// It also covers the selection, which the sidebar draws itself in a
    /// surface colour: the Mac's would be the system accent.
    private func rowBackground(selected: Bool) -> some View {
        listColor.overlay {
            if selected {
                RoundedRectangle(cornerRadius: Metrics.Corner.selection)
                    .fill(Palette.surface0)
                    .padding(.horizontal, 8)
            }
        }
    }

    private func archive(_ s: Session, removeWorktree: Bool) {
        Task { try? await connection.archive(s.id, removeWorktree: removeWorktree) }
    }
}

private struct NewSessionSheet: Identifiable {
    let id = UUID()
    var projectID = ""
}

struct SessionRow: View {
    let session: Session
    let project: Project?
    let harness: HarnessInfo?

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            StatusIcon(status: session.status, background: session.backgroundTasks?.isEmpty == false)
                .padding(.top, 1)
            VStack(alignment: .leading, spacing: 2) {
                Text(session.title)
                    .scaledFont(.body, weight: .medium)
                    .lineLimit(2)
                // The time sits on this line, so the title gets the full width.
                // Where the agent's name would leave the branch too little
                // room, its icon stands for it.
                ViewThatFits(in: .horizontal) {
                    details(agentName: true)
                    details(agentName: false)
                }
                .lineLimit(1)
                .scaledFont(.caption)
                .foregroundStyle(.secondary)
            }
        }
        .paletteText()
    }

    /// The project leads, the agent a step quieter after it.
    private func details(agentName: Bool) -> some View {
        HStack(spacing: 6) {
            if let project {
                Text(project.name)
                    .scaledFont(.caption, weight: .medium)
                    .fixedSize()
            }
            Group {
                if agentName {
                    HarnessBadge(id: session.harness, name: harness?.name)
                } else {
                    HarnessIcon(id: session.harness)
                        .imageScale(.small)
                        .help(harness?.name ?? session.harness)
                        .accessibilityLabel(harness?.name ?? session.harness)
                }
            }
            .foregroundStyle(.tertiary)
            .fixedSize()
            if session.workspace.kind == .worktree, let b = session.workspace.branch {
                HStack(spacing: 2) {
                    Image(systemName: "arrow.triangle.branch")
                    Text(b).truncationMode(.middle)
                }
            }
            Spacer(minLength: 4)
            Text(session.updatedAt, format: .relative(presentation: .named, unitsStyle: .narrow))
                .scaledFont(.caption2)
                .foregroundStyle(.tertiary)
                .fixedSize()
        }
    }
}

/// A project's section header in the Mac sidebar, or the archive's below
/// them. A click shows or hides its sessions, as the sidebar's chevron does;
/// on hover, a project's + starts a session in it.
private struct GroupHeader: View {
    let name: String
    let symbol: String
    var help: String?
    let count: Int
    @Binding var expanded: Bool
    var newSession: (() -> Void)?
    @State private var hovering = false

    var body: some View {
        HStack(spacing: 6) {
            Button {
                withAnimation(.snappy(duration: 0.2)) { expanded.toggle() }
            } label: {
                HStack(spacing: 6) {
                    Image(systemName: symbol)
                        .foregroundStyle(Palette.overlay)
                        .frame(width: 18)
                    Text(name)
                        .foregroundStyle(Palette.text)
                        .lineLimit(1)
                    if !expanded {
                        Text("\(count)")
                            .foregroundStyle(Palette.overlay)
                            .monospacedDigit()
                    }
                    Spacer(minLength: 0)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .help(help ?? name)
            .accessibilityLabel(name)
            .accessibilityValue(expanded ? "Expanded" : "Collapsed, \(count) sessions")

            if let newSession {
                Button("New session in \(name)", systemImage: "plus", action: newSession)
                    .labelStyle(.iconOnly)
                    .buttonStyle(.plain)
                    .foregroundStyle(Palette.subtext)
                    .help("New session in \(name)")
                    .opacity(hovering ? 1 : 0)
            }
        }
        .scaledFont(.body, weight: .semibold)
        .onHover { hovering = $0 }
        .contextMenu {
            if let newSession {
                Button("New Session in \(name)", systemImage: "plus", action: newSession)
            }
        }
        // The header is one element to VoiceOver, which can't hover.
        .accessibilityActions {
            if let newSession {
                Button("New session in \(name)", action: newSession)
            }
        }
    }
}

/// How the Mac sidebar sections the active sessions. Archived sessions keep
/// their own section below.
enum SessionGrouping: String, CaseIterable, Identifiable {
    /// Those waiting for an approval, then the rest.
    case status
    case project
    case flat

    static let key = "sessionGrouping"

    var id: Self { self }

    var title: String {
        switch self {
        case .status: "Status"
        case .project: "Project"
        case .flat: "None"
        }
    }

    /// The choices, for the View menu and the sidebar's toolbar.
    struct Picker: View {
        @Binding var grouping: SessionGrouping

        var body: some View {
            SwiftUI.Picker("Group Sessions By", selection: $grouping) {
                ForEach(SessionGrouping.allCases) { Text($0.title).tag($0) }
            }
        }
    }
}

/// Navigation value for a session on a given server.
struct SessionRoute: Hashable {
    let serverID: String
    let sessionID: String
}

extension View {
    /// Confirmation before forgetting a server.
    func removeServerDialog(_ server: Binding<SavedServer?>, onRemove: @escaping () -> Void = {}) -> some View {
        modifier(RemoveServerDialog(server: server, onRemove: onRemove))
    }
}

private struct RemoveServerDialog: ViewModifier {
    @Environment(ServerStore.self) private var store
    @Binding var server: SavedServer?
    var onRemove: () -> Void

    func body(content: Content) -> some View {
        content.confirmationDialog(
            "Remove \(server?.name ?? "server")?",
            isPresented: .init(get: { server != nil }, set: { if !$0 { server = nil } }),
            titleVisibility: .visible, presenting: server
        ) { s in
            Button("Remove", role: .destructive) {
                onRemove()
                store.remove(s)
            }
        } message: { _ in
            Text("This device is unpaired from the server. Run `rad pair` on the computer to add it again.")
        }
    }
}
