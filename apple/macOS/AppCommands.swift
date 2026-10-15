import SwiftUI

/// The Mac menu bar. Like Mail, ⌘N makes the app's main thing, a session.
/// The app has one window, so there is no New Window.
struct AppCommands: Commands {
    @FocusedValue(\.newSession) private var newSession
    @FocusedValue(\.pairServer) private var pairServer
    @AppStorage(SidebarLayout.key) private var layout = SidebarLayout.inbox

    var body: some Commands {
        CommandGroup(replacing: .newItem) {
            Button("New Session") { newSession?.wrappedValue = true }
                .keyboardShortcut("n")
                .disabled(newSession == nil)
            Divider()
            Button("Pair a Server…") { pairServer?.wrappedValue = true }
                .disabled(pairServer == nil)
        }
        SidebarCommands()
        // The sidebar's layouts, as Finder's View menu has "as Icons" and "as List".
        CommandGroup(after: .sidebar) {
            Divider()
            ForEach(Array(SidebarLayout.allCases.enumerated()), id: \.element) { i, l in
                Toggle("as \(l.title)", isOn: Binding { layout == l } set: { if $0 { layout = l } })
                    .keyboardShortcut(KeyEquivalent(Character("\(i + 1)")), modifiers: .command)
            }
            Divider()
        }
    }
}
