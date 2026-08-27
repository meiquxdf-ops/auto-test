package task

import (
	"errors"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/atest/atagent/internal/proto"
)

func newTestManager(t *testing.T, concurrency int) (*Manager, chan *Execution) {
	t.Helper()
	done := make(chan *Execution, 16)
	m := New(concurrency, Options{
		JournalDir:  filepath.Join(t.TempDir(), "journal"),
		MaxLogBytes: 1 << 20,
		KillGrace:   time.Second,
		AgentID:     "agent-test",
		BootID:      "boot-test",
		OnFinish:    func(e *Execution) { done <- e },
	})
	return m, done
}

func run(t *testing.T, m *Manager, spec Spec) *Execution {
	t.Helper()
	e, err := m.Accept(spec)
	if err != nil {
		t.Fatalf("accept %s: %v", spec.ExecuteID, err)
	}
	e.Start()
	return e
}

func waitFin(t *testing.T, done chan *Execution, timeout time.Duration) *Execution {
	t.Helper()
	select {
	case e := <-done:
		return e
	case <-time.After(timeout):
		t.Fatal("execution did not finish in time")
		return nil
	}
}

func TestExitCodeAndOutput(t *testing.T) {
	m, done := newTestManager(t, 1)
	run(t, m, Spec{ExecuteID: "e1", Token: "t1", Command: "echo hello; echo oops >&2; exit 7"})

	e := waitFin(t, done, 10*time.Second)
	fin := e.Fin()
	if fin.ExitCode != 7 {
		t.Errorf("exitCode = %d, want 7", fin.ExitCode)
	}
	if fin.Reason != proto.ReasonExited {
		t.Errorf("reason = %q, want exited", fin.Reason)
	}

	_, lines := e.Journal().Batch(100, 1<<20)
	var stdout, stderr int
	for _, l := range lines {
		switch l.S {
		case proto.StreamStdout:
			stdout++
		case proto.StreamStderr:
			stderr++
		}
	}
	if stdout != 1 || stderr != 1 {
		t.Errorf("stdout=%d stderr=%d, want one line each", stdout, stderr)
	}
}

func TestCwdAndEnv(t *testing.T) {
	m, done := newTestManager(t, 1)
	workdir := t.TempDir()
	m.opt.BaseEnv = map[string]string{"BASE_VAR": "base"}

	run(t, m, Spec{
		ExecuteID: "e-env",
		Token:     "t-env",
		Command:   "pwd; echo $BASE_VAR; echo $TASK_VAR; echo $ATEST_DISPATCH_TOKEN",
		Cwd:       workdir,
		Env:       map[string]string{"TASK_VAR": "task"},
	})

	e := waitFin(t, done, 10*time.Second)
	_, lines := e.Journal().Batch(100, 1<<20)
	var out []string
	for _, l := range lines {
		out = append(out, l.X)
	}
	joined := strings.Join(out, "\n")
	for _, want := range []string{workdir, "base", "task", "t-env"} {
		if !strings.Contains(joined, want) {
			t.Errorf("output missing %q:\n%s", want, joined)
		}
	}
}

func TestMissingCwdFailsCleanly(t *testing.T) {
	m, done := newTestManager(t, 1)
	run(t, m, Spec{
		ExecuteID: "e-cwd",
		Token:     "t-cwd",
		Command:   "echo never",
		Cwd:       filepath.Join(t.TempDir(), "nope"),
	})

	e := waitFin(t, done, 10*time.Second)
	fin := e.Fin()
	if fin.Reason != proto.ReasonStartFailed {
		t.Errorf("reason = %q, want start_failed", fin.Reason)
	}
	if fin.ExitCode == 0 {
		t.Error("a failed start must not report exit code 0")
	}
	if fin.Err == "" {
		t.Error("the failure message should be carried in the fin frame")
	}
}

func TestBusyAndDuplicateToken(t *testing.T) {
	m, _ := newTestManager(t, 1)
	run(t, m, Spec{ExecuteID: "e1", Token: "t1", Command: "sleep 5"})

	if _, err := m.Accept(Spec{ExecuteID: "e2", Token: "t2", Command: "echo hi"}); !errors.Is(err, ErrBusy) {
		t.Errorf("error = %v, want ErrBusy", err)
	}
	if _, err := m.Accept(Spec{ExecuteID: "e1", Token: "t1", Command: "echo hi"}); !errors.Is(err, ErrDupToken) {
		t.Errorf("error = %v, want ErrDupToken", err)
	}
	m.StopAll(proto.ReasonStopped)
}

