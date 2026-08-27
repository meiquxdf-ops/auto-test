// Package journal stores the tail of one execution's output on local disk and
// hands batches to the uploader.
//
// The journal is the agent's source of truth for logs: it keeps at most the
// last 5MB (configurable) of an execution, so a runaway command can never fill
// the disk or memory, and a disconnect never loses the recent output. Once the
// cap is hit the oldest lines are dropped and the journal is flagged truncated;
// the log frames then carry a `fromSeq` that does not follow the last
// acknowledged sequence, which is how the server learns about the gap.
package journal

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/atest/atagent/internal/proto"
)

// Stats is a point in time summary of one journal.
type Stats struct {
	Lines        int64 `json:"lines"`
	Bytes        int64 `json:"bytes"`
	TotalBytes   int64 `json:"totalBytes"`
	LastSeq      int64 `json:"lastSeq"`
	AckedSeq     int64 `json:"ackedSeq"`
	DroppedLines int64 `json:"droppedLines"`
	Truncated    bool  `json:"truncated"`
}

// Journal is safe for concurrent use.
type Journal struct {
	mu   sync.Mutex
	path string
	max  int64

	f *os.File
	w *bufio.Writer

	lines []proto.LogLine
	bytes int64

	nextSeq      int64
	ackedSeq     int64
	totalBytes   int64
	droppedLines int64
	droppedSince int64
	truncated    bool
	lastLine     string
	closed       bool
	removed      bool
}

// Dir returns the directory journals live in for a given data directory.
func Dir(dataDir string) string { return filepath.Join(dataDir, "journal") }

// Open creates the journal file for executeID under dir. maxBytes is the size
// of the retained tail.
func Open(dir, executeID string, maxBytes int64) (*Journal, error) {
	if maxBytes <= 0 {
		maxBytes = 5 << 20
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, fmt.Errorf("create journal dir %s: %w", dir, err)
	}
	path := filepath.Join(dir, safeName(executeID)+".log")
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return nil, fmt.Errorf("open journal %s: %w", path, err)
	}
	return &Journal{
		path:    path,
		max:     maxBytes,
		f:       f,
		w:       bufio.NewWriterSize(f, 64<<10),
		nextSeq: 1,
	}, nil
}

// Append records one line and returns its sequence number.
func (j *Journal) Append(stream, text string) int64 {
	text = sanitize(text)
	j.mu.Lock()
	defer j.mu.Unlock()

	line := proto.LogLine{Seq: j.nextSeq, TS: time.Now().UnixMilli(), S: stream, X: text}
	j.nextSeq++
	cost := int64(len(text)) + 1
	j.lines = append(j.lines, line)
	j.bytes += cost
	j.totalBytes += cost
	if stream != proto.StreamAgent && strings.TrimSpace(text) != "" {
		j.lastLine = text
	}
	j.writeLocked(line)
	j.trimLocked()
	return line.Seq
}

// Note records an agent generated line (kill notices, start failures) in the
// same stream so operators see it in the execution log.
func (j *Journal) Note(format string, args ...any) int64 {
	return j.Append(proto.StreamAgent, fmt.Sprintf(format, args...))
}

func (j *Journal) writeLocked(line proto.LogLine) {
	if j.w == nil {
		return
	}
	enc, err := json.Marshal(line)
	if err != nil {
		return
	}
	j.w.Write(enc)
	j.w.WriteByte('\n')
}

// trimLocked enforces the tail cap and compacts the on disk file once enough
// has been dropped to make a rewrite worthwhile.
func (j *Journal) trimLocked() {
	dropped := false
	for j.bytes > j.max && len(j.lines) > 1 {
		cost := int64(len(j.lines[0].X)) + 1
		j.bytes -= cost
		j.droppedSince += cost
		j.lines = j.lines[1:]
		j.droppedLines++
		j.truncated = true
		dropped = true
	}
	if dropped && j.droppedSince > j.max/4 {
		j.compactLocked()
	}
}

// compactLocked rewrites the journal file so it holds exactly the live window.
func (j *Journal) compactLocked() {
	if j.f == nil {
		return
	}
	j.droppedSince = 0
	tmp := j.path + ".tmp"
	f, err := os.OpenFile(tmp, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return
	}
	w := bufio.NewWriterSize(f, 64<<10)
	fmt.Fprintf(w, "{\"seq\":0,\"ts\":%d,\"s\":\"x\",\"x\":\"[atagent] journal truncated, %d earlier lines dropped\"}\n",
		time.Now().UnixMilli(), j.droppedLines)
	for _, line := range j.lines {
		if enc, err := json.Marshal(line); err == nil {
			w.Write(enc)
			w.WriteByte('\n')
		}
	}
	if err := w.Flush(); err != nil {
		f.Close()
		os.Remove(tmp)
		return
	}
	f.Close()
	if err := os.Rename(tmp, j.path); err != nil {
		os.Remove(tmp)
		return
	}
	j.w.Reset(nil)
	j.f.Close()
	nf, err := os.OpenFile(j.path, os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		j.f, j.w = nil, nil
		return
	}
	j.f = nf
	j.w = bufio.NewWriterSize(nf, 64<<10)
}

