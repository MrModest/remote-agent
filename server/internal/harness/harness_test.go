package harness

import (
	"context"
	"testing"

	"remote-agent/internal/model"
)

type stub struct{ info model.HarnessInfo }

func (s stub) ID() string                                         { return s.info.ID }
func (s stub) Probe(context.Context) model.HarnessInfo            { return s.info }
func (s stub) Open(context.Context, OpenOptions) (Runtime, error) { return nil, ErrUnsupported }

func TestInstalled(t *testing.T) {
	r := NewRegistry(
		stub{model.HarnessInfo{ID: "a", Installed: true, AuthOK: true}},
		stub{model.HarnessInfo{ID: "b"}},
		stub{model.HarnessInfo{ID: "c", Installed: true}},
	)
	var got []string
	for _, i := range r.Installed(context.Background(), false) {
		got = append(got, i.ID)
	}
	if len(got) != 2 || got[0] != "a" || got[1] != "c" {
		t.Fatalf("installed = %v, want [a c]", got)
	}
}

func TestInputCommand(t *testing.T) {
	for _, c := range []struct {
		in   Input
		args string
		ok   bool
	}{
		{Input{Text: "/compact"}, "", true},
		{Input{Text: "  /compact\n"}, "", true},
		{Input{Text: "/compact keep the API notes"}, "keep the API notes", true},
		{Input{Text: "/compact\nkeep the API notes"}, "keep the API notes", true},
		{Input{Text: "/compacted"}, "", false},
		{Input{Text: "compact"}, "", false},
		{Input{Text: "please /compact"}, "", false},
		{Input{Text: "/compact", Images: []Image{{Path: "a.png"}}}, "", false},
	} {
		if args, ok := c.in.Command("compact"); args != c.args || ok != c.ok {
			t.Errorf("Command(%q) = %q, %v; want %q, %v", c.in.Text, args, ok, c.args, c.ok)
		}
	}
}

func TestIsAuthError(t *testing.T) {
	for msg, want := range map[string]bool{
		"Invalid API key · Please run /login": true,
		`Failed to authenticate. API Error: 401 {"type":"error","error":{"type":"authentication_error","message":"OAuth token has expired."}}`: true,
		"Your access token could not be refreshed. Please log out and sign in again.":                                                          true,
		"unexpected status 401 Unauthorized: Missing bearer or basic authentication in header":                                                 true,
		"fake failure requested":    false,
		"API Error: 529 Overloaded": false,
	} {
		if got := IsAuthError(msg); got != want {
			t.Errorf("IsAuthError(%q) = %v", msg, got)
		}
	}
}

func TestSignedOut(t *testing.T) {
	r := NewRegistry(stub{model.HarnessInfo{ID: "x", Installed: true, AuthOK: true}})
	ctx := context.Background()
	h, _ := r.Get("x")
	if info := r.Info(ctx, h, false); !info.AuthOK {
		t.Fatalf("fresh: %+v", info)
	}
	r.SignedOut("x", "Signed out: token expired")
	if info := r.Info(ctx, h, false); info.AuthOK || info.Hint != "Signed out: token expired" {
		t.Fatalf("signed out: %+v", info)
	}
	// A probe can't tell an expired token, so it stays until the agent signs in.
	if info := r.Info(ctx, h, true); info.AuthOK {
		t.Fatalf("after a probe: %+v", info)
	}
	r.SignedIn("x")
	if info := r.Info(ctx, h, false); !info.AuthOK || info.Hint != "" {
		t.Fatalf("signed in: %+v", info)
	}
}
