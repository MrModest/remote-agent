package main

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"os"
	"os/signal"
	"sync"
	"syscall"

	"remote-agent/internal/model"
)

// The Remote Agent Server Mac app runs `rad serve --supervised` as its child,
// so macOS attributes rad and its agents to the app when asking for
// permissions. The app holds rad's stdin open and reads one JSON object per
// line from its stdout; the log still goes to stderr. The app writes JSON
// lines to rad's stdin too (appMessage).

// readyStatus is written once rad is listening.
type readyStatus struct {
	Event         string   `json:"event"` // "ready"
	Version       string   `json:"version"`
	URLs          []string `json:"urls"`
	Listen        []string `json:"listen"`
	Config        string   `json:"config"`
	Data          string   `json:"data"`
	PairedDevices int      `json:"pairedDevices"`
}

// agentsStatus follows once the agents have been probed.
type agentsStatus struct {
	Event  string              `json:"event"` // "agents"
	Agents []model.HarnessInfo `json:"agents"`
}

// updateRequest asks the app to install its latest release, which brings
// its own rad, and restart: a client asked for it (server.update).
type updateRequest struct {
	Event   string `json:"event"`   // "update"
	Version string `json:"version"` // the release rad found
}

// appMessage is a line from the app. For an update, its progress:
// "updateStatus" with Status "checking", "downloading", "installing" (the
// app quits next, and rad with it), "upToDate" or "failed".
type appMessage struct {
	Event   string `json:"event"`
	Status  string `json:"status,omitempty"`
	Version string `json:"version,omitempty"`
	Message string `json:"message,omitempty"`
}

// supervisor talks to the app.
type supervisor struct {
	mu   sync.Mutex
	out  *json.Encoder
	subs map[chan appMessage]struct{}
}

// supervise returns a context that ends when stdin closes, which happens when
// the app quits or dies, and the supervisor that talks to the app.
func supervise(ctx context.Context) (context.Context, *supervisor) {
	// A closed stdout must not kill rad before it has stopped its agents.
	signal.Ignore(syscall.SIGPIPE)
	ctx, cancel := context.WithCancel(ctx)
	s := &supervisor{out: json.NewEncoder(os.Stdout), subs: map[chan appMessage]struct{}{}}
	go func() {
		defer cancel()
		s.read(os.Stdin)
	}()
	return ctx, s
}

// read passes the app's messages to the subscribers until in ends.
func (s *supervisor) read(in io.Reader) {
	sc := bufio.NewScanner(in)
	for sc.Scan() {
		var m appMessage
		if json.Unmarshal(sc.Bytes(), &m) != nil {
			continue
		}
		s.mu.Lock()
		for ch := range s.subs {
			select {
			case ch <- m:
			default:
			}
		}
		s.mu.Unlock()
	}
}

// Send writes a status line for the app.
func (s *supervisor) Send(v any) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.out.Encode(v)
}

// Subscribe returns the app's messages from now on, until cancel.
func (s *supervisor) Subscribe() (msgs <-chan appMessage, cancel func()) {
	ch := make(chan appMessage, 16)
	s.mu.Lock()
	s.subs[ch] = struct{}{}
	s.mu.Unlock()
	return ch, func() {
		s.mu.Lock()
		delete(s.subs, ch)
		s.mu.Unlock()
	}
}
