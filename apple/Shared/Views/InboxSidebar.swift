import RAKit
import SwiftUI

/// The Inbox layout: every server's sessions by state. What needs you comes
/// first, with any server that gave up connecting; then what is working, then
/// the rest, the latest first in each. The servers are a section at the end,
/// one line each, rather than at the foot, where a window can hide them.
struct InboxSidebar: View {
    @Environment(ServerStore.self) private var store
    @Binding var selection: SessionRoute?
    @State private var showArchived = false

    var body: some View {
        let active = store.sessions(archived: false)
        let offline = store.connections.filter { if case .failed = $0.state { true } else { false } }
        let needsYou = active.filter(\.session.needsYou)
        let busy = active.filter { !$0.session.needsYou && $0.session.isBusy }
        let recent = active.filter { !$0.session.needsYou && !$0.session.isBusy }
        let archived = store.sessions(archived: true)
        List(selection: $selection) {
            if !needsYou.isEmpty || !offline.isEmpty {
                Section {
                    ForEach(offline, id: \.server.id) { OfflineServerRow(connection: $0) }
                    rows(needsYou)
                } header: {
                    GroupHeader(title: "Needs You", count: needsYou.count + offline.count, tint: Palette.yellow)
                }
            }
            if !busy.isEmpty {
                Section {
                    rows(busy)
                } header: {
                    GroupHeader(title: "Running", count: busy.count)
                }
            }
            if !recent.isEmpty {
                Section {
                    rows(recent)
                } header: {
                    GroupHeader(title: "Recent", count: recent.count)
                }
            }
            if !archived.isEmpty {
                Section {
                    ArchivedToggle(count: archived.count, shown: $showArchived)
                    if showArchived { rows(archived) }
                }
            }
            if !store.servers.isEmpty {
                Section {
                    ForEach(store.connections, id: \.server.id) { InboxServerRow(connection: $0) }
                } header: {
                    GroupHeader(title: "Servers")
                }
            }
        }
        .onChange(of: selectionArchived, initial: true) { _, archived in
            // Landing on an archived session, such as the one open last, shows its row.
            if archived { showArchived = true }
        }
    }

    private func rows(_ items: [ServerSession]) -> some View {
        ForEach(items) { item in
            SessionLink(item: item, selected: selection == item.id) {
                InboxRow(item: item, showsServer: store.servers.count > 1)
            }
        }
    }

    /// The open session is archived, which only the synced index can tell.
    private var selectionArchived: Bool {
        guard let route = selection,
              let server = store.servers.first(where: { $0.id == route.serverID }) else { return false }
        return store.connection(for: server).sessions[route.sessionID]?.archived == true
    }
}

/// A section's title, with a count; tinted for what needs you.
private struct GroupHeader: View {
    let title: String
    var count: Int?
    var tint: Color?

    var body: some View {
        HStack(spacing: 6) {
            Text(title)
                .foregroundStyle(tint ?? Palette.subtext)
            if let count {
                Text("\(count)")
                    .monospacedDigit()
                    .foregroundStyle(tint == nil ? Palette.overlay : Palette.onAccent)
                    .padding(.horizontal, tint == nil ? 0 : 5)
                    .background {
                        if let tint { Capsule().fill(tint) }
                    }
            }
        }
        .listHeaderStyle()
        #if os(iOS)
        .scaledFont(.subheadline, weight: .semibold)
        #endif
        .accessibilityElement(children: .combine)
    }
}

/// Shows or hides the archived sessions below it.
private struct ArchivedToggle: View {
    let count: Int
    @Binding var shown: Bool

    var body: some View {
        Button {
            withAnimation(.snappy(duration: 0.2)) { shown.toggle() }
        } label: {
            HStack(spacing: 8) {
                Image(systemName: "archivebox")
                    .frame(width: 16)
                Text("Archived")
                Spacer(minLength: 0)
                Text("\(count)")
                    .monospacedDigit()
                Image(systemName: "chevron.right")
                    .scaledFont(.caption2)
                    .rotationEffect(.degrees(shown ? 90 : 0))
            }
            .foregroundStyle(Palette.overlay)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .scaledFont(.callout)
        .accessibilityValue(shown ? "Shown" : "Hidden")
        #if os(iOS)
        .listRowInsets(Metrics.rowInsets)
        #endif
        .paletteRows(Palette.mantle)
    }
}

/// A server that gave up connecting, among what needs you. A click tries again.
private struct OfflineServerRow: View {
    let connection: ServerConnection

    var body: some View {
        Button { connection.retry() } label: {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Image(systemName: "wifi.exclamationmark")
                    .scaledFont(.caption)
                    .foregroundStyle(Palette.red)
                    .frame(width: 16)
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(connection.server.name) is offline")
                        .scaledFont(.body)
                        .foregroundStyle(Palette.text)
                    if case .failed(let msg) = connection.state {
                        Text(msg)
                            .scaledFont(.caption)
                            .foregroundStyle(Palette.subtext)
                            .lineLimit(2)
                    }
                    Text("Try again")
                        .scaledFont(.caption)
                        .foregroundStyle(Palette.accent)
                }
                Spacer(minLength: 0)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .contextMenu { ServerMenuItems(connection: connection) }
        #if os(iOS)
        .listRowInsets(Metrics.rowInsets)
        #endif
        .paletteRows(Palette.mantle)
    }
}

/// A server in the Inbox's list of them: its name, its sessions or how its
/// connection is doing, and its actions in the context menu (and a ⋯ menu on
/// iPad). On the Mac, a + takes the count's place on hover.
private struct InboxServerRow: View {
    let connection: ServerConnection
    @Environment(\.sessionActions) private var actions
    @State private var hovering = false

    var body: some View {
        HStack(spacing: 8) {
            Image(systemName: "desktopcomputer")
                .scaledFont(.caption)
                .foregroundStyle(Palette.overlay)
                .frame(width: 16)
            Text(connection.server.name)
                .foregroundStyle(Palette.text)
                .lineLimit(1)
            Spacer(minLength: 4)
            switch connection.state {
            case .connected:
                if hovering {
                    Button("New session on \(connection.server.name)", systemImage: "plus") { actions.newSession(connection) }
                        .labelStyle(.iconOnly)
                        .buttonStyle(.plain)
                        .foregroundStyle(Palette.subtext)
                } else {
                    Text("\(connection.activeSessions.count)")
                        .foregroundStyle(Palette.overlay)
                        .monospacedDigit()
                }
            case .connecting:
                ProgressView().controlSize(.mini)
            case .failed:
                Button("Try again") { connection.retry() }
                    .buttonStyle(.plain)
                    .foregroundStyle(Palette.accent)
            case .idle:
                EmptyView()
            }
            ConnectionDot(state: connection.state)
                .scaleEffect(0.7)
            #if os(iOS)
            Menu { ServerMenuItems(connection: connection) } label: {
                Label("\(connection.server.name) actions", systemImage: "ellipsis.circle")
                    .labelStyle(.iconOnly)
            }
            .foregroundStyle(Palette.subtext)
            #endif
        }
        .scaledFont(.callout)
        .contentShape(Rectangle())
        .onHover { hovering = $0 }
        .contextMenu { ServerMenuItems(connection: connection) }
        #if os(iOS)
        .listRowInsets(Metrics.rowInsets)
        #endif
        .paletteRows(Palette.mantle)
    }
}
