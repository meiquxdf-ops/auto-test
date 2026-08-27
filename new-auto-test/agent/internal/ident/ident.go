// Package ident owns the persistent agent identity and the per process boot id.
package ident

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// FileName is the agent id file inside the data directory
// (/var/lib/atagent/agent-id in production).
const FileName = "agent-id"

// LoadOrCreate reads the agent id from dataDir, generating and persisting a
// UUIDv4 the first time the agent runs on this machine.
func LoadOrCreate(dataDir string) (string, error) {
	path := filepath.Join(dataDir, FileName)
	raw, err := os.ReadFile(path)
	if err == nil {
		id := strings.TrimSpace(string(raw))
		if id != "" {
			if !valid(id) {
				return "", fmt.Errorf("agent id file %s holds %q, which is not a UUID", path, id)
			}
			return id, nil
		}
	} else if !os.IsNotExist(err) {
		return "", fmt.Errorf("read %s: %w", path, err)
	}

	id, err := NewUUID()
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(dataDir, 0o755); err != nil {
		return "", fmt.Errorf("create %s: %w", dataDir, err)
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(id+"\n"), 0o644); err != nil {
		return "", fmt.Errorf("write %s: %w", tmp, err)
	}
	if err := os.Rename(tmp, path); err != nil {
		return "", fmt.Errorf("install %s: %w", path, err)
	}
	return id, nil
}

// NewUUID returns a random RFC 4122 version 4 UUID.
func NewUUID() (string, error) {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", fmt.Errorf("read entropy: %w", err)
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	h := hex.EncodeToString(b[:])
	return strings.Join([]string{h[0:8], h[8:12], h[12:16], h[16:20], h[20:32]}, "-"), nil
}

// NewBootID identifies one agent process generation.
func NewBootID() (string, error) {
	var b [8]byte
	if _, err := rand.Read(b[:]); err != nil {
		return "", fmt.Errorf("read entropy: %w", err)
	}
	return hex.EncodeToString(b[:]), nil
}

func valid(id string) bool {
	if len(id) != 36 {
		return false
	}
	for i, c := range id {
		switch i {
		case 8, 13, 18, 23:
			if c != '-' {
				return false
			}
		default:
			isHex := (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')
			if !isHex {
				return false
			}
		}
	}
	return true
}
