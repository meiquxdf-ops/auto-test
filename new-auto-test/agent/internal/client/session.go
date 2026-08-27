// Package client owns one TCP session to the server plus the dial/backoff
// policy used to keep it up.
package client

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/atest/atagent/internal/proto"
)

// ErrSessionClosed is returned by Call once the session is gone.
var ErrSessionClosed = errors.New("session closed")

// Response is what a server initiated request produces. After, when set, runs
// once the response frame is on the wire - that is how exec starts the process
// strictly after the ACK.
type Response struct {
	Result any
	Err    *proto.Error
	After  func()
}

// OK builds a successful response.
func OK(result any) Response { return Response{Result: result} }

// Fail builds an error response.
func Fail(code, format string, args ...any) Response {
	return Response{Err: proto.Errf(code, format, args...)}
}

// Handler dispatches one server request.
type Handler func(m string, args json.RawMessage) Response

// SessionOptions tune timeouts.
type SessionOptions struct {
	// ReadTimeout closes the session when no frame arrives in this window.
	ReadTimeout time.Duration
	// WriteTimeout bounds a single frame write.
	WriteTimeout time.Duration
	// CallTimeout bounds an agent initiated request.
	CallTimeout time.Duration
	// Logf receives session level diagnostics.
	Logf func(format string, args ...any)
}

// Session multiplexes requests and responses over one connection.
type Session struct {
	conn    net.Conn
	fr      *proto.FrameReader
	opt     SessionOptions
	handler Handler

	writeMu sync.Mutex
	nextID  atomic.Int64

	pendingMu sync.Mutex
	pending   map[int64]chan *proto.Envelope

	// Exec requests are serviced strictly in arrival order by one worker
	// goroutine; see enqueueExec for why.
	execMu    sync.Mutex
	execQueue []*proto.Envelope
	execKick  chan struct{}
	execOnce  sync.Once

	closeOnce sync.Once
	closed    chan struct{}
	errMu     sync.Mutex
	err       error

	OpenedAt time.Time
}

// NewSession wraps an established connection.
func NewSession(conn net.Conn, handler Handler, opt SessionOptions) *Session {
	if opt.ReadTimeout <= 0 {
		opt.ReadTimeout = 60 * time.Second
	}
	if opt.WriteTimeout <= 0 {
		opt.WriteTimeout = 20 * time.Second
	}
	if opt.CallTimeout <= 0 {
		opt.CallTimeout = 30 * time.Second
	}
	if opt.Logf == nil {
		opt.Logf = func(string, ...any) {}
	}
	return &Session{
		conn:     conn,
		fr:       proto.NewFrameReader(conn),
		opt:      opt,
		handler:  handler,
		pending:  map[int64]chan *proto.Envelope{},
		execKick: make(chan struct{}, 1),
		closed:   make(chan struct{}),
		OpenedAt: time.Now(),
	}
}

// Serve runs the read loop until the connection fails or Close is called.
func (s *Session) Serve() {
	defer s.Close(nil)
	for {
		if err := s.conn.SetReadDeadline(time.Now().Add(s.opt.ReadTimeout)); err != nil {
			s.Close(err)
			return
		}
		payload, err := s.fr.ReadFrame()
		if err != nil {
			if errors.Is(err, io.EOF) {
				err = fmt.Errorf("server closed the connection")
			}
			s.Close(err)
			return
		}
		env, err := proto.Decode(payload)
		if err != nil {
			// A frame we cannot parse is a protocol break; drop the session so
			// the reconnect path can resynchronise.
			s.Close(err)
			return
		}
		switch env.T {
		case proto.KindRsp:
			s.deliver(env)
		case proto.KindReq:
			if env.M == proto.MExec {
				// Exec is order sensitive: handle it on the serial worker.
				s.enqueueExec(env)
			} else {
				go s.dispatch(env)
			}
		default:
			s.opt.Logf("ignoring frame with unknown type %q", env.T)
		}
	}
}

func (s *Session) deliver(env *proto.Envelope) {
	s.pendingMu.Lock()
	ch, ok := s.pending[env.ID]
	delete(s.pending, env.ID)
	s.pendingMu.Unlock()
	if !ok {
		s.opt.Logf("late response for id %d", env.ID)
		return
	}
	ch <- env
}

// enqueueExec queues an exec request for the single per-session worker and
// wakes it. Exec frames must be handled strictly in arrival order: the server
// dispatches queued executions oldest first, and handling them concurrently
// lets a later dispatch win the race for the last free slot so the queue head
// gets busy-rejected and runs after work that was enqueued behind it. Only the
// append happens here, so the read loop is never held up; cancel, stop and
// ping keep their own goroutines and can overtake a slow exec.
func (s *Session) enqueueExec(env *proto.Envelope) {
	s.execMu.Lock()
	s.execQueue = append(s.execQueue, env)
	s.execMu.Unlock()
	s.execOnce.Do(func() { go s.execLoop() })
	select {
	case s.execKick <- struct{}{}:
	default:
	}
}

