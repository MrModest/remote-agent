package terminal

import (
	"errors"
	"log/slog"
	"os"
	"sync"
	"time"
)

// ErrShutdown is returned by Open once rad has started shutting down.
var ErrShutdown = errors.New("rad is shutting down")

type Options struct {
	// Shell runs with Args in place of the user's login shell (tests).
	Shell string
	Args  []string
	Env   []string // added to the shell's environment
	Log   *slog.Logger
}

// Manager keeps a shell per session.
type Manager struct {
	opt   Options
	mu    sync.Mutex
	terms map[string]*Terminal
	shut  bool
}

func NewManager(opt Options) *Manager {
	if opt.Log == nil {
		opt.Log = slog.Default()
	}
	return &Manager{opt: opt, terms: map[string]*Terminal{}}
}

// Get is the session's shell, or nil.
func (m *Manager) Get(sessionID string) *Terminal {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.terms[sessionID]
}

// Open returns the session's shell, starting one in dir if it has none.
func (m *Manager) Open(sessionID, dir string, cols, rows int) (*Terminal, error) {
	shell, args := m.opt.Shell, m.opt.Args
	if shell == "" {
		shell, args = loginShell(), []string{"-l"}
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.shut {
		return nil, ErrShutdown
	}
	if t := m.terms[sessionID]; t != nil {
		return t, nil
	}
	t, err := start(sessionID, dir, shell, args, append(environ(os.Environ(), shell), m.opt.Env...), cols, rows)
	if err != nil {
		return nil, err
	}
	m.terms[sessionID] = t
	m.opt.Log.Info("terminal started", "session", sessionID, "shell", shell, "pid", t.cmd.Process.Pid)
	go func() {
		<-t.done
		m.mu.Lock()
		if m.terms[sessionID] == t {
			delete(m.terms, sessionID)
		}
		m.mu.Unlock()
		m.opt.Log.Info("terminal exited", "session", sessionID, "code", t.code)
	}()
	return t, nil
}

// Close ends the session's shell, waiting a few seconds for it to exit.
func (m *Manager) Close(sessionID string) {
	if t := m.Get(sessionID); t != nil {
		t.Kill()
		waitAll([]*Terminal{t})
	}
}

// Shutdown ends every shell, and Open starts no more.
func (m *Manager) Shutdown() {
	m.mu.Lock()
	m.shut = true
	var ts []*Terminal
	for _, t := range m.terms {
		ts = append(ts, t)
	}
	m.mu.Unlock()
	for _, t := range ts {
		t.Kill()
	}
	waitAll(ts)
}

func waitAll(ts []*Terminal) {
	deadline := time.After(3 * time.Second)
	for _, t := range ts {
		select {
		case <-t.done:
		case <-deadline:
			return
		}
	}
}
