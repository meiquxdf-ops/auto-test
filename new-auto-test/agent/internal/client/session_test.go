package client

import (
	"encoding/json"
	"errors"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/atest/atagent/internal/proto"
	"github.com/atest/atagent/internal/task"
)

// TestExecRequestsSerializedFIFO reproduces the five-run-04 incident: with
// concurrency=1 the server dispatches two queued executions oldest first, and
// the agent must accept the first executeId and busy-reject the second - never
// the other way around.
//
// The handler blocks the first exec *before* it reaches Manager.Accept, which
// is exactly the window the old `go dispatch` code left open: the second
// frame's goroutine could reach Accept first, grab the only slot, and the
// queue head came back `busy`. With the serial exec worker the second request
// cannot even enter the handler until the first one returned, so with the fix
// in place the test passes deterministically - no assertion depends on
// scheduling. Against the old concurrent dispatch it fails via the overlap /
// order / accept checks (a bounded wait below gives the racing goroutine
// ample time to expose itself).
//
// A ping is answered while the first exec is still parked, proving the exec
// queue holds neither the TCP read loop nor other request kinds.
func TestExecRequestsSerializedFIFO(t *testing.T) {
	agentConn, srvConn := net.Pipe()
	defer srvConn.Close()
	deadline := time.Now().Add(10 * time.Second)
	_ = srvConn.SetDeadline(deadline)

	mgr := task.New(1, task.Options{JournalDir: t.TempDir()})

	var (
		mu       sync.Mutex
		order    []string // executeIds in handler-entry order
		accepted []string // executeIds Manager.Accept let through
		inExec   int
		overlap  bool
	)
	firstEntered := make(chan struct{})
	secondEntered := make(chan struct{})
	release := make(chan struct{})

	handler := func(m string, args json.RawMessage) Response {
		switch m {
		case proto.MPing:
			return OK(map[string]bool{"pong": true})
		case proto.MExec:
		default:
			return Fail(proto.CodeUnsupported, "unexpected method %q", m)
		}

		var a struct {
			ExecuteID string `json:"executeId"`
			Token     string `json:"token"`
		}
		if err := json.Unmarshal(args, &a); err != nil {
			return Fail(proto.CodeBadRequest, "%v", err)
		}

		mu.Lock()
		inExec++
		if inExec > 1 {
			overlap = true
		}
		order = append(order, a.ExecuteID)
		first := len(order) == 1
		if len(order) == 2 {
			close(secondEntered)
		}
		mu.Unlock()

		if first {
			close(firstEntered)
			// Hold the queue head before Accept: the window in which the old
			// concurrent dispatch let a later exec steal the slot.
			<-release
		}

		_, err := mgr.Accept(task.Spec{ExecuteID: a.ExecuteID, Token: a.Token, Command: "true"})

		mu.Lock()
		inExec--
		if err == nil {
			accepted = append(accepted, a.ExecuteID)
		}
		mu.Unlock()

		if err != nil {
			if errors.Is(err, task.ErrBusy) {
				return Fail(proto.CodeBusy, "%v", err)
			}
			return Fail(proto.CodeInternal, "%v", err)
		}
		return OK(map[string]any{"accepted": true, "executeId": a.ExecuteID})
	}

	sess := NewSession(agentConn, handler, SessionOptions{
		ReadTimeout:  5 * time.Second,
		WriteTimeout: 5 * time.Second,
		CallTimeout:  5 * time.Second,
	})
	go sess.Serve()
	defer sess.Close(nil)

	send := func(id int64, m string, args any) {
		t.Helper()
		env, err := proto.NewReq(id, m, args)
		if err != nil {
			t.Fatalf("build %s req: %v", m, err)
		}
		payload, err := json.Marshal(env)
		if err != nil {
			t.Fatalf("marshal %s req: %v", m, err)
		}
		if err := proto.WriteFrame(srvConn, payload); err != nil {
			t.Fatalf("write %s frame: %v", m, err)
		}
	}

	// Two exec frames in queue order, older first, then a ping. net.Pipe is
	// synchronous, so once the ping write returns both execs have been read
	// off the wire and routed by the session.
	send(1, proto.MExec, map[string]string{"executeId": "e-old", "token": "t-old"})
	send(2, proto.MExec, map[string]string{"executeId": "e-new", "token": "t-new"})
	send(3, proto.MPing, nil)

	fr := proto.NewFrameReader(srvConn)
	rsps := map[int64]*proto.Envelope{}
	readRsp := func() {
		t.Helper()
		payload, err := fr.ReadFrame()
		if err != nil {
			t.Fatalf("read response frame: %v", err)
		}
		env, err := proto.Decode(payload)
		if err != nil {
			t.Fatalf("decode response frame: %v", err)
		}
		if env.T != proto.KindRsp {
			t.Fatalf("expected only responses from the agent, got %q", env.T)
		}
		rsps[env.ID] = env
	}

	// The ping must be answered while the first exec still holds the worker.
	for rsps[3] == nil {
		readRsp()
	}
	if !rsps[3].IsOK() {
		t.Fatalf("ping failed: %v", rsps[3].E)
	}

	select {
	case <-firstEntered:
	case <-time.After(5 * time.Second):
		t.Fatal("first exec never reached the handler")
	}

	// With serialized dispatch the second exec can never enter while the head
	// is parked, so this select always waits out the bound. Under concurrent
	// dispatch (the bug) the runnable second goroutine enters within the
	// bound and the overlap is recorded.
	select {
	case <-secondEntered:
	case <-time.After(100 * time.Millisecond):
	}

	// Both frames are delivered and the head is parked; now let it proceed.
	close(release)
	for rsps[1] == nil || rsps[2] == nil {
		readRsp()
	}

	if !rsps[1].IsOK() {
		t.Fatalf("queue head e-old must be accepted, got error %v", rsps[1].E)
	}
	if rsps[2].IsOK() {
		t.Fatal("later dispatch e-new was accepted; it must be busy-rejected behind the queue head")
	}
	if rsps[2].E == nil || rsps[2].E.C != proto.CodeBusy {
		t.Fatalf("e-new should be rejected with code %q, got %v", proto.CodeBusy, rsps[2].E)
	}

	mu.Lock()
	defer mu.Unlock()
	if overlap {
		t.Fatal("two exec handlers ran concurrently; exec dispatch must be serialized per session")
	}
	if len(order) != 2 || order[0] != "e-old" || order[1] != "e-new" {
		t.Fatalf("exec handler order = %v, want [e-old e-new]", order)
	}
	if len(accepted) != 1 || accepted[0] != "e-old" {
		t.Fatalf("accepted = %v, want exactly [e-old]", accepted)
	}
}

