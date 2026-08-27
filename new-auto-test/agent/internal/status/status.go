// Package status exposes the agent's local state over a unix socket and a
// status file, and reads both back for `atagent status`.
package status

import (
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/atest/atagent/internal/version"
)

// Exec is one execution in a status snapshot.
type Exec struct {
	ExecuteID  string  `json:"executeId"`
	Token      string  `json:"dispatchToken"`
	TaskID     string  `json:"taskId,omitempty"`
	PID        int     `json:"pid"`
	Command    string  `json:"command"`
	Cwd        string  `json:"cwd,omitempty"`
	State      string  `json:"state"`
	StartedAt  int64   `json:"startedAt"`
	ElapsedSec float64 `json:"elapsedSec"`
	TimeoutSec int     `json:"timeoutSec,omitempty"`
	LogSeq     int64   `json:"logSeq"`
	AckedSeq   int64   `json:"ackedSeq"`
	LogBytes   int64   `json:"logBytes"`
	Truncated  bool    `json:"truncated"`
}

// Snapshot is the full local view, served as JSON.
type Snapshot struct {
	AgentID string       `json:"agentId"`
	Tag     string       `json:"tag,omitempty"`
	BootID  string       `json:"bootId"`
	Build   version.Info `json:"build"`

	PID       int     `json:"pid"`
	StartedAt int64   `json:"startedAt"`
	Now       int64   `json:"now"`
	UptimeSec float64 `json:"uptimeSec"`

	Server         string `json:"server"`
	Connected      bool   `json:"connected"`
	SessionID      string `json:"sessionId,omitempty"`
	ConnectedSince int64  `json:"connectedSince,omitempty"`
	Reconnects     int64  `json:"reconnects"`
	LastError      string `json:"lastError,omitempty"`

	Concurrency   int    `json:"concurrency"`
	Running       []Exec `json:"running"`
	PendingFin    int    `json:"pendingFin"`
	PendingEvents int    `json:"pendingEvents"`
	LastEvtID     int64  `json:"lastEvtId"`

	ConfigPath  string `json:"configPath,omitempty"`
	DataDir     string `json:"dataDir"`
	Socket      string `json:"socket"`
	Shell       string `json:"shell"`
	MaxLogBytes int64  `json:"maxLogBytes"`
}

// Provider returns the current snapshot.
type Provider func() Snapshot

// Text renders the snapshot for a terminal. source describes where the data
// came from so an operator can tell a live read from a stale file.
func (s Snapshot) Text(source string) string {
	var b strings.Builder
	conn := "disconnected"
	if s.Connected {
		conn = "connected"
		if s.ConnectedSince > 0 {
			conn += " for " + humanDuration(time.Duration(s.Now-s.ConnectedSince)*time.Millisecond)
		}
	}

	fmt.Fprintf(&b, "agent        %s\n", nonEmpty(s.AgentID, "(unknown)"))
	fmt.Fprintf(&b, "tag          %s\n", nonEmpty(s.Tag, "(unset)"))
	fmt.Fprintf(&b, "version      %s (commit %s)\n", s.Build.Version, s.Build.Commit)
	fmt.Fprintf(&b, "pid          %d, boot %s\n", s.PID, s.BootID)
	fmt.Fprintf(&b, "uptime       %s\n", humanDuration(time.Duration(s.UptimeSec*float64(time.Second))))
	fmt.Fprintf(&b, "server       %s  [%s]\n", s.Server, conn)
	if s.LastError != "" {
		fmt.Fprintf(&b, "last error   %s\n", s.LastError)
	}
	fmt.Fprintf(&b, "reconnects   %d\n", s.Reconnects)
	fmt.Fprintf(&b, "slots        %d running / %d concurrency\n", len(s.Running), s.Concurrency)
	fmt.Fprintf(&b, "pending      %d fin, %d events (last evtId %d)\n", s.PendingFin, s.PendingEvents, s.LastEvtID)
	fmt.Fprintf(&b, "data dir     %s\n", s.DataDir)
	if s.ConfigPath != "" {
		fmt.Fprintf(&b, "config       %s\n", s.ConfigPath)
	}
	fmt.Fprintf(&b, "socket       %s\n", s.Socket)

	if len(s.Running) == 0 {
		b.WriteString("\nno executions\n")
	} else {
		b.WriteString("\nexecutions:\n")
		running := append([]Exec(nil), s.Running...)
		sort.Slice(running, func(i, j int) bool { return running[i].StartedAt < running[j].StartedAt })
		for _, e := range running {
			fmt.Fprintf(&b, "  %s  %s  pid=%d  %s\n", e.State, e.ExecuteID, e.PID,
				humanDuration(time.Duration(e.ElapsedSec*float64(time.Second))))
			fmt.Fprintf(&b, "      token=%s log=%d/%d lines %s%s\n", e.Token, e.AckedSeq, e.LogSeq,
				humanBytes(e.LogBytes), truncatedNote(e.Truncated))
			fmt.Fprintf(&b, "      cmd=%s\n", oneLine(e.Command, 100))
		}
	}
	if source != "" {
		fmt.Fprintf(&b, "\nsource: %s\n", source)
	}
	return b.String()
}

func truncatedNote(t bool) string {
	if t {
		return " (truncated)"
	}
	return ""
}

func nonEmpty(s, fallback string) string {
	if strings.TrimSpace(s) == "" {
		return fallback
	}
	return s
}

func oneLine(s string, max int) string {
	s = strings.TrimSpace(strings.ReplaceAll(strings.ReplaceAll(s, "\n", " ; "), "\r", ""))
	if len(s) > max {
		return s[:max] + "..."
	}
	return s
}

func humanDuration(d time.Duration) string {
	if d < 0 {
		d = 0
	}
	d = d.Round(time.Second)
	h := int(d.Hours())
	m := int(d.Minutes()) % 60
	sec := int(d.Seconds()) % 60
	switch {
	case h > 0:
		return fmt.Sprintf("%dh%02dm%02ds", h, m, sec)
	case m > 0:
		return fmt.Sprintf("%dm%02ds", m, sec)
	default:
		return fmt.Sprintf("%ds", sec)
	}
}

func humanBytes(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%dB", n)
	}
	div, exp := int64(unit), 0
	for v := n / unit; v >= unit && exp < 3; v /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f%cB", float64(n)/float64(div), "KMGT"[exp])
}
