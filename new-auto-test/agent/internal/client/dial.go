package client

import (
	"context"
	"math/rand"
	"net"
	"time"
)

// Dial opens a TCP connection to the server with keepalive enabled, so a
// silently dead peer is noticed even when nothing is being sent.
func Dial(ctx context.Context, addr string, timeout time.Duration) (net.Conn, error) {
	d := net.Dialer{Timeout: timeout, KeepAlive: 30 * time.Second}
	conn, err := d.DialContext(ctx, "tcp", addr)
	if err != nil {
		return nil, err
	}
	if tc, ok := conn.(*net.TCPConn); ok {
		_ = tc.SetNoDelay(true)
	}
	return conn, nil
}

// Backoff is exponential with full jitter, bounded by Min and Max.
type Backoff struct {
	Min  time.Duration
	Max  time.Duration
	next time.Duration
	rnd  *rand.Rand
}

// NewBackoff builds a backoff policy.
func NewBackoff(min, max time.Duration) *Backoff {
	if min <= 0 {
		min = 500 * time.Millisecond
	}
	if max < min {
		max = min
	}
	return &Backoff{
		Min: min,
		Max: max,
		rnd: rand.New(rand.NewSource(time.Now().UnixNano())),
	}
}

// Next returns the next delay and advances the schedule. Jitter keeps a fleet
// of 1000 agents from reconnecting in lockstep after a server restart.
func (b *Backoff) Next() time.Duration {
	if b.next == 0 {
		b.next = b.Min
	}
	d := b.next
	b.next *= 2
	if b.next > b.Max {
		b.next = b.Max
	}
	half := d / 2
	return half + time.Duration(b.rnd.Int63n(int64(half)+1))
}

// Reset restarts the schedule, called after a session that stayed up long
// enough to be considered healthy.
func (b *Backoff) Reset() { b.next = 0 }

// Sleep waits for d unless ctx is cancelled first.
func Sleep(ctx context.Context, d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return false
	case <-t.C:
		return true
	}
}
