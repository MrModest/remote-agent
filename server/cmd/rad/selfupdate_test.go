package main

import (
	"archive/tar"
	"bufio"
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"

	"remote-agent/internal/api"
	"remote-agent/internal/model"
	"remote-agent/internal/store"
	"remote-agent/internal/tasks"
	"remote-agent/internal/update"
)

// releases serves one release, v0.2.0, whose rad for this platform is a
// script that says so.
func releases(t *testing.T) *update.Client {
	asset := "rad-" + runtime.GOOS + "-" + runtime.GOARCH + ".tar.gz"
	var archive bytes.Buffer
	gz := gzip.NewWriter(&archive)
	tw := tar.NewWriter(gz)
	body := "#!/bin/sh\necho rad 0.2.0\n"
	tw.WriteHeader(&tar.Header{Name: "rad", Mode: 0o755, Size: int64(len(body)), Typeflag: tar.TypeReg})
	tw.Write([]byte(body))
	tw.Close()
	gz.Close()
	sum := sha256.Sum256(archive.Bytes())
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		base := "http://" + r.Host
		switch r.URL.Path {
		case "/repos/o/r/releases/latest":
			json.NewEncoder(w).Encode(map[string]any{
				"tag_name": "v0.2.0", "html_url": "https://example.com/v0.2.0",
				"assets": []map[string]string{
					{"name": asset, "browser_download_url": base + "/dl/" + asset},
					{"name": "SHA256SUMS", "browser_download_url": base + "/dl/SHA256SUMS"},
				},
			})
		case "/dl/" + asset:
			w.Write(archive.Bytes())
		case "/dl/SHA256SUMS":
			io.WriteString(w, hex.EncodeToString(sum[:])+"  "+asset+"\n")
		default:
			http.NotFound(w, r)
		}
	}))
	t.Cleanup(srv.Close)
	return &update.Client{API: srv.URL, Repo: "o/r", HTTP: srv.Client(), UserAgent: "rad/test", GOOS: runtime.GOOS, GOARCH: runtime.GOARCH}
}

func withVersion(t *testing.T, v string) {
	old := api.Version
	api.Version = v
	t.Cleanup(func() { api.Version = old })
}

