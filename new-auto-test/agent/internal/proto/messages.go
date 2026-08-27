package proto

import (
	"encoding/json"
	"fmt"
	"strings"
)

// ---------- A -> S ----------

// RunningItem describes one execution the agent believes is alive. It is sent
// with every hello and hb so the server can reconcile leases.
type RunningItem struct {
	ExecuteID string `json:"executeId"`
	Token     string `json:"dispatchToken"`
	PID       int    `json:"pid"`
	StartedAt int64  `json:"startedAt"`
	LogSeq    int64  `json:"logSeq"`
	AckedSeq  int64  `json:"ackedSeq"`
}

// HelloArgs registers the agent on a fresh connection.
type HelloArgs struct {
	AgentID     string           `json:"agentId"`
	BootID      string           `json:"bootId"`
	Ver         string           `json:"ver"`
	Aliases     []string         `json:"aliases,omitempty"`
	Tag         string           `json:"tag,omitempty"`
	Host        string           `json:"host,omitempty"`
	OS          string           `json:"os,omitempty"`
	Arch        string           `json:"arch,omitempty"`
	PID         int              `json:"pid,omitempty"`
	StartedAt   int64            `json:"startedAt,omitempty"`
	Concurrency int              `json:"concurrency"`
	Running     []RunningItem    `json:"running"`
	LastEvtID   int64            `json:"lastEvtId"`
	LastLog     map[string]int64 `json:"lastLog,omitempty"`
	PendingFin  []string         `json:"pendingFin,omitempty"`
	Reconnect   bool             `json:"reconnect,omitempty"`
}

// HbArgs is the heartbeat payload; the server renews the lease on receipt.
type HbArgs struct {
	AgentID     string        `json:"agentId"`
	BootID      string        `json:"bootId"`
	TS          int64         `json:"ts"`
	Concurrency int           `json:"concurrency"`
	Running     []RunningItem `json:"running"`
	PendingFin  int           `json:"pendingFin,omitempty"`
}

// ControlResult is the shared shape of hello/hb responses. Every field is
// optional: a server that answers with `{}` is still perfectly valid.
type ControlResult struct {
	SessionID   string           `json:"sessionId,omitempty"`
	ServerTime  int64            `json:"serverTime,omitempty"`
	Tag         string           `json:"tag,omitempty"`
	Concurrency *int             `json:"concurrency,omitempty"`
	Cancel      []string         `json:"cancel,omitempty"`
	LogAck      map[string]int64 `json:"logAck,omitempty"`
	EvtAck      int64            `json:"evtAck,omitempty"`
}

// LogLine is one captured output line.
type LogLine struct {
	Seq int64  `json:"seq"`
	TS  int64  `json:"ts"`
	S   string `json:"s"` // "o" stdout, "e" stderr, "x" agent note
	X   string `json:"x"`
}

// Stream tags for LogLine.S.
const (
	StreamStdout = "o"
	StreamStderr = "e"
	StreamAgent  = "x"
)

// LogArgs is a batch of log lines for one execution. FromSeq is the sequence
// the batch continues from, so the server can spot gaps caused by the local
// 5MB tail journal dropping the head of a very chatty execution.
type LogArgs struct {
	AgentID   string    `json:"agentId"`
	ExecuteID string    `json:"executeId"`
	Token     string    `json:"dispatchToken"`
	FromSeq   int64     `json:"fromSeq"`
	Lines     []LogLine `json:"lines"`
	Truncated bool      `json:"truncated,omitempty"`
	Dropped   int64     `json:"droppedLines,omitempty"`
}

// LogResult acknowledges log ingestion up to AckSeq.
type LogResult struct {
	AckSeq int64 `json:"ackSeq"`
}

// Event is one agent side timeline entry; (agentId, evtId) is idempotent.
type Event struct {
	EvtID     int64             `json:"evtId"`
	TS        int64             `json:"ts"`
	Kind      string            `json:"kind"`
	ExecuteID string            `json:"executeId,omitempty"`
	Token     string            `json:"dispatchToken,omitempty"`
	Msg       string            `json:"msg,omitempty"`
	Data      map[string]string `json:"data,omitempty"`
}

