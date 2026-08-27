// Package agentd is the atagent runtime: identity, the reconnecting server
// session, dispatch handling and the local status endpoint.
package agentd

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/atest/atagent/internal/client"
	"github.com/atest/atagent/internal/config"
	"github.com/atest/atagent/internal/events"
	"github.com/atest/atagent/internal/ident"
	"github.com/atest/atagent/internal/journal"
	"github.com/atest/atagent/internal/logx"
	"github.com/atest/atagent/internal/proto"
	"github.com/atest/atagent/internal/spool"
	"github.com/atest/atagent/internal/status"
	"github.com/atest/atagent/internal/task"
	"github.com/atest/atagent/internal/version"
)

// Agent is the long running process behind `atagent run`.
type Agent struct {
	cfg *config.Config
	log *logx.Logger

	agentID   string
	bootID    string
	host      string
	startedAt time.Time

	events *events.Store
	spool  *spool.Spool
	tasks  *task.Manager
	status *status.Server

	// wake nudges the uploader as soon as there is something to send.
	wake      chan struct{}
	flushMu   sync.Mutex
	drainOnce sync.Once

	mu             sync.Mutex
	tag            string
	session        *client.Session
	sessionID      string
	connectedSince time.Time
	lastErr        string
	reconnects     int64
	everConnected  bool
}

// New prepares the runtime without touching the network.
func New(cfg *config.Config, log *logx.Logger) (*Agent, error) {
	if err := os.MkdirAll(cfg.DataDir, 0o755); err != nil {
		return nil, fmt.Errorf("create data dir %s: %w", cfg.DataDir, err)
	}
	agentID, err := ident.LoadOrCreate(cfg.DataDir)
	if err != nil {
		return nil, err
	}
	bootID, err := ident.NewBootID()
	if err != nil {
		return nil, err
	}
	evtStore, err := events.Open(cfg.DataDir)
	if err != nil {
		return nil, err
	}
	finSpool, err := spool.Open(spool.Dir(cfg.DataDir))
	if err != nil {
		return nil, err
	}
	host, _ := os.Hostname()

	a := &Agent{
		cfg:       cfg,
		log:       log,
		agentID:   agentID,
		bootID:    bootID,
		host:      host,
		tag:       cfg.Tag,
		startedAt: time.Now(),
		events:    evtStore,
		spool:     finSpool,
		wake:      make(chan struct{}, 1),
	}

	a.tasks = task.New(cfg.Concurrency, task.Options{
		Shell:       cfg.Shell,
		JournalDir:  journal.Dir(cfg.DataDir),
		MaxLogBytes: cfg.MaxLogBytes,
		KillGrace:   time.Duration(cfg.KillGraceSec) * time.Second,
		BaseEnv:     cfg.Env,
		AgentID:     agentID,
		BootID:      bootID,
		Tag:         cfg.Tag,
		OnLog:       a.notify,
		OnFinish:    a.onFinish,
		OnEvent:     a.record,
	})
	a.status = status.NewServer(cfg.Socket, cfg.StatusFile, a.Snapshot)

	if n := finSpool.Len(); n > 0 {
		log.Warnf("%d fin frame(s) from a previous run are waiting for delivery", n)
	}
	if n, err := journal.Cleanup(journal.Dir(cfg.DataDir), finSpool.IDs()); err != nil {
		log.Warnf("clean up old journals: %v", err)
	} else if n > 0 {
		log.Infof("removed %d journal(s) left over from a previous run", n)
	}
	return a, nil
}

// AgentID is the persistent identity of this machine.
func (a *Agent) AgentID() string { return a.agentID }

