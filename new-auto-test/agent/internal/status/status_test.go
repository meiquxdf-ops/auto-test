package status

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func sampleSnapshot() Snapshot {
	now := time.Now().UnixMilli()
	return Snapshot{
		AgentID:     "11111111-2222-3333-4444-555555555555",
		Tag:         "build-01",
		BootID:      "abc123",
		PID:         4242,
		StartedAt:   now - 60000,
		Now:         now,
		UptimeSec:   60,
		Server:      "127.0.0.1:9800",
		Connected:   true,
		Concurrency: 2,
		Running: []Exec{{
			ExecuteID: "e-1",
			Token:     "t-1",
			PID:       5150,
			Command:   "make test",
			State:     "running",
			StartedAt: now - 5000,
			LogSeq:    120,
			AckedSeq:  100,
			LogBytes:  4096,
		}},
		DataDir: "/var/lib/atagent",
	}
}

func TestServeOverUnixSocket(t *testing.T) {
	dir := t.TempDir()
	socket := filepath.Join(dir, "atagent.sock")
	file := filepath.Join(dir, "status.json")

	srv := NewServer(socket, file, sampleSnapshot)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := srv.Start(ctx); err != nil {
		t.Fatalf("start: %v", err)
	}
	defer srv.Close()

	snap, source, err := Fetch(socket, file)
	if err != nil {
		t.Fatalf("fetch: %v", err)
	}
	if !strings.Contains(source, "unix socket") {
		t.Errorf("source = %q, want the live socket to be preferred", source)
	}
	if snap.AgentID != sampleSnapshot().AgentID || len(snap.Running) != 1 {
		t.Errorf("snapshot = %+v", snap)
	}

	// A second agent must not silently take over the socket.
	other := NewServer(socket, file, sampleSnapshot)
	if err := other.Start(ctx); err == nil {
		other.Close()
		t.Fatal("starting a second agent on the same socket should fail")
	}
}

func TestFallsBackToStatusFile(t *testing.T) {
	dir := t.TempDir()
	socket := filepath.Join(dir, "atagent.sock")
	file := filepath.Join(dir, "status.json")

	srv := NewServer(socket, file, sampleSnapshot)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	if err := srv.Start(ctx); err != nil {
		t.Fatalf("start: %v", err)
	}
	srv.Close()

	if _, err := os.Stat(file); err != nil {
		t.Fatalf("status file should have been written: %v", err)
	}
	snap, source, err := Fetch(socket, file)
	if err != nil {
		t.Fatalf("fetch: %v", err)
	}
	if !strings.Contains(source, "file") {
		t.Errorf("source = %q, want the file fallback", source)
	}
	if snap.Tag != "build-01" {
		t.Errorf("tag = %q", snap.Tag)
	}
}

func TestFetchWithoutAnySourceFails(t *testing.T) {
	dir := t.TempDir()
	if _, _, err := Fetch(filepath.Join(dir, "missing.sock"), filepath.Join(dir, "missing.json")); err == nil {
		t.Fatal("fetching with no socket and no file should fail")
	}
}

func TestTextRendering(t *testing.T) {
	out := sampleSnapshot().Text("unix socket /run/atagent.sock")
	for _, want := range []string{"build-01", "connected", "1 running / 2 concurrency", "make test", "e-1"} {
		if !strings.Contains(out, want) {
			t.Errorf("status text is missing %q:\n%s", want, out)
		}
	}
}
