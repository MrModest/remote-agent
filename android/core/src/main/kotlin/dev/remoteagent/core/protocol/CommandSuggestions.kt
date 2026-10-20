package dev.remoteagent.core.protocol

/** Only complete the leading command token; arguments and prose stay untouched. */
fun commandQuery(draft: String): String? =
    if (draft.startsWith("/") && draft.none { it.isWhitespace() }) draft.drop(1).lowercase() else null

fun commandSuggestions(draft: String, commands: List<SlashCommand>): List<SlashCommand> {
    val query = commandQuery(draft) ?: return emptyList()
    return commands.distinctBy { it.name }.filter {
        it.name.lowercase().contains(query) || it.description?.lowercase()?.contains(query) == true
    }
}
