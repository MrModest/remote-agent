package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestUsageShowsFiles(t *testing.T) {
	home := t.TempDir()
	t.Setenv("RAD_HOME", home)
	config := filepath.Join(home, "config.yaml")
	var b strings.Builder
	printUsage(&b)
	if out := b.String(); !strings.Contains(out, "config  "+config+" (not written yet") || !strings.Contains(out, "data    "+filepath.Join(home, "data")) {
		t.Errorf("usage without a config:\n%s", out)
	}
	if err := os.WriteFile(config, nil, 0o600); err != nil {
		t.Fatal(err)
	}
	b.Reset()
	printUsage(&b)
	if out := b.String(); !strings.Contains(out, "config  "+config+"\n") {
		t.Errorf("usage with a config:\n%s", out)
	}
}
