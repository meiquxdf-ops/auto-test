package agentd

import (
	"encoding/json"
	"fmt"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/atest/atagent/internal/proto"
)

// fakeServer is a minimal stand in for the Java server: it speaks the same
// framing and records everything the agent sends.
type fakeServer struct {
	t  *testing.T
	ln net.Listener

	mu        sync.Mutex
	cur       *srvConn
	conns     int
	hellos    []proto.HelloArgs
	heartbeat int
	logs      map[string][]proto.LogLine
	logFrames []proto.LogArgs
	fins      map[string]proto.FinArgs
	finTries  map[string]int
	events    []proto.Event
	control   proto.ControlResult
	finErr    func(proto.FinArgs) *proto.Error
	closed    bool
}

func newFakeServer(t *testing.T) *fakeServer {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	s := &fakeServer{
		t:        t,
		ln:       ln,
		logs:     map[string][]proto.LogLine{},
		fins:     map[string]proto.FinArgs{},
		finTries: map[string]int{},
	}
	go s.accept()
	t.Cleanup(s.Close)
	return s
}

func (s *fakeServer) Addr() string { return s.ln.Addr().String() }

func (s *fakeServer) Close() {
	s.mu.Lock()
	if s.closed {
		s.mu.Unlock()
		return
	}
	s.closed = true
	cur := s.cur
	s.mu.Unlock()
	_ = s.ln.Close()
	if cur != nil {
		cur.close()
	}
}

func (s *fakeServer) accept() {
	for {
		c, err := s.ln.Accept()
		if err != nil {
			return
		}
		conn := &srvConn{c: c, fr: proto.NewFrameReader(c), pending: map[int64]chan *proto.Envelope{}}
		s.mu.Lock()
		s.conns++
		s.cur = conn
		s.mu.Unlock()
		go s.serve(conn)
	}
}

func (s *fakeServer) serve(conn *srvConn) {
	defer conn.close()
	for {
		payload, err := conn.fr.ReadFrame()
		if err != nil {
			return
		}
		env, err := proto.Decode(payload)
		if err != nil {
			return
		}
		if env.T == proto.KindRsp {
			conn.deliver(env)
			continue
		}
		result, perr := s.handle(env)
		var out *proto.Envelope
		if perr != nil {
			out = &proto.Envelope{V: 1, T: proto.KindRsp, ID: env.ID, OK: boolp(false), E: perr}
		} else {
			out, err = proto.NewRsp(env.ID, result)
			if err != nil {
				return
			}
		}
		if err := conn.write(out); err != nil {
			return
		}
	}
}

func (s *fakeServer) handle(env *proto.Envelope) (any, *proto.Error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	switch env.M {
	case proto.MHello:
		var args proto.HelloArgs
		if err := json.Unmarshal(env.A, &args); err != nil {
			return nil, proto.Errf(proto.CodeBadRequest, "%v", err)
		}
		s.hellos = append(s.hellos, args)
		return s.control, nil
	case proto.MHb:
		s.heartbeat++
		return s.control, nil
	case proto.MLog:
		var args proto.LogArgs
		if err := json.Unmarshal(env.A, &args); err != nil {
			return nil, proto.Errf(proto.CodeBadRequest, "%v", err)
		}
		s.logFrames = append(s.logFrames, args)
		s.logs[args.ExecuteID] = append(s.logs[args.ExecuteID], args.Lines...)
		last := args.FromSeq
		if n := len(args.Lines); n > 0 {
			last = args.Lines[n-1].Seq
		}
		return proto.LogResult{AckSeq: last}, nil
	case proto.MEvt:
		var args proto.EvtArgs
		if err := json.Unmarshal(env.A, &args); err != nil {
			return nil, proto.Errf(proto.CodeBadRequest, "%v", err)
		}
		s.events = append(s.events, args.Events...)
		last := int64(0)
		if n := len(args.Events); n > 0 {
			last = args.Events[n-1].EvtID
		}
		return proto.EvtResult{AckEvtID: last}, nil
	case proto.MFin:
		var args proto.FinArgs
		if err := json.Unmarshal(env.A, &args); err != nil {
			return nil, proto.Errf(proto.CodeBadRequest, "%v", err)
		}
		s.finTries[args.ExecuteID]++
		if s.finErr != nil {
			if perr := s.finErr(args); perr != nil {
				return nil, perr
			}
		}
		s.fins[args.ExecuteID] = args
		return map[string]any{"ok": true}, nil
	default:
		return nil, proto.Errf(proto.CodeUnsupported, "unknown %q", env.M)
	}
}

