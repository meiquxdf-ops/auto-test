package journal

import (
	"os"
	"os/exec"
	"strings"
	"syscall"
	"testing"
	"time"
)

func TestWriteAndRemovePGID(t *testing.T) {
	dir := t.TempDir()
	if err := WritePGID(dir, "e-1", 4242); err != nil {
		t.Fatalf("write pgid: %v", err)
	}
	raw, err := os.ReadFile(PGIDPath(dir, "e-1"))
	if err != nil {
		t.Fatalf("read sidecar: %v", err)
	}
	if got := strings.TrimSpace(string(raw)); got != "4242" {
		t.Errorf("sidecar content = %q, want 4242", got)
	}
	if err := WritePGID(dir, "e-1", 4343); err != nil {
		t.Fatalf("overwrite pgid: %v", err)
	}
	if err := RemovePGID(dir, "e-1"); err != nil {
		t.Fatalf("remove pgid: %v", err)
	}
	if _, err := os.Stat(PGIDPath(dir, "e-1")); !os.IsNotExist(err) {
		t.Errorf("sidecar still exists: %v", err)
	}
	if err := RemovePGID(dir, "e-1"); err != nil {
		t.Errorf("a second remove should be a no-op, got %v", err)
	}
}

// A SIGKILL'ed agent cannot run shutdown hooks, so the pgid sidecars written
// at spawn time are the only record of surviving process groups. The next
// start must kill them, and must never signal a group this process is in.
func TestKillLeftoverGroupsReapsOrphans(t *testing.T) {
	dir := t.TempDir()

	cmd := exec.Command("sleep", "60")
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	if err := cmd.Start(); err != nil {
		t.Fatalf("start sleep: %v", err)
	}
	pgid := cmd.Process.Pid
	// Reap concurrently: in the real crash scenario the leftover is not our
	// child, so it must not linger as a zombie here either.
	go cmd.Wait()
	defer syscall.Kill(-pgid, syscall.SIGKILL)

	if err := WritePGID(dir, "e-orphan", pgid); err != nil {
		t.Fatalf("write pgid: %v", err)
	}
	// A sidecar naming this test process must be skipped, not killed.
	if err := WritePGID(dir, "e-self", os.Getpid()); err != nil {
		t.Fatalf("write self pgid: %v", err)
	}
	// Garbage never signals anything, its file is still cleaned up.
	if err := os.WriteFile(PGIDPath(dir, "e-junk"), []byte("not-a-pid"), 0o644); err != nil {
		t.Fatalf("write junk sidecar: %v", err)
	}

	killed, err := KillLeftoverGroups(dir, 5*time.Second)
	if err != nil {
		t.Fatalf("kill leftover groups: %v", err)
	}
	if killed != 1 {
		t.Errorf("signalled = %d, want 1 (only the orphan group)", killed)
	}

	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		if syscall.Kill(-pgid, 0) == syscall.ESRCH {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	if syscall.Kill(-pgid, 0) != syscall.ESRCH {
		t.Error("the orphan process group is still alive")
	}

	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("read dir: %v", err)
	}
	for _, e := range entries {
		if strings.HasSuffix(e.Name(), ".pgid") || strings.HasSuffix(e.Name(), ".pgid.tmp") {
			t.Errorf("sidecar %s should have been removed", e.Name())
		}
	}

	if _, err := KillLeftoverGroups(t.TempDir()+"/missing", time.Second); err != nil {
		t.Errorf("cleanup of a missing directory should be a no-op, got %v", err)
	}
}
