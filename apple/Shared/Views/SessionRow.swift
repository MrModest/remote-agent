import RAKit
import SwiftUI

/// Navigation value for a session on a given server.
struct SessionRoute: Hashable {
    let serverID: String
    let sessionID: String
}

/// A session in the iPhone's list of a server's sessions, or in the iPad and
/// Mac sidebar.
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

/// A session to archive, and the server it is on.
struct ArchiveRequest {
    let session: Session
    let connection: ServerConnection
}

extension View {
    /// Confirmation before forgetting a server.
    func removeServerDialog(_ server: Binding<SavedServer?>, onRemove: @escaping () -> Void = {}) -> some View {
        modifier(RemoveServerDialog(server: server, onRemove: onRemove))
    }

    /// Confirmation before archiving a session, which can also delete its worktree.
    func archiveSessionDialog(_ request: Binding<ArchiveRequest?>) -> some View {
        confirmationDialog("Archive session?", isPresented: .init(get: { request.wrappedValue != nil }, set: { if !$0 { request.wrappedValue = nil } }),
                           presenting: request.wrappedValue) { r in
            Button("Archive") { archive(r, removeWorktree: false) }
            if r.session.workspace.kind == .worktree {
                Button("Archive and delete worktree", role: .destructive) { archive(r, removeWorktree: true) }
            }
        } message: { r in
            Text(r.session.workspace.kind == .worktree ? "The branch \(r.session.workspace.branch ?? "") is kept either way." : "The agent process is stopped.")
        }
    }
}

private func archive(_ r: ArchiveRequest, removeWorktree: Bool) {
    Task { try? await r.connection.archive(r.session.id, removeWorktree: removeWorktree) }
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
