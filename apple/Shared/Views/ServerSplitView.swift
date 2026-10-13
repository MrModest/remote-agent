import RAKit
import SwiftUI

/// The iPad and Mac layout: one server's sessions in the sidebar, the chosen
/// session beside them. The title menu (on the Mac, the server menu at the
/// foot of the sidebar) switches servers. In a narrow iPad window it collapses
/// into a stack, like the iPhone.
struct ServerSplitView: View {
    @Environment(ServerStore.self) private var store
    @Binding var pendingLink: PairingLink?
    // Kept per window, so each window has its own server and session. One
    // value, so the two change together: of two values set at once, the view
    // can see one a render before the other.
    @SceneStorage("place") private var place = WindowPlace()
    @State private var window = UUID()
    @State private var showAdd = false
    #if os(macOS)
    @State private var removing: SavedServer?
    #endif

    /// The chosen server, or the first one if it was removed.
    private var server: SavedServer? {
        store.servers.first { $0.id == place.server } ?? store.servers.first
    }

    /// The open session. A session saved for a server that was removed since
    /// doesn't carry over to the server shown in its place.
    private var openSession: String? {
        guard let server, server.id == place.server, !place.session.isEmpty else { return nil }
        return place.session
    }

    /// The open session is no longer on the server. Only the synced index can
    /// tell: before it, every session looks missing.
    private var openSessionGone: Bool {
        guard let server, let id = openSession else { return false }
        let connection = store.connection(for: server)
        return connection.indexSynced && connection.sessions[id] == nil
    }

    var body: some View {
        NavigationSplitView {
            sidebar
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
            AddServerView(initialLink: pendingLink) { server in
                show(server: server.id)
            }
            .appStyle()
        }
        .onChange(of: pendingLink) { _, link in
            if link != nil { showAdd = true }
        }
        .onChange(of: showAdd) { _, shown in
            if !shown { pendingLink = nil }
        }
        .onAppear(perform: restore)
        .onDisappear { LastSession.windows.remove(window) }
        .onChange(of: server?.id) { _, id in
            // The window's server was removed, or it had none: another one takes its place.
            if id != place.server { show(server: id) }
        }
        .onChange(of: openSessionGone, initial: true) { _, gone in
            // Landing on a session that is gone shows nothing rather than an error.
            guard gone, let server else { return }
            if LastSession.session(on: server.id) == place.session {
                LastSession.set(nil, on: server.id, servers: store.servers)
            }
            place.session = ""
        }
        #if os(macOS)
        .removeServerDialog($removing)
        #endif
        .focusedSceneValue(\.pairServer, $showAdd)
    }

    @ViewBuilder private var sidebar: some View {
        if let server {
            ServerHomeView(connection: store.connection(for: server), selection: selection(on: server))
                .id(server.id)
                #if os(macOS)
                .safeAreaInset(edge: .bottom, spacing: 0) { macServerMenu(server) }
                #else
                .toolbarTitleMenu { serverMenu }
                #endif
        } else {
            List {}
                .listBackground(Palette.mantle)
                .navigationTitle("Servers")
                .toolbar {
                    ToolbarItem(placement: .primaryAction) {
                        Button("Add", systemImage: "plus") { showAdd = true }
                    }
                    #if os(iOS)
                    ToolbarItem(placement: .secondaryAction) {
                        SettingsButton()
                    }
                    #endif
                }
        }
    }

    @ViewBuilder private var serverMenu: some View {
        Picker("Server", selection: Binding(get: { server?.id ?? "" }, set: { show(server: $0) })) {
            ForEach(store.servers) { s in
                Text(s.name).tag(s.id)
            }
        }
        #if os(macOS)
        .pickerStyle(.inline) // A Mac menu would put it in a submenu.
        #endif
        Button("Pair a server", systemImage: "plus") { showAdd = true }
    }

