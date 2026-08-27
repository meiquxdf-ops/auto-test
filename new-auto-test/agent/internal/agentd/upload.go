package agentd

import (
	"context"
	"errors"
	"time"

	"github.com/atest/atagent/internal/client"
	"github.com/atest/atagent/internal/proto"
)

// Batch limits. A frame is capped at 1MiB by the protocol, so the payload
// budget stays well below that to leave room for JSON overhead.
const (
	maxLogLines      = 500
	maxLogBytes      = 384 << 10
	maxEventBatch    = 200
	uploadTick       = 200 * time.Millisecond
	journalSyncEvery = 2 * time.Second

	// finLogGrace bounds how long a fin waits for its own log tail to be
	// acknowledged. Past that the result matters more than the logs.
	finLogGrace = 30 * time.Second
	// finRetryBase is the delay before resending an unacknowledged fin.
	finRetryBase = 3 * time.Second
	finRetryMax  = 30 * time.Second
)

// uploadLoop pushes logs, events and fins for as long as the session lives.
func (a *Agent) uploadLoop(ctx context.Context, sess *client.Session) {
	tick := time.NewTicker(uploadTick)
	defer tick.Stop()
	sync := time.NewTicker(journalSyncEvery)
	defer sync.Stop()

	for {
		select {
		case <-ctx.Done():
			return
		case <-sync.C:
			a.tasks.SyncJournals()
			continue
		case <-a.wake:
		case <-tick.C:
		}

		if err := a.flushAll(ctx, sess); err != nil {
			if ctx.Err() != nil {
				return
			}
			a.log.Warnf("upload failed: %v", err)
			sess.Close(err)
			return
		}
	}
}

// flushAll drains logs first, then events, then fins: a fin must never
// overtake the output of its own execution.
//
// The shutdown path can call this while the upload loop is still active, so
// the lock keeps two flushes from picking up the same unacknowledged batch.
func (a *Agent) flushAll(ctx context.Context, sess *client.Session) error {
	a.flushMu.Lock()
	defer a.flushMu.Unlock()

	if err := a.flushLogs(ctx, sess); err != nil {
		return err
	}
	if err := a.flushEvents(ctx, sess); err != nil {
		return err
	}
	return a.flushFins(ctx, sess)
}

func (a *Agent) flushLogs(ctx context.Context, sess *client.Session) error {
	for _, e := range a.tasks.All() {
		spec := e.Spec()
		jr := e.Journal()
		for ctx.Err() == nil {
			fromSeq, lines := jr.Batch(maxLogLines, maxLogBytes)
			if len(lines) == 0 {
				break
			}
			stats := jr.Stats()
			args := proto.LogArgs{
				AgentID:   a.agentID,
				ExecuteID: spec.ExecuteID,
				Token:     spec.Token,
				FromSeq:   fromSeq,
				Lines:     lines,
				Truncated: stats.Truncated,
				Dropped:   stats.DroppedLines,
			}
			var res proto.LogResult
			err := sess.Call(ctx, proto.MLog, args, &res)
			if err != nil {
				var perr *proto.Error
				if errors.As(err, &perr) {
					// The server refuses this batch (unknown execution, bad
					// request). Retrying forever would wedge the uploader, so
					// drop it locally and move on.
					a.log.Warnf("server rejected logs for %s: %v; dropping %d line(s)",
						spec.ExecuteID, perr, len(lines))
					jr.Ack(lines[len(lines)-1].Seq)
					continue
				}
				return err
			}
			ack := res.AckSeq
			if ack <= 0 {
				ack = lines[len(lines)-1].Seq
			}
			jr.Ack(ack)
		}
	}
	return ctx.Err()
}

func (a *Agent) flushEvents(ctx context.Context, sess *client.Session) error {
	for ctx.Err() == nil {
		batch := a.events.Pending(maxEventBatch)
		if len(batch) == 0 {
			return nil
		}
		args := proto.EvtArgs{AgentID: a.agentID, BootID: a.bootID, Events: batch}
		var res proto.EvtResult
		if err := sess.Call(ctx, proto.MEvt, args, &res); err != nil {
			var perr *proto.Error
			if errors.As(err, &perr) {
				a.log.Warnf("server rejected %d event(s): %v; dropping them", len(batch), perr)
				a.events.Ack(batch[len(batch)-1].EvtID)
				continue
			}
			return err
		}
		ack := res.AckEvtID
		if ack <= 0 {
			ack = batch[len(batch)-1].EvtID
		}
		a.events.Ack(ack)
	}
	return ctx.Err()
}

// flushFins resends every pending fin until the server acknowledges it. This
// is the only frame that closes an execution, so it is retried indefinitely
// and only dropped when the server calls it malformed.
func (a *Agent) flushFins(ctx context.Context, sess *client.Session) error {
	for _, item := range a.spool.Pending() {
		if ctx.Err() != nil {
			return ctx.Err()
		}
		fin := item.Fin

		if e := a.tasks.ByToken(fin.Token, fin.ExecuteID); e != nil {
			waited := time.Since(time.UnixMilli(fin.FinishedAt))
			if e.Journal().HasPending() && waited < finLogGrace {
				continue
			}
		}
		if !item.LastSent.IsZero() {
			delay := time.Duration(item.Attempts) * finRetryBase
			if delay > finRetryMax {
				delay = finRetryMax
			}
			if time.Since(item.LastSent) < delay {
				continue
			}
		}

		fin.Attempt = item.Attempts + 1
		a.spool.MarkSent(fin.ExecuteID)
		if err := sess.Call(ctx, proto.MFin, fin, nil); err != nil {
			var perr *proto.Error
			if errors.As(err, &perr) {
				if perr.C == proto.CodeBadRequest {
					a.log.Errorf("server rejected fin for %s as malformed: %v; discarding", fin.ExecuteID, perr)
					_ = a.spool.Ack(fin.ExecuteID)
					a.tasks.Reap(fin.ExecuteID)
					continue
				}
				a.log.Warnf("fin for %s not accepted (%v); will retry", fin.ExecuteID, perr)
				continue
			}
			return err
		}

		if err := a.spool.Ack(fin.ExecuteID); err != nil {
			a.log.Warnf("remove spooled fin for %s: %v", fin.ExecuteID, err)
		}
		a.tasks.Reap(fin.ExecuteID)
		a.log.Debugf("fin for %s acknowledged after %d attempt(s)", fin.ExecuteID, fin.Attempt)
	}
	return nil
}
