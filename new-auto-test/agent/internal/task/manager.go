// Package task runs dispatched commands and owns everything about a local
// execution: the process group, the log journal, cancellation and the fin
// frame that closes it out.
package task

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"syscall"
	"time"

	"github.com/atest/atagent/internal/journal"
	"github.com/atest/atagent/internal/proto"
)

// Rejection reasons surfaced to the server as error codes.
var (
	// ErrBusy means the concurrency slot count is exhausted.
	ErrBusy = errors.New("agent is busy")
	// ErrDupToken means this dispatch token is already known.
	ErrDupToken = errors.New("duplicate dispatch token")
	// ErrShuttingDown means the agent is on its way out.
	ErrShuttingDown = errors.New("agent is shutting down")
)

// Spec is one dispatch.
type Spec struct {
	ExecuteID  string
	Token      string
	TaskID     string
	Command    string
	Cwd        string
	Env        map[string]string
	TimeoutSec int
	Shell      string
}

// Options configure the manager.
type Options struct {
	Shell        string
	JournalDir   string
	MaxLogBytes  int64
	KillGrace    time.Duration
	MaxLineBytes int
	BaseEnv      map[string]string
	AgentID      string
	BootID       string
	Tag          string

	// OnLog wakes the uploader when new output is available.
	OnLog func()
	// OnFinish is called once with the completed execution.
	OnFinish func(*Execution)
	// OnEvent records a timeline event.
	OnEvent func(kind, executeID, token, msg string, data map[string]string)
}

// Manager tracks every execution on this machine.
type Manager struct {
	opt Options

	mu          sync.Mutex
	concurrency int
	byToken     map[string]*Execution
	byID        map[string]*Execution
	shutdown    bool
}

// New builds a manager with the given concurrency (1..4).
func New(concurrency int, opt Options) *Manager {
	if opt.Shell == "" {
		opt.Shell = "/bin/bash"
	}
	if opt.MaxLogBytes <= 0 {
		opt.MaxLogBytes = 5 << 20
	}
	if opt.MaxLineBytes <= 0 {
		opt.MaxLineBytes = 64 << 10
	}
	if opt.KillGrace <= 0 {
		opt.KillGrace = 5 * time.Second
	}
	if opt.OnLog == nil {
		opt.OnLog = func() {}
	}
	if opt.OnFinish == nil {
		opt.OnFinish = func(*Execution) {}
	}
	if opt.OnEvent == nil {
		opt.OnEvent = func(string, string, string, string, map[string]string) {}
	}
	if concurrency < 1 {
		concurrency = 1
	}
	return &Manager{
		opt:         opt,
		concurrency: concurrency,
		byToken:     map[string]*Execution{},
		byID:        map[string]*Execution{},
	}
}

// Accept reserves a slot for spec. It performs every check that can reject a
// dispatch so the caller can ACK before the process is started. The returned
// execution is not running yet: call Start on it.
//
// The whole reservation happens under the manager lock, so a second dispatch
// racing this one is rejected instead of over subscribing the machine, and no
// caller can ever observe an execution before its journal exists.
func (m *Manager) Accept(spec Spec) (*Execution, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.shutdown {
		return nil, ErrShuttingDown
	}
	if _, ok := m.byToken[spec.Token]; ok {
		return nil, ErrDupToken
	}
	if _, ok := m.byID[spec.ExecuteID]; ok {
		return nil, ErrDupToken
	}
	if m.runningLocked() >= m.concurrency {
		return nil, ErrBusy
	}

	jr, err := journal.Open(m.opt.JournalDir, spec.ExecuteID, m.opt.MaxLogBytes)
	if err != nil {
		return nil, fmt.Errorf("open journal: %w", err)
	}
	e := &Execution{
		spec:       spec,
		mgr:        m,
		journal:    jr,
		acceptedAt: time.Now(),
		done:       make(chan struct{}),
	}
	m.byToken[spec.Token] = e
	m.byID[spec.ExecuteID] = e
	return e, nil
}

func (m *Manager) runningLocked() int {
	n := 0
	for _, e := range m.byToken {
		if !e.Finished() {
			n++
		}
	}
	return n
}

// Running is the number of live executions.
func (m *Manager) Running() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.runningLocked()
}

// Concurrency is the current slot count.
func (m *Manager) Concurrency() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.concurrency
}

// SetConcurrency changes the slot count. Per the spec it only applies while
// the machine is idle; the boolean reports whether the change took effect.
func (m *Manager) SetConcurrency(n int) bool {
	if n < 1 || n > 4 {
		return false
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if n == m.concurrency {
		return true
	}
	if m.runningLocked() > 0 {
		return false
	}
	m.concurrency = n
	return true
}

// ByToken looks up an execution by dispatch token, falling back to executeId
// so a cancel carrying only the execution id still works.
func (m *Manager) ByToken(token, executeID string) *Execution {
	m.mu.Lock()
	defer m.mu.Unlock()
	if e, ok := m.byToken[token]; ok && token != "" {
		return e
	}
	if e, ok := m.byID[executeID]; ok && executeID != "" {
		return e
	}
	return nil
}

// All returns every tracked execution, running or awaiting fin acknowledgement.
func (m *Manager) All() []*Execution {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]*Execution, 0, len(m.byToken))
	for _, e := range m.byToken {
		out = append(out, e)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].acceptedAt.Before(out[j].acceptedAt) })
	return out
}

