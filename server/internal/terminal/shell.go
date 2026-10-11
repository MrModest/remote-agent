package terminal

import (
	"context"
	"os"
	"os/exec"
	"os/user"
	"runtime"
	"strings"
	"sync"
	"time"
)

var loginShell = sync.OnceValue(findShell)

// findShell is the user's login shell: $SHELL, which a service's environment
// doesn't have, else the user database, else the system's default.
func findShell() string {
	if s := os.Getenv("SHELL"); executable(s) {
		return s
	}
	if u, err := user.Current(); err == nil {
		if s := userShell(u.Username); executable(s) {
			return s
		}
	}
	if runtime.GOOS == "darwin" {
		return "/bin/zsh"
	}
	return "/bin/sh"
}

func userShell(name string) string {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if runtime.GOOS == "darwin" {
		// /Search also finds users from network and directory accounts.
		out, err := exec.CommandContext(ctx, "dscl", "/Search", "-read", "/Users/"+name, "UserShell").Output()
		if _, v, ok := strings.Cut(string(out), "UserShell:"); err == nil && ok {
			return strings.TrimSpace(v)
		}
		return ""
	}
	if out, err := exec.CommandContext(ctx, "getent", "passwd", name).Output(); err == nil {
		return passwdShell(string(out), name)
	}
	b, _ := os.ReadFile("/etc/passwd")
	return passwdShell(string(b), name)
}

// passwdShell finds a user's shell in passwd(5) lines.
func passwdShell(lines, name string) string {
	for line := range strings.Lines(lines) {
		f := strings.Split(strings.TrimSpace(line), ":")
		if len(f) >= 7 && f[0] == name {
			return f[6]
		}
	}
	return ""
}

func executable(path string) bool {
	fi, err := os.Stat(path)
	return path != "" && err == nil && fi.Mode().IsRegular() && fi.Mode()&0o111 != 0
}

// dropped are variables that describe the terminal rad itself runs in, if
// any, rather than the shell's.
var dropped = []string{
	"TERM*", "COLORTERM", "ITERM_*", "TMUX*", "STY", "WINDOWID", "KITTY_*", "WEZTERM_*", "GHOSTTY_*",
	"VSCODE_*", "INSIDE_EMACS", "COLUMNS", "LINES", "PWD", "OLDPWD", "SHLVL", "_",
}

// environ is a shell's environment: rad's own, without the variables about
// rad's terminal, with the shell's terminal type, and with the user's
// variables that a background service runs without.
func environ(base []string, shell string) []string {
	var env []string
	has := map[string]bool{}
	for _, kv := range base {
		k, _, _ := strings.Cut(kv, "=")
		if !droppedVar(k) {
			env = append(env, kv)
			has[k] = true
		}
	}
	env = append(env, "TERM=xterm-256color", "COLORTERM=truecolor", "SHELL="+shell)
	if u, err := user.Current(); err == nil {
		for k, v := range map[string]string{"USER": u.Username, "LOGNAME": u.Username, "HOME": u.HomeDir} {
			if !has[k] && v != "" {
				env = append(env, k+"="+v)
			}
		}
	}
	if !has["LANG"] && !has["LC_ALL"] && !has["LC_CTYPE"] {
		if runtime.GOOS == "darwin" {
			env = append(env, "LANG=en_US.UTF-8")
		} else {
			env = append(env, "LANG=C.UTF-8")
		}
	}
	return env
}

func droppedVar(k string) bool {
	for _, d := range dropped {
		if p, ok := strings.CutSuffix(d, "*"); ok && strings.HasPrefix(k, p) || k == d || k == "SHELL" {
			return true
		}
	}
	return false
}
