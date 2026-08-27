package events

import "testing"

func TestIDsNeverRepeatAcrossRestarts(t *testing.T) {
	dir := t.TempDir()
	s, err := Open(dir)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	first := s.Add(KindAgentStart, "", "", "started", nil)
	if first.EvtID != 1 {
		t.Errorf("first evtId = %d, want 1", first.EvtID)
	}
	second := s.Add(KindExecAck, "e1", "t1", "accepted", nil)
	if second.EvtID != 2 {
		t.Errorf("second evtId = %d, want 2", second.EvtID)
	}

	// Restarting must not hand out an id that was already used, even though
	// the previous process never got to persist its exact counter.
	restarted, err := Open(dir)
	if err != nil {
		t.Fatalf("reopen: %v", err)
	}
	next := restarted.Add(KindAgentStart, "", "", "restarted", nil)
	if next.EvtID <= second.EvtID {
		t.Errorf("evtId after restart = %d, want greater than %d", next.EvtID, second.EvtID)
	}
}

func TestPendingAndAck(t *testing.T) {
	s, err := Open(t.TempDir())
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	for i := 0; i < 5; i++ {
		s.Add(KindExecStart, "e", "t", "msg", map[string]string{"i": "x"})
	}
	if s.Len() != 5 {
		t.Fatalf("len = %d, want 5", s.Len())
	}

	batch := s.Pending(3)
	if len(batch) != 3 || batch[0].EvtID != 1 {
		t.Fatalf("batch = %+v, want the three oldest", batch)
	}
	s.Ack(batch[len(batch)-1].EvtID)
	if s.Len() != 2 {
		t.Errorf("len = %d, want 2 after acking three", s.Len())
	}
	if got := s.Pending(10)[0].EvtID; got != 4 {
		t.Errorf("next pending evtId = %d, want 4", got)
	}
	if s.LastID() != 5 {
		t.Errorf("lastId = %d, want 5", s.LastID())
	}
}

func TestBufferIsBounded(t *testing.T) {
	s, err := Open(t.TempDir())
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	for i := 0; i < maxBuffered+50; i++ {
		s.Add(KindWarn, "", "", "flood", nil)
	}
	if s.Len() > maxBuffered {
		t.Errorf("len = %d, want at most %d", s.Len(), maxBuffered)
	}
	if s.Dropped() == 0 {
		t.Error("dropping events should be counted")
	}
	// The newest events are the ones worth keeping.
	pending := s.Pending(maxBuffered)
	if pending[len(pending)-1].EvtID != s.LastID() {
		t.Error("the most recent event should still be queued")
	}
}
