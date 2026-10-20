import Foundation

extension SlashCommand {
    /// Only complete the leading command token; arguments and prose stay untouched.
    public static func query(_ draft: String) -> String? {
        guard draft.first == "/", !draft.contains(where: \.isWhitespace) else { return nil }
        return draft.dropFirst().lowercased()
    }

    public static func suggestions(for draft: String, from commands: [Self]) -> [Self] {
        guard let query = query(draft) else { return [] }
        var seen = Set<String>()
        return commands.filter {
            guard seen.insert($0.name).inserted else { return false }
            return query.isEmpty || $0.name.lowercased().contains(query) || ($0.description?.lowercased().contains(query) ?? false)
        }
    }
}
