package dev.remoteagent.app.ui

/** One line of a file's diff. Mirrors PatchParser in apple/Shared/Views/DiffView.swift and web/src/ui/patch.ts. */
data class DiffLine(val text: String, val kind: Kind) {
    enum class Kind { ADD, DEL, HUNK, META, CONTEXT }
}

object PatchParser {
    /** Splits a multi-file unified diff into per-file sections keyed by new path, in order. */
    fun split(patch: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var current = mutableListOf<String>()
        fun flush() {
            val header = current.firstOrNull() ?: return
            var path: String? = null
            for (l in current.take(8)) {
                when {
                    l.startsWith("+++ b/") -> path = l.removePrefix("+++ b/")
                    l.startsWith("rename to ") -> path = l.removePrefix("rename to ")
                    l.startsWith("--- a/") && path == null -> path = l.removePrefix("--- a/")
                }
            }
            if (path == null) header.indexOf(" b/").takeIf { it >= 0 }?.let { path = header.substring(it + 3) }
            path?.let { out[it] = current.joinToString("\n") }
        }
        for (line in patch.split("\n")) {
            if (line.startsWith("diff --git ")) {
                flush()
                current = mutableListOf(line)
            } else if (current.isNotEmpty()) {
                current.add(line)
            }
        }
        flush()
        return out
    }

    /** A file's section as lines, from its first hunk. */
    fun lines(section: String): List<DiffLine> {
        val out = mutableListOf<DiffLine>()
        var inHunk = false
        for (text in section.split("\n")) {
            val kind = when {
                text.startsWith("@@") -> DiffLine.Kind.HUNK.also { inHunk = true }
                !inHunk -> continue // diff/index/---/+++ headers
                text.startsWith("+") -> DiffLine.Kind.ADD
                text.startsWith("-") -> DiffLine.Kind.DEL
                text.startsWith("\\") -> DiffLine.Kind.META
                else -> DiffLine.Kind.CONTEXT
            }
            out.add(DiffLine(text, kind))
        }
        if (out.lastOrNull()?.text == "") out.removeAt(out.lastIndex)
        return out
    }
}
