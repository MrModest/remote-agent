import Foundation

/// Prompts written and not sent yet, by session, for one server. They stay
/// on this device, in UserDefaults; the server never sees them.
@MainActor
final class Drafts {
    private let defaults: UserDefaults
    private let key: String
    private var texts: [String: String]

    init(serverID: String, defaults: UserDefaults) {
        self.defaults = defaults
        key = "drafts." + serverID
        texts = defaults.dictionary(forKey: key) as? [String: String] ?? [:]
    }

    /// The session's draft, or "" for none. Setting "" forgets it.
    subscript(sessionID: String) -> String {
        get { texts[sessionID] ?? "" }
        set {
            guard newValue != self[sessionID] else { return }
            texts[sessionID] = newValue.isEmpty ? nil : newValue
            save()
        }
    }

    /// Forgets the drafts of sessions that are gone from the server.
    func keep(where isKept: (String) -> Bool) {
        let gone = texts.keys.filter { !isKept($0) }
        guard !gone.isEmpty else { return }
        for id in gone { texts[id] = nil }
        save()
    }

    func removeAll() {
        texts = [:]
        save()
    }

    private func save() {
        if texts.isEmpty { defaults.removeObject(forKey: key) } else { defaults.set(texts, forKey: key) }
    }
}
