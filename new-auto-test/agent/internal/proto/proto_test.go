package proto

import (
	"bytes"
	"encoding/json"
	"errors"
	"strings"
	"testing"
)

func TestFrameRoundTrip(t *testing.T) {
	var buf bytes.Buffer
	payloads := [][]byte{
		[]byte(`{"v":1,"t":"req","id":1,"m":"hello","a":{}}`),
		[]byte(`{"v":1,"t":"rsp","id":1,"ok":true}`),
		bytes.Repeat([]byte("x"), 100000),
	}
	for _, p := range payloads {
		if err := WriteFrame(&buf, p); err != nil {
			t.Fatalf("write frame: %v", err)
		}
	}

	fr := NewFrameReader(&buf)
	for i, want := range payloads {
		got, err := fr.ReadFrame()
		if err != nil {
			t.Fatalf("read frame %d: %v", i, err)
		}
		if !bytes.Equal(got, want) {
			t.Fatalf("frame %d: got %d bytes, want %d", i, len(got), len(want))
		}
	}
}

func TestFrameSizeLimit(t *testing.T) {
	var buf bytes.Buffer
	err := WriteFrame(&buf, bytes.Repeat([]byte("x"), MaxFrame+1))
	if !errors.Is(err, ErrFrameTooLarge) {
		t.Fatalf("error = %v, want ErrFrameTooLarge", err)
	}

	// A header claiming more than 1MiB must be refused before allocating.
	oversized := []byte{0x00, 0x20, 0x00, 0x01}
	fr := NewFrameReader(bytes.NewReader(oversized))
	if _, err := fr.ReadFrame(); !errors.Is(err, ErrFrameTooLarge) {
		t.Fatalf("error = %v, want ErrFrameTooLarge", err)
	}
}

func TestEnvelopeEncoding(t *testing.T) {
	req, err := NewReq(42, MHello, HelloArgs{AgentID: "a", BootID: "b"})
	if err != nil {
		t.Fatalf("new req: %v", err)
	}
	raw, err := json.Marshal(req)
	if err != nil {
		t.Fatalf("marshal: %v", err)
	}
	if !strings.Contains(string(raw), `"t":"req"`) || !strings.Contains(string(raw), `"m":"hello"`) {
		t.Fatalf("unexpected envelope: %s", raw)
	}

	decoded, err := Decode(raw)
	if err != nil {
		t.Fatalf("decode: %v", err)
	}
	if decoded.ID != 42 || decoded.M != MHello || decoded.V != Version {
		t.Fatalf("decoded = %+v", decoded)
	}

	errRsp := NewErrRsp(42, CodeBusy, "already running %d", 1)
	if errRsp.IsOK() {
		t.Fatal("an error response must not report ok")
	}
	if errRsp.E.C != CodeBusy || !strings.Contains(errRsp.E.Msg, "already running 1") {
		t.Fatalf("error payload = %+v", errRsp.E)
	}
}

func TestExecArgsAcceptsFieldAliases(t *testing.T) {
	cases := []string{
		`{"executeId":"e1","dispatchToken":"t1","command":"echo hi","cwd":"/tmp","timeoutSec":30}`,
		`{"execId":"e1","token":"t1","cmd":"echo hi","workDir":"/tmp","timeout":30}`,
	}
	for _, raw := range cases {
		var args ExecArgs
		if err := json.Unmarshal([]byte(raw), &args); err != nil {
			t.Fatalf("unmarshal %s: %v", raw, err)
		}
		if err := args.Validate(); err != nil {
			t.Fatalf("validate %s: %v", raw, err)
		}
		if args.ExecuteID != "e1" || args.Token != "t1" || args.Command != "echo hi" ||
			args.Cwd != "/tmp" || args.TimeoutSec != 30 {
			t.Fatalf("decoded %s as %+v", raw, args)
		}
	}
}

func TestExecArgsValidation(t *testing.T) {
	var args ExecArgs
	if err := json.Unmarshal([]byte(`{"executeId":"e1","dispatchToken":"t1","command":"  "}`), &args); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if err := args.Validate(); err == nil {
		t.Fatal("a blank command must be rejected")
	}
}

func TestEnvMapAcceptsBothEncodings(t *testing.T) {
	var asMap ExecArgs
	if err := json.Unmarshal([]byte(`{"executeId":"e","dispatchToken":"t","command":"x","env":{"A":"1","B":2}}`), &asMap); err != nil {
		t.Fatalf("unmarshal map env: %v", err)
	}
	if asMap.Env["A"] != "1" || asMap.Env["B"] != "2" {
		t.Fatalf("env = %#v", asMap.Env)
	}

	var asList ExecArgs
	if err := json.Unmarshal([]byte(`{"executeId":"e","dispatchToken":"t","command":"x","env":["A=1","B=2"]}`), &asList); err != nil {
		t.Fatalf("unmarshal list env: %v", err)
	}
	if asList.Env["A"] != "1" || asList.Env["B"] != "2" {
		t.Fatalf("env = %#v", asList.Env)
	}
}

func TestCancelArgsAcceptsTokenAlias(t *testing.T) {
	var args CancelArgs
	if err := json.Unmarshal([]byte(`{"token":"t9","execId":"e9","reason":"user"}`), &args); err != nil {
		t.Fatalf("unmarshal: %v", err)
	}
	if args.Token != "t9" || args.ExecuteID != "e9" || args.Reason != "user" {
		t.Fatalf("decoded = %+v", args)
	}
}
