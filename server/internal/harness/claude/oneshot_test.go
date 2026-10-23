package claude

import (
	"context"
	"strings"
	"testing"

	"remote-agent/internal/config"
	"remote-agent/internal/harness"
	"remote-agent/internal/harness/oneshottest"
)

func TestOneShot(t *testing.T) {
	cmd, args, stdin := oneshottest.Script(t, `echo '{"type":"result","subtype":"success","is_error":false,"result":"{\"title\": \"Hi\"}"}'`)
	h := New(config.Command{Command: cmd})
	got, err := h.OneShot(context.Background(), harness.OneShotOptions{Cwd: t.TempDir(), Model: "haiku", Effort: "low", Prompt: "name it"})
	if err != nil || got != `{"title": "Hi"}` {
		t.Fatalf("OneShot = %q, %v", got, err)
	}
	// Nothing kept: no session file, no tools, no MCP servers, safe mode.
	want := "[-p] [--output-format] [json] [--no-session-persistence] [--safe-mode] [--strict-mcp-config] [--disable-slash-commands] [--tools] [] [--model] [haiku] [--effort] [low]"
	if a := oneshottest.Args(t, args); a != want {
		t.Fatalf("args %s", a)
	}
	if p := oneshottest.Read(t, stdin); p != "name it" {
		t.Fatalf("stdin %q", p)
	}
}

func TestOneShotWithoutSafeMode(t *testing.T) {
	cmd, args, _ := oneshottest.Script(t, `case " $* " in *" --safe-mode "*) echo "error: unknown option '--safe-mode'" >&2; exit 1;; esac
echo '{"type":"result","is_error":false,"result":"ok"}'`)
	got, err := New(config.Command{Command: cmd}).OneShot(context.Background(), harness.OneShotOptions{Cwd: t.TempDir(), Prompt: "x"})
	if err != nil || got != "ok" {
		t.Fatalf("OneShot = %q, %v", got, err)
	}
	if a := oneshottest.Args(t, args); strings.Contains(a, "safe-mode") || !strings.Contains(a, "[--no-session-persistence]") {
		t.Fatalf("args %s", a)
	}
}

func TestOneShotError(t *testing.T) {
	cmd, _, _ := oneshottest.Script(t, `echo '{"type":"result","subtype":"success","is_error":true,"result":"Not logged in · Please run /login"}'; exit 1`)
	_, err := New(config.Command{Command: cmd}).OneShot(context.Background(), harness.OneShotOptions{Cwd: t.TempDir(), Prompt: "x"})
	if err == nil || !strings.Contains(err.Error(), "Not logged in") {
		t.Fatalf("err = %v", err)
	}
}

func TestMaintenanceCommands(t *testing.T) {
	h := New(config.Command{Command: "/opt/claude", Env: map[string]string{"A": "1"}})
	if c, ok := h.UpdateCommand(); !ok || strings.Join(c.Args, " ") != "/opt/claude update" || c.Env["A"] != "1" {
		t.Fatalf("update %+v", c)
	}
	if c, ok := h.LoginCommand(); !ok || strings.Join(c.Args, " ") != "/opt/claude auth login" {
		t.Fatalf("login %+v", c)
	}
	h = New(config.Command{Command: "claude", Update: []string{"brew", "upgrade", "claude-code"}, Login: []string{}})
	if c, ok := h.UpdateCommand(); !ok || strings.Join(c.Args, " ") != "brew upgrade claude-code" {
		t.Fatalf("configured update %+v", c)
	}
	if _, ok := h.LoginCommand(); ok {
		t.Fatal("login turned off, but offered")
	}
}
