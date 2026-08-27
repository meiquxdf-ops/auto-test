package journal

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/atest/atagent/internal/proto"
)

func TestAppendBatchAck(t *testing.T) {
	j, err := Open(t.TempDir(), "e-1", 1<<20)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer j.Close()

	for i := 1; i <= 5; i++ {
		if got := j.Append(proto.StreamStdout, fmt.Sprintf("line %d", i)); got != int64(i) {
			t.Fatalf("sequence = %d, want %d", got, i)
		}
	}

	fromSeq, lines := j.Batch(10, 1<<20)
	if fromSeq != 0 {
		t.Errorf("fromSeq = %d, want 0 for the first batch", fromSeq)
	}
	if len(lines) != 5 {
		t.Fatalf("batch size = %d, want 5", len(lines))
	}

	j.Ack(3)
	fromSeq, lines = j.Batch(10, 1<<20)
	if fromSeq != 3 {
		t.Errorf("fromSeq = %d, want 3 after acking", fromSeq)
	}
	if len(lines) != 2 || lines[0].Seq != 4 {
		t.Fatalf("batch = %+v, want sequences 4 and 5", lines)
	}

	j.Ack(5)
	if j.HasPending() {
		t.Error("nothing should be pending once everything is acked")
	}
	if _, lines = j.Batch(10, 1<<20); len(lines) != 0 {
		t.Errorf("batch = %+v, want empty", lines)
	}
}

func TestBatchRespectsLimits(t *testing.T) {
	j, err := Open(t.TempDir(), "e-limits", 1<<20)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer j.Close()

	for i := 0; i < 50; i++ {
		j.Append(proto.StreamStdout, strings.Repeat("x", 100))
	}
	if _, lines := j.Batch(10, 1<<20); len(lines) != 10 {
		t.Errorf("line limit ignored: got %d lines", len(lines))
	}
	if _, lines := j.Batch(1000, 500); len(lines) >= 50 {
		t.Errorf("byte limit ignored: got %d lines", len(lines))
	}
}

// The journal keeps the tail and reports the gap, which is how the server
// learns a log was truncated.
func TestTailCapDropsOldestAndFlagsTruncation(t *testing.T) {
	const cap = 4 << 10
	j, err := Open(t.TempDir(), "e-tail", cap)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer j.Close()

	payload := strings.Repeat("y", 200)
	for i := 0; i < 200; i++ {
		j.Append(proto.StreamStdout, fmt.Sprintf("%03d-%s", i, payload))
	}

	st := j.Stats()
	if !st.Truncated {
		t.Fatal("the journal should be flagged truncated")
	}
	if st.Bytes > cap {
		t.Errorf("retained %d bytes, want at most %d", st.Bytes, cap)
	}
	if st.DroppedLines == 0 {
		t.Error("dropped line count should be non zero")
	}
	if st.LastSeq != 200 {
		t.Errorf("lastSeq = %d, want 200", st.LastSeq)
	}

	fromSeq, lines := j.Batch(1000, 1<<20)
	if fromSeq == 0 {
		t.Error("fromSeq should point past the dropped head so the server sees the gap")
	}
	if len(lines) == 0 || lines[len(lines)-1].Seq != 200 {
		t.Errorf("the newest line must be retained, got %+v", lines[len(lines)-1:])
	}

	// The file on disk must stay bounded too.
	info, err := os.Stat(j.Path())
	if err != nil {
		t.Fatalf("stat: %v", err)
	}
	if info.Size() > 4*cap {
		t.Errorf("journal file is %d bytes, want it compacted near the %d byte cap", info.Size(), cap)
	}
}

func TestLastLineSurvivesTruncation(t *testing.T) {
	j, err := Open(t.TempDir(), "e-last", 1<<10)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer j.Close()

	for i := 0; i < 100; i++ {
		j.Append(proto.StreamStdout, fmt.Sprintf("line-%d", i))
	}
	j.Append(proto.StreamStdout, "final-verdict")
	// Agent notes and blank lines must not become the verdict line.
	j.Append(proto.StreamStdout, "   ")
	j.Note("killed by operator")

	if got := j.LastLine(); got != "final-verdict" {
		t.Errorf("lastLine = %q, want final-verdict", got)
	}
}

func TestRemoveDeletesTheFile(t *testing.T) {
	dir := t.TempDir()
	j, err := Open(dir, "e-remove", 1<<20)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	j.Append(proto.StreamStdout, "hello")
	path := j.Path()
	if err := j.Remove(); err != nil {
		t.Fatalf("remove: %v", err)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Errorf("journal file still exists: %v", err)
	}
	if err := j.Remove(); err != nil {
		t.Errorf("a second remove should be a no-op, got %v", err)
	}
}

func TestCleanupKeepsReferencedJournals(t *testing.T) {
	dir := t.TempDir()
	keep, err := Open(dir, "e-keep", 1<<20)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	keep.Append(proto.StreamStdout, "still needed")
	keep.Close()

	orphan, err := Open(dir, "e-orphan", 1<<20)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	orphan.Append(proto.StreamStdout, "nobody is coming back for this")
	orphan.Close()

	removed, err := Cleanup(dir, []string{"e-keep"})
	if err != nil {
		t.Fatalf("cleanup: %v", err)
	}
	if removed != 1 {
		t.Errorf("removed = %d, want 1", removed)
	}
	if _, err := os.Stat(keep.Path()); err != nil {
		t.Errorf("the referenced journal should survive: %v", err)
	}
	if _, err := os.Stat(orphan.Path()); !os.IsNotExist(err) {
		t.Errorf("the orphan journal should be gone: %v", err)
	}

	if _, err := Cleanup(filepath.Join(dir, "missing"), nil); err != nil {
		t.Errorf("cleanup of a missing directory should be a no-op, got %v", err)
	}
}

func TestExecutionIDCannotEscapeTheJournalDirectory(t *testing.T) {
	dir := t.TempDir()
	j, err := Open(dir, "../../etc/passwd", 1<<20)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer j.Remove()
	if got := filepath.Dir(j.Path()); got != dir {
		t.Errorf("journal was written to %q, want it inside %q", got, dir)
	}
	if strings.ContainsAny(filepath.Base(j.Path()), `/\`) {
		t.Errorf("journal file name %q contains a path separator", filepath.Base(j.Path()))
	}
}