// Batch returns up to maxLines/maxBytes unacknowledged lines together with the
// sequence they follow. A fromSeq greater than the last acknowledged sequence
// means the head of the log was dropped by the tail cap.
func (j *Journal) Batch(maxLines int, maxBytes int64) (fromSeq int64, lines []proto.LogLine) {
	j.mu.Lock()
	defer j.mu.Unlock()
	if len(j.lines) == 0 {
		return j.ackedSeq, nil
	}
	base := j.lines[0].Seq
	start := 0
	if j.ackedSeq >= base {
		start = int(j.ackedSeq - base + 1)
	}
	if start >= len(j.lines) {
		return j.ackedSeq, nil
	}
	fromSeq = j.lines[start].Seq - 1
	var size int64
	for i := start; i < len(j.lines) && len(lines) < maxLines; i++ {
		size += int64(len(j.lines[i].X)) + 64
		if size > maxBytes && len(lines) > 0 {
			break
		}
		lines = append(lines, j.lines[i])
	}
	return fromSeq, lines
}

// Ack marks every sequence up to seq as accepted by the server.
func (j *Journal) Ack(seq int64) {
	j.mu.Lock()
	defer j.mu.Unlock()
	if seq > j.ackedSeq {
		j.ackedSeq = seq
	}
	if j.ackedSeq > j.nextSeq-1 {
		j.ackedSeq = j.nextSeq - 1
	}
}

// HasPending reports whether any line still needs to be uploaded.
func (j *Journal) HasPending() bool {
	j.mu.Lock()
	defer j.mu.Unlock()
	if len(j.lines) == 0 {
		return false
	}
	return j.ackedSeq < j.lines[len(j.lines)-1].Seq
}

// LastLine is the last non blank process output line, used by the server for
// the final verdict. It survives truncation.
func (j *Journal) LastLine() string {
	j.mu.Lock()
	defer j.mu.Unlock()
	return j.lastLine
}

// Stats snapshots the counters.
func (j *Journal) Stats() Stats {
	j.mu.Lock()
	defer j.mu.Unlock()
	return Stats{
		Lines:        int64(len(j.lines)),
		Bytes:        j.bytes,
		TotalBytes:   j.totalBytes,
		LastSeq:      j.nextSeq - 1,
		AckedSeq:     j.ackedSeq,
		DroppedLines: j.droppedLines,
		Truncated:    j.truncated,
	}
}

// Path is the journal file location.
func (j *Journal) Path() string { return j.path }

// Sync flushes buffered lines to the file.
func (j *Journal) Sync() error {
	j.mu.Lock()
	defer j.mu.Unlock()
	if j.w == nil {
		return nil
	}
	return j.w.Flush()
}

// Close flushes and closes the file; the journal stays readable in memory.
func (j *Journal) Close() error {
	j.mu.Lock()
	defer j.mu.Unlock()
	if j.closed {
		return nil
	}
	j.closed = true
	var err error
	if j.w != nil {
		err = j.w.Flush()
	}
	if j.f != nil {
		if cerr := j.f.Close(); err == nil {
			err = cerr
		}
	}
	j.w, j.f = nil, nil
	return err
}

// Remove deletes the journal file. Called once the fin frame is acknowledged.
func (j *Journal) Remove() error {
	_ = j.Close()
	j.mu.Lock()
	defer j.mu.Unlock()
	if j.removed {
		return nil
	}
	j.removed = true
	j.lines, j.bytes = nil, 0
	if err := os.Remove(j.path); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

// Cleanup removes journals left behind by a previous run. Executions whose fin
// is still undelivered are kept, everything else is an orphan: the processes
// did not survive the restart, so nothing will ever read those files again.
func Cleanup(dir string, keep []string) (int, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			return 0, nil
		}
		return 0, err
	}
	wanted := make(map[string]bool, len(keep))
	for _, id := range keep {
		wanted[safeName(id)+".log"] = true
	}

	removed := 0
	for _, e := range entries {
		if e.IsDir() || wanted[e.Name()] {
			continue
		}
		if !strings.HasSuffix(e.Name(), ".log") && !strings.HasSuffix(e.Name(), ".log.tmp") {
			continue
		}
		if err := os.Remove(filepath.Join(dir, e.Name())); err == nil {
			removed++
		}
	}
	return removed, nil
}

// safeName keeps arbitrary execution ids from escaping the journal directory.
func safeName(s string) string {
	if s == "" {
		return "unknown"
	}
	var b strings.Builder
	for _, r := range s {
		switch {
		case r >= 'a' && r <= 'z', r >= 'A' && r <= 'Z', r >= '0' && r <= '9', r == '-', r == '_', r == '.':
			b.WriteRune(r)
		default:
			b.WriteByte('_')
		}
	}
	out := b.String()
	if len(out) > 96 {
		out = out[:96]
	}
	return strings.TrimLeft(out, ".")
}

func sanitize(s string) string {
	s = strings.TrimRight(s, "\r\n")
	s = strings.ReplaceAll(s, "\x00", "")
	return s
}
