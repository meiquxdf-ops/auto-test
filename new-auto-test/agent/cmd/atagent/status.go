package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"

	"github.com/atest/atagent/internal/status"
)

func cmdStatus(argv []string) error {
	fs := flag.NewFlagSet("atagent status", flag.ContinueOnError)
	var common commonFlags
	registerCommon(fs, &common)
	asJSON := fs.Bool("json", false, "print the raw status document")
	if err := fs.Parse(argv); err != nil {
		return err
	}

	cfg, err := loadConfig(fs, &common)
	if err != nil {
		return err
	}

	snap, source, err := status.Fetch(cfg.Socket, cfg.StatusFile)
	if err != nil {
		return fmt.Errorf("agent is not reachable: %w", err)
	}

	if *asJSON {
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		return enc.Encode(snap)
	}
	fmt.Print(snap.Text(source))
	return nil
}