// Run drives the agent until ctx is cancelled.
func (a *Agent) Run(ctx context.Context) error {
	if err := a.status.Start(ctx); err != nil {
		return err
	}
	defer a.status.Close()

	a.log.Infof("atagent %s starting: agentId=%s tag=%q server=%s dataDir=%s concurrency=%d",
		version.String(), a.agentID, a.cfg.Tag, a.cfg.Server, a.cfg.DataDir, a.cfg.Concurrency)
	a.record(events.KindAgentStart, "", "", "agent started", map[string]string{
		"ver":    version.String(),
		"host":   a.host,
		"bootId": a.bootID,
		"pid":    strconv.Itoa(os.Getpid()),
	})

	backoff := client.NewBackoff(
		time.Duration(a.cfg.ReconnectMinMs)*time.Millisecond,
		time.Duration(a.cfg.ReconnectMaxMs)*time.Millisecond,
	)

	for ctx.Err() == nil {
		started := time.Now()
		err := a.runSession(ctx)
		if ctx.Err() != nil {
			break
		}
		lasted := time.Since(started)
		if lasted > 60*time.Second {
			// The session was healthy; the next outage starts from scratch.
			backoff.Reset()
		}
		delay := backoff.Next()
		if errors.Is(err, errDupSession) {
			// The server still believes an older connection is alive. Give it
			// time to expire rather than hammering it.
			if delay < 15*time.Second {
				delay = 15 * time.Second
			}
		}
		a.setDisconnected(err)
		a.log.Warnf("disconnected after %s: %v; reconnecting in %s", lasted.Truncate(time.Millisecond), err, delay.Truncate(time.Millisecond))
		if !client.Sleep(ctx, delay) {
			break
		}
	}

	a.drain(a.currentSession())
	a.log.Infof("atagent stopped")
	return nil
}

var (
	errDupSession   = errors.New("server rejected the session as duplicate")
	errShuttingDown = errors.New("agent is shutting down")
)

// runSession owns one connection from dial to teardown.
func (a *Agent) runSession(parent context.Context) error {
	dialCtx, cancelDial := context.WithTimeout(parent, time.Duration(a.cfg.ConnectTimeoutSec)*time.Second)
	conn, err := client.Dial(dialCtx, a.cfg.Server, time.Duration(a.cfg.ConnectTimeoutSec)*time.Second)
	cancelDial()
	if err != nil {
		return fmt.Errorf("connect to %s: %w", a.cfg.Server, err)
	}

	hb := time.Duration(a.cfg.HeartbeatSec) * time.Second
	sess := client.NewSession(conn, a.handle, client.SessionOptions{
		// Tolerate two missed heartbeats before declaring the link dead.
		ReadTimeout:  hb*3 + 10*time.Second,
		WriteTimeout: 20 * time.Second,
		CallTimeout:  30 * time.Second,
		Logf:         a.log.Debugf,
	})
	go sess.Serve()

	ctx, cancel := context.WithCancel(parent)
	defer cancel()
	go func() {
		defer cancel()
		select {
		case <-sess.Done():
		case <-parent.Done():
			// Shutting down with a live link: drain results before dropping it,
			// otherwise every in flight fin waits for the next boot.
			a.drain(sess)
			sess.Close(errShuttingDown)
		}
	}()

	if err := a.hello(ctx, sess); err != nil {
		sess.Close(err)
		return err
	}

	var wg sync.WaitGroup
	wg.Add(2)
	go func() { defer wg.Done(); a.heartbeatLoop(ctx, sess) }()
	go func() { defer wg.Done(); a.uploadLoop(ctx, sess) }()

	<-sess.Done()
	cancel()
	wg.Wait()

	if err := sess.Err(); err != nil {
		return err
	}
	return errors.New("session ended")
}

// hello registers on a fresh connection and reconciles state with the server.
func (a *Agent) hello(ctx context.Context, sess *client.Session) error {
	a.mu.Lock()
	reconnect := a.everConnected
	tag := a.tag
	a.mu.Unlock()

	running := a.tasks.RunningItems()
	args := proto.HelloArgs{
		AgentID:     a.agentID,
		BootID:      a.bootID,
		Ver:         version.String(),
		Aliases:     a.cfg.AliasList(),
		Tag:         tag,
		Host:        a.host,
		OS:          version.Current().OS,
		Arch:        version.Current().Arch,
		PID:         os.Getpid(),
		StartedAt:   a.startedAt.UnixMilli(),
		Concurrency: a.tasks.Concurrency(),
		Running:     running,
		LastEvtID:   a.events.LastID(),
		LastLog:     a.tasks.LastLogSeqs(),
		PendingFin:  a.spool.IDs(),
		Reconnect:   reconnect,
	}

	var res proto.ControlResult
	if err := sess.Call(ctx, proto.MHello, args, &res); err != nil {
		var perr *proto.Error
		if errors.As(err, &perr) && perr.C == proto.CodeDupSession {
			return fmt.Errorf("%w: %s", errDupSession, perr.Msg)
		}
		return fmt.Errorf("hello: %w", err)
	}

	a.mu.Lock()
	a.session = sess
	a.sessionID = res.SessionID
	a.connectedSince = time.Now()
	a.lastErr = ""
	if a.everConnected {
		a.reconnects++
	}
	a.everConnected = true
	a.mu.Unlock()

	a.spool.ResetAttempts()
	a.notify()

	a.log.Infof("connected to %s (session %q): %d running, %d fin pending",
		sess.RemoteAddr(), res.SessionID, len(running), a.spool.Len())
	a.record(events.KindConnected, "", "", "connected to server", map[string]string{
		"server":    a.cfg.Server,
		"sessionId": res.SessionID,
		"running":   strconv.Itoa(len(running)),
	})
	if reconnect {
		// Reconciliation is advisory: local processes keep running unless the
		// server explicitly asks for a cancel. A brief outage must never kill
		// a healthy execution.
		a.record(events.KindReconcile, "", "", "reconciled after reconnect", map[string]string{
			"running":      strconv.Itoa(len(running)),
			"serverCancel": strconv.Itoa(len(res.Cancel)),
		})
	}
	a.applyControl(&res, "hello")
	return nil
}

