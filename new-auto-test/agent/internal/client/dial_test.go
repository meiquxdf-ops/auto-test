package client

import (
	"context"
	"testing"
	"time"
)

func TestBackoffGrowsAndResets(t *testing.T) {
	b := NewBackoff(100*time.Millisecond, 2*time.Second)

	var last time.Duration
	for i := 0; i < 8; i++ {
		d := b.Next()
		if d < 50*time.Millisecond {
			t.Fatalf("delay %s is below the jitter floor", d)
		}
		if d > 2*time.Second {
			t.Fatalf("delay %s exceeds the configured maximum", d)
		}
		last = d
	}
	if last < 500*time.Millisecond {
		t.Errorf("the schedule should have grown towards the maximum, got %s", last)
	}

	b.Reset()
	if d := b.Next(); d > 100*time.Millisecond {
		t.Errorf("after reset the first delay was %s, want it back near the minimum", d)
	}
}

func TestSleepIsCancellable(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	start := time.Now()
	if Sleep(ctx, time.Hour) {
		t.Fatal("Sleep should report cancellation")
	}
	if time.Since(start) > time.Second {
		t.Fatal("Sleep ignored the cancelled context")
	}
}

func TestDialFailsFastOnAClosedPort(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if _, err := Dial(ctx, "127.0.0.1:1", time.Second); err == nil {
		t.Fatal("dialing a closed port should fail")
	}
}
