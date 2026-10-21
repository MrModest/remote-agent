import AppKit
import Observation
import Sparkle

/// The app's updates, through Sparkle: it reads the latest release's feed
/// (SUFeedURL, written by scripts/appcast.sh) once a day, and an update brings
/// its own rad. A menu bar app shouldn't put a window in front of the user's
/// work, so an update that a daily check finds waits in the menu, where
/// choosing it opens Sparkle's dialog. Installing restarts the app, and so
/// rad: if that would interrupt sessions, it asks first. Only release builds
/// update; a build from Xcode is version 0.1.
@MainActor @Observable
final class AppUpdater: NSObject {
    /// The version a daily check found, until the user looks at it.
    private(set) var available: String?
    /// The version that waits to install until sessions are done, or the app quits.
    private(set) var postponed: String?
    /// False while a check or an update is under way.
    private(set) var canCheck = false

    @ObservationIgnored private let rad: RadSupervisor
    @ObservationIgnored private var controller: SPUStandardUpdaterController?
    @ObservationIgnored private var observation: NSKeyValueObservation?
    @ObservationIgnored private var install: (() -> Void)?

    init(rad: RadSupervisor) {
        self.rad = rad
        super.init()
    }

    func start() {
        guard RadBinary.updatable, controller == nil else { return }
        let controller = SPUStandardUpdaterController(startingUpdater: true, updaterDelegate: self, userDriverDelegate: self)
        self.controller = controller
        observation = controller.updater.observe(\.canCheckForUpdates, options: [.initial, .new]) { [weak self] updater, _ in
            MainActor.assumeIsolated { self?.canCheck = updater.canCheckForUpdates }
        }
    }

    func checkForUpdates() {
        NSApp.activate()
        controller?.checkForUpdates(nil)
    }

    /// Installs the postponed update, which restarts the app.
    func installPostponed() {
        Task { await installIfIdle() }
    }

    /// Calls Sparkle's install handler once rad has no sessions to interrupt,
    /// or the user says to go ahead; otherwise keeps it for later.
    private func installIfIdle() async {
        guard let install else { return }
        let busy = await busySessions()
        if !busy.isEmpty {
            let sessions = busy.count == 1 ? "a session" : "\(busy.count) sessions"
            let alert = NSAlert()
            alert.messageText = "Interrupt \(sessions)?"
            alert.informativeText = "Updating restarts rad, which interrupts \(busy.map { "“\($0)”" }.joined(separator: ", ")). Otherwise the update installs when you quit the app."
            alert.addButton(withTitle: "Update Anyway")
            alert.addButton(withTitle: "Later")
            NSApp.activate()
            guard alert.runModal() == .alertFirstButtonReturn else { return }
        }
        self.install = nil
        postponed = nil
        install()
    }

    /// The sessions with a turn running or an approval waiting.
    private func busySessions() async -> [String] {
        struct Busy: Decodable { let busy: [String] }
        guard rad.state == .running,
              let result = try? await RadSupervisor.run(["update", "--busy", "--json"], environment: rad.environment, executable: rad.executable),
              result.status == 0, let line = result.output.split(separator: "\n").last,
              let busy = try? JSONDecoder().decode(Busy.self, from: Data(line.utf8))
        else { return [] }
        return busy.busy
    }
}

extension AppUpdater: SPUUpdaterDelegate {
    /// Sparkle is about to quit the app, which stops rad and its agents.
    func updater(_ updater: SPUUpdater, shouldPostponeRelaunchForUpdate item: SUAppcastItem,
                 untilInvokingBlock installHandler: @escaping () -> Void) -> Bool {
        install = installHandler
        postponed = item.displayVersionString
        Task { await installIfIdle() }
        return true
    }
}

extension AppUpdater: @preconcurrency SPUStandardUserDriverDelegate {
    var supportsGentleScheduledUpdateReminders: Bool { true }

    /// Sparkle shows an update itself when it's in focus anyway, such as just
    /// after launch; otherwise the menu offers it.
    func standardUserDriverShouldHandleShowingScheduledUpdate(_ update: SUAppcastItem, andInImmediateFocus immediateFocus: Bool) -> Bool {
        immediateFocus
    }

    func standardUserDriverWillHandleShowingUpdate(_ handleShowingUpdate: Bool, forUpdate update: SUAppcastItem, state: SPUUserUpdateState) {
        if !handleShowingUpdate { available = update.displayVersionString }
    }

    func standardUserDriverDidReceiveUserAttention(forUpdate update: SUAppcastItem) {
        available = nil
    }

    func standardUserDriverWillFinishUpdateSession() {
        available = nil
    }
}
