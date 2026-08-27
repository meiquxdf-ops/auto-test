package status

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"time"
)

// Fetch reads the current status, preferring the live unix socket and falling
// back to the status file. The second return value describes the source.
func Fetch(socketPath, filePath string) (Snapshot, string, error) {
	if socketPath != "" {
		snap, err := fetchSocket(socketPath)
		if err == nil {
			return snap, "unix socket " + socketPath, nil
		}
		if snap, ferr := fetchFile(filePath); ferr == nil {
			return snap, fmt.Sprintf("%s (socket unavailable: %v)", fileSource(filePath, snap), err), nil
		}
		return Snapshot{}, "", fmt.Errorf("socket %s: %w", socketPath, err)
	}
	snap, err := fetchFile(filePath)
	if err != nil {
		return Snapshot{}, "", err
	}
	return snap, fileSource(filePath, snap), nil
}

func fetchSocket(socketPath string) (Snapshot, error) {
	httpc := &http.Client{
		Timeout: 3 * time.Second,
		Transport: &http.Transport{
			DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
				var d net.Dialer
				return d.DialContext(ctx, "unix", socketPath)
			},
		},
	}
	resp, err := httpc.Get("http://atagent/status")
	if err != nil {
		return Snapshot{}, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return Snapshot{}, fmt.Errorf("status endpoint returned %s", resp.Status)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 8<<20))
	if err != nil {
		return Snapshot{}, err
	}
	var snap Snapshot
	if err := json.Unmarshal(body, &snap); err != nil {
		return Snapshot{}, fmt.Errorf("decode status: %w", err)
	}
	return snap, nil
}

func fetchFile(filePath string) (Snapshot, error) {
	if filePath == "" {
		return Snapshot{}, fmt.Errorf("no status file configured")
	}
	raw, err := os.ReadFile(filePath)
	if err != nil {
		return Snapshot{}, err
	}
	var snap Snapshot
	if err := json.Unmarshal(raw, &snap); err != nil {
		return Snapshot{}, fmt.Errorf("decode %s: %w", filePath, err)
	}
	return snap, nil
}

// fileSource labels a file read with the age of the data, because a status
// file from a dead agent looks exactly like a fresh one otherwise.
func fileSource(filePath string, snap Snapshot) string {
	if snap.Now == 0 {
		return "file " + filePath
	}
	age := time.Since(time.UnixMilli(snap.Now))
	if age < 0 {
		age = 0
	}
	return fmt.Sprintf("file %s (%s old)", filePath, humanDuration(age))
}