// execLoop drains the exec queue one request at a time, so ACK/busy decisions
// are made in exactly the order the server sent the frames. Queued requests
// still pending when the session dies are dropped: their responses could
// never be written, and the server retries on the next session.
func (s *Session) execLoop() {
	for {
		s.execMu.Lock()
		var env *proto.Envelope
		if len(s.execQueue) > 0 {
			env = s.execQueue[0]
			s.execQueue = s.execQueue[1:]
		}
		s.execMu.Unlock()
		if env == nil {
			select {
			case <-s.execKick:
				continue
			case <-s.closed:
				return
			}
		}
		select {
		case <-s.closed:
			return
		default:
		}
		after, ok := s.respond(env)
		if !ok {
			return
		}
		if after != nil {
			// The process start happens off the worker so a slow spawn cannot
			// delay the ACK of the next queued exec. The slot was already
			// reserved by the handler, so ordering is unaffected.
			go after()
		}
	}
}

func (s *Session) dispatch(env *proto.Envelope) {
	after, ok := s.respond(env)
	if ok && after != nil {
		after()
	}
}

// respond runs the handler and writes the response frame. It returns the
// After hook (nil when the handler set none) and whether the write succeeded;
// on a failed write the session is already closed.
func (s *Session) respond(env *proto.Envelope) (func(), bool) {
	var resp Response
	if s.handler == nil {
		resp = Fail(proto.CodeUnsupported, "no handler for %q", env.M)
	} else {
		resp = s.handler(env.M, env.A)
	}

	var out *proto.Envelope
	if resp.Err != nil {
		out = &proto.Envelope{V: proto.Version, T: proto.KindRsp, ID: env.ID, OK: boolPtr(false), E: resp.Err}
	} else {
		built, err := proto.NewRsp(env.ID, resp.Result)
		if err != nil {
			out = proto.NewErrRsp(env.ID, proto.CodeInternal, "encode result: %v", err)
		} else {
			out = built
		}
	}
	if err := s.write(out); err != nil {
		s.Close(err)
		return nil, false
	}
	return resp.After, true
}

// Call sends a request and waits for its response. A protocol level failure is
// returned as *proto.Error; anything else means the session is broken.
func (s *Session) Call(ctx context.Context, m string, args any, result any) error {
	id := s.nextID.Add(1)
	env, err := proto.NewReq(id, m, args)
	if err != nil {
		return err
	}

	ch := make(chan *proto.Envelope, 1)
	s.pendingMu.Lock()
	s.pending[id] = ch
	s.pendingMu.Unlock()

	cleanup := func() {
		s.pendingMu.Lock()
		delete(s.pending, id)
		s.pendingMu.Unlock()
	}

	if err := s.write(env); err != nil {
		cleanup()
		s.Close(err)
		return err
	}

	ctx, cancel := context.WithTimeout(ctx, s.opt.CallTimeout)
	defer cancel()

	select {
	case rsp := <-ch:
		if !rsp.IsOK() {
			if rsp.E != nil {
				return rsp.E
			}
			return proto.Errf(proto.CodeInternal, "%s failed without an error body", m)
		}
		if result != nil && len(rsp.R) > 0 {
			if err := json.Unmarshal(rsp.R, result); err != nil {
				return fmt.Errorf("decode %s result: %w", m, err)
			}
		}
		return nil
	case <-ctx.Done():
		cleanup()
		err := fmt.Errorf("%s timed out after %s", m, s.opt.CallTimeout)
		s.Close(err)
		return err
	case <-s.closed:
		cleanup()
		if e := s.Err(); e != nil {
			return e
		}
		return ErrSessionClosed
	}
}

func (s *Session) write(env *proto.Envelope) error {
	payload, err := json.Marshal(env)
	if err != nil {
		return fmt.Errorf("encode %s: %w", env.M, err)
	}
	if len(payload) > proto.MaxFrame {
		return fmt.Errorf("%s frame is %d bytes, over the 1MiB limit", env.M, len(payload))
	}
	s.writeMu.Lock()
	defer s.writeMu.Unlock()
	select {
	case <-s.closed:
		return ErrSessionClosed
	default:
	}
	if err := s.conn.SetWriteDeadline(time.Now().Add(s.opt.WriteTimeout)); err != nil {
		return err
	}
	return proto.WriteFrame(s.conn, payload)
}

// Close tears the session down; the first cause reported wins.
func (s *Session) Close(cause error) {
	s.closeOnce.Do(func() {
		s.errMu.Lock()
		if s.err == nil {
			s.err = cause
		}
		s.errMu.Unlock()
		close(s.closed)
		_ = s.conn.Close()
		// Callers blocked in Call observe s.closed and unregister themselves.
	})
}

// Done is closed when the session ends.
func (s *Session) Done() <-chan struct{} { return s.closed }

// Alive reports whether the session can still carry frames.
func (s *Session) Alive() bool {
	select {
	case <-s.closed:
		return false
	default:
		return true
	}
}

// Err reports why the session ended.
func (s *Session) Err() error {
	s.errMu.Lock()
	defer s.errMu.Unlock()
	return s.err
}

// RemoteAddr is the server address of this session.
func (s *Session) RemoteAddr() string {
	if s.conn == nil {
		return ""
	}
	return s.conn.RemoteAddr().String()
}

func boolPtr(b bool) *bool { return &b }
