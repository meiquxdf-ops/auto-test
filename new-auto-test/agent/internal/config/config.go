// Package config resolves atagent settings from defaults, an optional YAML
// file, environment variables and command line flags (in that order).
package config

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
)

// Well known locations and limits.
const (
	// DefaultConfigPath is read when -config is not given. Missing is fine.
	DefaultConfigPath = "/etc/atagent/config.yaml"
	// DefaultDataDir holds the agent id, journals and the fin spool.
	DefaultDataDir = "/var/lib/atagent"
	// DefaultServer is used when nothing else says otherwise.
	DefaultServer = "127.0.0.1:9800"
	// MaxConcurrency is the per machine ceiling fixed by the spec.
	MaxConcurrency = 4
	// DefaultMaxLogBytes is the 5MB tail kept per execution.
	DefaultMaxLogBytes int64 = 5 << 20
)

// Config is the fully resolved agent configuration.
type Config struct {
	Server      string
	Tag         string
	Aliases     []string
	DataDir     string
	Concurrency int
	Shell       string

	Socket     string
	StatusFile string

	HeartbeatSec      int
	ConnectTimeoutSec int
	ReconnectMinMs    int
	ReconnectMaxMs    int

	MaxLogBytes  int64
	KillGraceSec int

	LogLevel       string
	KillOnShutdown bool

	// Env is injected into every execution before the per dispatch env.
	Env map[string]string

	// Source is the config file that was actually loaded ("" when none).
	Source string
}

// Default returns the built in configuration.
func Default() *Config {
	return &Config{
		Server:            DefaultServer,
		DataDir:           DefaultDataDir,
		Concurrency:       1,
		Shell:             "/bin/bash",
		HeartbeatSec:      5,
		ConnectTimeoutSec: 10,
		ReconnectMinMs:    500,
		ReconnectMaxMs:    30000,
		MaxLogBytes:       DefaultMaxLogBytes,
		KillGraceSec:      5,
		LogLevel:          "info",
		KillOnShutdown:    true,
		Env:               map[string]string{},
	}
}

// Load builds a config from the file at path (or DefaultConfigPath when empty)
// and then overlays environment variables. A missing default file is not an
// error; a missing explicit -config file is.
func Load(path string) (*Config, error) {
	cfg := Default()
	explicit := path != ""
	if !explicit {
		if envPath := strings.TrimSpace(os.Getenv("ATEST_CONFIG")); envPath != "" {
			path, explicit = envPath, true
		} else {
			path = DefaultConfigPath
		}
	}

	data, err := os.ReadFile(path)
	switch {
	case err == nil:
		parsed, perr := parseYAML(data)
		if perr != nil {
			return nil, fmt.Errorf("parse %s: %w", path, perr)
		}
		if aerr := cfg.applyMap(parsed); aerr != nil {
			return nil, fmt.Errorf("load %s: %w", path, aerr)
		}
		cfg.Source = path
	case errors.Is(err, os.ErrNotExist) && !explicit:
		// No file installed yet: defaults plus environment are enough.
	default:
		return nil, fmt.Errorf("read %s: %w", path, err)
	}

	if err := cfg.applyEnv(os.Getenv); err != nil {
		return nil, err
	}
	return cfg, nil
}

func (c *Config) applyMap(m map[string]value) error {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)

	for _, key := range keys {
		v := m[key]
		var err error
		switch key {
		case "server", "serveraddr", "serveraddress":
			c.Server = v.scalar
		case "tag", "displaytag":
			c.Tag = v.scalar
		case "aliases":
			c.Aliases = append(c.Aliases[:0], v.list...)
		case "datadir":
			c.DataDir = v.scalar
		case "concurrency", "maxconcurrency":
			c.Concurrency, err = atoi(key, v.scalar)
		case "shell":
			c.Shell = v.scalar
		case "socket", "socketpath":
			c.Socket = v.scalar
		case "statusfile":
			c.StatusFile = v.scalar
		case "heartbeatsec", "hbsec":
			c.HeartbeatSec, err = atoi(key, v.scalar)
		case "connecttimeoutsec":
			c.ConnectTimeoutSec, err = atoi(key, v.scalar)
		case "reconnectminms":
			c.ReconnectMinMs, err = atoi(key, v.scalar)
		case "reconnectmaxms":
			c.ReconnectMaxMs, err = atoi(key, v.scalar)
		case "maxlogbytes", "logtailbytes":
			c.MaxLogBytes, err = parseBytes(v.scalar)
		case "killgracesec":
			c.KillGraceSec, err = atoi(key, v.scalar)
		case "loglevel":
			c.LogLevel = v.scalar
		case "killonshutdown":
			c.KillOnShutdown, err = parseBool(v.scalar)
		case "env":
			for ek, ev := range v.dict {
				c.Env[ek] = ev
			}
		default:
			// Unknown keys are ignored so a newer server side config template
			// never stops an older agent from starting.
			continue
		}
		if err != nil {
			return fmt.Errorf("key %q: %w", key, err)
		}
	}
	return nil
}