func testStore(t *testing.T) *store.Store {
	st, err := store.Open(filepath.Join(t.TempDir(), "rad.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	return st
}

func waitTask(t *testing.T, m *tasks.Manager, id string) model.Task {
	t.Helper()
	for deadline := time.Now().Add(10 * time.Second); time.Now().Before(deadline); time.Sleep(10 * time.Millisecond) {
		if s, _ := m.Get(id); s.Status != model.TaskRunning {
			return s
		}
	}
	s, _ := m.Get(id)
	t.Fatalf("task still running: %+v", s)
	return s
}

func TestSelfUpdateInstallsAndRestarts(t *testing.T) {
	withVersion(t, "0.1.0")
	st := testStore(t)
	ctx := context.Background()
	st.Write(ctx, func(w *store.W) error {
		return w.PutSession(&model.Session{ID: "s1", ProjectID: "p", Title: "Busy one", Status: model.SessionRunning})
	})
	exe := filepath.Join(t.TempDir(), "rad")
	os.WriteFile(exe, []byte("#!/bin/sh\necho rad 0.1.0\n"), 0o755)
	restarted := make(chan struct{})
	u := &selfUpdater{client: releases(t), st: st, exe: exe, restart: func() { close(restarted) }}

	info := u.Check(ctx, false)
	if info.Current != "0.1.0" || info.Latest != "0.2.0" || !info.Available || info.Via != "rad" || info.Reason != "" ||
		len(info.Busy) != 1 || info.Busy[0] != "Busy one" {
		t.Fatalf("check: %+v", info)
	}

	m := tasks.NewManager(nil)
	defer m.Shutdown()
	task := waitTask(t, m, m.Start(model.TaskUpdateServer, "", u.Install).ID)
	if task.Status != model.TaskSucceeded || task.Version != "0.2.0" || !strings.Contains(task.Output, "Restarting") {
		t.Fatalf("task: %+v", task)
	}
	if b, _ := os.ReadFile(exe); !strings.Contains(string(b), "0.2.0") {
		t.Fatalf("binary now: %s", b)
	}
	if b, _ := os.ReadFile(exe + ".old"); !strings.Contains(string(b), "0.1.0") {
		t.Fatalf("old binary: %s", b)
	}
	select {
	case <-restarted:
	case <-time.After(5 * time.Second):
		t.Fatal("rad didn't restart")
	}
}

func TestSelfUpdateDevelopmentBuild(t *testing.T) {
	withVersion(t, "dev")
	u := &selfUpdater{client: releases(t), st: testStore(t), exe: filepath.Join(t.TempDir(), "rad")}
	info := u.Check(context.Background(), false)
	if info.Available || info.Via != "" || !strings.Contains(info.Reason, "development build") || info.Latest != "0.2.0" {
		t.Fatalf("check: %+v", info)
	}
	withVersion(t, "0.1.0")
	u.exe = "/Applications/Remote Agent Server.app/Contents/MacOS/rad"
	if info := u.Check(context.Background(), false); info.Via != "" || !strings.Contains(info.Reason, "update it from the app's menu") {
		t.Fatalf("app's rad, unsupervised: %+v", info)
	}
}

// fakeApp plays the Server app: it reads rad's status lines and answers an
// update request with the given statuses.
func fakeApp(t *testing.T, answers ...appMessage) *supervisor {
	toApp, fromRad := io.Pipe()
	toRad, fromApp := io.Pipe()
	s := &supervisor{out: json.NewEncoder(fromRad), subs: map[chan appMessage]struct{}{}}
	go s.read(toRad)
	go func() {
		sc := bufio.NewScanner(toApp)
		for sc.Scan() {
			var req updateRequest
			if json.Unmarshal(sc.Bytes(), &req) != nil || req.Event != "update" {
				continue
			}
			for _, a := range answers {
				time.Sleep(10 * time.Millisecond)
				json.NewEncoder(fromApp).Encode(a)
			}
		}
	}()
	t.Cleanup(func() { fromRad.Close(); fromApp.Close() })
	return s
}

func TestSelfUpdateByApp(t *testing.T) {
	withVersion(t, "0.1.0")
	app := fakeApp(t,
		appMessage{Event: "updateStatus", Status: "checking"},
		appMessage{Event: "updateStatus", Status: "downloading", Version: "0.2.0"},
		appMessage{Event: "updateStatus", Status: "installing", Version: "0.2.0"})
	u := &selfUpdater{client: releases(t), st: testStore(t), exe: "/nowhere/rad", app: app,
		restart: func() { t.Error("rad restarted itself") }, appAnswer: 5 * time.Second, appInstall: 5 * time.Second}
	if info := u.Check(context.Background(), false); info.Via != "app" || !info.Available {
		t.Fatalf("check: %+v", info)
	}
	m := tasks.NewManager(nil)
	defer m.Shutdown()
	task := waitTask(t, m, m.Start(model.TaskUpdateServer, "", u.Install).ID)
	if task.Status != model.TaskSucceeded || task.Version != "0.2.0" ||
		!strings.Contains(task.Output, "Downloading Remote Agent Server 0.2.0") || !strings.Contains(task.Output, "restarts") {
		t.Fatalf("task: %+v", task)
	}

	// The app finds nothing to install.
	u.app = fakeApp(t, appMessage{Event: "updateStatus", Status: "upToDate", Message: "Remote Agent Server 0.1.0 is the latest"})
	if task := waitTask(t, m, m.Start(model.TaskUpdateServer, "", u.Install).ID); task.Status != model.TaskFailed || !strings.Contains(task.Error, "is the latest") {
		t.Fatalf("up to date: %+v", task)
	}

	// An app that doesn't know the request says nothing.
	u.app, u.appAnswer = fakeApp(t), 100*time.Millisecond
	if task := waitTask(t, m, m.Start(model.TaskUpdateServer, "", u.Install).ID); task.Status != model.TaskFailed || !strings.Contains(task.Error, "didn't answer") {
		t.Fatalf("no answer: %+v", task)
	}
}
