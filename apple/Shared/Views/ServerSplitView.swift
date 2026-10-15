import RAKit
import SwiftUI

/// The iPad and Mac layout: every server's sessions in the sidebar, the
/// chosen session beside them. In a narrow iPad window it collapses into a
/// stack. The app has this one window, which opens again on the session
/// that was open last.
struct ServerSplitView: View {
    @Environment(ServerStore.self) private var store
    @Binding var pendingLink: PairingLink?
    /// The open session, as "<server id> <session id>", or "" for none.
    @AppStorage("openSession") private var openSession = ""
    @State private var showAdd = false
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

    private func connection(_ route: SessionRoute) -> ServerConnection? {
        store.servers.first { $0.id == route.serverID }.map { store.connection(for: $0) }
    }

    /// The open session is no longer on the server. Only the synced index can
    /// tell: before it, every session looks missing.
    private var openSessionGone: Bool {
        guard let route, let connection = connection(route) else { return false }
        return connection.indexSynced && connection.sessions[route.sessionID] == nil
    }

    var body: some View {
        NavigationSplitView {
            SessionsSidebar(selection: selection, showAdd: $showAdd)
                #if os(macOS)
                .navigationSplitViewColumnWidth(min: 240, ideal: 300, max: 420)
                #else
                // As wide as an iPhone, which the rows and toolbar were laid out for.
                .navigationSplitViewColumnWidth(min: 320, ideal: 390, max: 480)
                #endif
        } detail: {
            detail
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Palette.base)
        }
        .sheet(isPresented: $showAdd) {
            AddServerView(initialLink: pendingLink) { _ in }
                .appStyle()
        }
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
        .focusedSceneValue(\.pairServer, $showAdd)
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

    @ViewBuilder private var detail: some View {
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
}
