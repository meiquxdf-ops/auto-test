package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"os/signal"
	"syscall"

	"github.com/atest/atagent/internal/agentd"
	"github.com/atest/atagent/internal/config"
	"github.com/atest/atagent/internal/logx"
)

// commonFlags are shared by run and status so both resolve the same paths.
type commonFlags struct {
	config      string
	dataDir     string
	server      string
	tag         string
	socket      string
	concurrency int
	logLevel    string
}

func registerCommon(fs *flag.FlagSet, f *commonFlags) {
	fs.StringVar(&f.config, "config", "", "config file (default "+config.DefaultConfigPath+")")
	fs.StringVar(&f.dataDir, "data-dir", "", "data directory for agent-id, journals and the fin spool (default "+config.DefaultDataDir+")")
	fs.StringVar(&f.server, "server", "", "server address host:port (default "+config.DefaultServer+")")
	fs.StringVar(&f.tag, "tag", "", "display tag for this machine")
	fs.StringVar(&f.socket, "socket", "", "status unix socket (default <data-dir>/atagent.sock)")
	fs.IntVar(&f.concurrency, "concurrency", 0, "parallel executions, 1..4 (default 1)")
	fs.StringVar(&f.logLevel, "log-level", "", "debug|info|warn|error (default info)")
}

// loadConfig applies the file and environment, then overlays only the flags
// the operator actually passed.
func loadConfig(fs *flag.FlagSet, f *commonFlags) (*config.Config, error) {
	cfg, err := config.Load(f.config)
	if err != nil {
		return nil, err
	}
	set := map[string]bool{}
	fs.Visit(func(fl *flag.Flag) { set[fl.Name] = true })

	if set["data-dir"] {
		cfg.DataDir = f.dataDir
	}
	if set["server"] {
		cfg.Server = f.server
	}
	if set["tag"] {
		cfg.Tag = f.tag
	}
	if set["socket"] {
		cfg.Socket = f.socket
	}
	if set["concurrency"] {
		cfg.Concurrency = f.concurrency
	}
	if set["log-level"] {
		cfg.LogLevel = f.logLevel
	}
	if err := cfg.Normalize(); err != nil {
		return nil, err
	}
	return cfg, nil
}

func cmdRun(argv []string) error {
	fs := flag.NewFlagSet("atagent run", flag.ContinueOnError)
	var common commonFlags
	registerCommon(fs, &common)
	printConfig := fs.Bool("print-config", false, "print the resolved configuration and exit")
	if err := fs.Parse(argv); err != nil {
		return err
	}

	cfg, err := loadConfig(fs, &common)
	if err != nil {
		return err
	}
	if *printConfig {
		return describeConfig(cfg)
	}

	log := logx.New(cfg.LogLevel, os.Stderr)
	agent, err := agentd.New(cfg, log)
	if err != nil {
		return err
	}

	ctx, stop := context.WithCancel(context.Background())
	defer stop()

	sig := make(chan os.Signal, 2)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		s := <-sig
		log.Warnf("received %s, shutting down", s)
		stop()
		// A second signal means "now", skipping the graceful drain.
		s = <-sig
		log.Errorf("received %s again, exiting immediately", s)
		os.Exit(130)
	}()

	return agent.Run(ctx)
}

func describeConfig(cfg *config.Config) error {
	source := cfg.Source
	if source == "" {
		source = "(none, using defaults and environment)"
	}
	fmt.Printf("config file  %s\n", source)
	fmt.Printf("server       %s\n", cfg.Server)
	fmt.Printf("tag          %s\n", cfg.Tag)
	fmt.Printf("data dir     %s\n", cfg.DataDir)
	fmt.Printf("socket       %s\n", cfg.Socket)
	fmt.Printf("status file  %s\n", cfg.StatusFile)
	fmt.Printf("shell        %s\n", cfg.Shell)
	fmt.Printf("concurrency  %d\n", cfg.Concurrency)
	fmt.Printf("heartbeat    %ds\n", cfg.HeartbeatSec)
	fmt.Printf("reconnect    %d..%dms\n", cfg.ReconnectMinMs, cfg.ReconnectMaxMs)
	fmt.Printf("log tail     %d bytes\n", cfg.MaxLogBytes)
	fmt.Printf("kill grace   %ds\n", cfg.KillGraceSec)
	fmt.Printf("kill on exit %t\n", cfg.KillOnShutdown)
	fmt.Printf("log level    %s\n", cfg.LogLevel)
	return nil
}
