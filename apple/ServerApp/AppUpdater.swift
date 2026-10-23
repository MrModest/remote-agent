import AppKit
import Observation
import Sparkle

/// The app's updates, through Sparkle: it reads the latest release's feed
/// (SUFeedURL, written by scripts/appcast.sh) once a day, and an update brings
/// its own rad. A menu bar app shouldn't put a window in front of the user's
/// work, so the daily check downloads an update by itself
/// (SUAutomaticallyUpdate), and the menu offers to install it; otherwise it
/// installs when the app quits. Installing restarts the app, and so rad: if
/// that would interrupt sessions, it asks first. A client can ask for the
/// update too, through rad (installForClient). Only release builds update; a
/// build from Xcode is version 0.1.
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
    /// A client asked for the update, through rad: it installs as soon as
    /// it's downloaded, and rad hears how it goes.
    @ObservationIgnored private var clientAsked = false

    init(rad: RadSupervisor) {
        self.rad = rad
        super.init()
        rad.onUpdateRequest = { [weak self] _ in self?.installForClient() }
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

    /// A client asked rad for the latest release (server.update). The user
    /// isn't at the Mac, so the app installs it without asking, now if it's
    /// downloaded already, else once it is.
    func installForClient() {
        guard let controller else {
            rad.send(UpdateStatus(status: "failed", message: "This build of Remote Agent Server doesn't update itself."))
            return
        }
        clientAsked = true
        if let install {
            rad.send(UpdateStatus(status: "installing", version: postponed))
            self.install = nil
            postponed = nil
            install()
            return
        }
        rad.send(UpdateStatus(status: "checking"))
        // A session already under way, such as a download, ends in the
        // delegate calls below too.
        if !controller.updater.sessionInProgress {
            controller.updater.checkForUpdatesInBackground()
        }
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
        if clientAsked {
            rad.send(UpdateStatus(status: "installing", version: item.displayVersionString))
            return false
        }
        install = installHandler
        postponed = item.displayVersionString
        Task { await installIfIdle() }
        return true
    }

    /// The daily check downloaded an update. It waits in the menu, and
    /// installs when the app quits; a client's request installs it now.
    func updater(_ updater: SPUUpdater, willInstallUpdateOnQuit item: SUAppcastItem,
                 immediateInstallationBlock immediateInstallHandler: @escaping () -> Void) -> Bool {
        if clientAsked {
            rad.send(UpdateStatus(status: "installing", version: item.displayVersionString))
            immediateInstallHandler()
        } else {
            install = immediateInstallHandler
            postponed = item.displayVersionString
        }
        return true
    }

    func updater(_ updater: SPUUpdater, didFindValidUpdate item: SUAppcastItem) {
        if clientAsked { rad.send(UpdateStatus(status: "downloading", version: item.displayVersionString)) }
    }

    func updaterDidNotFindUpdate(_ updater: SPUUpdater, error: any Error) {
        guard clientAsked else { return }
        clientAsked = false
        rad.send(UpdateStatus(status: "upToDate", message: "Remote Agent Server \(RadBinary.bundledVersion) is the latest version."))
    }

    func updater(_ updater: SPUUpdater, didAbortWithError error: any Error) {
        guard clientAsked, (error as NSError).code != Int(SUError.noUpdateError.rawValue) else { return }
        clientAsked = false
        rad.send(UpdateStatus(status: "failed", message: error.localizedDescription))
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
