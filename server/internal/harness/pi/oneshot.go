package pi

import (
	"context"
	"fmt"
	"strings"

	"remote-agent/internal/harness"
	"remote-agent/internal/harness/proc"
)

// OneShot answers a prompt with `pi --print`, in an in-memory session,
// without tools or context files.
func (h *Harness) OneShot(ctx context.Context, o harness.OneShotOptions) (string, error) {
	args := append(append([]string{}, h.cmd.Args...), "--print", "--no-session", "--no-tools", "--no-context-files")
	if o.Model != "" {
		args = append(args, "--model", o.Model)
	}
	if o.Effort != "" {
		args = append(args, "--thinking", o.Effort)
	}
	args = append(args, o.Prompt)
	out, err := proc.Run(ctx, proc.Spec{Command: h.cmd.Command, Args: args, Dir: o.Cwd, Env: h.cmd.Env}, "")
	if err != nil {
		return "", fmt.Errorf("pi --print: %w", err)
	}
	return strings.TrimSpace(string(out)), nil
}

// UpdateCommand is `pi update`, which updates Pi itself.
func (h *Harness) UpdateCommand() (harness.Command, bool) {
	args, ok := harness.Configured(h.cmd.Update, []string{h.cmd.Command, "update"})
	return harness.Command{Args: args, Env: h.cmd.Env}, ok
}

// LoginCommand: Pi signs in with /login in its own interface, which has no
// command line of its own, unless the config gives one.
func (h *Harness) LoginCommand() (harness.Command, bool) {
	args, ok := harness.Configured(h.cmd.Login, nil)
	return harness.Command{Args: args, Env: h.cmd.Env}, ok
}