// TestNonExecRequestsStayConcurrent pins the routing split: only exec goes
// through the serial worker, so a cancel must be answerable while an exec is
// still in flight (otherwise a wedged exec could never be cancelled).
func TestNonExecRequestsStayConcurrent(t *testing.T) {
	agentConn, srvConn := net.Pipe()
	defer srvConn.Close()
	_ = srvConn.SetDeadline(time.Now().Add(10 * time.Second))

	execParked := make(chan struct{})
	release := make(chan struct{})
	handler := func(m string, args json.RawMessage) Response {
		switch m {
		case proto.MExec:
			close(execParked)
			<-release
			return OK(nil)
		case proto.MCancel:
			return OK(map[string]bool{"killed": true})
		default:
			return Fail(proto.CodeUnsupported, "unexpected method %q", m)
		}
	}

	sess := NewSession(agentConn, handler, SessionOptions{
		ReadTimeout:  5 * time.Second,
		WriteTimeout: 5 * time.Second,
		CallTimeout:  5 * time.Second,
	})
	go sess.Serve()
	defer sess.Close(nil)

	send := func(id int64, m string) {
		t.Helper()
		env, err := proto.NewReq(id, m, map[string]string{"executeId": "e-1", "token": "t-1"})
		if err != nil {
			t.Fatalf("build %s req: %v", m, err)
		}
		payload, err := json.Marshal(env)
		if err != nil {
			t.Fatalf("marshal %s req: %v", m, err)
		}
		if err := proto.WriteFrame(srvConn, payload); err != nil {
			t.Fatalf("write %s frame: %v", m, err)
		}
	}

	send(1, proto.MExec)
	select {
	case <-execParked:
	case <-time.After(5 * time.Second):
		t.Fatal("exec never reached the handler")
	}
	send(2, proto.MCancel)

	fr := proto.NewFrameReader(srvConn)
	payload, err := fr.ReadFrame()
	if err != nil {
		t.Fatalf("read cancel response: %v", err)
	}
	env, err := proto.Decode(payload)
	if err != nil {
		t.Fatalf("decode cancel response: %v", err)
	}
	if env.ID != 2 || !env.IsOK() {
		t.Fatalf("cancel must be answered while exec is parked, got id=%d ok=%v err=%v", env.ID, env.IsOK(), env.E)
	}
	close(release)
}
