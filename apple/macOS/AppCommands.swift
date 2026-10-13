import SwiftUI

/// The Mac menu bar. Like Mail, ⌘N makes the app's main thing, a session.
/// The app has one window, so there is no New Window.
struct AppCommands: Commands {
    @FocusedValue(\.newSession) private var newSession
    @FocusedValue(\.pairServer) private var pairServer

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
    }
}
