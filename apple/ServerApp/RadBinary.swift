import Foundation

/// The rad the app runs: its own, which build-rad.sh builds into it with the
/// app's version. An app update brings a new one (AppUpdater).
enum RadBinary {
    nonisolated static let bundled = Bundle.main.url(forAuxiliaryExecutable: "rad")!

    nonisolated static let bundledVersion =
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"

    /// Only release builds update: X.Y.Z or X.Y.Z-pre. A development build's
    /// version, such as Xcode's "0.1" or git describe's "0.1.2-6-gd609b51", isn't one.
    nonisolated static var updatable: Bool {
        bundledVersion.wholeMatch(of: /\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?/) != nil
            && !bundledVersion.contains(/-\d+-g[0-9a-f]+$/)
    }

    /// Deletes the copies of rad that Update rad downloaded, before the app
    /// updated itself (0.9 and earlier): rad-<version> in Application Support.
    nonisolated static func removeOldDownloads() {
        let folder: URL
        if let home = ProcessInfo.processInfo.environment["RAD_HOME"] {
            folder = URL(fileURLWithPath: home).appendingPathComponent("Remote Agent Server")
        } else {
            folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
                .appendingPathComponent("Remote Agent Server")
        }
        let files = FileManager.default
        guard let contents = try? files.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil) else { return }
        for url in contents where url.lastPathComponent.hasPrefix("rad-") {
            try? files.removeItem(at: url)
        }
        if (try? files.contentsOfDirectory(atPath: folder.path))?.isEmpty == true {
            try? files.removeItem(at: folder)
        }
    }
}
