import RAKit
import SwiftUI

/// The Projects layout: each server a section, its projects folding open to
/// their sessions, a line each, what needs you first. The projects come in
/// order of their latest session, those without any by name after them; a
/// server's archived sessions fold up last.
struct ProjectsSidebar: View {
    @Environment(ServerStore.self) private var store
    @Binding var selection: SessionRoute?
    private var collapsed = CollapsedServers()
    /// The projects folded shut, as "<server id>/<project id>", separated by spaces.
    @AppStorage("collapsedProjects") private var collapsedProjects = ""
    /// The servers shown with their archived sessions.
    @State private var showArchived: Set<String> = []

    init(selection: Binding<SessionRoute?>) {
        _selection = selection
    }

    var body: some View {
        List(selection: $selection) {
            ForEach(store.connections, id: \.server.id) { c in
                let expanded = collapsed.expanded(c.server.id, among: store.servers)
                Section(isExpanded: expanded) {
                    ConnectionStatusRow(connection: c)
                    ForEach(groups(c), id: \.projectID) { group in
                        let open = projectExpanded(c.server.id, group.projectID)
                        let label = ProjectLabel(name: group.name, count: group.items.count,
                                                 waiting: !open.wrappedValue && group.items.contains(where: \.session.needsYou))
                            // The whole row, past the name, takes a right-click.
                            .contentShape(Rectangle())
                            .contextMenu { ProjectMenuItems(connection: c, projectID: group.projectID) }
                        if group.items.isEmpty {
                            // Nothing to fold open.
                            label.paletteRows(Palette.mantle)
                        } else {
                            DisclosureGroup(isExpanded: open) {
                                rows(group.items)
                            } label: {
                                label
                            }
                            .paletteRows(Palette.mantle)
                        }
                    }
                    let archived = c.archivedSessions.map { ServerSession(connection: c, session: $0) }
                    if !archived.isEmpty {
                        DisclosureGroup(isExpanded: archivedShown(c.server.id)) {
                            rows(archived)
                        } label: {
                            HStack(spacing: 8) {
                                Image(systemName: "archivebox")
                                    .frame(width: 16)
                                Text("Archived")
                                Spacer(minLength: 0)
                                Text("\(archived.count)")
                                    .monospacedDigit()
                            }
                            .scaledFont(.callout)
                            .foregroundStyle(Palette.overlay)
                        }
                        .paletteRows(Palette.mantle)
                    }
                } header: {
                    ServerHeader(connection: c, expanded: expanded)
                }
            }
        }
        // Again once the index arrives: before it, the open session can't be found.
        .onChange(of: selectedPlace, initial: true) { _, _ in reveal(selection) }
    }

    private func rows(_ items: [ServerSession]) -> some View {
        ForEach(items) { item in
            SessionLink(item: item, selected: selection == item.id) {
                TreeRow(item: item)
            }
        }
    }

    private struct ProjectGroup {
        let projectID: String
        let name: String
        let items: [ServerSession]
    }

    /// The server's projects: those with sessions, the one worked in last
    /// first, then the others by name. In each, what needs you, then the latest.
    private func groups(_ c: ServerConnection) -> [ProjectGroup] {
        var order: [String] = []
        var byProject: [String: [ServerSession]] = [:]
        for s in c.activeSessions {
            if byProject[s.projectId] == nil { order.append(s.projectId) }
            byProject[s.projectId, default: []].append(ServerSession(connection: c, session: s))
        }
        let worked = order.map { id in
            let items = byProject[id] ?? []
            return ProjectGroup(projectID: id, name: c.projects[id]?.name ?? "Project",
                                items: items.filter(\.session.needsYou) + items.filter { !$0.session.needsYou })
        }
        return worked + c.sortedProjects.filter { byProject[$0.id] == nil }
            .map { ProjectGroup(projectID: $0.id, name: $0.name, items: []) }
    }

    private func projectExpanded(_ server: String, _ project: String) -> Binding<Bool> {
        let key = "\(server)/\(project)"
        return Binding {
            !collapsedProjects.split(separator: " ").contains(Substring(key))
        } set: { shown in
            var keys = collapsedProjects.split(separator: " ").map(String.init).filter { $0 != key }
            if !shown { keys.append(key) }
            collapsedProjects = keys.joined(separator: " ")
        }
    }

    private func archivedShown(_ id: String) -> Binding<Bool> {
        Binding {
            showArchived.contains(id)
        } set: { shown in
            if shown { showArchived.insert(id) } else { showArchived.remove(id) }
        }
    }

    /// Where the open session is: its server, project and whether it is archived.
    private var selectedPlace: String? {
        guard let route = selection, let server = store.servers.first(where: { $0.id == route.serverID }),
              let s = store.connection(for: server).sessions[route.sessionID] else { return nil }
        return "\(server.id) \(s.projectId) \(s.archived)"
    }

    /// Landing on a session, such as the one open last or one a notification
    /// opened, unfolds its server, its project or the archive to show its row.
    private func reveal(_ route: SessionRoute?) {
        guard let route, let server = store.servers.first(where: { $0.id == route.serverID }),
              let s = store.connection(for: server).sessions[route.sessionID] else { return }
        collapsed.expanded(server.id, among: store.servers).wrappedValue = true
        if s.archived {
            showArchived.insert(server.id)
        } else {
            projectExpanded(server.id, s.projectId).wrappedValue = true
        }
    }
}

/// A project in the Projects layout: its name and how many sessions it has;
/// while folded shut, whether one needs you.
private struct ProjectLabel: View {
    let name: String
    let count: Int
    let waiting: Bool

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "folder")
                .foregroundStyle(Palette.accent)
                .frame(width: 16)
            Text(name)
                .foregroundStyle(Palette.text)
                .lineLimit(1)
            if waiting {
                Image(systemName: "hand.raised.fill")
                    .scaledFont(.caption)
                    .foregroundStyle(Palette.yellow)
                    .accessibilityLabel("Needs you")
            }
            Spacer(minLength: 4)
            Text("\(count)")
                .scaledFont(.caption)
                .foregroundStyle(Palette.overlay)
                .monospacedDigit()
        }
        .scaledFont(.body, weight: .medium)
    }
}
