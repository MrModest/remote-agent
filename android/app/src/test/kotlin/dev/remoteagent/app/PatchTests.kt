package dev.remoteagent.app

import dev.remoteagent.app.ui.DiffLine.Kind
import dev.remoteagent.app.ui.PatchParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** As web/src/ui/patch.test.ts. */
class PatchTests {
    private val patch = """
        diff --git a/main.go b/main.go
        index 1..2 100644
        --- a/main.go
        +++ b/main.go
        @@ -1,3 +1,3 @@
         package main
        -func a() {}
        +func b() {}
        \ No newline at end of file
        diff --git a/old.go b/new.go
        similarity index 100%
        rename from old.go
        rename to new.go
        diff --git a/gone.go b/gone.go
        deleted file mode 100644
        --- a/gone.go
        +++ /dev/null
        @@ -1 +0,0 @@
        -package gone

    """.trimIndent()

    @Test
    fun splitsByFile() {
        val sections = PatchParser.split(patch)
        assertEquals(listOf("main.go", "new.go", "gone.go"), sections.keys.toList())
        assertTrue(sections.getValue("main.go").startsWith("diff --git a/main.go"))
    }

    @Test
    fun listsAFilesLinesFromItsFirstHunk() {
        val sections = PatchParser.split(patch)
        assertEquals(listOf(Kind.HUNK, Kind.CONTEXT, Kind.DEL, Kind.ADD, Kind.META), PatchParser.lines(sections.getValue("main.go")).map { it.kind })
        assertEquals(emptyList(), PatchParser.lines(sections.getValue("new.go")))
        assertEquals(listOf(Kind.HUNK, Kind.DEL), PatchParser.lines(sections.getValue("gone.go")).map { it.kind })
    }
}