// EvtArgs is a batch of timeline events.
type EvtArgs struct {
	AgentID string  `json:"agentId"`
	BootID  string  `json:"bootId"`
	Events  []Event `json:"events"`
}

// EvtResult acknowledges events up to AckEvtID.
type EvtResult struct {
	AckEvtID int64 `json:"ackEvtId"`
}

// Finish reasons reported in FinArgs.Reason.
const (
	ReasonExited      = "exited"
	ReasonCanceled    = "canceled"
	ReasonStopped     = "stopped"
	ReasonTimeout     = "timeout"
	ReasonStartFailed = "start_failed"
	ReasonShutdown    = "agent_shutdown"
)

// FinArgs is the terminal frame for one execution. It is resent until the
// server acknowledges it, so it must be self contained.
type FinArgs struct {
	AgentID    string `json:"agentId"`
	BootID     string `json:"bootId"`
	ExecuteID  string `json:"executeId"`
	Token      string `json:"dispatchToken"`
	ExitCode   int    `json:"exitCode"`
	Signal     string `json:"signal,omitempty"`
	Reason     string `json:"reason"`
	Err        string `json:"err,omitempty"`
	StartedAt  int64  `json:"startedAt"`
	FinishedAt int64  `json:"finishedAt"`
	LastLine   string `json:"lastLine"`
	LogSeq     int64  `json:"logSeq"`
	LogBytes   int64  `json:"logBytes"`
	Truncated  bool   `json:"truncated"`
	Attempt    int    `json:"attempt,omitempty"`
}

// ---------- S -> A ----------

// EnvMap accepts either `{"K":"V"}` or `["K=V"]` so the agent stays
// compatible with either server side encoding.
type EnvMap map[string]string

// UnmarshalJSON implements json.Unmarshaler.
func (m *EnvMap) UnmarshalJSON(b []byte) error {
	trimmed := strings.TrimSpace(string(b))
	if trimmed == "" || trimmed == "null" {
		*m = nil
		return nil
	}
	if trimmed[0] == '[' {
		var list []string
		if err := json.Unmarshal(b, &list); err != nil {
			return err
		}
		out := make(EnvMap, len(list))
		for _, kv := range list {
			k, v, ok := strings.Cut(kv, "=")
			if !ok || k == "" {
				continue
			}
			out[k] = v
		}
		*m = out
		return nil
	}
	var raw map[string]any
	if err := json.Unmarshal(b, &raw); err != nil {
		return err
	}
	out := make(EnvMap, len(raw))
	for k, v := range raw {
		if k == "" {
			continue
		}
		switch tv := v.(type) {
		case string:
			out[k] = tv
		case nil:
			out[k] = ""
		case float64:
			out[k] = strings.TrimSuffix(fmt.Sprintf("%v", tv), ".0")
		case bool:
			out[k] = fmt.Sprintf("%t", tv)
		default:
			enc, err := json.Marshal(tv)
			if err != nil {
				return err
			}
			out[k] = string(enc)
		}
	}
	*m = out
	return nil
}

// ExecArgs is a dispatch. Field aliases are accepted on the wire because the
// dispatch token appears as both `dispatchToken` and `token` in practice.
type ExecArgs struct {
	ExecuteID  string `json:"executeId"`
	Token      string `json:"dispatchToken"`
	TaskID     string `json:"taskId,omitempty"`
	Command    string `json:"command"`
	Cwd        string `json:"cwd,omitempty"`
	Env        EnvMap `json:"env,omitempty"`
	TimeoutSec int    `json:"timeoutSec,omitempty"`
	Shell      string `json:"shell,omitempty"`
}

