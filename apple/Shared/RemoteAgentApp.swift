import RAKit
import SwiftUI
#if os(macOS)
import AppKit
#endif

@main
struct RemoteAgentApp: App {
    @State private var store = ServerStore()
    @Environment(\.scenePhase) private var phase

    #if os(macOS)
    init() {
        // The app has one window: without this, "Prefer tabs when opening
        // documents" would still give it a tab bar and the View menu its items.
        NSWindow.allowsAutomaticWindowTabbing = false
    }
    #endif

    var body: some Scene {
        mainWindow
            .onChange(of: phase) { _, newPhase in
                #if os(macOS)
                // A Mac window stays in view while another app is in front.
                store.setActive(newPhase != .background)
                #else
                // Sockets don't survive backgrounding; reconnect and resync on return.
                store.setActive(newPhase == .active)
                #endif
            }
        #if os(macOS)
        Settings {
            SettingsView()
                .frame(width: 460)
                // As tall as the form, which grows with the text sizes. The
                // form can still come out a fraction of a point taller, which
                // would show a scroller.
                .fixedSize(horizontal: false, vertical: true)
                .scrollIndicators(.never)
                .appStyle()
        }
        .windowResizability(.contentSize)
        #endif
    }

    // One window. On the Mac a Window scene has no New Window; on iPad,
    // project.yml turns off multiple scenes.
    #if os(macOS)
    private var mainWindow: some Scene {
        Window("Remote Agent", id: "main") {
            WindowRoot()
                .environment(store)
        }
        .defaultSize(width: 1100, height: 760)
        .commands { AppCommands() }
    }
    #else
    private var mainWindow: some Scene {
        WindowGroup(id: "main") {
            WindowRoot()
                .environment(store)
        }
    }
    #endif
}

/// The app's window: the server list on iPhone, the split view on iPad and
/// the Mac. A pairing link opens the pairing sheet in it.
private struct WindowRoot: View {
    @State private var pendingLink: PairingLink?
    #if os(iOS)
    @State private var showSettings = false
    #endif

    var body: some View {
        Group {
            #if os(macOS)
            ServerSplitView(pendingLink: $pendingLink)
            #else
            // By device, not size class: the split view collapses in a narrow
            // iPad window by itself, and swapping roots would drop the navigation.
            if UIDevice.current.userInterfaceIdiom == .pad {
                ServerSplitView(pendingLink: $pendingLink)
            } else {
                ServersView(pendingLink: $pendingLink)
            }
            #endif
        }
        .onOpenURL { url in
            pendingLink = PairingLink(string: url.absoluteString)
        }
        #if os(iOS)
        .environment(\.showSettings, $showSettings)
        .sheet(isPresented: $showSettings) {
            NavigationStack { SettingsView() }
                .appStyle()
        }
        #endif
        .appStyle()
        #if os(macOS)
        .containerBackground(Palette.base, for: .window)
        #endif
    }
}

extension FocusedValues {
    /// Shows the New session sheet, while a server is connected.
    @Entry var newSession: Binding<Bool>?
    /// Shows the pairing sheet.
    @Entry var pairServer: Binding<Bool>?
}
