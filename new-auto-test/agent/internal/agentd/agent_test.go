package agentd

import (
	"context"
	"io"
	"os"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/atest/atagent/internal/config"
	"github.com/atest/atagent/internal/logx"
	"github.com/atest/atagent/internal/proto"
)

// startAgent boots an agent against srv in a temp data directory.
func startAgent(t *testing.T, srv *fakeServer, tune func(*config.Config)) (*Agent, func()) {
	t.Helper()
	dir := t.TempDir()
	cfg := config.Default()
	cfg.Server = srv.Addr()
	cfg.DataDir = dir
	cfg.Tag = "test-agent"
	cfg.HeartbeatSec = 1
	cfg.ReconnectMinMs = 100
	cfg.ReconnectMaxMs = 400
	cfg.KillGraceSec = 1
	cfg.LogLevel = "error"
	if tune != nil {
		tune(cfg)
	}
	if err := cfg.Normalize(); err != nil {
		t.Fatalf("normalize config: %v", err)
	}

	out := io.Discard
	if testing.Verbose() {
		out = os.Stderr
	}
	agent, err := New(cfg, logx.New(cfg.LogLevel, out))
	if err != nil {
		t.Fatalf("new agent: %v", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		if err := agent.Run(ctx); err != nil {
			t.Errorf("agent run: %v", err)
		}
	}()

	stop := func() {
		cancel()
		select {
		case <-done:
		case <-time.After(15 * time.Second):
			t.Error("agent did not shut down in time")
		}
	}
	t.Cleanup(stop)

	waitFor(t, 5*time.Second, "the agent to register", func() bool { return srv.helloCount() > 0 })
	return agent, stop
}

func TestExecRunsCommandAndReportsFin(t *testing.T) {
	srv := newFakeServer(t)
	startAgent(t, srv, nil)

	rsp, err := srv.exec(proto.ExecArgs{
		ExecuteID: "e-1",
		Token:     "tok-1",
		Command:   "echo out-line; echo err-line >&2; exit 3",
	})
	if err != nil {
		t.Fatalf("exec: %v", err)
	}
	if !rsp.IsOK() {
		t.Fatalf("exec was rejected: %v", rsp.E)
	}

	waitFor(t, 10*time.Second, "the fin frame", func() bool {
		_, ok := srv.fin("e-1")
		return ok
	})

	fin, _ := srv.fin("e-1")
	if fin.ExitCode != 3 {
		t.Errorf("exit code = %d, want 3", fin.ExitCode)
	}
	if fin.Reason != proto.ReasonExited {
		t.Errorf("reason = %q, want %q", fin.Reason, proto.ReasonExited)
	}
	if fin.Token != "tok-1" {
		t.Errorf("token = %q, want tok-1", fin.Token)
	}
	if fin.Truncated {
		t.Error("fin should not be flagged truncated")
	}

	logText := srv.logText("e-1")
	if !strings.Contains(logText, "out-line") {
		t.Errorf("stdout missing from logs: %q", logText)
	}
	if !strings.Contains(logText, "err-line") {
		t.Errorf("stderr missing from logs: %q", logText)
	}

	// The verdict is taken from the last line, so it must be the last thing
	// the process actually printed.
	if fin.LastLine != "out-line" && fin.LastLine != "err-line" {
		t.Errorf("lastLine = %q, want one of the emitted lines", fin.LastLine)
	}

	var sawStderr bool
	for _, l := range srv.logLines("e-1") {
		if l.S == proto.StreamStderr && l.X == "err-line" {
			sawStderr = true
		}
	}
	if !sawStderr {
		t.Error("stderr line was not tagged with the stderr stream")
	}
}

func TestExecHonoursCwdAndEnv(t *testing.T) {
	srv := newFakeServer(t)
	startAgent(t, srv, nil)

	workdir := t.TempDir()
	rsp, err := srv.exec(proto.ExecArgs{
		ExecuteID: "e-env",
		Token:     "tok-env",
		Command:   "pwd; echo $MY_VAR; echo $ATEST_EXECUTE_ID",
		Cwd:       workdir,
		Env:       proto.EnvMap{"MY_VAR": "from-dispatch"},
	})
	if err != nil {
		t.Fatalf("exec: %v", err)
	}
	if !rsp.IsOK() {
		t.Fatalf("exec was rejected: %v", rsp.E)
	}

	waitFor(t, 10*time.Second, "the fin frame", func() bool {
		_, ok := srv.fin("e-env")
		return ok
	})

	out := srv.logText("e-env")
	// macOS style /private symlinks do not apply here, but resolve anyway.
	resolved, _ := os.Readlink(workdir)
	if !strings.Contains(out, workdir) && (resolved == "" || !strings.Contains(out, resolved)) {
		t.Errorf("cwd %q not reflected in output: %q", workdir, out)
	}
	if !strings.Contains(out, "from-dispatch") {
		t.Errorf("dispatch env missing from output: %q", out)
	}
	if !strings.Contains(out, "e-env") {
		t.Errorf("ATEST_EXECUTE_ID missing from output: %q", out)
	}
}

func TestCancelKillsTheWholeProcessGroup(t *testing.T) {
	srv := newFakeServer(t)
	agent, _ := startAgent(t, srv, nil)

	// The backgrounded sleep only dies if the whole process group is signalled.
	rsp, err := srv.exec(proto.ExecArgs{
		ExecuteID: "e-cancel",
		Token:     "tok-cancel",
		Command:   "sleep 60 & echo started; wait",
	})
	if err != nil {
		t.Fatalf("exec: %v", err)
	}
	if !rsp.IsOK() {
		t.Fatalf("exec was rejected: %v", rsp.E)
	}

	waitFor(t, 10*time.Second, "the process to start", func() bool {
		return strings.Contains(srv.logText("e-cancel"), "started")
	})

	var pgid int
	for _, e := range agent.tasks.All() {
		if e.Spec().ExecuteID == "e-cancel" {
			pgid = e.PID()
		}
	}
	if pgid <= 0 {
		t.Fatal("no pid recorded for the running execution")
	}

	cancelRsp, err := srv.call(proto.MCancel, proto.CancelArgs{Token: "tok-cancel"})
	if err != nil {
		t.Fatalf("cancel: %v", err)
	}
	if !cancelRsp.IsOK() {
		t.Fatalf("cancel was rejected: %v", cancelRsp.E)
	}

	waitFor(t, 10*time.Second, "the fin frame", func() bool {
		_, ok := srv.fin("e-cancel")
		return ok
	})
	fin, _ := srv.fin("e-cancel")
	if fin.Reason != proto.ReasonCanceled {
		t.Errorf("reason = %q, want %q", fin.Reason, proto.ReasonCanceled)
	}

	waitFor(t, 10*time.Second, "the process group to disappear", func() bool {
		return syscall.Kill(-pgid, 0) == syscall.ESRCH
	})
}

func TestTimeoutKillsExecution(t *testing.T) {
	srv := newFakeServer(t)
	startAgent(t, srv, nil)

	rsp, err := srv.exec(proto.ExecArgs{
		ExecuteID:  "e-timeout",
		Token:      "tok-timeout",
		Command:    "echo working; sleep 60",
		TimeoutSec: 1,
	})
	if err != nil {
		t.Fatalf("exec: %v", err)
	}
	if !rsp.IsOK() {
		t.Fatalf("exec was rejected: %v", rsp.E)
	}

	waitFor(t, 15*time.Second, "the fin frame", func() bool {
		_, ok := srv.fin("e-timeout")
		return ok
	})
	fin, _ := srv.fin("e-timeout")
	if fin.Reason != proto.ReasonTimeout {
		t.Errorf("reason = %q, want %q", fin.Reason, proto.ReasonTimeout)
	}
	if fin.ExitCode == 0 {
		t.Error("a killed execution must not report exit code 0")
	}
}

func TestConcurrencyLimitAndDuplicateToken(t *testing.T) {
	srv := newFakeServer(t)
	startAgent(t, srv, func(c *config.Config) { c.Concurrency = 1 })

	first, err := srv.exec(proto.ExecArgs{ExecuteID: "e-busy-1", Token: "tok-a", Command: "sleep 5"})
	if err != nil {
		t.Fatalf("exec: %v", err)
	}
	if !first.IsOK() {
		t.Fatalf("first exec was rejected: %v", first.E)
	}

	second, err := srv.exec(proto.ExecArgs{ExecuteID: "e-busy-2", Token: "tok-b", Command: "echo nope"})
	if err != nil {
		t.Fatalf("exec: %v", err)
	}
	if second.IsOK() {
		t.Fatal("the second dispatch should have been rejected as busy")
	}
	if second.E.C != proto.CodeBusy {
		t.Errorf("error code = %q, want %q", second.E.C, proto.CodeBusy)
	}

	dup, err := srv.exec(proto.ExecArgs{ExecuteID: "e-busy-1", Token: "tok-a", Command: "echo nope"})
	if err != nil {
		t.Fatalf("exec: %v", err)
	}
	if dup.IsOK() {
		t.Fatal("a repeated dispatch token should have been rejected")
	}
	if dup.E.C != proto.CodeDupToken {
		t.Errorf("error code = %q, want %q", dup.E.C, proto.CodeDupToken)
	}

	stopRsp, err := srv.call(proto.MStop, proto.StopArgs{Reason: "test cleanup"})
	if err != nil {
		t.Fatalf("stop: %v", err)
	}
	if !stopRsp.IsOK() {
		t.Fatalf("stop was rejected: %v", stopRsp.E)
	}
	waitFor(t, 10*time.Second, "the stopped execution to report", func() bool {
		_, ok := srv.fin("e-busy-1")
		return ok
	})
	fin, _ := srv.fin("e-busy-1")
	if fin.Reason != proto.ReasonStopped {
		t.Errorf("reason = %q, want %q", fin.Reason, proto.ReasonStopped)
	}
}

// A short outage must not disturb a running execution: the agent reconnects,
// re-announces what it is running and keeps streaming.
func TestReconnectKeepsExecutionAlive(t *testing.T) {
	srv := newFakeServer(t)
	agent, _ := startAgent(t, srv, nil)

	rsp, err := srv.exec(proto.ExecArgs{
		ExecuteID: "e-reconnect",
		Token:     "tok-reconnect",
		Command:   "echo started; for i in 1 2 3 4 5 6 7 8; do echo tick-$i; sleep 0.25; done; echo done",
	})
	if err != nil {
		t.Fatalf("exec: %v", err)
	}
	if !rsp.IsOK() {
		t.Fatalf("exec was rejected: %v", rsp.E)
	}

	waitFor(t, 10*time.Second, "the process to start", func() bool {
		return strings.Contains(srv.logText("e-reconnect"), "started")
	})

	var pid int
	for _, e := range agent.tasks.All() {
		if e.Spec().ExecuteID == "e-reconnect" {
			pid = e.PID()
		}
	}
	if pid <= 0 {
		t.Fatal("no pid recorded for the running execution")
	}

	before := srv.helloCount()
	srv.dropConnection()

	waitFor(t, 10*time.Second, "the agent to reconnect", func() bool { return srv.helloCount() > before })

	hello, _ := srv.lastHello()
	if !hello.Reconnect {
		t.Error("the second hello should be flagged as a reconnect")
	}
	found := false
	for _, r := range hello.Running {
		if r.ExecuteID == "e-reconnect" {
			found = true
			if r.PID != pid {
				t.Errorf("reconciled pid = %d, want %d", r.PID, pid)
			}
		}
	}
	if !found {
		t.Error("the running execution was not reported for reconciliation")
	}
	if err := syscall.Kill(pid, 0); err != nil {
		t.Errorf("the execution was killed by the disconnect: %v", err)
	}

	waitFor(t, 20*time.Second, "the fin frame", func() bool {
		_, ok := srv.fin("e-reconnect")
		return ok
	})
	fin, _ := srv.fin("e-reconnect")
	if fin.ExitCode != 0 {
		t.Errorf("exit code = %d, want 0", fin.ExitCode)
	}

	// Logs produced during the outage are journalled locally and shipped after
	// the reconnect, so nothing is lost.
	out := srv.logText("e-reconnect")
	for _, want := range []string{"tick-1", "tick-8", "done"} {
		if !strings.Contains(out, want) {
			t.Errorf("log line %q missing after reconnect: %q", want, out)
		}
	}
}

// A fin is only complete once the server acknowledges it; an outage in between
// must not lose the result.
func TestFinIsResentUntilAcknowledged(t *testing.T) {
	srv := newFakeServer(t)
	dropped := false
	srv.setFinErr(func(proto.FinArgs) *proto.Error {
		if !dropped {
			dropped = true
			go srv.dropConnection()
			return proto.Errf(proto.CodeInternal, "server is not ready")
		}
		return nil
	})
	startAgent(t, srv, nil)

	rsp, err := srv.exec(proto.ExecArgs{ExecuteID: "e-fin", Token: "tok-fin", Command: "echo hello"})
	if err != nil {
		t.Fatalf("exec: %v", err)
	}
	if !rsp.IsOK() {
		t.Fatalf("exec was rejected: %v", rsp.E)
	}

	waitFor(t, 20*time.Second, "the fin frame to be accepted", func() bool {
		_, ok := srv.fin("e-fin")
		return ok
	})
	if got := srv.finAttempts("e-fin"); got < 2 {
		t.Errorf("fin delivery attempts = %d, want at least 2", got)
	}
	if srv.connCount() < 2 {
		t.Errorf("connections = %d, want the agent to have reconnected", srv.connCount())
	}
}

func TestServerCanCancelDuringReconciliation(t *testing.T) {
	srv := newFakeServer(t)
	startAgent(t, srv, nil)

	rsp, err := srv.exec(proto.ExecArgs{ExecuteID: "e-recon", Token: "tok-recon", Command: "sleep 60"})
	if err != nil {
		t.Fatalf("exec: %v", err)
	}
	if !rsp.IsOK() {
		t.Fatalf("exec was rejected: %v", rsp.E)
	}
	waitFor(t, 10*time.Second, "the execution to be running", func() bool {
		hello, ok := srv.lastHello()
		_ = hello
		return ok
	})

	// The heartbeat response tells the agent this execution is no longer wanted.
	srv.setControl(proto.ControlResult{Cancel: []string{"tok-recon"}})

	waitFor(t, 15*time.Second, "the fin frame", func() bool {
		_, ok := srv.fin("e-recon")
		return ok
	})
	fin, _ := srv.fin("e-recon")
	if fin.Reason != proto.ReasonCanceled {
		t.Errorf("reason = %q, want %q", fin.Reason, proto.ReasonCanceled)
	}
}

// An execution that produces more than the retention cap is flagged truncated,
// but while the link is healthy the lines are streamed out before the local
// window drops them, so the server still receives the whole output.
func TestChattyExecutionIsTruncatedAndFlagged(t *testing.T) {
	srv := newFakeServer(t)
	startAgent(t, srv, func(c *config.Config) { c.MaxLogBytes = 64 << 10 })

	rsp, err := srv.exec(proto.ExecArgs{
		ExecuteID: "e-chatty",
		Token:     "tok-chatty",
		Command:   "for i in $(seq 1 4000); do echo \"line-$i-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"; done; echo THE-END",
	})
	if err != nil {
		t.Fatalf("exec: %v", err)
	}
	if !rsp.IsOK() {
		t.Fatalf("exec was rejected: %v", rsp.E)
	}

	waitFor(t, 30*time.Second, "the fin frame", func() bool {
		_, ok := srv.fin("e-chatty")
		return ok
	})

	fin, _ := srv.fin("e-chatty")
	if fin.ExitCode != 0 {
		t.Errorf("exit code = %d, want 0", fin.ExitCode)
	}
	if !fin.Truncated {
		t.Error("fin should be flagged truncated")
	}
	if fin.LastLine != "THE-END" {
		t.Errorf("lastLine = %q, want THE-END", fin.LastLine)
	}
	if fin.LogSeq != 4001 {
		t.Errorf("logSeq = %d, want 4001 (the sequence keeps counting past the cap)", fin.LogSeq)
	}

	if out := srv.logText("e-chatty"); !strings.Contains(out, "THE-END") {
		t.Error("the end of the output was not delivered")
	}

	// Whether the head survives depends on how fast the uploader drains the
	// journal, but the invariants do not: batches stay inside the frame
	// budget, every gap is announced through fromSeq, and the tail always
	// arrives.
	srv.mu.Lock()
	frames, gaps := 0, 0
	var next int64
	for _, frame := range srv.logFrames {
		if frame.ExecuteID != "e-chatty" {
			continue
		}
		frames++
		if frame.FromSeq != next {
			gaps++
			if !frame.Truncated {
				t.Errorf("frame with a gap (fromSeq %d after %d) was not flagged truncated", frame.FromSeq, next)
			}
		}
		if len(frame.Lines) > maxLogLines {
			t.Errorf("frame carried %d lines, over the %d line budget", len(frame.Lines), maxLogLines)
		}
		if n := len(frame.Lines); n > 0 {
			next = frame.Lines[n-1].Seq
		}
	}
	srv.mu.Unlock()

	if frames < 2 {
		t.Errorf("log frames = %d, want the output split into batches", frames)
	}
	if next != fin.LogSeq {
		t.Errorf("last delivered sequence = %d, want %d", next, fin.LogSeq)
	}
	if gaps > 0 {
		t.Logf("uploader fell behind the %d byte cap: %d gap(s) reported to the server", 64<<10, gaps)
	}
}

func TestPingAndTimelineEvents(t *testing.T) {
	srv := newFakeServer(t)
	agent, _ := startAgent(t, srv, nil)

	rsp, err := srv.call(proto.MPing, map[string]any{})
	if err != nil {
		t.Fatalf("ping: %v", err)
	}
	if !rsp.IsOK() {
		t.Fatalf("ping was rejected: %v", rsp.E)
	}

	waitFor(t, 10*time.Second, "the agent_start event", func() bool {
		for _, k := range srv.eventKinds() {
			if k == "agent_start" {
				return true
			}
		}
		return false
	})

	snap := agent.Snapshot()
	if snap.AgentID == "" {
		t.Error("snapshot is missing the agent id")
	}
	if !snap.Connected {
		t.Error("snapshot should report the agent as connected")
	}
	if snap.Tag != "test-agent" {
		t.Errorf("snapshot tag = %q, want test-agent", snap.Tag)
	}
}