type execArgsAlias struct {
	ExecuteID   string `json:"executeId"`
	ExecID      string `json:"execId"`
	ExecutionID string `json:"executionId"`
	Token       string `json:"dispatchToken"`
	Token2      string `json:"token"`
	TaskID      string `json:"taskId"`
	Command     string `json:"command"`
	Cmd         string `json:"cmd"`
	Cwd         string `json:"cwd"`
	WorkDir     string `json:"workDir"`
	Env         EnvMap `json:"env"`
	TimeoutSec  int    `json:"timeoutSec"`
	Timeout     int    `json:"timeout"`
	Shell       string `json:"shell"`
}

// UnmarshalJSON implements json.Unmarshaler.
func (a *ExecArgs) UnmarshalJSON(b []byte) error {
	var raw execArgsAlias
	if err := json.Unmarshal(b, &raw); err != nil {
		return err
	}
	*a = ExecArgs{
		ExecuteID:  firstNonEmpty(raw.ExecuteID, raw.ExecID, raw.ExecutionID),
		Token:      firstNonEmpty(raw.Token, raw.Token2),
		TaskID:     raw.TaskID,
		Command:    firstNonEmpty(raw.Command, raw.Cmd),
		Cwd:        firstNonEmpty(raw.Cwd, raw.WorkDir),
		Env:        raw.Env,
		TimeoutSec: firstPositive(raw.TimeoutSec, raw.Timeout),
		Shell:      raw.Shell,
	}
	return nil
}

// Validate checks the fields the agent cannot run without.
func (a *ExecArgs) Validate() error {
	if a.ExecuteID == "" {
		return fmt.Errorf("executeId is required")
	}
	if a.Token == "" {
		return fmt.Errorf("dispatchToken is required")
	}
	if strings.TrimSpace(a.Command) == "" {
		return fmt.Errorf("command is required")
	}
	return nil
}

// ExecResult is the ACK: the dispatch was accepted, nothing more.
type ExecResult struct {
	Accepted  bool   `json:"accepted"`
	ExecuteID string `json:"executeId"`
	Token     string `json:"dispatchToken"`
	PID       int    `json:"pid,omitempty"`
	AckedAt   int64  `json:"ackedAt"`
}

// CancelArgs kills one execution by dispatch token.
type CancelArgs struct {
	ExecuteID string `json:"executeId,omitempty"`
	Token     string `json:"dispatchToken"`
	Reason    string `json:"reason,omitempty"`
}

type cancelArgsAlias struct {
	ExecuteID string `json:"executeId"`
	ExecID    string `json:"execId"`
	Token     string `json:"dispatchToken"`
	Token2    string `json:"token"`
	Reason    string `json:"reason"`
}

// UnmarshalJSON implements json.Unmarshaler.
func (a *CancelArgs) UnmarshalJSON(b []byte) error {
	var raw cancelArgsAlias
	if err := json.Unmarshal(b, &raw); err != nil {
		return err
	}
	*a = CancelArgs{
		ExecuteID: firstNonEmpty(raw.ExecuteID, raw.ExecID),
		Token:     firstNonEmpty(raw.Token, raw.Token2),
		Reason:    raw.Reason,
	}
	return nil
}

// CancelResult reports whether a matching execution was found.
type CancelResult struct {
	Killed    bool   `json:"killed"`
	ExecuteID string `json:"executeId,omitempty"`
	Token     string `json:"dispatchToken,omitempty"`
	Msg       string `json:"msg,omitempty"`
}

// StopArgs stops every execution on this machine.
type StopArgs struct {
	Reason string `json:"reason,omitempty"`
}

// StopResult reports how many executions were signalled.
type StopResult struct {
	Killed int `json:"killed"`
}

// PingResult answers a server liveness probe.
type PingResult struct {
	AgentID string `json:"agentId"`
	BootID  string `json:"bootId"`
	TS      int64  `json:"ts"`
	Running int    `json:"running"`
}

func firstNonEmpty(values ...string) string {
	for _, v := range values {
		if v != "" {
			return v
		}
	}
	return ""
}

func firstPositive(values ...int) int {
	for _, v := range values {
		if v > 0 {
			return v
		}
	}
	return 0
}
