package claude

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"remote-agent/internal/harness"
	"remote-agent/internal/harness/proc"
)

// oneShotArgs make `claude -p` answer from the model alone and keep nothing:
// no session file, no tools, no MCP servers, and in safe mode no hooks,
// plugins or CLAUDE.md.
var oneShotArgs = []string{"-p", "--output-format", "json", "--no-session-persistence", "--safe-mode",
	"--strict-mcp-config", "--disable-slash-commands", "--tools", ""}

// OneShot answers a prompt with `claude -p`, which reads it from stdin.
func (h *Harness) OneShot(ctx context.Context, o harness.OneShotOptions) (string, error) {
	args := append(append([]string{}, h.cmd.Args...), oneShotArgs...)
	if o.Model != "" {
		args = append(args, "--model", o.Model)
	}
	if o.Effort != "" {
		args = append(args, "--effort", o.Effort)
	}
	spec := h.spec(o.Cwd)
	spec.Args = args
	out, err := proc.Run(ctx, spec, o.Prompt)
	if err != nil && strings.Contains(err.Error(), "--safe-mode") {
		// Claude Code before safe mode: the rest still keeps nothing.
		spec.Args = withoutArg(args, "--safe-mode")
		out, err = proc.Run(ctx, spec, o.Prompt)
	}
	var res struct {
		Result  string `json:"result"`
		IsError bool   `json:"is_error"`
		Subtype string `json:"subtype"`
	}
	if jerr := json.Unmarshal(out, &res); jerr != nil {
		if err != nil {
			return "", err
		}
		return "", fmt.Errorf("claude -p: %w", jerr)
	}
	if res.IsError {
		msg := strings.TrimSpace(res.Result)
		if msg == "" {
			msg = res.Subtype
		}
		return "", errors.New("claude: " + msg)
	}
	return res.Result, err
}

func withoutArg(args []string, arg string) []string {
	var out []string
	for _, a := range args {
		if a != arg {
			out = append(out, a)
		}
	}
	return out
}

// UpdateCommand is `claude update`, which updates a native install and an
// npm one alike.
func (h *Harness) UpdateCommand() (harness.Command, bool) {
	args, ok := harness.Configured(h.cmd.Update, []string{h.cmd.Command, "update"})
	return h.command(args), ok
}

// LoginCommand is `claude auth login`. It prints a sign-in link, and asks for
// the code the page shows once the browser can't hand it back.
func (h *Harness) LoginCommand() (harness.Command, bool) {
	args, ok := harness.Configured(h.cmd.Login, []string{h.cmd.Command, "auth", "login"})
	return h.command(args), ok
}

func (h *Harness) command(args []string) harness.Command {
	return harness.Command{Args: args, Env: h.cmd.Env, Unset: []string{"CLAUDECODE", "CLAUDE_CODE_SSE_PORT"}}
}
