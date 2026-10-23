package codex

import (
	"context"
	"strings"
	"testing"

	"remote-agent/internal/config"
	"remote-agent/internal/harness"
	"remote-agent/internal/harness/oneshottest"
)

func TestOneShot(t *testing.T) {
	// codex exec writes its last message to the file after -o.
	cmd, args, stdin := oneshottest.Script(t, `while [ $# -gt 0 ]; do [ "$1" = -o ] && out=$2; shift; done; echo '{"title":"Hi"}' > "$out"`)
	dir := t.TempDir()
	got, err := New(config.Command{Command: cmd}).OneShot(context.Background(), harness.OneShotOptions{Cwd: dir, Model: "gpt-mini", Effort: "low", Prompt: "name it"})
	if err != nil || got != `{"title":"Hi"}` {
		t.Fatalf("OneShot = %q, %v", got, err)
	}
	want := "[exec] [--ephemeral] [--skip-git-repo-check] [--ignore-rules] [--sandbox] [read-only] [--color] [never] [-C] [" + dir +
		"] [-o] [" + dir + "/last-message.txt] [-m] [gpt-mini] [-c] [model_reasoning_effort=\"low\"] [-]"
	if a := oneshottest.Args(t, args); a != want {
		t.Fatalf("args %s", a)
	}
	if p := oneshottest.Read(t, stdin); p != "name it" {
		t.Fatalf("stdin %q", p)
	}
}

func TestOneShotFails(t *testing.T) {
	cmd, _, _ := oneshottest.Script(t, `echo "Error: not logged in" >&2; exit 1`)
	_, err := New(config.Command{Command: cmd}).OneShot(context.Background(), harness.OneShotOptions{Cwd: t.TempDir(), Prompt: "x"})
	if err == nil || !strings.Contains(err.Error(), "not logged in") {
		t.Fatalf("err = %v", err)
	}
}

func TestMaintenanceCommands(t *testing.T) {
	h := New(config.Command{Command: "codex"})
	if c, ok := h.UpdateCommand(); !ok || strings.Join(c.Args, " ") != "codex update" {
		t.Fatalf("update %+v", c)
	}
	if c, ok := h.LoginCommand(); !ok || strings.Join(c.Args, " ") != "codex login --device-auth" {
		t.Fatalf("login %+v", c)
	}
}
