import Foundation
import Observation
import Sparkle

/// The app's updates, through Sparkle: it reads the latest release's feed
/// (SUFeedURL, written by scripts/appcast.sh) once a day, asks before
/// installing, and replaces the app through Sparkle's installer service,
/// since the sandbox keeps the app out of /Applications. Only release builds
/// update; a build from Xcode is version 0.1.
@MainActor @Observable
final class AppUpdater {
    let isRelease: Bool
    /// False while a check or an update is under way.
    private(set) var canCheck = false

    @ObservationIgnored private let controller: SPUStandardUpdaterController?
    @ObservationIgnored private var observation: NSKeyValueObservation?

    init() {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        isRelease = Self.isRelease(version)
        guard isRelease else {
            controller = nil
            return
        }
        let controller = SPUStandardUpdaterController(startingUpdater: true, updaterDelegate: nil, userDriverDelegate: nil)
        self.controller = controller
        observation = controller.updater.observe(\.canCheckForUpdates, options: [.initial, .new]) { [weak self] updater, _ in
            MainActor.assumeIsolated { self?.canCheck = updater.canCheckForUpdates }
        }
    }

    func checkForUpdates() {
        controller?.checkForUpdates(nil)
    }

    var checksAutomatically: Bool {
        get {
            access(keyPath: \.checksAutomatically)
            return controller?.updater.automaticallyChecksForUpdates ?? false
        }
        set {
            withMutation(keyPath: \.checksAutomatically) { controller?.updater.automaticallyChecksForUpdates = newValue }
        }
    }

    var downloadsAutomatically: Bool {
        get {
            access(keyPath: \.downloadsAutomatically)
            return controller?.updater.automaticallyDownloadsUpdates ?? false
        }
        set {
            withMutation(keyPath: \.downloadsAutomatically) { controller?.updater.automaticallyDownloadsUpdates = newValue }
        }
    }

    /// A release's version is X.Y.Z or X.Y.Z-pre. A development build's, such
    /// as "0.1" or git describe's "0.1.2-6-gd609b51", isn't.
    nonisolated static func isRelease(_ version: String) -> Bool {
        version.wholeMatch(of: /\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?/) != nil && !version.contains(/-\d+-g[0-9a-f]+$/)
    }
}