// heartbeatLoop renews the server side lease.
func (a *Agent) heartbeatLoop(ctx context.Context, sess *client.Session) {
	interval := time.Duration(a.cfg.HeartbeatSec) * time.Second
	t := time.NewTicker(interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		args := proto.HbArgs{
			AgentID:     a.agentID,
			BootID:      a.bootID,
			TS:          time.Now().UnixMilli(),
			Concurrency: a.tasks.Concurrency(),
			Running:     a.tasks.RunningItems(),
			PendingFin:  a.spool.Len(),
		}
		var res proto.ControlResult
		if err := sess.Call(ctx, proto.MHb, args, &res); err != nil {
			if ctx.Err() != nil {
				return
			}
			var perr *proto.Error
			if errors.As(err, &perr) {
				// The server is reachable but unhappy; log and keep the
				// session so running executions are not disturbed.
				a.log.Warnf("heartbeat rejected: %v", perr)
				continue
			}
			a.log.Warnf("heartbeat failed: %v", err)
			sess.Close(err)
			return
		}
		a.applyControl(&res, "hb")
	}
}

// applyControl acts on the optional directives a server may attach to a
// hello or hb response.
func (a *Agent) applyControl(res *proto.ControlResult, from string) {
	for execID, seq := range res.LogAck {
		if e := a.tasks.ByToken("", execID); e != nil {
			e.Journal().Ack(seq)
		}
	}
	if res.EvtAck > 0 {
		a.events.Ack(res.EvtAck)
	}
	for _, token := range res.Cancel {
		if e := a.tasks.Cancel(token, token, proto.ReasonCanceled); e != nil {
			a.log.Infof("%s: server asked to cancel %s", from, e.Spec().ExecuteID)
			a.record(events.KindExecCancel, e.Spec().ExecuteID, e.Spec().Token,
				"canceled by server during "+from, nil)
		}
	}
	if res.Tag != "" {
		a.mu.Lock()
		changed := res.Tag != a.tag
		a.tag = res.Tag
		a.mu.Unlock()
		if changed {
			a.log.Infof("%s: server assigned tag %q", from, res.Tag)
			a.record(events.KindConfigChange, "", "", "tag set to "+res.Tag, nil)
		}
	}
	if res.Concurrency != nil {
		want := *res.Concurrency
		if want != a.tasks.Concurrency() {
			if a.tasks.SetConcurrency(want) {
				a.log.Infof("%s: concurrency set to %d", from, want)
				a.record(events.KindConfigChange, "", "", "concurrency set to "+strconv.Itoa(want), nil)
			} else {
				a.log.Warnf("%s: refusing concurrency %d while %d execution(s) run", from, want, a.tasks.Running())
			}
		}
	}
}

// handle serves a server initiated request.
func (a *Agent) handle(m string, args json.RawMessage) client.Response {
	switch m {
	case proto.MExec:
		return a.handleExec(args)
	case proto.MCancel:
		return a.handleCancel(args)
	case proto.MStop:
		return a.handleStop(args)
	case proto.MPing:
		return client.OK(proto.PingResult{
			AgentID: a.agentID,
			BootID:  a.bootID,
			TS:      time.Now().UnixMilli(),
			Running: a.tasks.Running(),
		})
	default:
		return client.Fail(proto.CodeUnsupported, "unknown message %q", m)
	}
}

