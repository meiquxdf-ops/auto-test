// Package events buffers agent side timeline entries until the server
// acknowledges them. (agentId, evtId) is the idempotency key, so evtId must
// never repeat - even across restarts - which is why the counter is reserved
// on disk in blocks.
package events

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/atest/atagent/internal/proto"
)

// Event kinds emitted by the agent.
const (
	KindAgentStart   = "agent_start"
	KindAgentStop    = "agent_stop"
	KindConnected    = "connected"
	KindDisconnected = "disconnected"
	KindExecAck      = "exec_ack"
	KindExecReject   = "exec_reject"
	KindExecStart    = "exec_start"
	KindExecExit     = "exec_exit"
	KindExecCancel   = "exec_cancel"
	KindExecTimeout  = "exec_timeout"
	KindStopAll      = "stop_all"
	KindReconcile    = "reconcile"
	KindConfigChange = "config_change"
	KindWarn         = "warn"
)

const (
	seqFileName  = "evt-seq"
	reserveBlock = 128
	maxBuffered  = 4096
)

// Store is safe for concurrent use.
type Store struct {
	mu       sync.Mutex
	seqPath  string
	next     int64
	reserved int64
	pending  []proto.Event
	dropped  int64
}

// Open loads the persisted event counter from dataDir.
func Open(dataDir string) (*Store, error) {
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		return nil, fmt.Errorf("create %s: %w", dataDir, err)
	}
	s := &Store{seqPath: filepath.Join(dataDir, seqFileName), next: 1}
	raw, err := os.ReadFile(s.seqPath)
	if err == nil {
		if n, perr := strconv.ParseInt(strings.TrimSpace(string(raw)), 10, 64); perr == nil && n > 0 {
			s.next = n
		}
	} else if !os.IsNotExist(err) {
		return nil, fmt.Errorf("read %s: %w", s.seqPath, err)
	}
	s.reserved = s.next
	if err := s.reserveLocked(); err != nil {
		return nil, err
	}
	return s, nil
}

// reserveLocked pushes the persisted high water mark ahead of the ids handed
// out so far, so a crash can only skip ids, never reuse them.
func (s *Store) reserveLocked() error {
	if s.next < s.reserved {
		return nil
	}
	s.reserved = s.next + reserveBlock
	tmp := s.seqPath + ".tmp"
	if err := os.WriteFile(tmp, []byte(strconv.FormatInt(s.reserved, 10)+"\n"), 0o644); err != nil {
		return fmt.Errorf("write %s: %w", tmp, err)
	}
	if err := os.Rename(tmp, s.seqPath); err != nil {
		return fmt.Errorf("install %s: %w", s.seqPath, err)
	}
	return nil
}

// Add queues one event and returns it.
func (s *Store) Add(kind, executeID, token, msg string, data map[string]string) proto.Event {
	s.mu.Lock()
	defer s.mu.Unlock()
	evt := proto.Event{
		EvtID:     s.next,
		TS:        time.Now().UnixMilli(),
		Kind:      kind,
		ExecuteID: executeID,
		Token:     token,
		Msg:       msg,
		Data:      data,
	}
	s.next++
	_ = s.reserveLocked()

	if len(s.pending) >= maxBuffered {
		// The server has been unreachable for a very long time: keep the most
		// recent history and count what was lost.
		drop := len(s.pending) - maxBuffered + 1
		s.pending = append(s.pending[:0], s.pending[drop:]...)
		s.dropped += int64(drop)
	}
	s.pending = append(s.pending, evt)
	return evt
}

// Pending returns at most max queued events, oldest first.
func (s *Store) Pending(max int) []proto.Event {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.pending) == 0 {
		return nil
	}
	if max <= 0 || max > len(s.pending) {
		max = len(s.pending)
	}
	out := make([]proto.Event, max)
	copy(out, s.pending[:max])
	return out
}

// Ack drops every queued event with an id up to evtID.
func (s *Store) Ack(evtID int64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	keep := 0
	for keep < len(s.pending) && s.pending[keep].EvtID <= evtID {
		keep++
	}
	if keep > 0 {
		s.pending = append(s.pending[:0], s.pending[keep:]...)
	}
}

// LastID is the highest event id generated so far, reported in hello.
func (s *Store) LastID() int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.next - 1
}

// Len is the number of queued events.
func (s *Store) Len() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.pending)
}

// Dropped counts events discarded because the buffer was full.
func (s *Store) Dropped() int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.dropped
}