// RunningItems is the reconciliation payload carried by hello and hb.
func (m *Manager) RunningItems() []proto.RunningItem {
	items := []proto.RunningItem{}
	for _, e := range m.All() {
		if e.Finished() {
			continue
		}
		st := e.journal.Stats()
		items = append(items, proto.RunningItem{
			ExecuteID: e.spec.ExecuteID,
			Token:     e.spec.Token,
			PID:       e.PID(),
			StartedAt: e.StartedAt().UnixMilli(),
			LogSeq:    st.LastSeq,
			AckedSeq:  st.AckedSeq,
		})
	}
	return items
}

// LastLogSeqs maps executeId to the last locally known log sequence; sent in
// hello so the server can tell the agent where to resume from.
func (m *Manager) LastLogSeqs() map[string]int64 {
	out := map[string]int64{}
	for _, e := range m.All() {
		out[e.spec.ExecuteID] = e.journal.Stats().LastSeq
	}
	return out
}

// Cancel kills the process group of one execution.
func (m *Manager) Cancel(token, executeID, reason string) *Execution {
	e := m.ByToken(token, executeID)
	if e == nil {
		return nil
	}
	if reason == "" {
		reason = proto.ReasonCanceled
	}
	e.Kill(reason)
	return e
}

// StopAll kills every running execution and returns how many were signalled.
func (m *Manager) StopAll(reason string) int {
	if reason == "" {
		reason = proto.ReasonStopped
	}
	n := 0
	for _, e := range m.All() {
		if e.Finished() {
			continue
		}
		if e.Kill(reason) {
			n++
		}
	}
	return n
}

// BeginShutdown stops accepting new dispatches and returns the live executions.
func (m *Manager) BeginShutdown() []*Execution {
	m.mu.Lock()
	m.shutdown = true
	m.mu.Unlock()
	live := []*Execution{}
	for _, e := range m.All() {
		if !e.Finished() {
			live = append(live, e)
		}
	}
	return live
}

// WaitIdle blocks until no execution is running or the timeout expires.
func (m *Manager) WaitIdle(timeout time.Duration) bool {
	deadline := time.Now().Add(timeout)
	for {
		if m.Running() == 0 {
			return true
		}
		if time.Now().After(deadline) {
			return false
		}
		time.Sleep(50 * time.Millisecond)
	}
}

// Reap releases an execution once its fin has been acknowledged.
func (m *Manager) Reap(executeID string) {
	m.mu.Lock()
	e, ok := m.byID[executeID]
	if ok {
		delete(m.byID, executeID)
		delete(m.byToken, e.spec.Token)
	}
	m.mu.Unlock()
	if ok && e.journal != nil {
		_ = e.journal.Remove()
	}
}

// SyncJournals flushes buffered journal writes to disk.
func (m *Manager) SyncJournals() {
	for _, e := range m.All() {
		if e.journal != nil {
			_ = e.journal.Sync()
		}
	}
}

// buildEnv layers process env, agent config env, dispatch env and the ATEST_*
// identifiers that scripts can rely on.
func (m *Manager) buildEnv(spec Spec) []string {
	merged := map[string]string{}
	for _, kv := range os.Environ() {
		if k, v, ok := cut(kv); ok {
			merged[k] = v
		}
	}
	for k, v := range m.opt.BaseEnv {
		merged[k] = v
	}
	for k, v := range spec.Env {
		merged[k] = v
	}
	merged["ATEST_AGENT_ID"] = m.opt.AgentID
	merged["ATEST_BOOT_ID"] = m.opt.BootID
	merged["ATEST_EXECUTE_ID"] = spec.ExecuteID
	merged["ATEST_DISPATCH_TOKEN"] = spec.Token
	if spec.TaskID != "" {
		merged["ATEST_TASK_ID"] = spec.TaskID
	}
	if m.opt.Tag != "" {
		merged["ATEST_TAG"] = m.opt.Tag
	}

	out := make([]string, 0, len(merged))
	for k, v := range merged {
		out = append(out, k+"="+v)
	}
	sort.Strings(out)
	return out
}

func (m *Manager) shell(spec Spec) string {
	if spec.Shell != "" {
		return spec.Shell
	}
	return m.opt.Shell
}

func cut(kv string) (string, string, bool) {
	for i := 0; i < len(kv); i++ {
		if kv[i] == '=' {
			return kv[:i], kv[i+1:], true
		}
	}
	return "", "", false
}

// resolveCwd validates the working directory before the process is spawned so
// the failure lands in the execution log instead of a bare exec error.
func resolveCwd(cwd string) (string, error) {
	if cwd == "" {
		return "", nil
	}
	abs, err := filepath.Abs(cwd)
	if err != nil {
		return "", fmt.Errorf("resolve cwd %q: %w", cwd, err)
	}
	info, err := os.Stat(abs)
	if err != nil {
		return "", fmt.Errorf("cwd %s: %w", abs, err)
	}
	if !info.IsDir() {
		return "", fmt.Errorf("cwd %s is not a directory", abs)
	}
	return abs, nil
}

// killGroup signals the whole process group so `bash -c` children die too.
func killGroup(pid int, sig syscall.Signal) error {
	if pid <= 0 {
		return fmt.Errorf("no pid")
	}
	if err := syscall.Kill(-pid, sig); err != nil {
		// Fall back to the direct pid: the group may already be gone.
		if errors.Is(err, syscall.ESRCH) {
			return err
		}
		return syscall.Kill(pid, sig)
	}
	return nil
}
