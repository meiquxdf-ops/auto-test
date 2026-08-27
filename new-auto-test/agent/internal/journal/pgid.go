package journal

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"
)

// The *.pgid sidecars exist because a SIGKILL of the agent cannot run any
// shutdown hook: executions run in their own process group (Setpgid), so the
// group survives the agent and nothing would ever kill it. The group id is
// persisted next to the journal when the command starts and deleted when the
// execution finishes; any sidecar still present at the next start names a
// leftover group that must be reaped before the agent accepts new work.

// PGIDPath is the sidecar file recording the process group of a running
// execution.
func PGIDPath(dir, executeID string) string {
	return filepath.Join(dir, safeName(executeID)+".pgid")
}

// WritePGID atomically persists the process group id for executeID.
func WritePGID(dir, executeID string, pgid int) error {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return err
	}
	path := PGIDPath(dir, executeID)
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, []byte(strconv.Itoa(pgid)+"\n"), 0o644); err != nil {
		return err
	}
	if err := os.Rename(tmp, path); err != nil {
		os.Remove(tmp)
		return err
	}
	return nil
}

// RemovePGID deletes the sidecar once the execution has finished.
func RemovePGID(dir, executeID string) error {
	if err := os.Remove(PGIDPath(dir, executeID)); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

// KillLeftoverGroups reaps process groups left behind by a previous agent
// death: every *.pgid sidecar under dir is read and removed, and the group it
// names is sent SIGTERM, escalated to SIGKILL if it is still alive after
// grace. Group ids that would signal this very process are skipped, so a
// stale sidecar can never take the agent down with it. It returns how many
// groups were signalled.
func KillLeftoverGroups(dir string, grace time.Duration) (int, error) {
	entries, err := os.ReadDir(dir)
	if err != nil {
		if os.IsNotExist(err) {
			return 0, nil
		}
		return 0, err
	}
	if grace <= 0 {
		grace = 2 * time.Second
	}

	self := os.Getpid()
	selfGroup := syscall.Getpgrp()

	var pgids []int
	for _, e := range entries {
		name := e.Name()
		if e.IsDir() {
			continue
		}
		if strings.HasSuffix(name, ".pgid.tmp") {
			os.Remove(filepath.Join(dir, name))
			continue
		}
		if !strings.HasSuffix(name, ".pgid") {
			continue
		}
		path := filepath.Join(dir, name)
		if raw, err := os.ReadFile(path); err == nil {
			pgid, perr := strconv.Atoi(strings.TrimSpace(string(raw)))
			// pgid <= 1 guards against ever signalling "every process"
			// through kill(-1) or the init group.
			if perr == nil && pgid > 1 && pgid != self && pgid != selfGroup {
				pgids = append(pgids, pgid)
			}
		}
		os.Remove(path)
	}

	signalled := 0
	var live []int
	for _, pgid := range pgids {
		if syscall.Kill(-pgid, syscall.SIGTERM) == nil {
			signalled++
			live = append(live, pgid)
		}
	}

	// Poll instead of sleeping the full grace so a promptly exiting group
	// does not stall agent startup.
	deadline := time.Now().Add(grace)
	for len(live) > 0 && time.Now().Before(deadline) {
		time.Sleep(20 * time.Millisecond)
		next := live[:0]
		for _, pgid := range live {
			if syscall.Kill(-pgid, 0) == nil {
				next = append(next, pgid)
			}
		}
		live = next
	}
	for _, pgid := range live {
		_ = syscall.Kill(-pgid, syscall.SIGKILL)
	}
	return signalled, nil
}
