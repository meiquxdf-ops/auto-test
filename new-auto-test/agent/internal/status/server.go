package status

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// ErrAlreadyRunning means another atagent answered on the status socket.
var ErrAlreadyRunning = errors.New("another atagent is already running")

// Server publishes the snapshot on a unix socket and mirrors it to a file, so
// `atagent status` works even when the socket is unavailable (for example when
// the caller lacks permission or the agent is wedged).
type Server struct {
	socketPath string
	filePath   string
	provider   Provider
	interval   time.Duration

	ln   net.Listener
	http *http.Server

	mu      sync.Mutex
	stopped bool
}

// NewServer builds the local status endpoint.
func NewServer(socketPath, filePath string, provider Provider) *Server {
	return &Server{
		socketPath: socketPath,
		filePath:   filePath,
		provider:   provider,
		interval:   5 * time.Second,
	}
}

// Start begins serving. It refuses to start when another agent owns the socket
// and cleans up a stale socket file otherwise.
func (s *Server) Start(ctx context.Context) error {
	if s.socketPath != "" {
		if err := os.MkdirAll(filepath.Dir(s.socketPath), 0o755); err != nil {
			return fmt.Errorf("create socket dir: %w", err)
		}
		if alive(s.socketPath) {
			return fmt.Errorf("%w (socket %s)", ErrAlreadyRunning, s.socketPath)
		}
		_ = os.Remove(s.socketPath)

		ln, err := net.Listen("unix", s.socketPath)
		if err != nil {
			return fmt.Errorf("listen on %s: %w", s.socketPath, err)
		}
		_ = os.Chmod(s.socketPath, 0o666)
		s.ln = ln

		mux := http.NewServeMux()
		mux.HandleFunc("/status", s.handleStatus)
		mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprint(w, `{"ok":true}`)
		})
		mux.HandleFunc("/", s.handleStatus)
		s.http = &http.Server{Handler: mux, ReadHeaderTimeout: 5 * time.Second}
		go func() {
			if err := s.http.Serve(ln); err != nil && !errors.Is(err, http.ErrServerClosed) {
				// The socket is a diagnostic surface; losing it must never
				// take the agent down.
				_ = err
			}
		}()
	}

	if s.filePath != "" {
		s.writeFile()
		go s.fileLoop(ctx)
	}
	return nil
}

func (s *Server) handleStatus(w http.ResponseWriter, r *http.Request) {
	snap := s.provider()
	w.Header().Set("Content-Type", "application/json")
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	_ = enc.Encode(snap)
}

func (s *Server) fileLoop(ctx context.Context) {
	t := time.NewTicker(s.interval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			s.writeFile()
			return
		case <-t.C:
			s.writeFile()
		}
	}
}

// writeFile publishes the snapshot atomically so a reader never sees a partial
// document. Writes stop once the server is closed, so a late tick cannot
// recreate the file after shutdown.
func (s *Server) writeFile() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.stopped {
		return
	}
	snap := s.provider()
	raw, err := json.MarshalIndent(snap, "", "  ")
	if err != nil {
		return
	}
	if err := os.MkdirAll(filepath.Dir(s.filePath), 0o755); err != nil {
		return
	}
	tmp := s.filePath + ".tmp"
	if err := os.WriteFile(tmp, append(raw, '\n'), 0o644); err != nil {
		return
	}
	_ = os.Rename(tmp, s.filePath)
}

// Close stops serving and removes the socket.
func (s *Server) Close() {
	if s.http != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = s.http.Shutdown(ctx)
	}
	if s.socketPath != "" {
		_ = os.Remove(s.socketPath)
	}
	s.writeFile()
	s.mu.Lock()
	s.stopped = true
	s.mu.Unlock()
}

// alive reports whether a live agent answers on the socket.
func alive(socketPath string) bool {
	if _, err := os.Stat(socketPath); err != nil {
		return false
	}
	conn, err := net.DialTimeout("unix", socketPath, 500*time.Millisecond)
	if err != nil {
		return false
	}
	_ = conn.Close()
	return true
}