    #if os(macOS)
    /// At the foot of the sidebar: the server's name and status, and a menu
    /// with the server actions that iOS keeps in the sidebar's toolbar.
    private func macServerMenu(_ server: SavedServer) -> some View {
        let connection = store.connection(for: server)
        return Menu {
            serverMenu
            Divider()
            Button("Reconnect", systemImage: "arrow.clockwise") { connection.retry() }
            Button("Remove \(server.name)…", systemImage: "trash", role: .destructive) { removing = server }
        } label: {
            HStack(spacing: 8) {
                ConnectionDot(state: connection.state)
                Text(server.name).lineLimit(1).scaledFont(.body)
                Spacer(minLength: 0)
                Image(systemName: "chevron.up.chevron.down")
                    .scaledFont(.caption)
                    .foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
            .paletteText()
            .accessibilityElement(children: .ignore)
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .menuIndicator(.hidden)
        .help(ConnectionDot(state: connection.state).label)
        .accessibilityLabel("\(server.name), \(ConnectionDot(state: connection.state).label)")
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
    }
    #endif

    private func selection(on server: SavedServer) -> Binding<SessionRoute?> {
        Binding {
            openSession.map { SessionRoute(serverID: server.id, sessionID: $0) }
        } set: { route in
            place = WindowPlace(server: server.id, session: route?.sessionID ?? "")
            LastSession.set(route?.sessionID, on: server.id, servers: store.servers)
        }
    }

    /// A window with no server of its own to restore opens on the server used
    /// last. When it is the only window, it also opens the session that was
    /// open there: the Mac doesn't restore the windows it closes, on quit or
    /// otherwise. A window beside others starts with no session open.
    private func restore() {
        let alone = LastSession.windows.subtracting([window]).isEmpty
        LastSession.windows.insert(window)
        guard server?.id != place.server else { return }
        let last = (store.servers.first { $0.id == LastSession.server } ?? server)?.id
        if alone {
            show(server: last)
        } else {
            place = WindowPlace(server: last ?? "")
        }
    }

    /// Shows the server's sessions, and the session that was open on it last.
    private func show(server id: String?) {
        place = WindowPlace(server: id ?? "", session: id.flatMap(LastSession.session(on:)) ?? "")
        if let id { LastSession.server = id }
    }

    @ViewBuilder private var detail: some View {
        if let server, let sessionID = openSession, !openSessionGone {
            let connection = store.connection(for: server)
            // A new identity per session, so the attachments and the subscription don't carry over.
            SessionView(connection: connection, store: connection.sessionStore(sessionID))
                .id(sessionID)
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
                #if os(macOS)
                // The window's title; on iPad the sidebar shows the name.
                .navigationTitle(server?.name ?? "")
                #endif
        }
    }
}

/// A window's server, and the session open in it ("" for none).
private struct WindowPlace: RawRepresentable {
    var server = ""
    var session = ""

    init(server: String = "", session: String = "") {
        self.server = server
        self.session = session
    }

    init?(rawValue: String) {
        let ids = rawValue.split(separator: " ", omittingEmptySubsequences: false)
        guard ids.count == 2 else { return nil }
        server = String(ids[0])
        session = String(ids[1])
    }

    var rawValue: String { server + " " + session }
}

/// The server used last, and the session open last on each server, which a
/// window goes back to. Kept in UserDefaults, so they outlast the app.
@MainActor
private enum LastSession {
    /// The windows open now.
    static var windows: Set<UUID> = []

    static var server: String? {
        get { UserDefaults.standard.string(forKey: "lastServer") }
        set { UserDefaults.standard.set(newValue, forKey: "lastServer") }
    }

    static func session(on server: String) -> String? {
        sessions[server]
    }

    /// Remembers the session open on `server`, nil for none, and forgets
    /// the servers that are no longer in `servers`.
    static func set(_ session: String?, on server: String, servers: [SavedServer]) {
        var all = sessions.filter { id, _ in servers.contains { $0.id == id } }
        all[server] = session
        UserDefaults.standard.set(all, forKey: "lastSessions")
        Self.server = server
    }

    private static var sessions: [String: String] {
        UserDefaults.standard.dictionary(forKey: "lastSessions") as? [String: String] ?? [:]
    }
}
