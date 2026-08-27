package task

import (
	"bufio"
	"errors"
	"io"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/atest/atagent/internal/events"
	"github.com/atest/atagent/internal/journal"
	"github.com/atest/atagent/internal/proto"
)

// Execution is one dispatched command on this machine.
type Execution struct {
	spec Spec
	mgr  *Manager

	journal *journal.Journal
	done    chan struct{}

	acceptedAt time.Time

	mu         sync.Mutex
	startedAt  time.Time
	finishedAt time.Time
	pid        int
	finished   bool
	fin        proto.FinArgs
	killReason string
	killedAt   time.Time
	cmd        *exec.Cmd
	timer      *time.Timer
}

// Spec returns the dispatch this execution was created from.
func (e *Execution) Spec() Spec { return e.spec }

// Journal exposes the log tail for the uploader.
func (e *Execution) Journal() *journal.Journal { return e.journal }

// Done is closed when the execution has produced its fin frame.
func (e *Execution) Done() <-chan struct{} { return e.done }

// Finished reports whether the process is gone and fin has been built.
func (e *Execution) Finished() bool {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.finished
}

// Fin returns the terminal frame; only valid once Finished is true.
func (e *Execution) Fin() proto.FinArgs {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.fin
}

// PID is the process group leader, 0 before the process starts.
func (e *Execution) PID() int {
	e.mu.Lock()
	defer e.mu.Unlock()
	return e.pid
}

// StartedAt is the launch time, falling back to the accept time.
func (e *Execution) StartedAt() time.Time {
	e.mu.Lock()
	defer e.mu.Unlock()
	if e.startedAt.IsZero() {
		return e.acceptedAt
	}
	return e.startedAt
}

// Start launches `bash -c command` in its own process group and returns
// immediately; the execution completes asynchronously via Options.OnFinish.
func (e *Execution) Start() {
	go e.run()
}

func (e *Execution) run() {
	m := e.mgr
	spec := e.spec

	cwd, err := resolveCwd(spec.Cwd)
	if err != nil {
		e.journal.Note("[atagent] %v", err)
		e.finish(-1, "", proto.ReasonStartFailed, err.Error())
		return
	}

	cmd := exec.Command(m.shell(spec), "-c", spec.Command)
	cmd.Dir = cwd
	cmd.Env = m.buildEnv(spec)
	// Own process group: cancel and timeout kill the whole tree, and the
	// children survive a plain agent restart instead of catching its signals.
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		e.journal.Note("[atagent] stdout pipe: %v", err)
		e.finish(-1, "", proto.ReasonStartFailed, err.Error())
		return
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		e.journal.Note("[atagent] stderr pipe: %v", err)
		e.finish(-1, "", proto.ReasonStartFailed, err.Error())
		return
	}

	if err := cmd.Start(); err != nil {
		e.journal.Note("[atagent] start failed: %v", err)
		e.finish(-1, "", proto.ReasonStartFailed, err.Error())
		return
	}

	e.mu.Lock()
	e.cmd = cmd
	e.pid = cmd.Process.Pid
	e.startedAt = time.Now()
	pending := e.killReason
	e.mu.Unlock()

	m.opt.OnEvent(events.KindExecStart, spec.ExecuteID, spec.Token,
		"started pid "+itoa(cmd.Process.Pid), map[string]string{
			"pid":     itoa(cmd.Process.Pid),
			"command": truncate(spec.Command, 512),
			"cwd":     cwd,
		})

	if pending != "" {
		// A cancel arrived between ACK and spawn.
		e.Kill(pending)
	}

	if spec.TimeoutSec > 0 {
		timer := time.AfterFunc(time.Duration(spec.TimeoutSec)*time.Second, func() {
			if e.Kill(proto.ReasonTimeout) {
				m.opt.OnEvent(events.KindExecTimeout, spec.ExecuteID, spec.Token,
					"timeout after "+itoa(spec.TimeoutSec)+"s", nil)
			}
		})
		e.mu.Lock()
		e.timer = timer
		e.mu.Unlock()
	}

	var wg sync.WaitGroup
	wg.Add(2)
	go e.pump(&wg, stdout, proto.StreamStdout)
	go e.pump(&wg, stderr, proto.StreamStderr)
	wg.Wait()

	waitErr := cmd.Wait()

	e.mu.Lock()
	if e.timer != nil {
		e.timer.Stop()
	}
	reason := e.killReason
	e.mu.Unlock()

	code, signal := exitStatus(waitErr)
	if reason == "" {
		reason = proto.ReasonExited
	}
	errMsg := ""
	if waitErr != nil && code == -1 && signal == "" {
		errMsg = waitErr.Error()
	}
	e.finish(code, signal, reason, errMsg)
}

