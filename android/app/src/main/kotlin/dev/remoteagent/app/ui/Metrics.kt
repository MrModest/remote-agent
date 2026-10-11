package dev.remoteagent.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/** Spacing and corner sizes the views share, kept small for a dense layout. Mirrors apple/Shared/Views/Metrics.swift. */
object Metrics {
    /** Space between transcript blocks. */
    val gap = 8.dp
    /** The side margin of the transcript and the composer. */
    val margin = 12.dp
    /** Padding inside cards: tool calls, plans, approvals. */
    val padding = 8.dp
    /** The transcript stops widening here, so lines stay readable on a tablet. */
    val readableWidth = 1000.dp
    /** Rows of the server and session lists. */
    val rowInsets = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
    /** The default size of messages, prompts and replies (Settings can change it). */
    const val TEXT_SIZE = 15f
    /** Tool calls' text, as a fraction of the message size. */
    const val TOOL_TEXT = 0.9f
    /** Labels in tool calls, such as "Output", as a fraction of the message size. */
    const val TOOL_LABEL = 0.8f
    /** The round buttons beside the prompt: add images, dictate, send and stop. */
    val roundButton = 30.dp

    object Corner {
        /** Snippets inside a card: code, outputs, diffs. */
        val inset = 4.dp
        /** Cards and buttons. */
        val card = 6.dp
        /** User messages and approvals. */
        val bubble = 8.dp
        /** The composer's text field. */
        val field = 10.dp
        /** The selected row in a tablet's list pane. */
        val selection = 12.dp
    }
}