// handleExec accepts a dispatch. The ACK means "received and slotted"; the
// process itself is started by the After hook, strictly after the response
// frame has been written.
func (a *Agent) handleExec(raw json.RawMessage) client.Response {
	var args proto.ExecArgs
	if err := json.Unmarshal(raw, &args); err != nil {
		return client.Fail(proto.CodeBadRequest, "decode exec: %v", err)
	}
	if err := args.Validate(); err != nil {
		return client.Fail(proto.CodeBadRequest, "%v", err)
	}

	spec := task.Spec{
		ExecuteID:  args.ExecuteID,
		Token:      args.Token,
		TaskID:     args.TaskID,
		Command:    args.Command,
		Cwd:        args.Cwd,
		Env:        args.Env,
		TimeoutSec: args.TimeoutSec,
		Shell:      args.Shell,
	}

	e, err := a.tasks.Accept(spec)
	if err != nil {
		code := proto.CodeInternal
		switch {
		case errors.Is(err, task.ErrBusy):
			code = proto.CodeBusy
		case errors.Is(err, task.ErrDupToken):
			code = proto.CodeDupToken
		case errors.Is(err, task.ErrShuttingDown):
			code = proto.CodeBusy
		}
		a.log.Warnf("rejecting exec %s: %v", args.ExecuteID, err)
		a.record(events.KindExecReject, args.ExecuteID, args.Token, err.Error(),
			map[string]string{"code": code})
		return client.Fail(code, "%v", err)
	}

	a.log.Infof("accepted exec %s (token %s, timeout %ds)", spec.ExecuteID, spec.Token, spec.TimeoutSec)
	a.record(events.KindExecAck, spec.ExecuteID, spec.Token, "dispatch accepted", nil)

	return client.Response{
		Result: proto.ExecResult{
			Accepted:  true,
			ExecuteID: spec.ExecuteID,
			Token:     spec.Token,
			AckedAt:   time.Now().UnixMilli(),
		},
		After: e.Start,
	}
}

func (a *Agent) handleCancel(raw json.RawMessage) client.Response {
	var args proto.CancelArgs
	if err := json.Unmarshal(raw, &args); err != nil {
		return client.Fail(proto.CodeBadRequest, "decode cancel: %v", err)
	}
	if args.Token == "" && args.ExecuteID == "" {
		return client.Fail(proto.CodeBadRequest, "cancel needs dispatchToken or executeId")
	}

	e := a.tasks.ByToken(args.Token, args.ExecuteID)
	if e == nil {
		// Unknown or already reaped: report it instead of failing, so a repeat
		// cancel is harmless.
		return client.OK(proto.CancelResult{
			Killed:    false,
			ExecuteID: args.ExecuteID,
			Token:     args.Token,
			Msg:       "no such execution on this agent",
		})
	}
	if e.Finished() {
		return client.OK(proto.CancelResult{
			Killed:    false,
			ExecuteID: e.Spec().ExecuteID,
			Token:     e.Spec().Token,
			Msg:       "execution already finished",
		})
	}

	killed := e.Kill(proto.ReasonCanceled)
	a.log.Infof("cancel %s (token %s): killed=%t", e.Spec().ExecuteID, e.Spec().Token, killed)
	a.record(events.KindExecCancel, e.Spec().ExecuteID, e.Spec().Token, "canceled by server",
		map[string]string{"reason": args.Reason})
	return client.OK(proto.CancelResult{
		Killed:    killed,
		ExecuteID: e.Spec().ExecuteID,
		Token:     e.Spec().Token,
	})
}

func (a *Agent) handleStop(raw json.RawMessage) client.Response {
	var args proto.StopArgs
	if len(raw) > 0 {
		_ = json.Unmarshal(raw, &args)
	}
	n := a.tasks.StopAll(proto.ReasonStopped)
	a.log.Warnf("stop requested (%s): signalled %d execution(s)", args.Reason, n)
	a.record(events.KindStopAll, "", "", "stop all executions",
		map[string]string{"killed": strconv.Itoa(n), "reason": args.Reason})
	return client.OK(proto.StopResult{Killed: n})
}

// notify wakes the uploader without ever blocking the caller.
func (a *Agent) notify() {
	select {
	case a.wake <- struct{}{}:
	default:
	}
}

// onFinish persists the fin frame before anything else, so the result survives
// a crash between process exit and delivery.
func (a *Agent) onFinish(e *task.Execution) {
	fin := e.Fin()
	if err := a.spool.Add(fin); err != nil {
		a.log.Errorf("persist fin for %s: %v", fin.ExecuteID, err)
	}
	a.log.Infof("execution %s finished: exit=%d reason=%s lines=%d truncated=%t",
		fin.ExecuteID, fin.ExitCode, fin.Reason, fin.LogSeq, fin.Truncated)
	a.notify()
}