// exec dispatches a command and returns the ACK response.
func (s *fakeServer) exec(args proto.ExecArgs) (*proto.Envelope, error) {
	return s.call(proto.MExec, args)
}

func (s *fakeServer) call(m string, args any) (*proto.Envelope, error) {
	conn := s.conn()
	if conn == nil {
		return nil, fmt.Errorf("no agent connection")
	}
	return conn.call(m, args)
}

func (s *fakeServer) conn() *srvConn {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.cur
}

// dropConnection simulates a network break without stopping the server.
func (s *fakeServer) dropConnection() {
	if c := s.conn(); c != nil {
		c.close()
	}
}

func (s *fakeServer) connCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.conns
}

func (s *fakeServer) helloCount() int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return len(s.hellos)
}

func (s *fakeServer) lastHello() (proto.HelloArgs, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.hellos) == 0 {
		return proto.HelloArgs{}, false
	}
	return s.hellos[len(s.hellos)-1], true
}

func (s *fakeServer) fin(executeID string) (proto.FinArgs, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	f, ok := s.fins[executeID]
	return f, ok
}

func (s *fakeServer) finAttempts(executeID string) int {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.finTries[executeID]
}

func (s *fakeServer) logText(executeID string) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := ""
	for _, l := range s.logs[executeID] {
		out += l.X + "\n"
	}
	return out
}

func (s *fakeServer) logLines(executeID string) []proto.LogLine {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]proto.LogLine(nil), s.logs[executeID]...)
}

func (s *fakeServer) eventKinds() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := make([]string, 0, len(s.events))
	for _, e := range s.events {
		out = append(out, e.Kind)
	}
	return out
}

func (s *fakeServer) setControl(c proto.ControlResult) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.control = c
}

func (s *fakeServer) setFinErr(fn func(proto.FinArgs) *proto.Error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.finErr = fn
}

type srvConn struct {
	c   net.Conn
	fr  *proto.FrameReader
	wmu sync.Mutex

	idMu   sync.Mutex
	nextID int64

	pmu     sync.Mutex
	pending map[int64]chan *proto.Envelope
	once    sync.Once
}

func (c *srvConn) write(env *proto.Envelope) error {
	payload, err := json.Marshal(env)
	if err != nil {
		return err
	}
	c.wmu.Lock()
	defer c.wmu.Unlock()
	return proto.WriteFrame(c.c, payload)
}

func (c *srvConn) call(m string, args any) (*proto.Envelope, error) {
	c.idMu.Lock()
	c.nextID++
	id := c.nextID
	c.idMu.Unlock()

	env, err := proto.NewReq(id, m, args)
	if err != nil {
		return nil, err
	}
	ch := make(chan *proto.Envelope, 1)
	c.pmu.Lock()
	c.pending[id] = ch
	c.pmu.Unlock()

	if err := c.write(env); err != nil {
		return nil, err
	}
	select {
	case rsp := <-ch:
		return rsp, nil
	case <-time.After(10 * time.Second):
		return nil, fmt.Errorf("%s timed out", m)
	}
}

func (c *srvConn) deliver(env *proto.Envelope) {
	c.pmu.Lock()
	ch, ok := c.pending[env.ID]
	delete(c.pending, env.ID)
	c.pmu.Unlock()
	if ok {
		ch <- env
	}
}

func (c *srvConn) close() { c.once.Do(func() { _ = c.c.Close() }) }

func boolp(b bool) *bool { return &b }

// waitFor polls cond until it holds or the deadline passes.
func waitFor(t *testing.T, timeout time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out after %s waiting for %s", timeout, what)
}
