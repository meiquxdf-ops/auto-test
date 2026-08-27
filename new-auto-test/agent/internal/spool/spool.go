// Package spool persists fin frames until the server acknowledges them.
//
// A fin is the only frame that decides an execution's terminal state, so it
// must survive a disconnect and an agent restart. Each pending fin is one JSON
// file under <dataDir>/spool/fin; the file is removed the moment the server
// answers ok.
package spool

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/atest/atagent/internal/proto"
)

// Item is one pending fin plus its local delivery bookkeeping.
type Item struct {
	Fin      proto.FinArgs
	Attempts int
	LastSent time.Time
	Restored bool

	path string
}

// Spool is safe for concurrent use.
type Spool struct {
	mu    sync.Mutex
	dir   string
	items map[string]*Item
}

// Dir returns the fin spool directory for a data directory.
func Dir(dataDir string) string { return filepath.Join(dataDir, "spool", "fin") }

// Open loads every fin left over from a previous run.
func Open(dir string) (*Spool, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, fmt.Errorf("create spool dir %s: %w", dir, err)
	}
	s := &Spool{dir: dir, items: map[string]*Item{}}
	entries, err := os.ReadDir(dir)
	if err != nil {
		return nil, fmt.Errorf("read spool dir %s: %w", dir, err)
	}
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		path := filepath.Join(dir, e.Name())
		raw, err := os.ReadFile(path)
		if err != nil {
			continue
		}
		var fin proto.FinArgs
		if err := json.Unmarshal(raw, &fin); err != nil || fin.ExecuteID == "" {
			// A truncated file cannot be delivered; drop it rather than
			// retrying a broken frame forever.
			os.Remove(path)
			continue
		}
		s.items[fin.ExecuteID] = &Item{Fin: fin, Restored: true, path: path}
	}
	return s, nil
}

// Add persists a fin and queues it for delivery.
func (s *Spool) Add(fin proto.FinArgs) error {
	if fin.ExecuteID == "" {
		return fmt.Errorf("fin without executeId")
	}
	path := filepath.Join(s.dir, safeName(fin.ExecuteID)+".json")
	raw, err := json.Marshal(fin)
	if err != nil {
		return fmt.Errorf("encode fin: %w", err)
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, raw, 0o644); err != nil {
		return fmt.Errorf("write %s: %w", tmp, err)
	}
	if err := os.Rename(tmp, path); err != nil {
		return fmt.Errorf("install %s: %w", path, err)
	}

	s.mu.Lock()
	defer s.mu.Unlock()
	s.items[fin.ExecuteID] = &Item{Fin: fin, path: path}
	return nil
}

// Pending returns a snapshot ordered by finish time, oldest first.
func (s *Spool) Pending() []Item {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]Item, 0, len(s.items))
	for _, it := range s.items {
		out = append(out, *it)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Fin.FinishedAt < out[j].Fin.FinishedAt })
	return out
}

// MarkSent records a delivery attempt.
func (s *Spool) MarkSent(executeID string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if it, ok := s.items[executeID]; ok {
		it.Attempts++
		it.LastSent = time.Now()
	}
}

// Attempts reports how many times a fin has been sent.
func (s *Spool) Attempts(executeID string) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	if it, ok := s.items[executeID]; ok {
		return it.Attempts
	}
	return 0
}

// Ack removes an acknowledged fin from disk.
func (s *Spool) Ack(executeID string) error {
	s.mu.Lock()
	it, ok := s.items[executeID]
	if ok {
		delete(s.items, executeID)
	}
	s.mu.Unlock()
	if !ok {
		return nil
	}
	if err := os.Remove(it.path); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

// Len is the number of undelivered fins.
func (s *Spool) Len() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.items)
}

// IDs lists the executions with an undelivered fin; reported in hello so the
// server knows results are still on the way.
func (s *Spool) IDs() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]string, 0, len(s.items))
	for id := range s.items {
		out = append(out, id)
	}
	sort.Strings(out)
	return out
}

// ResetAttempts is called after a reconnect so pending fins go out immediately
// instead of waiting for the previous session's backoff.
func (s *Spool) ResetAttempts() {
	s.mu.Lock()
	defer s.mu.Unlock()
	for _, it := range s.items {
		it.LastSent = time.Time{}
	}
}

func safeName(s string) string {
	var b strings.Builder
	for _, r := range s {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '-', r == '_':
			b.WriteRune(r)
		default:
			b.WriteByte('_')
		}
	}
	out := b.String()
	if len(out) > 96 {
		out = out[:96]
	}
	if out == "" {
		out = "unknown"
	}
	return out
}