func TestConcurrencyAllowsParallelExecutions(t *testing.T) {
	m, done := newTestManager(t, 2)
	run(t, m, Spec{ExecuteID: "e1", Token: "t1", Command: "sleep 0.2"})
	run(t, m, Spec{ExecuteID: "e2", Token: "t2", Command: "sleep 0.2"})

	if _, err := m.Accept(Spec{ExecuteID: "e3", Token: "t3", Command: "echo hi"}); !errors.Is(err, ErrBusy) {
		t.Errorf("error = %v, want ErrBusy on the third dispatch", err)
	}
	waitFin(t, done, 10*time.Second)
	waitFin(t, done, 10*time.Second)

	if m.Running() != 0 {
		t.Errorf("running = %d, want 0", m.Running())
	}
	if !m.SetConcurrency(4) {
		t.Error("concurrency should be changeable while idle")
	}
}

func TestConcurrencyIsPinnedWhileBusy(t *testing.T) {
	m, _ := newTestManager(t, 1)
	run(t, m, Spec{ExecuteID: "e1", Token: "t1", Command: "sleep 5"})
	if m.SetConcurrency(4) {
		t.Error("concurrency must not change while an execution is running")
	}
	m.StopAll(proto.ReasonStopped)
}

// Setpgid plus a group kill is what makes background children die with the
// execution instead of being orphaned.
func TestCancelKillsProcessGroup(t *testing.T) {
	m, done := newTestManager(t, 1)
	e := run(t, m, Spec{ExecuteID: "e-kill", Token: "t-kill", Command: "sleep 60 & echo started; wait"})

	deadline := time.Now().Add(10 * time.Second)
	for e.PID() == 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	pgid := e.PID()
	if pgid <= 0 {
		t.Fatal("no pid was recorded")
	}

	if m.Cancel("t-kill", "", proto.ReasonCanceled) == nil {
		t.Fatal("cancel did not find the execution")
	}
	fin := waitFin(t, done, 10*time.Second).Fin()
	if fin.Reason != proto.ReasonCanceled {
		t.Errorf("reason = %q, want canceled", fin.Reason)
	}

	for time.Now().Before(deadline) {
		if syscall.Kill(-pgid, 0) == syscall.ESRCH {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Error("the process group survived the cancel")
}

// SIGTERM is only advisory; a process that ignores it must still be killed.
func TestKillEscalatesToSIGKILL(t *testing.T) {
	m, done := newTestManager(t, 1)
	e := run(t, m, Spec{
		ExecuteID: "e-stubborn",
		Token:     "t-stubborn",
		Command:   "trap '' TERM; echo ready; sleep 60",
	})

	deadline := time.Now().Add(10 * time.Second)
	for e.PID() == 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if !e.Kill(proto.ReasonCanceled) {
		t.Fatal("kill reported nothing to do")
	}

	fin := waitFin(t, done, 15*time.Second).Fin()
	if fin.Reason != proto.ReasonCanceled {
		t.Errorf("reason = %q, want canceled", fin.Reason)
	}
	if fin.Signal == "" {
		t.Errorf("expected a signal in the fin frame, got %+v", fin)
	}
}

func TestTimeoutKillsExecution(t *testing.T) {
	m, done := newTestManager(t, 1)
	run(t, m, Spec{ExecuteID: "e-to", Token: "t-to", Command: "sleep 30", TimeoutSec: 1})

	fin := waitFin(t, done, 15*time.Second).Fin()
	if fin.Reason != proto.ReasonTimeout {
		t.Errorf("reason = %q, want timeout", fin.Reason)
	}
}

func TestReapRemovesJournal(t *testing.T) {
	m, done := newTestManager(t, 1)
	e := run(t, m, Spec{ExecuteID: "e-reap", Token: "t-reap", Command: "echo bye"})
	waitFin(t, done, 10*time.Second)

	path := e.Journal().Path()
	if _, err := os.Stat(path); err != nil {
		t.Fatalf("journal should exist before reaping: %v", err)
	}
	m.Reap("e-reap")
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Errorf("journal should be gone after reaping: %v", err)
	}
	if m.ByToken("t-reap", "e-reap") != nil {
		t.Error("a reaped execution should no longer be tracked")
	}
}

func TestRunningItemsReportProgress(t *testing.T) {
	m, done := newTestManager(t, 1)
	run(t, m, Spec{ExecuteID: "e-run", Token: "t-run", Command: "echo tick; sleep 2"})

	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		items := m.RunningItems()
		if len(items) == 1 && items[0].PID > 0 && items[0].LogSeq > 0 {
			m.StopAll(proto.ReasonStopped)
			waitFin(t, done, 10*time.Second)
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Error("running items never reported a pid and log progress")
}

func TestShutdownRejectsNewWork(t *testing.T) {
	m, _ := newTestManager(t, 1)
	m.BeginShutdown()
	if _, err := m.Accept(Spec{ExecuteID: "e-late", Token: "t-late", Command: "echo hi"}); !errors.Is(err, ErrShuttingDown) {
		t.Errorf("error = %v, want ErrShuttingDown", err)
	}
}