func (c *Config) applyEnv(getenv func(string) string) error {
	str := func(name string, dst *string) {
		if v := strings.TrimSpace(getenv(name)); v != "" {
			*dst = v
		}
	}
	num := func(name string, dst *int) error {
		v := strings.TrimSpace(getenv(name))
		if v == "" {
			return nil
		}
		n, err := strconv.Atoi(v)
		if err != nil {
			return fmt.Errorf("%s: invalid integer %q", name, v)
		}
		*dst = n
		return nil
	}

	str("ATEST_SERVER", &c.Server)
	str("ATEST_TAG", &c.Tag)
	str("ATEST_DATA_DIR", &c.DataDir)
	str("ATEST_SHELL", &c.Shell)
	str("ATEST_SOCKET", &c.Socket)
	str("ATEST_STATUS_FILE", &c.StatusFile)
	str("ATEST_LOG_LEVEL", &c.LogLevel)

	if err := num("ATEST_CONCURRENCY", &c.Concurrency); err != nil {
		return err
	}
	if err := num("ATEST_HEARTBEAT_SEC", &c.HeartbeatSec); err != nil {
		return err
	}
	if err := num("ATEST_KILL_GRACE_SEC", &c.KillGraceSec); err != nil {
		return err
	}
	if v := strings.TrimSpace(getenv("ATEST_MAX_LOG_BYTES")); v != "" {
		n, err := parseBytes(v)
		if err != nil {
			return fmt.Errorf("ATEST_MAX_LOG_BYTES: %w", err)
		}
		c.MaxLogBytes = n
	}
	if v := strings.TrimSpace(getenv("ATEST_KILL_ON_SHUTDOWN")); v != "" {
		b, err := parseBool(v)
		if err != nil {
			return fmt.Errorf("ATEST_KILL_ON_SHUTDOWN: %w", err)
		}
		c.KillOnShutdown = b
	}
	return nil
}

// Normalize fills derived paths, clamps values into their legal range and
// validates what is left. It must be called after flags are applied.
func (c *Config) Normalize() error {
	c.Server = strings.TrimSpace(c.Server)
	if c.Server == "" {
		c.Server = DefaultServer
	}
	if !strings.Contains(c.Server, ":") {
		c.Server += ":9800"
	}
	c.Tag = strings.TrimSpace(c.Tag)

	c.DataDir = strings.TrimSpace(c.DataDir)
	if c.DataDir == "" {
		c.DataDir = DefaultDataDir
	}
	abs, err := filepath.Abs(c.DataDir)
	if err != nil {
		return fmt.Errorf("resolve dataDir %q: %w", c.DataDir, err)
	}
	c.DataDir = abs

	if c.Shell = strings.TrimSpace(c.Shell); c.Shell == "" {
		c.Shell = "/bin/bash"
	}
	if c.Socket = strings.TrimSpace(c.Socket); c.Socket == "" {
		c.Socket = filepath.Join(c.DataDir, "atagent.sock")
	}
	if c.StatusFile = strings.TrimSpace(c.StatusFile); c.StatusFile == "" {
		c.StatusFile = filepath.Join(c.DataDir, "status.json")
	}

	c.Concurrency = clamp(c.Concurrency, 1, MaxConcurrency)
	c.HeartbeatSec = clamp(c.HeartbeatSec, 1, 300)
	c.ConnectTimeoutSec = clamp(c.ConnectTimeoutSec, 1, 120)
	c.ReconnectMinMs = clamp(c.ReconnectMinMs, 100, 60000)
	c.ReconnectMaxMs = clamp(c.ReconnectMaxMs, c.ReconnectMinMs, 300000)
	c.KillGraceSec = clamp(c.KillGraceSec, 0, 300)
	if c.MaxLogBytes < 64<<10 {
		c.MaxLogBytes = 64 << 10
	}

	switch strings.ToLower(c.LogLevel) {
	case "debug", "info", "warn", "error":
		c.LogLevel = strings.ToLower(c.LogLevel)
	case "":
		c.LogLevel = "info"
	default:
		return fmt.Errorf("logLevel %q: want debug|info|warn|error", c.LogLevel)
	}

	if c.Env == nil {
		c.Env = map[string]string{}
	}
	return nil
}

// AliasList is the name set announced in hello: the display tag first, then
// any extra aliases, deduplicated.
func (c *Config) AliasList() []string {
	seen := make(map[string]bool, len(c.Aliases)+1)
	out := make([]string, 0, len(c.Aliases)+1)
	for _, a := range append([]string{c.Tag}, c.Aliases...) {
		a = strings.TrimSpace(a)
		if a == "" || seen[a] {
			continue
		}
		seen[a] = true
		out = append(out, a)
	}
	return out
}

func atoi(key, s string) (int, error) {
	n, err := strconv.Atoi(strings.TrimSpace(s))
	if err != nil {
		return 0, fmt.Errorf("invalid integer %q", s)
	}
	return n, nil
}

// parseBytes accepts a plain byte count or a suffixed size such as 5MB / 5MiB.
func parseBytes(s string) (int64, error) {
	s = strings.TrimSpace(s)
	if s == "" {
		return 0, fmt.Errorf("empty size")
	}
	mult := int64(1)
	upper := strings.ToUpper(s)
	for _, suffix := range []struct {
		text string
		mult int64
	}{
		{"KIB", 1 << 10}, {"MIB", 1 << 20}, {"GIB", 1 << 30},
		{"KB", 1 << 10}, {"MB", 1 << 20}, {"GB", 1 << 30},
		{"K", 1 << 10}, {"M", 1 << 20}, {"G", 1 << 30},
	} {
		if strings.HasSuffix(upper, suffix.text) {
			mult = suffix.mult
			upper = strings.TrimSpace(strings.TrimSuffix(upper, suffix.text))
			break
		}
	}
	n, err := strconv.ParseInt(upper, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("invalid size %q", s)
	}
	if n < 0 {
		return 0, fmt.Errorf("negative size %q", s)
	}
	return n * mult, nil
}

func clamp(v, lo, hi int) int {
	if v < lo {
		return lo
	}
	if v > hi {
		return hi
	}
	return v
}
