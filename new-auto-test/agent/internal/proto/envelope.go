package proto

import (
	"encoding/json"
	"fmt"
)

// Envelope kinds.
const (
	KindReq = "req"
	KindRsp = "rsp"
)

// Messages sent by the agent to the server.
const (
	MHello = "hello"
	MHb    = "hb"
	MLog   = "log"
	MEvt   = "evt"
	MFin   = "fin"
)

// Messages sent by the server to the agent.
const (
	MExec   = "exec"
	MCancel = "cancel"
	MStop   = "stop"
	MPing   = "ping"
)

// Error codes used in the "e.c" field.
const (
	CodeBusy        = "busy"
	CodeDupToken    = "dup_token"
	CodeDupSession  = "dup_session"
	CodeBadRequest  = "bad_request"
	CodeNotFound    = "not_found"
	CodeUnsupported = "unsupported"
	CodeInternal    = "internal"
)

// Envelope is the single frame body shape for both directions.
//
//	{"v":1,"t":"req","id":42,"m":"hello","a":{}}
//	{"v":1,"t":"rsp","id":42,"ok":true,"r":{}}
//	{"v":1,"t":"rsp","id":42,"ok":false,"e":{"c":"busy","msg":"..."}}
type Envelope struct {
	V  int             `json:"v"`
	T  string          `json:"t"`
	ID int64           `json:"id"`
	M  string          `json:"m,omitempty"`
	A  json.RawMessage `json:"a,omitempty"`
	OK *bool           `json:"ok,omitempty"`
	R  json.RawMessage `json:"r,omitempty"`
	E  *Error          `json:"e,omitempty"`
}

// IsOK reports whether a response envelope carries a success result.
func (e *Envelope) IsOK() bool { return e.OK != nil && *e.OK }

// Error is the protocol level error payload.
type Error struct {
	C   string `json:"c"`
	Msg string `json:"msg,omitempty"`
}

func (e *Error) Error() string {
	if e == nil {
		return "<nil>"
	}
	if e.Msg == "" {
		return e.C
	}
	return fmt.Sprintf("%s: %s", e.C, e.Msg)
}

// Errf builds a protocol error with a formatted message.
func Errf(code, format string, args ...any) *Error {
	return &Error{C: code, Msg: fmt.Sprintf(format, args...)}
}

// NewReq builds a request envelope; args may be nil.
func NewReq(id int64, m string, args any) (*Envelope, error) {
	env := &Envelope{V: Version, T: KindReq, ID: id, M: m}
	if args != nil {
		raw, err := json.Marshal(args)
		if err != nil {
			return nil, fmt.Errorf("marshal %s args: %w", m, err)
		}
		env.A = raw
	}
	return env, nil
}

// NewRsp builds a success response envelope; result may be nil.
func NewRsp(id int64, result any) (*Envelope, error) {
	ok := true
	env := &Envelope{V: Version, T: KindRsp, ID: id, OK: &ok}
	if result != nil {
		raw, err := json.Marshal(result)
		if err != nil {
			return nil, fmt.Errorf("marshal rsp result: %w", err)
		}
		env.R = raw
	}
	return env, nil
}

// NewErrRsp builds a failure response envelope.
func NewErrRsp(id int64, code, format string, args ...any) *Envelope {
	ok := false
	return &Envelope{V: Version, T: KindRsp, ID: id, OK: &ok, E: Errf(code, format, args...)}
}

// Decode unmarshals a frame payload into an envelope.
func Decode(payload []byte) (*Envelope, error) {
	var env Envelope
	if err := json.Unmarshal(payload, &env); err != nil {
		return nil, fmt.Errorf("decode envelope: %w", err)
	}
	if env.V == 0 {
		env.V = Version
	}
	return &env, nil
}