// record queues a timeline event.
func (a *Agent) record(kind, executeID, token, msg string, data map[string]string) {
	a.events.Add(kind, executeID, token, msg, data)
	a.notify()
}

func (a *Agent) setDisconnected(cause error) {
	a.mu.Lock()
	a.session = nil
	a.sessionID = ""
	a.connectedSince = time.Time{}
	if cause != nil {
		a.lastErr = cause.Error()
	}
	a.mu.Unlock()
	if cause != nil {
		a.record(events.KindDisconnected, "", "", "disconnected: "+cause.Error(), nil)
	}
}

// drain runs the shutdown sequence exactly once: stop executions (when
// configured), then hand over whatever the still open session can carry.
func (a *Agent) drain(sess *client.Session) {
	a.drainOnce.Do(func() {
		live := a.tasks.BeginShutdown()
		switch {
		case len(live) > 0 && a.cfg.KillOnShutdown:
			a.log.Warnf("stopping %d running execution(s) before exit", len(live))
			for _, e := range live {
				e.Kill(proto.ReasonShutdown)
			}
			grace := time.Duration(a.cfg.KillGraceSec)*time.Second + 3*time.Second
			if !a.tasks.WaitIdle(grace) {
				a.log.Warnf("%d execution(s) still running after %s", a.tasks.Running(), grace)
			}
		case len(live) > 0:
			a.log.Warnf("leaving %d execution(s) running (killOnShutdown=false); the server will reconcile them", len(live))
		}

		a.record(events.KindAgentStop, "", "", "agent stopped", nil)
		a.tasks.SyncJournals()

		// One last delivery attempt so results are not held back until the
		// next boot; anything left over stays in the spool.
		if sess != nil && sess.Alive() {
			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			if err := a.flushAll(ctx, sess); err != nil {
				a.log.Warnf("final flush incomplete: %v", err)
			}
			cancel()
		}
		if n := a.spool.Len(); n > 0 {
			a.log.Warnf("%d fin frame(s) left in the spool; they will be resent on next start", n)
		}
	})
}

func (a *Agent) currentSession() *client.Session {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.session
}

// Snapshot builds the payload served by the status socket and file.
func (a *Agent) Snapshot() status.Snapshot {
	a.mu.Lock()
	tag := a.tag
	connected := a.session != nil
	sessionID := a.sessionID
	var since int64
	if !a.connectedSince.IsZero() {
		since = a.connectedSince.UnixMilli()
	}
	lastErr := a.lastErr
	reconnects := a.reconnects
	a.mu.Unlock()

	now := time.Now()
	snap := status.Snapshot{
		AgentID:        a.agentID,
		Tag:            tag,
		BootID:         a.bootID,
		Build:          version.Current(),
		PID:            os.Getpid(),
		StartedAt:      a.startedAt.UnixMilli(),
		Now:            now.UnixMilli(),
		UptimeSec:      now.Sub(a.startedAt).Seconds(),
		Server:         a.cfg.Server,
		Connected:      connected,
		SessionID:      sessionID,
		ConnectedSince: since,
		Reconnects:     reconnects,
		LastError:      lastErr,
		Concurrency:    a.tasks.Concurrency(),
		PendingFin:     a.spool.Len(),
		PendingEvents:  a.events.Len(),
		LastEvtID:      a.events.LastID(),
		ConfigPath:     a.cfg.Source,
		DataDir:        a.cfg.DataDir,
		Socket:         a.cfg.Socket,
		Shell:          a.cfg.Shell,
		MaxLogBytes:    a.cfg.MaxLogBytes,
		Running:        []status.Exec{},
	}

	for _, e := range a.tasks.All() {
		spec := e.Spec()
		st := e.Journal().Stats()
		state := "running"
		if e.Finished() {
			state = "reporting"
		}
		snap.Running = append(snap.Running, status.Exec{
			ExecuteID:  spec.ExecuteID,
			Token:      spec.Token,
			TaskID:     spec.TaskID,
			PID:        e.PID(),
			Command:    spec.Command,
			Cwd:        spec.Cwd,
			State:      state,
			StartedAt:  e.StartedAt().UnixMilli(),
			ElapsedSec: now.Sub(e.StartedAt()).Seconds(),
			TimeoutSec: spec.TimeoutSec,
			LogSeq:     st.LastSeq,
			AckedSeq:   st.AckedSeq,
			LogBytes:   st.TotalBytes,
			Truncated:  st.Truncated,
		})
	}
	return snap
}
