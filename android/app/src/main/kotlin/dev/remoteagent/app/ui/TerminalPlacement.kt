package dev.remoteagent.app.ui

/** Where a session's terminal opens: in place of the messages, below them, or beside them. */
enum class TerminalPlacement(val title: String) {
    OVER("Instead of the messages"), BELOW("Below the messages"), RIGHT("To the right");
}
