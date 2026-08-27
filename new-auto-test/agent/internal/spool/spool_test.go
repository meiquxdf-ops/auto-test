package spool

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/atest/atagent/internal/proto"
)

func TestFinSurvivesRestart(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "fin")
	s, err := Open(dir)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	fin := proto.FinArgs{ExecuteID: "e-1", Token: "t-1", ExitCode: 0, Reason: proto.ReasonExited, LastLine: "ok"}
	if err := s.Add(fin); err != nil {
		t.Fatalf("add: %v", err)
	}
	if s.Len() != 1 {
		t.Fatalf("len = %d, want 1", s.Len())
	}

	// A fresh Spool over the same directory is what happens after a restart.
	reopened, err := Open(dir)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	pending := reopened.Pending()
	if len(pending) != 1 {
		t.Fatalf("pending = %d, want 1", len(pending))
	}
	if !pending[0].Restored {
		t.Error("a fin loaded from disk should be flagged as restored")
	}
	if pending[0].Fin.LastLine != "ok" || pending[0].Fin.Token != "t-1" {
		t.Errorf("restored fin = %+v", pending[0].Fin)
	}

	if err := reopened.Ack("e-1"); err != nil {
		t.Fatalf("ack: %v", err)
	}
	if reopened.Len() != 0 {
		t.Errorf("len = %d, want 0 after ack", reopened.Len())
	}
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		if filepath.Ext(e.Name()) == ".json" {
			t.Errorf("file %s should have been removed", e.Name())
		}
	}
}

func TestAttemptsAndOrdering(t *testing.T) {
	s, err := Open(filepath.Join(t.TempDir(), "fin"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	if err := s.Add(proto.FinArgs{ExecuteID: "later", FinishedAt: 200}); err != nil {
		t.Fatalf("add: %v", err)
	}
	if err := s.Add(proto.FinArgs{ExecuteID: "earlier", FinishedAt: 100}); err != nil {
		t.Fatalf("add: %v", err)
	}

	pending := s.Pending()
	if len(pending) != 2 || pending[0].Fin.ExecuteID != "earlier" {
		t.Fatalf("pending order = %+v, want oldest first", pending)
	}

	s.MarkSent("earlier")
	s.MarkSent("earlier")
	if got := s.Attempts("earlier"); got != 2 {
		t.Errorf("attempts = %d, want 2", got)
	}
	s.ResetAttempts()
	for _, it := range s.Pending() {
		if !it.LastSent.IsZero() {
			t.Error("ResetAttempts should clear the retry timer so a reconnect resends immediately")
		}
	}

	ids := s.IDs()
	if len(ids) != 2 || ids[0] != "earlier" || ids[1] != "later" {
		t.Errorf("ids = %v", ids)
	}
}

func TestCorruptFinIsDiscarded(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "fin")
	if err := os.MkdirAll(dir, 0o755); err != nil {
		t.Fatalf("mkdir: %v", err)
	}
	bad := filepath.Join(dir, "broken.json")
	if err := os.WriteFile(bad, []byte("{not json"), 0o644); err != nil {
		t.Fatalf("write: %v", err)
	}

	s, err := Open(dir)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	if s.Len() != 0 {
		t.Errorf("len = %d, want the unreadable file skipped", s.Len())
	}
	if _, err := os.Stat(bad); !os.IsNotExist(err) {
		t.Error("an undeliverable file should be removed instead of retried forever")
	}
}
