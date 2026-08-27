package config

import (
	"os"
	"path/filepath"
	"testing"
)

const sample = `# atagent configuration
server: 10.0.0.5:9800
tag: "build-01"
concurrency: 2
data_dir: /var/lib/atagent
maxLogBytes: 5MB     # tail kept per execution
killOnShutdown: false
env:
  CI: "true"
  REGION: cn-east
aliases:
  - build-01.internal
  - legacy-name
`

func TestLoadFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")
	if err := os.WriteFile(path, []byte(sample), 0o644); err != nil {
		t.Fatalf("write config: %v", err)
	}

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if err := cfg.Normalize(); err != nil {
		t.Fatalf("normalize: %v", err)
	}

	if cfg.Server != "10.0.0.5:9800" {
		t.Errorf("server = %q", cfg.Server)
	}
	if cfg.Tag != "build-01" {
		t.Errorf("tag = %q", cfg.Tag)
	}
	if cfg.Concurrency != 2 {
		t.Errorf("concurrency = %d", cfg.Concurrency)
	}
	if cfg.MaxLogBytes != 5<<20 {
		t.Errorf("maxLogBytes = %d, want %d", cfg.MaxLogBytes, 5<<20)
	}
	if cfg.KillOnShutdown {
		t.Error("killOnShutdown should be false")
	}
	if cfg.Env["CI"] != "true" || cfg.Env["REGION"] != "cn-east" {
		t.Errorf("env = %#v", cfg.Env)
	}
	if cfg.Source != path {
		t.Errorf("source = %q, want %q", cfg.Source, path)
	}

	aliases := cfg.AliasList()
	want := []string{"build-01", "build-01.internal", "legacy-name"}
	if len(aliases) != len(want) {
		t.Fatalf("aliases = %#v, want %#v", aliases, want)
	}
	for i := range want {
		if aliases[i] != want[i] {
			t.Fatalf("aliases = %#v, want %#v", aliases, want)
		}
	}
}

func TestMissingDefaultFileIsFine(t *testing.T) {
	t.Setenv("ATEST_CONFIG", "")
	cfg, err := Load(filepath.Join(t.TempDir(), "does-not-exist.yaml"))
	if err == nil {
		t.Fatal("an explicit -config path that does not exist must fail")
	}
	if cfg != nil {
		t.Fatal("no config should be returned on error")
	}

	// The default path is allowed to be absent.
	saved := DefaultConfigPath
	_ = saved
	cfg, err = Load("")
	if err != nil {
		t.Fatalf("load without config: %v", err)
	}
	if err := cfg.Normalize(); err != nil {
		t.Fatalf("normalize: %v", err)
	}
	if cfg.Server == "" {
		t.Error("server should fall back to the default")
	}
}

func TestEnvironmentOverridesFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.yaml")
	if err := os.WriteFile(path, []byte("server: 1.2.3.4:1000\ntag: from-file\n"), 0o644); err != nil {
		t.Fatalf("write config: %v", err)
	}

	t.Setenv("ATEST_SERVER", "9.9.9.9:9900")
	t.Setenv("ATEST_TAG", "from-env")
	t.Setenv("ATEST_CONCURRENCY", "3")

	cfg, err := Load(path)
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if cfg.Server != "9.9.9.9:9900" {
		t.Errorf("server = %q, want the environment value", cfg.Server)
	}
	if cfg.Tag != "from-env" {
		t.Errorf("tag = %q, want the environment value", cfg.Tag)
	}
	if cfg.Concurrency != 3 {
		t.Errorf("concurrency = %d, want 3", cfg.Concurrency)
	}
}

func TestNormalizeDerivesPathsAndClamps(t *testing.T) {
	cfg := Default()
	cfg.DataDir = t.TempDir()
	cfg.Server = "10.0.0.9"
	cfg.Concurrency = 99
	cfg.MaxLogBytes = 10
	if err := cfg.Normalize(); err != nil {
		t.Fatalf("normalize: %v", err)
	}
	if cfg.Server != "10.0.0.9:9800" {
		t.Errorf("server = %q, want the default port appended", cfg.Server)
	}
	if cfg.Concurrency != MaxConcurrency {
		t.Errorf("concurrency = %d, want it clamped to %d", cfg.Concurrency, MaxConcurrency)
	}
	if cfg.MaxLogBytes < 64<<10 {
		t.Errorf("maxLogBytes = %d, want a sane floor", cfg.MaxLogBytes)
	}
	if cfg.Socket != filepath.Join(cfg.DataDir, "atagent.sock") {
		t.Errorf("socket = %q", cfg.Socket)
	}
	if cfg.StatusFile != filepath.Join(cfg.DataDir, "status.json") {
		t.Errorf("statusFile = %q", cfg.StatusFile)
	}
}

func TestNormalizeRejectsBadLogLevel(t *testing.T) {
	cfg := Default()
	cfg.DataDir = t.TempDir()
	cfg.LogLevel = "loud"
	if err := cfg.Normalize(); err == nil {
		t.Fatal("an unknown log level must be rejected")
	}
}

func TestParseBytes(t *testing.T) {
	cases := map[string]int64{
		"1024": 1024,
		"5MB":  5 << 20,
		"5MiB": 5 << 20,
		"2 gb": 2 << 30,
		"512K": 512 << 10,
		"0":    0,
	}
	for in, want := range cases {
		got, err := parseBytes(in)
		if err != nil {
			t.Errorf("parseBytes(%q): %v", in, err)
			continue
		}
		if got != want {
			t.Errorf("parseBytes(%q) = %d, want %d", in, got, want)
		}
	}
	if _, err := parseBytes("many"); err == nil {
		t.Error("a non numeric size must fail")
	}
}

func TestYAMLSubset(t *testing.T) {
	parsed, err := parseYAML([]byte(sample))
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	if got := parsed["server"].scalar; got != "10.0.0.5:9800" {
		t.Errorf("server = %q", got)
	}
	if got := parsed["maxlogbytes"].scalar; got != "5MB" {
		t.Errorf("maxLogBytes = %q, want the inline comment stripped", got)
	}
	if got := parsed["env"].dict["CI"]; got != "true" {
		t.Errorf("env.CI = %q", got)
	}
	if got := parsed["aliases"].list; len(got) != 2 || got[0] != "build-01.internal" {
		t.Errorf("aliases = %#v", got)
	}
	if _, err := parseYAML([]byte("just-a-string\n")); err == nil {
		t.Error("a line without a colon must be rejected")
	}
}