// pump turns a pipe into journal lines. Output that never terminates a line is
// still flushed once it exceeds the line cap, so `printf` without a newline
// cannot stall the log stream.
func (e *Execution) pump(wg *sync.WaitGroup, r io.Reader, stream string) {
	defer wg.Done()
	br := bufio.NewReaderSize(r, 64<<10)
	limit := e.mgr.opt.MaxLineBytes
	var acc []byte

	emit := func(b []byte) {
		e.journal.Append(stream, string(b))
		e.mgr.opt.OnLog()
	}

	for {
		chunk, err := br.ReadSlice('\n')
		if len(chunk) > 0 {
			if err == bufio.ErrBufferFull {
				acc = append(acc, chunk...)
				if len(acc) >= limit {
					emit(acc[:limit])
					acc = append(acc[:0], acc[limit:]...)
				}
				continue
			}
			line := chunk
			if len(acc) > 0 {
				acc = append(acc, chunk...)
				line = acc
			}
			emit(line)
			acc = acc[:0]
		}
		if err != nil {
			if len(acc) > 0 {
				emit(acc)
			}
			return
		}
	}
}

// Kill terminates the process group with SIGTERM and escalates to SIGKILL
// after the configured grace period. It returns false when the execution has
// already finished. The first reason wins so a timeout is not relabelled by a
// later stop.
func (e *Execution) Kill(reason string) bool {
	e.mu.Lock()
	if e.finished {
		e.mu.Unlock()
		return false
	}
	first := e.killReason == ""
	if first {
		e.killReason = reason
		e.killedAt = time.Now()
	}
	pid := e.pid
	e.mu.Unlock()

	if pid <= 0 {
		// Not spawned yet: run() applies the reason as soon as it has a pid.
		return first
	}
	if first {
		e.journal.Note("[atagent] %s: sending SIGTERM to process group %d", reason, pid)
	}
	_ = killGroup(pid, syscall.SIGTERM)

	if first {
		grace := e.mgr.opt.KillGrace
		go func() {
			select {
			case <-e.done:
			case <-time.After(grace):
				// Re-check: the process may have exited just as the timer
				// fired, and its pid could already belong to someone else.
				if e.Finished() {
					return
				}
				e.journal.Note("[atagent] process group %d still alive after %s, sending SIGKILL", pid, grace)
				_ = killGroup(pid, syscall.SIGKILL)
			}
		}()
	}
	return true
}

// finish builds the fin frame exactly once and hands it to the manager.
func (e *Execution) finish(code int, signal, reason, errMsg string) {
	e.mu.Lock()
	if e.finished {
		e.mu.Unlock()
		return
	}
	e.finished = true
	e.finishedAt = time.Now()
	startedAt := e.startedAt
	if startedAt.IsZero() {
		startedAt = e.acceptedAt
	}
	stats := e.journal.Stats()
	e.fin = proto.FinArgs{
		AgentID:    e.mgr.opt.AgentID,
		BootID:     e.mgr.opt.BootID,
		ExecuteID:  e.spec.ExecuteID,
		Token:      e.spec.Token,
		ExitCode:   code,
		Signal:     signal,
		Reason:     reason,
		Err:        errMsg,
		StartedAt:  startedAt.UnixMilli(),
		FinishedAt: e.finishedAt.UnixMilli(),
		LastLine:   e.journal.LastLine(),
		LogSeq:     stats.LastSeq,
		LogBytes:   stats.TotalBytes,
		Truncated:  stats.Truncated,
	}
	e.mu.Unlock()

	close(e.done)
	_ = e.journal.Sync()

	e.mgr.opt.OnEvent(events.KindExecExit, e.spec.ExecuteID, e.spec.Token,
		"exit "+itoa(code)+" ("+reason+")", map[string]string{
			"exitCode": itoa(code),
			"reason":   reason,
			"signal":   signal,
		})
	e.mgr.opt.OnFinish(e)
}

// exitStatus maps a wait error onto (exitCode, signalName). A signalled
// process reports 128+signal, matching shell convention.
func exitStatus(err error) (int, string) {
	if err == nil {
		return 0, ""
	}
	var ee *exec.ExitError
	if !errors.As(err, &ee) {
		return -1, ""
	}
	if ws, ok := ee.Sys().(syscall.WaitStatus); ok {
		if ws.Signaled() {
			sig := ws.Signal()
			return 128 + int(sig), sig.String()
		}
		return ws.ExitStatus(), ""
	}
	return ee.ExitCode(), ""
}

func itoa(n int) string { return strconv.Itoa(n) }

func truncate(s string, max int) string {
	if len(s) <= max {
		return s
	}
	return strings.TrimSpace(s[:max]) + "..."
}
