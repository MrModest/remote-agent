package codex

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"remote-agent/internal/harness"
	"remote-agent/internal/harness/proc"
)

// OneShot answers a prompt with `codex exec --ephemeral`, which keeps no
// session files, in a read-only sandbox. It reads the prompt from stdin and
// writes its last message to a file in the cwd.
func (h *Harness) OneShot(ctx context.Context, o harness.OneShotOptions) (string, error) {
	last := filepath.Join(o.Cwd, "last-message.txt")
	args := append(append([]string{}, h.cmd.Args...), "exec", "--ephemeral", "--skip-git-repo-check", "--ignore-rules",
		"--sandbox", "read-only", "--color", "never", "-C", o.Cwd, "-o", last)
	if o.Model != "" {
		args = append(args, "-m", o.Model)
	}
	if o.Effort != "" {
		args = append(args, "-c", fmt.Sprintf("model_reasoning_effort=%q", o.Effort))
	}
	args = append(args, "-")
	if _, err := proc.Run(ctx, proc.Spec{Command: h.cmd.Command, Args: args, Dir: o.Cwd, Env: h.cmd.Env}, o.Prompt); err != nil {
		return "", fmt.Errorf("codex exec: %w", err)
	}
	b, err := os.ReadFile(last)
	if err != nil {
		return "", fmt.Errorf("codex exec wrote no reply: %w", err)
	}
	return strings.TrimSpace(string(b)), nil
}

// UpdateCommand is `codex update`, which knows how Codex was installed.
func (h *Harness) UpdateCommand() (harness.Command, bool) {
	args, ok := harness.Configured(h.cmd.Update, []string{h.cmd.Command, "update"})
	return harness.Command{Args: args, Env: h.cmd.Env}, ok
}

// LoginCommand signs in with a device code: the user opens the link it
// prints anywhere and enters the code, and Codex waits for that.
func (h *Harness) LoginCommand() (harness.Command, bool) {
	args, ok := harness.Configured(h.cmd.Login, []string{h.cmd.Command, "login", "--device-auth"})
	return harness.Command{Args: args, Env: h.cmd.Env}, ok
}
