package pi

import (
	"context"
	"testing"

	"remote-agent/internal/config"
	"remote-agent/internal/harness"
	"remote-agent/internal/harness/oneshottest"
)

func TestOneShot(t *testing.T) {
	cmd, args, _ := oneshottest.Script(t, `echo '{"title":"Hi"}'`)
	h := New(config.Command{Command: cmd})
	got, err := h.OneShot(context.Background(), harness.OneShotOptions{Cwd: t.TempDir(), Model: "anthropic/haiku", Effort: "low", Prompt: "name it"})
	if err != nil || got != `{"title":"Hi"}` {
		t.Fatalf("OneShot = %q, %v", got, err)
	}
	want := "[--print] [--no-session] [--no-tools] [--no-context-files] [--model] [anthropic/haiku] [--thinking] [low] [name it]"
	if a := oneshottest.Args(t, args); a != want {
		t.Fatalf("args %s", a)
	}
	if _, ok := h.UpdateCommand(); !ok {
		t.Fatal("no update command")
	}
	if _, ok := h.LoginCommand(); ok {
		t.Fatal("a login command Pi doesn't have")
	}
}
