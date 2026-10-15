import Foundation

/// A change in a session that the user wants to hear about while they look
/// elsewhere, such as in a notification: the agent waits for them, finished
/// its turn, or failed.
public enum SessionAlert: Equatable, Sendable {
    /// It waits for an approval or an answer.
    case needsYou
    /// Its turn ended.
    case finished
    /// The agent didn't start, or exited with an error.
    case failed(String?)

    /// The alert for a session that changed from `old` to `new`, if any. An
    /// archived session has none.
    public init?(from old: Session, to new: Session) {
        guard !new.archived, old.status != new.status else { return nil }
        switch new.status {
        case .awaitingApproval: self = .needsYou
        case .idle where old.status == .running: self = .finished
        case .error: self = .failed(new.error)
        default: return nil
        }
    }
}
