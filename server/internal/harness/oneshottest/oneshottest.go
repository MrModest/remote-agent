// Package oneshottest makes stand-ins for agent CLIs: shell scripts that
// record how they were run and answer as the CLI would.
package oneshottest

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// Script writes an executable script that saves its arguments, one per
// line, to the returned file and its stdin to the second, then runs body.
func Script(t *testing.T, body string) (cmd, argsFile, stdinFile string) {
	t.Helper()
	dir := t.TempDir()
	argsFile, stdinFile = filepath.Join(dir, "args"), filepath.Join(dir, "stdin")
	cmd = filepath.Join(dir, "agent")
	script := "#!/bin/sh\nfor a in \"$@\"; do printf '%s\\n' \"[$a]\"; done > " + argsFile + "\ncat > " + stdinFile + "\n" + body + "\n"
	if err := os.WriteFile(cmd, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	return cmd, argsFile, stdinFile
}

// Args reads the arguments a script was run with, each in brackets.
func Args(t *testing.T, file string) string {
	t.Helper()
	b, err := os.ReadFile(file)
	if err != nil {
		t.Fatal(err)
	}
	return strings.ReplaceAll(strings.TrimSpace(string(b)), "\n", " ")
}

func Read(t *testing.T, file string) string {
	t.Helper()
	b, _ := os.ReadFile(file)
	return string(b)
}
