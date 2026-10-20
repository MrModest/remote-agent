package orchestrator

import (
	"context"
	"testing"
	"time"

	"remote-agent/internal/harness"
	"remote-agent/internal/harness/fake"
	"remote-agent/internal/model"
)

type blockedCatalogHarness struct {
	fake.Harness
	entered chan struct{}
	release chan struct{}
	opened  chan *blockedCatalogRuntime
}

type blockedCatalogRuntime struct {
	harness.Runtime
	entered chan struct{}
	release chan struct{}
	events  chan harness.Event
}

func (h blockedCatalogHarness) Open(ctx context.Context, opts harness.OpenOptions) (harness.Runtime, error) {
	inner, err := h.Harness.Open(ctx, opts)
	if err != nil {
		return nil, err
	}
	rt := &blockedCatalogRuntime{Runtime: inner, entered: h.entered, release: h.release, events: make(chan harness.Event, 256)}
	go func() {
		for ev := range inner.Events() {
			rt.events <- ev
		}
		close(rt.events)
	}()
	h.opened <- rt
	return rt, nil
}
func (r *blockedCatalogRuntime) Events() <-chan harness.Event { return r.events }
func (r *blockedCatalogRuntime) Commands(ctx context.Context) ([]model.Command, error) {
	close(r.entered)
	select {
	case <-r.release:
		return []model.Command{{Name: "stale-skill"}}, nil
	case <-ctx.Done():
		return nil, ctx.Err()
	}
}

func TestCommandDiscoveryAllowsEventsAndStop(t *testing.T) {
	h := blockedCatalogHarness{entered: make(chan struct{}), release: make(chan struct{}), opened: make(chan *blockedCatalogRuntime, 1)}
	e := newEnvAt(t, t.TempDir(), false, h)
	s := e.create(CreateSessionParams{Prompt: "slow slow slow slow slow slow slow slow"})
	rt := <-h.opened
	ctx, cancel := context.WithCancel(bg)
	defer cancel()
	type result struct {
		commands []model.Command
		err      error
	}
	listed := make(chan result, 1)
	go func() { commands, err := e.o.Commands(ctx, s.ID); listed <- result{commands, err} }()
	select {
	case <-h.entered:
	case <-time.After(time.Second):
		t.Fatal("discovery never entered")
	}
	// This frame must be consumed even though the catalog response is still pending.
	rt.events <- harness.ItemEvent{Item: model.Item{ID: "during-discovery", Kind: model.ItemNotice, Status: model.ItemCompleted, Text: "streaming during discovery"}}
	deadline := time.After(time.Second)
	ticker := time.NewTicker(5 * time.Millisecond)
	defer ticker.Stop()
	streamed := false
	for !streamed {
		select {
		case <-deadline:
			t.Fatal("command discovery blocked streamed events")
		case <-ticker.C:
			for _, it := range e.items(s.ID) {
				if it.Text == "streaming during discovery" {
					streamed = true
				}
			}
		}
	}
	stopped := make(chan error, 1)
	go func() { stopped <- e.o.Interrupt(bg, s.ID, true) }()
	select {
	case err := <-stopped:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(time.Second):
		t.Fatal("command discovery blocked force-stop")
	}
	e.prompt(s.ID, "resume after force-stop", "restart-after-discovery")
	close(h.release)
	select {
	case res := <-listed:
		if res.err != nil || len(res.commands) != 0 {
			t.Fatalf("stopped runtime returned obsolete catalog: %+v", res)
		}
	case <-time.After(time.Second):
		t.Fatal("catalog did not finish")
	}
}
